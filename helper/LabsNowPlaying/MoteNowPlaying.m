// Mote Labs, "Context & now playing" (preview).
//
// macOS 15.4+ only answers MediaRemote now-playing queries for Apple-signed clients
// (bundle ids starting with com.apple.). /usr/bin/perl is one ("com.apple.perl5"), so the helper
// runs `/usr/bin/perl mote-nowplaying.pl <this dylib>`; the script loads this library with
// DynaLoader and calls mote_np_main(). Same idea as ungive/mediaremote-adapter (BSD-3-Clause),
// written from scratch for Mote.
//
// Protocol over stdio, one JSON object per line:
//   stdout: {"ok":true} once, then {"np": {...}} on every change, {"np": null} when nothing plays.
//   stdin:  "seek <seconds>", "cmd <MRMediaRemoteCommand id>", "get".
// MOTE_NP_ONCE=1 prints one {"np": ...} line and exits (the spike / probe).
#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#include <dlfcn.h>

typedef void (*GetInfoFn)(dispatch_queue_t, void (^)(CFDictionaryRef));
typedef void (*GetPidFn)(dispatch_queue_t, void (^)(int));
typedef void (*RegisterFn)(dispatch_queue_t);
typedef void (*SetElapsedFn)(double);
typedef Boolean (*SendCommandFn)(int, CFDictionaryRef);

static GetInfoFn getInfo;
static GetPidFn getPid;
static SetElapsedFn setElapsed;
static SendCommandFn sendCommand;
static dispatch_queue_t q;
static NSString *lastLine;
static NSData *lastArtSrc;
static NSString *lastArt;

static void emit(id obj) {
    NSData *d = [NSJSONSerialization dataWithJSONObject:obj options:0 error:nil];
    if (!d) return;
    NSString *s = [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding];
    if ([s isEqualToString:lastLine]) return;  // only changes
    lastLine = s;
    fprintf(stdout, "%s\n", s.UTF8String);
    fflush(stdout);
}

// Small JPEG (<= 192 px), base64. Cached by source bytes.
static NSString *artwork(NSData *src) {
    if (!src.length) return nil;
    if ([src isEqualToData:lastArtSrc]) return lastArt;
    NSString *out = nil;
    CGImageSourceRef is = CGImageSourceCreateWithData((__bridge CFDataRef)src, NULL);
    if (is) {
        NSDictionary *o = @{(id)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
                            (id)kCGImageSourceThumbnailMaxPixelSize: @192,
                            (id)kCGImageSourceCreateThumbnailWithTransform: @YES};
        CGImageRef img = CGImageSourceCreateThumbnailAtIndex(is, 0, (__bridge CFDictionaryRef)o);
        if (img) {
            NSMutableData *jpg = [NSMutableData data];
            CGImageDestinationRef dst = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)jpg, CFSTR("public.jpeg"), 1, NULL);
            if (dst) {
                CGImageDestinationAddImage(dst, img, (__bridge CFDictionaryRef)@{(id)kCGImageDestinationLossyCompressionQuality: @0.6});
                if (CGImageDestinationFinalize(dst) && jpg.length < 40000) out = [jpg base64EncodedStringWithOptions:0];
                CFRelease(dst);
            }
            CGImageRelease(img);
        }
        CFRelease(is);
    }
    lastArtSrc = src; lastArt = out;
    return out;
}

