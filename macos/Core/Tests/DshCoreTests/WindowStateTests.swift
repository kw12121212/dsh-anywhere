import Foundation
import XCTest
@testable import DshCore

/// 窗口状态记忆纯逻辑（macos-app 规格 macos-window-state-memory）：编解码、数值
/// 校验、屏幕相交判定与 0600 原子持久化的容错语义。
final class WindowStateTests: XCTestCase {
    private func makeTempDir() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("window-state-\(UUID().uuidString)")
    }

    private func makeState(
        x: Double = 200,
        y: Double = 120,
        width: Double = 1200,
        height: Double = 800,
        sidebarVisible: Bool = true,
        inspectorVisible: Bool = false,
        sidebarWidth: Double = 260
    ) -> MacosWindowState {
        MacosWindowState(
            x: x,
            y: y,
            width: width,
            height: height,
            sidebarVisible: sidebarVisible,
            inspectorVisible: inspectorVisible,
            sidebarWidth: sidebarWidth
        )
    }

    // MARK: - 编解码

    func testEncodeDecodeRoundTrip() {
        let original = makeState(x: -100.5, y: 42, width: 1180, height: 760, sidebarWidth: 300)
        let decoded = MacosWindowState.decode(original.encode())
        XCTAssertEqual(decoded, original)
    }

    func testDecodeRejectsMissingOrMalformedFields() {
        XCTAssertNil(MacosWindowState.decode("{"))
        XCTAssertNil(MacosWindowState.decode("{\"x\":200}"))
        XCTAssertNil(MacosWindowState.decode("{\"x\":200,\"y\":120,\"width\":\"1200\",\"height\":800}"))
        XCTAssertNil(MacosWindowState.decode("{\"x\":200,\"y\":120,\"width\":1200}"))
    }

    func testDecodeRejectsInvalidGeometry() {
        // 非正或超上限尺寸：按无状态处理（回退默认几何）
        XCTAssertNil(MacosWindowState.decode(makeState(width: 0).encode()))
        XCTAssertNil(MacosWindowState.decode(makeState(width: -320).encode()))
        XCTAssertNil(MacosWindowState.decode(makeState(height: 99_999).encode()))
        XCTAssertNil(MacosWindowState.decode(makeState(width: 300).encode())) // 低于最小宽度
    }

    func testDecodeAllowsOffscreenPosition() {
        // 位置允许在所有屏幕之外（是否可见由相交判定负责），坐标本身必须有限
        let offscreen = makeState(x: 5000, y: 3000)
        XCTAssertEqual(MacosWindowState.decode(offscreen.encode()), offscreen)
        XCTAssertNil(
            MacosWindowState.decode("{\"x\":1e999,\"y\":120,\"width\":1200,\"height\":800}")
        )
    }

    func testDecodeDefaultsForPanelFields() {
        // 开合与宽度缺省：侧栏默认展开、检查器默认展开、宽度取默认
        let decoded = MacosWindowState.decode(
            "{\"x\":100,\"y\":80,\"width\":1000,\"height\":640}"
        )
        XCTAssertEqual(decoded?.sidebarVisible, true)
        XCTAssertEqual(decoded?.inspectorVisible, true)
        XCTAssertEqual(decoded?.sidebarWidth, MacosWindowState.defaultSidebarWidth)
    }

    func testSidebarWidthClampedOnDecodeAndInit() {
        XCTAssertEqual(MacosWindowState.clampSidebarWidth(50), MacosWindowState.minSidebarWidth)
        XCTAssertEqual(MacosWindowState.clampSidebarWidth(900), MacosWindowState.maxSidebarWidth)
        XCTAssertEqual(MacosWindowState.clampSidebarWidth(.nan), MacosWindowState.defaultSidebarWidth)
        // 越界宽度只钳制、不使整个状态失效
        let decoded = MacosWindowState.decode(
            "{\"x\":100,\"y\":80,\"width\":1000,\"height\":640,\"sidebarWidth\":20}"
        )
        XCTAssertEqual(decoded?.sidebarWidth, MacosWindowState.minSidebarWidth)
        XCTAssertEqual(makeState(sidebarWidth: 999).sidebarWidth, MacosWindowState.maxSidebarWidth)
    }

    // MARK: - 屏幕相交判定

    private let mainScreen = MacosScreenState(x: 0, y: 0, width: 1920, height: 1080)
    private let sideScreen = MacosScreenState(x: 1920, y: 0, width: 1280, height: 800)

    func testIntersectsAnyScreen() {
        XCTAssertTrue(makeState(x: 100, y: 100).intersectsAnyScreen([mainScreen, sideScreen]))
        // 与外接屏部分相交（左上角伸入外接屏）同样可见
        XCTAssertTrue(
            makeState(x: 1700, y: 600, width: 600, height: 500)
                .intersectsAnyScreen([mainScreen, sideScreen])
        )
        // 完全在两块屏之外
        XCTAssertFalse(
            makeState(x: 5000, y: 3000).intersectsAnyScreen([mainScreen, sideScreen])
        )
        // 仅贴边（重叠面积 0）不算可见
        XCTAssertFalse(
            makeState(x: 1920, y: 100).intersectsAnyScreen([mainScreen])
        )
        XCTAssertFalse(makeState(x: 100, y: 100).intersectsAnyScreen([]))
    }

    // MARK: - 持久化

    func testStoreSaveLoadRoundTripAndPermissions() throws {
        let dir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = WindowStateStore(directory: dir)

        XCTAssertNil(store.load(), "目录尚无状态文件时应返回 nil")

        let state = makeState()
        try store.save(state)
        XCTAssertEqual(store.load(), state)

        let permissions = try FileManager.default.attributesOfItem(atPath: store.fileURL.path)[.posixPermissions] as? Int
        XCTAssertEqual(permissions, 0o600, "状态文件权限必须为 0600")
        // 目录内不残留临时文件
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasPrefix(MacosWindowState.tmpPrefix) }
        XCTAssertTrue(leftovers.isEmpty, "原子写后不得残留临时文件")
    }

    func testStoreOverwritesPreviousState() throws {
        let dir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = WindowStateStore(directory: dir)

        try store.save(makeState(x: 300, y: 200, width: 1100, height: 700))
        try store.save(makeState(x: 40, y: 60, width: 900, height: 600))
        XCTAssertEqual(store.load(), makeState(x: 40, y: 60, width: 900, height: 600))
    }

    func testStoreToleratesCorruptAndInvalidContent() throws {
        let dir = makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = WindowStateStore(directory: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try "not-json{".write(to: store.fileURL, atomically: true, encoding: .utf8)
        XCTAssertNil(store.load(), "损坏内容按无状态处理")

        try "{\"x\":10,\"y\":10,\"width\":0,\"height\":800}".write(
            to: store.fileURL, atomically: true, encoding: .utf8
        )
        XCTAssertNil(store.load(), "数值无效按无状态处理")
    }

    func testJsonNumberFormatting() {
        XCTAssertEqual(JsonNumber.of(1200), "1200")
        XCTAssertEqual(JsonNumber.of(-0.5), "-0.5")
        XCTAssertEqual(JsonNumber.of(100.25), "100.25")
        XCTAssertEqual(JsonNumber.of(.infinity), "0")
    }
}
