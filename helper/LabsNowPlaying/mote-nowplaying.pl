# Mote Labs (preview): runs MoteNowPlaying.dylib inside /usr/bin/perl, an Apple-signed binary that
# MediaRemote still answers on macOS 15.4+. Usage: /usr/bin/perl mote-nowplaying.pl /path/MoteNowPlaying.dylib
use strict;
use DynaLoader;
my $path = shift or die "usage: mote-nowplaying.pl DYLIB\n";
my $lib = DynaLoader::dl_load_file($path, 0) or die "load failed: " . DynaLoader::dl_error() . "\n";
my $sym = DynaLoader::dl_find_symbol($lib, "mote_np_main") or die "symbol missing\n";
my $fn = DynaLoader::dl_install_xsub("main::mote_np_main", $sym);
&$fn();
