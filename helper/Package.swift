// swift-tools-version:5.9
// Mote Helper: macOS menu-bar app that turns authenticated Mote messages (Wi-Fi WebSocket or BLE) into input.
// Build the .app with ./build.sh (SwiftPM + a script; no Xcode needed).
import PackageDescription
import Foundation

// With only the Command Line Tools, Testing.framework is outside the default search paths: add them (only if present).
let cltFw = "/Library/Developer/CommandLineTools/Library/Developer/Frameworks"
let cltLib = "/Library/Developer/CommandLineTools/Library/Developer/usr/lib"
let hasCLTTesting = FileManager.default.fileExists(atPath: cltFw + "/Testing.framework")
let testSettings: [SwiftSetting] = hasCLTTesting ? [.unsafeFlags(["-F", cltFw])] : []
let testLinker: [LinkerSetting] = hasCLTTesting
    ? [.unsafeFlags(["-F", cltFw, "-Xlinker", "-rpath", "-Xlinker", cltFw, "-Xlinker", "-rpath", "-Xlinker", cltLib])] : []

let package = Package(
    name: "MoteHelper",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "MoteHelper",
            path: "Sources/MoteHelper",
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("CoreBluetooth"),
                .linkedFramework("IOBluetooth"),
                .linkedFramework("Network"),
                .linkedFramework("ScreenCaptureKit"),
                .linkedFramework("ServiceManagement"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("WebKit"),
            ]
        ),
        // helper/tests/unit.sh: pure helper math (Swift Testing, ships with the Command Line Tools).
        .testTarget(name: "MoteHelperTests", dependencies: ["MoteHelper"], path: "tests/MoteHelperTests",
                    swiftSettings: testSettings, linkerSettings: testLinker),
    ]
)
