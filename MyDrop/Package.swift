// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MyDrop",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.0"),
        .package(url: "https://github.com/leif-ibsen/SwiftECC", from: "3.9.0"),
        .package(url: "https://github.com/weichsel/ZIPFoundation.git", from: "0.9.20"),
        .package(url: "https://github.com/dagronf/qrcode.git", from: "20.0.0"),
    ],
    targets: [
        .executableTarget(
            name: "MyDrop",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
                .product(name: "SwiftECC", package: "SwiftECC"),
                .product(name: "ZIPFoundation", package: "ZIPFoundation"),
                .product(name: "QRCode", package: "qrcode"),
            ]
        )
        ,
        .executableTarget(
            name: "MyDropShare",
            linkerSettings: [.unsafeFlags(["-Xlinker", "-e", "-Xlinker", "_NSExtensionMain"])]
        )
    ]
)
