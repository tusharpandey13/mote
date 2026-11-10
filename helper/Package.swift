// swift-tools-version:5.9
// Mote Helper: macOS menu-bar app that turns authenticated Mote messages (Wi-Fi WebSocket or BLE) into input.
// Build the .app with ./build.sh (SwiftPM + a script; no Xcode needed).
import PackageDescription

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
        // helper/tests/unit.sh: unit tests (Swift Testing, ships with the Command Line Tools; plain `swift test` does not work without Xcode).
        .testTarget(name: "MoteHelperTests", dependencies: ["MoteHelper"], path: "tests/MoteHelperTests"),
    ]
)