static void refresh(void) {
    getInfo(q, ^(CFDictionaryRef ref) {
        NSDictionary *info = (__bridge NSDictionary *)ref;
        getPid(q, ^(int pid) {
            if (!info.count) { emit(@{@"np": [NSNull null]}); return; }
            NSMutableDictionary *np = [NSMutableDictionary dictionary];
            id v;
            if ((v = info[@"kMRMediaRemoteNowPlayingInfoTitle"])) np[@"title"] = v;
            if ((v = info[@"kMRMediaRemoteNowPlayingInfoArtist"])) np[@"artist"] = v;
            if ((v = info[@"kMRMediaRemoteNowPlayingInfoAlbum"])) np[@"album"] = v;
            if ((v = info[@"kMRMediaRemoteNowPlayingInfoDuration"])) np[@"duration"] = v;
            double elapsed = [info[@"kMRMediaRemoteNowPlayingInfoElapsedTime"] doubleValue];
            double rate = [info[@"kMRMediaRemoteNowPlayingInfoPlaybackRate"] doubleValue];
            NSDate *ts = info[@"kMRMediaRemoteNowPlayingInfoTimestamp"];
            np[@"elapsed"] = @(elapsed);
            np[@"rate"] = @(rate);
            np[@"playing"] = @(rate > 0);
            // Wall-clock time (ms since 1970) at which `elapsed` was true; clients extrapolate.
            np[@"at"] = @((long long)(([ts isKindOfClass:NSDate.class] ? ts.timeIntervalSince1970 : NSDate.date.timeIntervalSince1970) * 1000));
            NSString *art = artwork(info[@"kMRMediaRemoteNowPlayingInfoArtworkData"]);
            if (art) np[@"art"] = art;
            NSRunningApplication *app = pid > 0 ? [NSRunningApplication runningApplicationWithProcessIdentifier:pid] : nil;
            if (app.bundleIdentifier) np[@"bundle"] = app.bundleIdentifier;
            if (app.localizedName) np[@"app"] = app.localizedName;
            emit(@{@"np": np});
            if (getenv("MOTE_NP_ONCE")) exit(0);
        });
    });
}

static void readCommands(void) {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        char buf[256];
        while (fgets(buf, sizeof buf, stdin)) {
            NSString *line = [[NSString stringWithUTF8String:buf] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
            dispatch_async(q, ^{
                if ([line hasPrefix:@"seek "] && setElapsed) {
                    setElapsed(MAX(0, [[line substringFromIndex:5] doubleValue]));
                    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 300 * NSEC_PER_MSEC), q, ^{ refresh(); });
                } else if ([line hasPrefix:@"cmd "] && sendCommand) {
                    sendCommand([[line substringFromIndex:4] intValue], NULL);
                } else if ([line isEqualToString:@"get"]) {
                    lastLine = nil; refresh();
                }
            });
        }
        exit(0);  // the helper went away
    });
}

// Installed as a perl XSUB: void(pTHX_ CV *). Arguments are ignored; it never returns.
void mote_np_main(void *perl, void *cv) {
    (void)perl; (void)cv;
    @autoreleasepool {
        void *mr = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_NOW);
        getInfo = mr ? (GetInfoFn)dlsym(mr, "MRMediaRemoteGetNowPlayingInfo") : NULL;
        getPid = mr ? (GetPidFn)dlsym(mr, "MRMediaRemoteGetNowPlayingApplicationPID") : NULL;
        setElapsed = mr ? (SetElapsedFn)dlsym(mr, "MRMediaRemoteSetElapsedTime") : NULL;
        sendCommand = mr ? (SendCommandFn)dlsym(mr, "MRMediaRemoteSendCommand") : NULL;
        RegisterFn reg = mr ? (RegisterFn)dlsym(mr, "MRMediaRemoteRegisterForNowPlayingNotifications") : NULL;
        if (!getInfo || !getPid || !reg) { fprintf(stdout, "{\"ok\":false}\n"); fflush(stdout); exit(2); }
        q = dispatch_get_main_queue();
        fprintf(stdout, "{\"ok\":true,\"seek\":%s}\n", setElapsed ? "true" : "false");
        fflush(stdout);
        reg(q);
        for (NSString *n in @[@"kMRMediaRemoteNowPlayingInfoDidChangeNotification",
                              @"kMRMediaRemoteNowPlayingApplicationIsPlayingDidChangeNotification",
                              @"kMRMediaRemoteNowPlayingApplicationDidChangeNotification"]) {
            [NSNotificationCenter.defaultCenter addObserverForName:n object:nil queue:nil usingBlock:^(NSNotification *note) { refresh(); }];
        }
        refresh();
        if (!getenv("MOTE_NP_ONCE")) readCommands();
        else dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC), q, ^{ exit(0); });
        dispatch_main();
    }
}
