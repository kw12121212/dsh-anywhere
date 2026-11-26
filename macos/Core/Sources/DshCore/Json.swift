import Foundation

/// 保序 JSON 对象：保留插入顺序用于确定性序列化与顺序遍历，`Equatable` 按字典键值语义比较。
public struct JsonObject: Equatable, Sendable {
    public private(set) var entries: [(key: String, value: JsonValue)] = []
    private var indexByKey: [String: Int] = [:]

    public init(_ entries: [(String, JsonValue)] = []) {
        for (k, v) in entries {
            set(k, v)
        }
    }

    public var count: Int { entries.count }
    public var isEmpty: Bool { entries.isEmpty }
    public var keys: [String] { entries.map(\.key) }

    public func contains(_ key: String) -> Bool {
        indexByKey[key] != nil
    }

    public subscript(_ key: String) -> JsonValue? {
        get {
            guard let idx = indexByKey[key] else { return nil }
            return entries[idx].value
        }
        set {
            if let value = newValue {
                set(key, value)
            } else if let idx = indexByKey.removeValue(forKey: key) {
                entries.remove(at: idx)
                for i in idx..<entries.count {
                    indexByKey[entries[i].key] = i
                }
            }
        }
    }

    public mutating func set(_ key: String, _ value: JsonValue) {
        if let idx = indexByKey[key] {
            entries[idx] = (key, value)
        } else {
            indexByKey[key] = entries.count
            entries.append((key, value))
        }
    }

    public static func == (lhs: JsonObject, rhs: JsonObject) -> Bool {
        guard lhs.count == rhs.count else { return false }
        for (k, v) in lhs.entries {
            guard rhs[k] == v else { return false }
        }
        return true
    }

    // MARK: - 便捷读取

    public func string(_ key: String) -> String? {
        self[key]?.stringValue
    }

    public func nonEmptyString(_ key: String) -> String? {
        guard let s = string(key), !s.isEmpty else { return nil }
        return s
    }

