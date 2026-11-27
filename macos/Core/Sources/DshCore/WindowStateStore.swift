import Foundation

/// 主窗口状态记忆的纯逻辑（macos-app 规格 macos-window-state-memory）：
/// 窗口几何、侧栏与检查器开合及侧栏宽度的模型、数值校验、与屏幕矩形的相交判定、
/// JSON 编解码与 `0600` 原子持久化。坐标沿用调用方一贯的坐标系（App 层直接传
/// AppKit 全局坐标），本类型只做矩形相交运算、不感知 NSScreen。遵循
/// tech/state-persistence：独立 window-state.json、临时文件+重命名原子写、
/// 读取端任何失败一律按无状态处理，绝不影响其他配置。
public struct MacosScreenState: Equatable, Sendable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

/// 一份已保存的主窗口状态；几何字段为窗口 frame 的 origin 与 size。
public struct MacosWindowState: Equatable, Sendable {
    public static let defaultWidth: Double = 960
    public static let defaultHeight: Double = 640
    public static let defaultSidebarWidth: Double = 220
    public static let minSidebarWidth: Double = 160
    public static let maxSidebarWidth: Double = 480

    /// 窗口尺寸的合理范围（超出按无状态处理，回退默认几何）。
    public static let minWindowWidth: Double = 320
    public static let maxWindowWidth: Double = 16_384
    public static let minWindowHeight: Double = 240
    public static let maxWindowHeight: Double = 16_384

    public var x: Double
    public var y: Double
    public var width: Double
    public var height: Double
    public var sidebarVisible: Bool
    public var inspectorVisible: Bool
    public var sidebarWidth: Double

    public init(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        sidebarVisible: Bool = true,
        inspectorVisible: Bool = true,
        sidebarWidth: Double = MacosWindowState.defaultSidebarWidth
    ) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.sidebarVisible = sidebarVisible
        self.inspectorVisible = inspectorVisible
        self.sidebarWidth = MacosWindowState.clampSidebarWidth(sidebarWidth)
    }

    /// 几何是否有效（坐标有限且尺寸在合理范围内）；位置允许在所有屏幕之外，
    /// 由 `intersectsAnyScreen` 单独判定。
    public var hasValidGeometry: Bool {
        let dimsValid = (Self.minWindowWidth...Self.maxWindowWidth).contains(width)
            && (Self.minWindowHeight...Self.maxWindowHeight).contains(height)
        return x.isFinite && y.isFinite && dimsValid
    }

    /// 与任一屏幕矩形有面积大于 0 的重叠即视为可见（仅贴边不算）。
    public func intersectsAnyScreen(_ screens: [MacosScreenState]) -> Bool {
        screens.contains { screen in
            x < screen.x + screen.width
                && screen.x < x + width
                && y < screen.y + screen.height
                && screen.y < y + height
        }
    }

    /// 把侧栏宽度钳入合法范围（恢复端钳制而非拒绝，避免旧状态因宽度越界整体失效）。
    public static func clampSidebarWidth(_ raw: Double) -> Double {
        guard raw.isFinite else { return defaultSidebarWidth }
        return min(max(raw, minSidebarWidth), maxSidebarWidth)
    }
}

public extension MacosWindowState {
    static let fileName = "window-state.json"
    static let tmpPrefix = ".window-state.json."

    /// 解析 window-state.json 内容：语法错误、几何字段缺失/类型不符/数值非法一律
    /// 返回 nil（按无状态处理）；侧栏/检查器开合与宽度缺省时取默认，宽度钳入范围。
    static func decode(_ text: String) -> MacosWindowState? {
        guard let root = Json.parse(text)?.objectValue else { return nil }
        guard let x = root["x"]?.doubleValue,
              let y = root["y"]?.doubleValue,
              let width = root["width"]?.doubleValue,
              let height = root["height"]?.doubleValue
        else { return nil }
        let sidebarVisible = root.bool("sidebarVisible") ?? true
        let inspectorVisible = root.bool("inspectorVisible") ?? true
        let sidebarWidth = root["sidebarWidth"]?.doubleValue ?? defaultSidebarWidth
        let state = MacosWindowState(
            x: x,
            y: y,
            width: width,
            height: height,
            sidebarVisible: sidebarVisible,
            inspectorVisible: inspectorVisible,
            sidebarWidth: sidebarWidth
        )
        guard state.hasValidGeometry else { return nil }
        return state
    }

    /// 序列化为 window-state.json 的字节形态（与桌面端配置文件一致的换行风格）。
    func encode() -> String {
        "{\n"
            + "  \"x\": \(JsonNumber.of(x)),\n"
            + "  \"y\": \(JsonNumber.of(y)),\n"
            + "  \"width\": \(JsonNumber.of(width)),\n"
            + "  \"height\": \(JsonNumber.of(height)),\n"
            + "  \"sidebarVisible\": \(sidebarVisible),\n"
            + "  \"inspectorVisible\": \(inspectorVisible),\n"
            + "  \"sidebarWidth\": \(JsonNumber.of(sidebarWidth))\n"
            + "}\n"
    }
}

enum JsonNumber {
    /// Double → JSON 数字文本：整数值输出整数字面量，其余保留至多 3 位小数
    /// （`String(format:)` 的 `%f` 恒用小数点，不随 locale 变化）。
    static func of(_ value: Double) -> String {
        guard value.isFinite else { return "0" }
        if value == value.rounded(), abs(value) < 1e15 {
            return String(Int64(value))
        }
        var text = String(format: "%.3f", value)
        while text.hasSuffix("0") {
            text.removeLast()
        }
        if text.hasSuffix(".") {
            text.removeLast()
        }
        return text
    }
}

/// 基于指定目录的 window-state.json 读写与 `0600` 原子持久化（目录由 App 层注入）。
/// 读取端把文件缺失、无法读取、内容损坏与数值无效一律当作没有保存状态。
public final class WindowStateStore: Sendable {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public var fileURL: URL {
        directory.appendingPathComponent(MacosWindowState.fileName)
    }

    public func load() -> MacosWindowState? {
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8)
        else {
            return nil
        }
        return MacosWindowState.decode(text)
    }

    public func save(_ state: MacosWindowState) throws {
        try AtomicFileWriter.write(
            data: Data(state.encode().utf8),
            to: fileURL,
            in: directory,
            tmpPrefix: MacosWindowState.tmpPrefix
        )
    }
}
