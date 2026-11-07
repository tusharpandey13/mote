import CoreGraphics
import Testing
@testable import MoteHelper

/// Labs › Live screen: tap -> click mapping (docs/labs/live-screen.md "Coordinates") and rate control.
struct ScreenMathTests {
    let main = ScreenMath.Geometry(id: "1", bounds: CGRect(x: 0, y: 0, width: 1512, height: 982), scale: 2)
    // A 1x external display left of and above the main one: negative global origin.
    let left = ScreenMath.Geometry(id: "2", bounds: CGRect(x: -1920, y: -300, width: 1920, height: 1080), scale: 1)

    @Test func cornersAndCentreOnMain() {
        #expect(ScreenMath.point(nx: 0, ny: 0, in: main) == CGPoint(x: 0, y: 0))
        #expect(ScreenMath.point(nx: 0.5, ny: 0.5, in: main) == CGPoint(x: 756, y: 491))
        #expect(ScreenMath.point(nx: 1, ny: 1, in: main) == CGPoint(x: 1511, y: 981))   // stays on the display
    }

    @Test func offsetDisplay() {
        #expect(ScreenMath.point(nx: 0, ny: 0, in: left) == CGPoint(x: -1920, y: -300))
        #expect(ScreenMath.point(nx: 0.5, ny: 0.5, in: left) == CGPoint(x: -960, y: 240))
        #expect(ScreenMath.point(nx: 1, ny: 1, in: left) == CGPoint(x: -1, y: 779))
    }

    @Test func retinaFramePixelsMapToPoints() {
        // An 800 px frame of a 1512 pt (3024 px Retina) display: pixel 400 is the middle, in points.
        #expect(main.height(forWidth: 800) == 520)
        #expect(ScreenMath.point(px: 400, py: 260, frameWidth: 800, in: main) == CGPoint(x: 756, y: 491))
        // The same display at 1x maps identically: the backing scale never enters the mapping.
        let oneX = ScreenMath.Geometry(id: "3", bounds: main.bounds, scale: 1)
        #expect(ScreenMath.point(px: 400, py: 260, frameWidth: 800, in: oneX) == ScreenMath.point(px: 400, py: 260, frameWidth: 800, in: main))
    }

    @Test func outOfRangeIsClamped() {
        #expect(ScreenMath.point(nx: -3, ny: 7, in: left) == CGPoint(x: -1920, y: 779))
    }

    @Test func cursorRoundTrip() throws {
        let p = ScreenMath.point(nx: 0.25, ny: 0.75, in: left)
        let n = try #require(ScreenMath.norm(p, in: left))
        #expect(abs(n.x - 0.25) < 0.001 && abs(n.y - 0.75) < 0.001)
        #expect(ScreenMath.norm(CGPoint(x: 10, y: 10), in: left) == nil)   // on the other display
    }

    @Test func fakeDisplayIsRetinaWithOffset() {
        let g = ScreenMath.fakeDisplay
        #expect(g.scale == 2 && g.bounds.minX < 0 && g.bounds.minY < 0)
        #expect(ScreenMath.point(nx: 0.5, ny: 0.5, in: g) == CGPoint(x: -756, y: 291))
    }

    @Test func adaptBacksOffThenRecovers() {
        var r = ScreenMath.adapt(fps: 8, quality: 0.55, width: 800, starved: 3, rttMs: 50, fpsCap: 15, widthCap: 960)
        #expect(r.fps == 6 && r.quality == 0.55)
        r = ScreenMath.adapt(fps: 2, quality: 0.55, width: 800, starved: 1, rttMs: 400, fpsCap: 15, widthCap: 960)
        #expect(r.fps == 2 && r.quality < 0.55)
        r = ScreenMath.adapt(fps: 2, quality: 0.3, width: 800, starved: 1, rttMs: 400, fpsCap: 15, widthCap: 960)
        #expect(r.width == 640)
        r = ScreenMath.adapt(fps: 2, quality: 0.3, width: 480, starved: 9, rttMs: 900, fpsCap: 15, widthCap: 960)
        #expect(r.width == 480 && r.fps == 2)   // floor
        r = ScreenMath.adapt(fps: 14, quality: 0.68, width: 960, starved: 0, rttMs: 40, fpsCap: 15, widthCap: 960)
        #expect(r.fps == 15 && r.quality <= 0.7)
    }

    @Test func jpegFitsBudget() throws {
        let img = try #require(ScreenMath.fakeFrame(n: 7, width: 960, height: 624))
        let j = try #require(ScreenMath.jpeg(img, width: 960, height: 624, quality: 0.7, budget: 240 * 1024))
        #expect(j.data.starts(with: [0xFF, 0xD8]))
        #expect((j.data.count + 2) / 3 * 4 <= 240 * 1024)
        #expect(ScreenMath.jpeg(img, width: 960, height: 624, quality: 0.7, budget: 100) == nil)
    }
}
