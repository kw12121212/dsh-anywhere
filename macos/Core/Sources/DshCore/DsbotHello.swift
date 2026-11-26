import Foundation

/// DS-Bot 0.3.10 版本握手与 feed 首帧 hello 解码（与 `DsbotHello.kt` 对齐）。
public enum DsbotHello: Sendable {
    public static let clientApi = 1
    public static let requestPayload = "{\"api\":[\(clientApi)]}"

    public struct Hello: Equatable, Sendable {
        public let api: Int?
        public let capabilities: Set<String>
        public let boot: String?
        public let seq: Int64?
        public let pluginVersion: String?

        public init(
            api: Int?,
            capabilities: Set<String>,
            boot: String?,
            seq: Int64?,
            pluginVersion: String?
        ) {
            self.api = api
            self.capabilities = capabilities
            self.boot = boot
            self.seq = seq
            self.pluginVersion = pluginVersion
        }
    }

    public struct FeedHello: Equatable, Sendable {
        public let revision: String?
        public let api: Int?
        public let boot: String?
        public let seq: Int64?

        public init(revision: String?, api: Int?, boot: String?, seq: Int64?) {
            self.revision = revision
            self.api = api
            self.boot = boot
            self.seq = seq
        }
    }

    public static func decode(_ value: JsonValue) -> Hello? {
        guard let obj = value.objectValue,
              let capsArr = obj.array("capabilities")
        else {
            return nil
        }
        let caps = Set(capsArr.compactMap(\.stringValue))
        let plugin = obj.object("plugin")
        return Hello(
            api: obj.int("api"),
            capabilities: caps,
            boot: obj.string("boot"),
            seq: obj.int64("seq"),
            pluginVersion: plugin?.string("version")
        )
    }

    public static func decodeFeedHello(_ dataJson: String) -> FeedHello? {
        guard let obj = Json.parse(dataJson)?.objectValue else { return nil }
        return FeedHello(
            revision: obj.scalarString("revision"),
            api: obj.int("api"),
            boot: obj.string("boot"),
            seq: obj.int64("seq")
        )
    }
}

/// 远端能力集合门控（与 `Capabilities` in `DsbotHello.kt` 对齐）。
public struct Capabilities: Equatable, Sendable {
    public enum Mode: Equatable, Sendable {
        case declared
        case legacy
        case incompatible
    }

    public let mode: Mode
    public let names: Set<String>
    public let boot: String?

    private init(mode: Mode, names: Set<String>, boot: String?) {
        self.mode = mode
        self.names = names
        self.boot = boot
    }

    public static let legacy = Capabilities(mode: .legacy, names: [], boot: nil)

    public static func from(_ result: DsbotApi.Result) -> Capabilities {
        guard case .ok(let value) = result,
              let hello = DsbotHello.decode(value)
        else {
            return .legacy
        }
        guard let api = hello.api, api <= DsbotHello.clientApi else {
            return Capabilities(mode: .incompatible, names: [], boot: hello.boot)
        }
        return Capabilities(mode: .declared, names: hello.capabilities, boot: hello.boot)
    }

    public func has(_ name: String) -> Bool {
        mode == .declared && names.contains(name)
    }

    public var versionMismatch: Bool {
        mode == .incompatible
    }

    public func shouldReread(_ hello: DsbotHello.FeedHello) -> Bool {
        if let remoteBoot = hello.boot {
            return remoteBoot != boot
        }
        return mode != .legacy
    }
}