    public func nonBlankString(_ key: String) -> String? {
        guard let s = string(key), !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    public func bool(_ key: String) -> Bool? {
        self[key]?.boolValue
    }

    public func int(_ key: String) -> Int? {
        self[key]?.intValue
    }

    public func int64(_ key: String) -> Int64? {
        self[key]?.int64Value
    }

    public func scalarString(_ key: String) -> String? {
        switch self[key] {
        case .number(let raw): return raw
        case .string(let s): return s
        default: return nil
        }
    }

    public func object(_ key: String) -> JsonObject? {
        self[key]?.objectValue
    }

    public func array(_ key: String) -> [JsonValue]? {
        self[key]?.arrayValue
    }

    public func stringList(_ key: String) -> [String] {
        array(key)?.compactMap(\.stringValue) ?? []
    }
}

/// 严格 JSON 值模型（与 `Json.kt` 对齐，保留数字原始文本以兼容大整数与版本/修订号）。
public indirect enum JsonValue: Equatable, Sendable {
    case object(JsonObject)
    case array([JsonValue])
    case string(String)
    case number(String)
    case bool(Bool)
    case null

    public var objectValue: JsonObject? {
        if case .object(let obj) = self { return obj }
        return nil
    }

    public var arrayValue: [JsonValue]? {
        if case .array(let arr) = self { return arr }
        return nil
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var numberRaw: String? {
        if case .number(let raw) = self { return raw }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var intValue: Int? {
        numberRaw.flatMap(Int.init)
    }

    public var int64Value: Int64? {
        numberRaw.flatMap(Int64.init)
    }

    public var doubleValue: Double? {
        numberRaw.flatMap(Double.init)
    }
}

/// JSON 字符串字面量构造（转义集合与 `Json.parse` 及 Kotlin `JsonString` 对齐）。
public enum JsonString: Sendable {
    public static func of(_ s: String) -> String {
        var out = "\""
        out.reserveCapacity(s.count + 2)
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x00..<0x20:
                let hex = String(scalar.value, radix: 16, uppercase: false)
                out += "\\u" + String(repeating: "0", count: max(0, 4 - hex.count)) + hex
            default:
                out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
        return out
    }
}

/// 极简严格 RFC 8259 JSON 解析与序列化：拒绝前导零、裸负号、尾随非空白、控制字符与超深嵌套。
public enum Json: Sendable {
    public static let maxDepth = 512

    public static func encode(_ value: JsonValue) -> String {
        var out = ""
        encodeTo(value, into: &out)
        return out
    }

    private static func encodeTo(_ value: JsonValue, into out: inout String) {
        switch value {
        case .object(let obj):
            out += "{"
            var first = true
            for (k, v) in obj.entries {
                if !first { out += "," }
                first = false
                out += JsonString.of(k)
                out += ":"
                encodeTo(v, into: &out)
            }
            out += "}"
        case .array(let items):
            out += "["
            var first = true
            for item in items {
                if !first { out += "," }
                first = false
                encodeTo(item, into: &out)
            }
            out += "]"
        case .string(let s):
            out += JsonString.of(s)
        case .number(let raw):
            out += raw
        case .bool(let b):
            out += b ? "true" : "false"
        case .null:
            out += "null"
        }
    }

    public static func parse(_ text: String) -> JsonValue? {
        var parser = Parser(text)
        guard let value = parser.parseValue(depth: 0) else { return nil }
        parser.skipWhitespace()
        return parser.atEnd ? value : nil
    }

    private struct Parser {
        private let bytes: [UInt8]
        private var pos: Int = 0

        init(_ text: String) {
            self.bytes = Array(text.utf8)
        }

        var atEnd: Bool { pos >= bytes.count }

        mutating func skipWhitespace() {
            while pos < bytes.count {
                switch bytes[pos] {
                case 0x20, 0x09, 0x0A, 0x0D:
                    pos += 1
                default:
                    return
                }
            }
        }

        mutating func parseValue(depth: Int) -> JsonValue? {
            if depth > Json.maxDepth { return nil }
            skipWhitespace()
            guard !atEnd else { return nil }
            switch bytes[pos] {
            case 0x7B: // '{'
                return parseObject(depth: depth).map(JsonValue.object)
            case 0x5B: // '['
                return parseArray(depth: depth).map(JsonValue.array)
            case 0x22: // '"'
                return parseString().map(JsonValue.string)
            case 0x74: // 't'
                return expectASCII("true") ? .bool(true) : nil
            case 0x66: // 'f'
                return expectASCII("false") ? .bool(false) : nil
            case 0x6E: // 'n'
                return expectASCII("null") ? .null : nil
            case 0x2D, 0x30...0x39:
                return parseNumber()
            default:
                return nil
            }
        }

        private mutating func expectASCII(_ word: StaticString) -> Bool {
            let count = word.utf8CodeUnitCount
            guard pos + count <= bytes.count else { return false }
            for i in 0..<count {
                if bytes[pos + i] != word.utf8Start[i] { return false }
            }
            pos += count
            return true
        }

        private mutating func parseObject(depth: Int) -> JsonObject? {
            pos += 1 // '{'
            var obj = JsonObject()
            skipWhitespace()
            if !atEnd && bytes[pos] == 0x7D { // '}'
                pos += 1
                return obj
            }
            while true {
                skipWhitespace()
                guard !atEnd, bytes[pos] == 0x22, let key = parseString() else { return nil }
                skipWhitespace()
                guard !atEnd, bytes[pos] == 0x3A else { return nil } // ':'
                pos += 1
                guard let val = parseValue(depth: depth + 1) else { return nil }
                obj.set(key, val)
                skipWhitespace()
                guard !atEnd else { return nil }
                switch bytes[pos] {
                case 0x2C: // ','
                    pos += 1
                case 0x7D: // '}'
                    pos += 1
                    return obj
                default:
                    return nil
                }
            }
        }

        private mutating func parseArray(depth: Int) -> [JsonValue]? {
            pos += 1 // '['
            var items: [JsonValue] = []
            skipWhitespace()
            if !atEnd && bytes[pos] == 0x5D { // ']'
                pos += 1
                return items
            }
            while true {
                guard let val = parseValue(depth: depth + 1) else { return nil }
                items.append(val)
                skipWhitespace()
                guard !atEnd else { return nil }
                switch bytes[pos] {
                case 0x2C: // ','
                    pos += 1
                case 0x5D: // ']'
                    pos += 1
                    return items
                default:
                    return nil
                }
            }
        }

        private mutating func parseString() -> String? {
            pos += 1 // '"'
            var outBytes: [UInt8] = []
            while !atEnd {
                let b = bytes[pos]
                if b == 0x22 { // '"'
                    pos += 1
                    return String(decoding: outBytes, as: UTF8.self)
                }
                if b == 0x5C { // '\'
                    pos += 1
                    guard !atEnd else { return nil }
                    switch bytes[pos] {
                    case 0x22: outBytes.append(0x22); pos += 1
                    case 0x5C: outBytes.append(0x5C); pos += 1
                    case 0x2F: outBytes.append(0x2F); pos += 1
                    case 0x62: outBytes.append(0x08); pos += 1
                    case 0x66: outBytes.append(0x0C); pos += 1
                    case 0x6E: outBytes.append(0x0A); pos += 1
                    case 0x72: outBytes.append(0x0D); pos += 1
                    case 0x74: outBytes.append(0x09); pos += 1
                    case 0x75: // 'u'
                        pos += 1
                        guard let u1 = readHex4() else { return nil }
                        if (0xD800...0xDBFF).contains(u1) {
                            // 期待紧随其后的低代理 \uXXXX
                            guard pos + 6 <= bytes.count, bytes[pos] == 0x5C, bytes[pos + 1] == 0x75 else {
                                return nil
                            }
                            pos += 2
                            guard let u2 = readHex4(), (0xDC00...0xDFFF).contains(u2) else {
                                return nil
                            }
                            let cp = 0x10000 + ((UInt32(u1) - 0xD800) << 10) + (UInt32(u2) - 0xDC00)
                            guard let scalar = UnicodeScalar(cp) else { return nil }
                            outBytes.append(contentsOf: String(scalar).utf8)
                        } else if (0xDC00...0xDFFF).contains(u1) {
                            return nil
                        } else {
                            guard let scalar = UnicodeScalar(u1) else { return nil }
                            outBytes.append(contentsOf: String(scalar).utf8)
                        }
                    default:
                        return nil
                    }
                } else {
                    if b < 0x20 { return nil }
                    outBytes.append(b)
                    pos += 1
                }
            }
            return nil
        }

        private mutating func readHex4() -> UInt16? {
            guard pos + 4 <= bytes.count else { return nil }
            var val: UInt16 = 0
            for i in 0..<4 {
                let c = bytes[pos + i]
                let digit: UInt16
                switch c {
                case 0x30...0x39: digit = UInt16(c - 0x30)
                case 0x61...0x66: digit = UInt16(c - 0x61 + 10)
                case 0x41...0x46: digit = UInt16(c - 0x41 + 10)
                default: return nil
                }
                val = (val << 4) | digit
            }
            pos += 4
            return val
        }

        private mutating func parseNumber() -> JsonValue? {
            let start = pos
            if !atEnd && bytes[pos] == 0x2D { // '-'
                pos += 1
            }
            let intStart = pos
            guard !atEnd else { return nil }
            if bytes[pos] == 0x30 { // '0'
                pos += 1
                if !atEnd && (0x30...0x39).contains(bytes[pos]) {
                    return nil
                }
            } else if (0x31...0x39).contains(bytes[pos]) {
                while !atEnd && (0x30...0x39).contains(bytes[pos]) {
                    pos += 1
                }
            } else {
                return nil
            }
            if pos == intStart { return nil }

            if !atEnd && bytes[pos] == 0x2E { // '.'
                pos += 1
                let fracStart = pos
                while !atEnd && (0x30...0x39).contains(bytes[pos]) {
                    pos += 1
                }
                if pos == fracStart { return nil }
            }

            if !atEnd && (bytes[pos] == 0x65 || bytes[pos] == 0x45) { // 'e' / 'E'
                pos += 1
                if !atEnd && (bytes[pos] == 0x2B || bytes[pos] == 0x2D) { // '+' / '-'
                    pos += 1
                }
                let expStart = pos
                while !atEnd && (0x30...0x39).contains(bytes[pos]) {
                    pos += 1
                }
                if pos == expStart { return nil }
            }

            let raw = String(decoding: bytes[start..<pos], as: UTF8.self)
            return .number(raw)
        }
    }
}
