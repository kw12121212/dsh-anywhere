import Foundation
import XCTest
@testable import DshCore

final class HarnessUrlAndConfigTests: XCTestCase {
    private func loadVectors() throws -> JsonObject {
        let raw = try TestFixtures.loadText(relativeToTestdata: "harness-url-vectors.json")
        return try XCTUnwrap(Json.parse(raw)?.objectValue, "harness-url-vectors.json 不是合法 JSON 对象")
    }

    func testNormalizeVectors() throws {
        let vectors = try loadVectors()
        let cases = try XCTUnwrap(vectors.array("normalize"))
        XCTAssertGreaterThanOrEqual(cases.count, 15, "normalize 向量应覆盖主要形态")

        for (index, item) in cases.enumerated() {
            let m = try XCTUnwrap(item.objectValue)
            let input = try XCTUnwrap(m.string("input"))
            let valid = try XCTUnwrap(m.bool("valid"))
            if valid {
                let expected = try XCTUnwrap(m.string("stripped"))
                let actual = try HarnessUrl.normalize(input)
                XCTAssertEqual(expected, actual, "向量 #\(index) 输入 \(input) 的剥离结果不匹配")
            } else {
                let catStr = try XCTUnwrap(m.string("errorCategory"))
                let expected: HarnessUrl.ErrorCategory
                switch catStr {
                case "empty": expected = .empty
                case "malformed": expected = .malformed
                case "notAbsolute": expected = .notAbsolute
                case "unsupportedScheme": expected = .unsupportedScheme
                default:
                    XCTFail("未知错误类别: \(catStr)")
                    return
                }
                do {
                    _ = try HarnessUrl.normalize(input)
                    XCTFail("向量 #\(index) 输入 \(input) 应以 \(expected) 拒绝，却成功了")
                } catch let err as HarnessUrl.ValidationError {
                    XCTAssertEqual(expected, err.category, "向量 #\(index) 错误类别")
                    XCTAssertFalse(err.description.contains("abc123"), "ValidationError 不得回显凭据")
                }
            }
        }
    }

    func testRedactAndHasToken() {
        XCTAssertEqual("https://dsh.example.test/", HarnessUrl.redact("https://dsh.example.test/?token=abc123"))
        XCTAssertEqual("https://user:xxxxx@dsh.example.test/", HarnessUrl.redact("https://user:secret@dsh.example.test/"))
        XCTAssertEqual("<无法解析的地址>", HarnessUrl.redact("not a url token=abc123"))

        XCTAssertTrue(HarnessUrl.hasToken("https://dsh.example.test/?token=abc123"))
        XCTAssertTrue(HarnessUrl.hasToken("https://dsh.example.test/?%74oken=abc"))
        XCTAssertFalse(HarnessUrl.hasToken("https://dsh.example.test/?Token=2&tokens=1"))
        XCTAssertFalse(HarnessUrl.hasToken("not a url"))
    }

    func testReadConfigVectors() throws {
        let vectors = try loadVectors()
        let cases = try XCTUnwrap(vectors.array("readConfig"))
        XCTAssertGreaterThanOrEqual(cases.count, 6)

        for (index, item) in cases.enumerated() {
            let m = try XCTUnwrap(item.objectValue)
            let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("dsh-cfg-vec-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: tmpDir) }
            try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)

            let store = HarnessConfigStore(directory: tmpDir)
            if let contentVal = m["fileContent"], contentVal != .null {
                let text = try XCTUnwrap(contentVal.stringValue)
                try Data(text.utf8).write(to: tmpDir.appendingPathComponent(HarnessConfig.fileName))
            }

            let expectedStatus = try XCTUnwrap(m.string("expectedStatus"))
            let load = store.load()
            switch expectedStatus {
            case "missing":
                XCTAssertEqual(.missing, load, "向量 #\(index)")
            case "invalid":
                XCTAssertEqual(.invalid, load, "向量 #\(index)")
            case "ok":
                let expectedUrl = try XCTUnwrap(m.string("expectedUrl"))
                XCTAssertEqual(.ok(expectedUrl), load, "向量 #\(index)")
            default:
                XCTFail("未知状态: \(expectedStatus)")
            }
        }
    }

    func testSaveIsAtomicAndRoundTrips() throws {
        let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("dsh-cfg-save-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let store = HarnessConfigStore(directory: tmpDir)
        XCTAssertEqual(.missing, store.load())

        let clean = try store.save("http://127.0.0.1:13080/?token=abc123")
        XCTAssertEqual("http://127.0.0.1:13080/", clean)
        XCTAssertEqual(.ok("http://127.0.0.1:13080/"), store.load())

        let fileContent = try String(contentsOf: tmpDir.appendingPathComponent("config.json"), encoding: .utf8)
        XCTAssertFalse(fileContent.contains("abc123"), "配置文件不得含 token")
        XCTAssertEqual("{\n  \"harnessURL\": \"http://127.0.0.1:13080/\"\n}\n", fileContent)

        let leftovers = try FileManager.default.contentsOfDirectory(atPath: tmpDir.path)
            .filter { $0.hasPrefix(HarnessConfig.tmpPrefix) }
        XCTAssertTrue(leftovers.isEmpty, "不应残留临时文件: \(leftovers)")

        try store.save("https://b.example.test/")
        XCTAssertEqual(.ok("https://b.example.test/"), store.load())
    }

    func testSaveRejectsInvalidWithoutTouchingExisting() throws {
        let tmpDir = FileManager.default.temporaryDirectory.appendingPathComponent("dsh-cfg-inv-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let store = HarnessConfigStore(directory: tmpDir)
        try store.save("https://a.example.test/")
        XCTAssertThrowsError(try store.save("ftp://dsh.example.test/?token=secret"))
        XCTAssertEqual(.ok("https://a.example.test/"), store.load())
    }

    func testJsonStrictNumberGrammarDeepNestingAndSurrogates() throws {
        for bad in ["\"x\": -", "\"x\": 01", "\"x\": 1.", "\"x\": 1.5e", "\"x\": -.5"] {
            XCTAssertNil(Json.parse("{\(bad)}"), "应拒绝: \(bad)")
        }
        for good in ["\"x\": 0", "\"x\": -3", "\"x\": 1.25", "\"x\": 1.5e10", "\"x\": 2E-4"] {
            XCTAssertNotNil(Json.parse("{\(good)}"), "应接受: \(good)")
        }

        let deep = String(repeating: "{\"a\":", count: Json.maxDepth + 8) + "1" + String(repeating: "}", count: Json.maxDepth + 8)
        XCTAssertNil(Json.parse(deep))

        let allowed = "{\"harnessURL\":\"https://dsh.example.test/\",\"deep\":" +
            String(repeating: "[1,", count: 64) + "1" + String(repeating: "]", count: 64) + "}"
        XCTAssertEqual(.ok("https://dsh.example.test/"), HarnessConfig.parseContent(allowed))

        let emoji = Json.parse(#"{"s":"\uD83D\uDE00"}"#)?.objectValue?.string("s")
        XCTAssertEqual("😀", emoji)
        XCTAssertNil(Json.parse(#"{"bad":"\uD83D"}"#))
        XCTAssertNil(Json.parse(#"{"a":1}x"#), "拒绝尾随非空白字符")

        // 保序编码与键序无关相等性
        let o1 = JsonObject([("b", .number("1")), ("a", .number("2"))])
        let o2 = JsonObject([("a", .number("2")), ("b", .number("1"))])
        XCTAssertEqual(o1, o2)
        XCTAssertEqual(#"{"b":1,"a":2}"#, Json.encode(.object(o1)))
    }
}
