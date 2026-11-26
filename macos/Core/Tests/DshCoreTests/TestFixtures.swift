import Foundation
import XCTest
@testable import DshCore

enum TestFixtures {
    static func repoRootURL(filePath: StaticString = #filePath) -> URL {
        // .../macos/Core/Tests/DshCoreTests/TestFixtures.swift -> 向上五级到仓库根
        URL(fileURLWithPath: "\(filePath)")
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    static func testdataURL(filePath: StaticString = #filePath) -> URL {
        repoRootURL(filePath: filePath).appendingPathComponent("testdata")
    }

    static func loadText(relativeToTestdata path: String, filePath: StaticString = #filePath) throws -> String {
        let url = testdataURL(filePath: filePath).appendingPathComponent(path)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "找不到共享测试文件: \(url.path)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    static func dsbotText(_ name: String, filePath: StaticString = #filePath) throws -> String {
        try loadText(relativeToTestdata: "dsbot/\(name)", filePath: filePath)
    }

    static func dsbotJson(_ name: String, filePath: StaticString = #filePath) throws -> JsonValue {
        let text = try dsbotText(name, filePath: filePath)
        return try XCTUnwrap(Json.parse(text), "\(name) 不是合法 JSON")
    }

    static func dsbotValue(_ name: String, filePath: StaticString = #filePath) throws -> JsonValue {
        let root = try XCTUnwrap(try dsbotJson(name, filePath: filePath).objectValue, "\(name) 根节点不是对象")
        return try XCTUnwrap(root["value"], "\(name) 缺少 value 字段")
    }
}
