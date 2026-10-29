// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "GDirectCore", platforms: [.iOS(.v16), .macOS(.v13)], products: [.library(name: "GDirectCore", targets: ["GDirectCore"])], targets: [.target(name: "GDirectCore"), .testTarget(name: "GDirectCoreTests", dependencies: ["GDirectCore"])])
