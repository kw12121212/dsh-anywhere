import Foundation

/// 视图卡片的公式（dsbot-rich-render，与 `ViewFormula.kt` 对齐）：移植 deep-view 0.1.2
/// core/formula.js 的小表达式语言——`{ "=": "price * qty" }`，读名字、调用固定函数，无属性
/// 访问、赋值与循环。值为 Double / String / Bool，与 JS 语义一致（数字均为双精度，+ 遇字符串
/// 拼接，== 为严格相等）。
public enum ViewFormula: Sendable {
    public struct FormulaError: Error, Equatable, Sendable {
        public let message: String
        public init(_ message: String) { self.message = message }
    }

    /// 公式值（对应 Kotlin 的 Any：Double / String / Boolean）。
    public indirect enum Value: Equatable, Sendable {
        case number(Double)
        case string(String)
        case boolean(Bool)
    }

    public indirect enum Node: Equatable, Sendable {
        case value(Value)
        case name(String)
        case unary(op: String, value: Node)
        case binary(op: String, left: Node, right: Node)
        case ifNode(test: Node, yes: Node, no: Node)
        case call(name: String, args: [Node])
    }

    public struct Compiled: Equatable, Sendable {
        public let tree: Node?
        public let names: [String]
        public let error: String?

        public init(tree: Node?, names: [String], error: String?) {
            self.tree = tree
            self.names = names
            self.error = error
        }
    }

    struct Fn: Sendable {
        let min: Int
        let max: Int
        let run: @Sendable ([Value]) throws -> Value
    }

    static let functions: [String: Fn] = [
        "min": Fn(min: 1, max: .max) { a in .number(try a.map(num).min() ?? 0) },
        "max": Fn(min: 1, max: .max) { a in .number(try a.map(num).max() ?? 0) },
        "abs": Fn(min: 1, max: 1) { a in .number(abs(try num(a[0]))) },
        "floor": Fn(min: 1, max: 1) { a in .number((try num(a[0])).rounded(.down)) },
        "ceil": Fn(min: 1, max: 1) { a in .number((try num(a[0])).rounded(.up)) },
        "sqrt": Fn(min: 1, max: 1) { a in .number((try num(a[0])).squareRoot()) },
        "pow": Fn(min: 2, max: 2) { a in .number(Foundation.pow(try num(a[0]), try num(a[1]))) },
        "round": Fn(min: 1, max: 2) { a in
            let d = try digitsOf(a.count > 1 ? a[1] : .number(0))
            return .number(roundTo(try num(a[0]), d))
        },
        "clamp": Fn(min: 3, max: 3) { a in
            .number(min(max(try num(a[0]), try num(a[1])), try num(a[2])))
        },
        "fixed": Fn(min: 1, max: 2) { a in
            let d = try digitsOf(a.count > 1 ? a[1] : .number(0))
            return .string(toFixed(roundTo(try num(a[0]), d), d))
        },
        "format": Fn(min: 1, max: 2) { a in
            let d = try digitsOf(a.count > 1 ? a[1] : .number(0))
            return .string(grouped(try num(a[0]), d))
        },
        "percent": Fn(min: 1, max: 2) { a in
            let d = try digitsOf(a.count > 1 ? a[1] : .number(0))
            return .string(grouped(try num(a[0]) * 100, d) + "%")
        },
    ]

    static let maxNesting = 64
    static let operators: [String] = ["==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "%", "<", ">", "!", "?", ":", "(", ")", ","]
    static let binaryLevels: [[String]] = [["||"], ["&&"], ["==", "!="], ["<", "<=", ">", ">="], ["+", "-"], ["*", "/", "%"]]

    /// 函数名的展示序（与 Kotlin FUNCTIONS 声明序一致）。
    static let functionOrder = ["min", "max", "abs", "floor", "ceil", "sqrt", "pow", "round", "clamp", "fixed", "format", "percent"]

    private static func isAsciiLetter(_ c: Character) -> Bool {
        ("a"..."z").contains(c) || ("A"..."Z").contains(c)
    }

    public static func isName(_ value: String?) -> Bool {
        guard let value, !value.isEmpty else { return false }
        let first = value.first!
        guard isAsciiLetter(first) || first == "_" else { return false }
        return value.dropFirst().allSatisfy { isAsciiLetter($0) || $0.isASCII && $0.isNumber || $0 == "_" }
    }

    /// 与 JS Number(string) 一致的子集：十进制（含指数）与 0x 十六进制。
    public static func num(_ value: Value) throws -> Double {
        switch value {
        case .number(let d): return d
        case .boolean(let b): return b ? 1 : 0
        case .string(let s):
            if let d = jsNumber(s.trimmingCharacters(in: .whitespaces)) { return d }
            throw FormulaError("\(quote(value)) is not a number")
        }
    }

    static func jsNumber(_ t: String) -> Double? {
        if t.isEmpty { return nil }
        if isDecimal(t) { return Double(t) }
        if t.hasPrefix("0x") || t.hasPrefix("0X") {
            let hex = t.dropFirst(2)
            guard !hex.isEmpty, hex.allSatisfy({ $0.isHexDigit }) else { return nil }
            // Kotlin BigInteger→Double：超出 UInt64 饱和到 ±∞ 而非归 0。
            guard let v = UInt64(hex, radix: 16) else {
                return t.hasPrefix("-") ? -.infinity : .infinity
            }
            return Double(v)
        }
        if t == "Infinity" || t == "+Infinity" { return .infinity }
        if t == "-Infinity" { return -.infinity }
        return nil
    }

    private static func isDecimal(_ t: String) -> Bool {
        var s = Substring(t)
        if s.hasPrefix("+") || s.hasPrefix("-") { s = s.dropFirst() }
        var seenDigit = false
        var seenDot = false
        var seenExp = false
        var i = s.startIndex
        while i < s.endIndex {
            let c = s[i]
            if c.isNumber {
                seenDigit = true
            } else if c == "." && !seenDot && !seenExp {
                seenDot = true
            } else if (c == "e" || c == "E") && seenDigit && !seenExp {
                seenExp = true
                let next = s.index(after: i)
                if next < s.endIndex, s[next] == "+" || s[next] == "-" { i = next }
            } else {
                return false
            }
            i = s.index(after: i)
        }
        return seenDigit
    }

    static func quote(_ value: Value?) -> String {
        switch value {
        case .string(let s): return "\"" + s.replacingOccurrences(of: "\"", with: "\\\"") + "\""
        case .none: return "null"
        default: return show(value ?? .boolean(false))
        }
    }

    static func digitsOf(_ digits: Value) throws -> Int {
        let v = try num(digits)
        if v != v.rounded(.down) || v < 0 || v > 10 {
            throw FormulaError("digits must be a whole number from 0 to 10")
        }
        return Int(v)
    }

    /// JS Math.round(value * scale) / scale（Math.round：.5 向正无穷）。
    static func roundTo(_ value: Double, _ digits: Int) -> Double {
        let scale = Foundation.pow(10, Double(digits))
        return (value * scale + 0.5).rounded(.down) / scale
    }

    static func toFixed(_ value: Double, _ digits: Int) -> String {
        guard value.isFinite else { return show(.number(value)) }
        // HALF_UP 定标（对应 BigDecimal.setScale(…, HALF_UP)）：放大后 .5 远离零取整再缩回，
        // 避免 printf 的 half-even（fixed(2.5,0) 应为 "3" 而非 "2"）。
        let scale = Foundation.pow(10, Double(digits))
        let scaled = value * scale
        let roundedAway = (abs(scaled) + 0.5).rounded(.down) * (scaled < 0 ? -1 : 1)
        return String(format: "%.\(digits)f", roundedAway / scale)
    }

    static func grouped(_ value: Double, _ digits: Int) -> String {
        guard value.isFinite else { return show(.number(value)) }
        let text = toFixed(abs(roundTo(value, digits)), digits)
        let parts = text.components(separatedBy: ".")
        let whole = parts[0]
        let part = parts.count > 1 ? parts[1] : ""
        let negative = value < 0 && !(whole + part).allSatisfy { $0 == "0" }
        var out = ""
        let chars = Array(whole)
        for (i, c) in chars.enumerated() {
            let remaining = chars.count - i
            if i > 0 && remaining % 3 == 0 { out += "," }
            out.append(c)
        }
        return (negative ? "-" : "") + out + (part.isEmpty ? "" : "." + part)
    }

    /// 值的文字形式：数字去掉浮点噪声（0.1 + 0.2 显示 0.3），整数不带小数点；
    /// ≥1e21 或 <1e-6 用 JS 指数形式。
    public static func show(_ value: Value) -> String {
        switch value {
        case .string(let s): return s
        case .boolean(let b): return b ? "true" : "false"
        case .number(let d):
            if d.isNaN { return "NaN" }
            if d == .infinity { return "Infinity" }
            if d == -.infinity { return "-Infinity" }
            if d == 0 { return "0" }
            let absD = abs(d)
            if absD >= 1e21 || absD < 1e-6 {
                var exp = 0
                var mantissa = d
                if absD >= 1 {
                    while abs(mantissa) >= 10 { mantissa /= 10; exp += 1 }
                } else {
                    while abs(mantissa) < 1 { mantissa *= 10; exp -= 1 }
                }
                let m = stripNoise(mantissa)
                return m + "e" + (exp >= 0 ? "+" : "-") + String(abs(exp))
            }
            return stripNoise(d)
        }
    }

    /// 12 位有效数字四舍五入后去尾零，按纯小数输出（对应
    /// BigDecimal(MathContext(12, HALF_UP)).stripTrailingZeros().toPlainString()；
    /// 不用 %g——它在指数 ≥12 时切 e 记法而 Kotlin 是 toPlainString）。
    private static func stripNoise(_ d: Double) -> String {
        guard d.isFinite, d != 0 else { return d == 0 ? "0" : show(.number(d)) }
        let negative = d < 0
        // %.11e = 12 位有效数字的科学记法（如 "1.23456789012e+12"）。
        let sci = String(format: "%.11e", abs(d))
        let parts = sci.split(separator: "e")
        guard parts.count == 2, let exp = Int(parts[1]) else { return sci }
        var digits = parts[0].drop(while: { $0 == "0" }).replacingOccurrences(of: ".", with: "")
        while digits.hasSuffix("0") { digits.removeLast() }
        if digits.isEmpty { digits = "0" }
        // 小数点位置：digits 首位在 10^exp 位上。
        let pointPos = exp + 1
        let sign = negative ? "-" : ""
        if pointPos <= 0 {
            return sign + "0." + String(repeating: "0", count: -pointPos) + digits
        }
        if pointPos >= digits.count {
            return sign + digits + String(repeating: "0", count: pointPos - digits.count)
        }
        let idx = digits.index(digits.startIndex, offsetBy: pointPos)
        return sign + digits[..<idx] + "." + digits[idx...]
    }

    // MARK: - 词法与解析

    struct Token: Sendable {
        let kind: String
        let value: TokenValue
        let at: Int
    }

    enum TokenValue: Equatable, Sendable {
        case number(Double)
        case string(String)
        case op(String)
        case name(String)
    }

    static func tokenize(_ source: String) throws -> [Token] {
        var tokens: [Token] = []
        let chars = Array(source)
        var at = 0
        while at < chars.count {
            let c = chars[at]
            if c.isWhitespace { at += 1; continue }
            let rest = String(chars[at...])
            if let m = matchNumber(rest) {
                tokens.append(Token(kind: "number", value: .number(Double(m) ?? 0), at: at))
                at += m.count
                continue
            }
            if let m = matchName(rest) {
                tokens.append(Token(kind: "name", value: .name(m), at: at))
                at += m.count
                continue
            }
            if c == "\"" || c == "'" {
                var end = at + 1
                var text = ""
                while end < chars.count && chars[end] != c {
                    if chars[end] == "\\" && end + 1 < chars.count {
                        text.append(chars[end + 1])
                        end += 2
                        continue
                    }
                    text.append(chars[end])
                    end += 1
                }
                if end >= chars.count { throw FormulaError("unclosed string at \(at + 1)") }
                tokens.append(Token(kind: "string", value: .string(text), at: at))
                at = end + 1
                continue
            }
            guard let op = operators.first(where: { rest.hasPrefix($0) }) else {
                throw FormulaError("unexpected \"\(c)\" at \(at + 1)")
            }
            tokens.append(Token(kind: "op", value: .op(op), at: at))
            at += op.count
        }
        return tokens
    }

    static func matchNumber(_ s: String) -> String? {
        var i = s.startIndex
        var seenDigit = false
        while i < s.endIndex, s[i].isNumber {
            seenDigit = true
            i = s.index(after: i)
        }
        if i < s.endIndex, s[i] == "." {
            i = s.index(after: i)
            while i < s.endIndex, s[i].isNumber {
                seenDigit = true
                i = s.index(after: i)
            }
        }
        guard seenDigit else { return nil }
        if i < s.endIndex, s[i] == "e" || s[i] == "E" {
            var j = s.index(after: i)
            if j < s.endIndex, s[j] == "+" || s[j] == "-" { j = s.index(after: j) }
            var expDigits = false
            while j < s.endIndex, s[j].isNumber {
                expDigits = true
                j = s.index(after: j)
            }
            if expDigits { i = j }
        }
        return String(s[s.startIndex..<i])
    }

    static func matchName(_ s: String) -> String? {
        var i = s.startIndex
        guard i < s.endIndex else { return nil }
        let first = s[i]
        guard isAsciiLetter(first) || first == "_" else { return nil }
        i = s.index(after: i)
        while i < s.endIndex {
            let c = s[i]
            if isAsciiLetter(c) || (c.isASCII && c.isNumber) || c == "_" { i = s.index(after: i) } else { break }
        }
        return String(s[s.startIndex..<i])
    }

    struct Parser {
        let tokens: [Token]
        var index = 0
        var depth = 0

        mutating func parse() throws -> Node {
            if tokens.isEmpty { throw FormulaError("the formula is empty") }
            let tree = try ternary()
            if index < tokens.count {
                throw FormulaError("unexpected \"\(describe(tokens[index]))\" at \(tokens[index].at + 1)")
            }
            return tree
        }

        func describe(_ t: Token) -> String {
            switch t.value {
            case .op(let o): return o
            case .name(let n): return n
            case .number(let n): return String(n) // Kotlin Double.toString 形态
            case .string(let s): return s
            }
        }

        func peek() -> Token? { index < tokens.count ? tokens[index] : nil }

        func isOp(_ v: String) -> Bool {
            guard let t = peek(), t.kind == "op" else { return false }
            if case .op(let o) = t.value { return o == v }
            return false
        }

        mutating func expect(_ v: String) throws {
            guard isOp(v) else {
                if let t = peek() {
                    throw FormulaError("expected \"\(v)\" at \(t.at + 1)")
                }
                throw FormulaError("expected \"\(v)\" at the end")
            }
            index += 1
        }

        mutating func ternary() throws -> Node {
            depth += 1
            defer { depth -= 1 }
            if depth > maxNesting { throw FormulaError("nested more than \(maxNesting) levels") }
            let test = try binary(0)
            guard isOp("?") else { return test }
            index += 1
            let yes = try ternary()
            try expect(":")
            return .ifNode(test: test, yes: yes, no: try ternary())
        }

        mutating func binary(_ level: Int) throws -> Node {
            guard level < binaryLevels.count else { return try unary() }
            var left = try binary(level + 1)
            while let t = peek(), t.kind == "op",
                  case .op(let op) = t.value, binaryLevels[level].contains(op) {
                index += 1
                left = .binary(op: op, left: left, right: try binary(level + 1))
            }
            return left
        }

        mutating func primary() throws -> Node {
            guard let t = peek() else { throw FormulaError("the formula ends too early") }
            index += 1
            switch t.value {
            case .number(let n): return .value(.number(n))
            case .string(let s): return .value(.string(s))
            case .op(let o) where o == "(":
                let inner = try ternary()
                try expect(")")
                return inner
            case .name(let name):
                guard !isOp("(") else {
                    guard let fn = functions[name] else {
                        throw FormulaError("unknown function \"\(name)\"; allowed: \(functionOrder.joined(separator: ", "))")
                    }
                    index += 1
                    var args: [Node] = []
                    if !isOp(")") {
                        args.append(try ternary())
                        while isOp(",") {
                            index += 1
                            args.append(try ternary())
                        }
                    }
                    try expect(")")
                    if args.count < fn.min || args.count > fn.max {
                        throw FormulaError("\(name)() takes a different number of arguments; got \(args.count)")
                    }
                    return .call(name: name, args: args)
                }
                switch name {
                case "true": return .value(.boolean(true))
                case "false": return .value(.boolean(false))
                default: return .name(name)
                }
            default:
                throw FormulaError("unexpected \"\(describe(t))\" at \(t.at + 1)")
            }
        }

        mutating func unary() throws -> Node {
            if isOp("-") || isOp("!") || isOp("+") {
                guard let t = peek(), case .op(let op) = t.value else { throw FormulaError("bad unary") }
                index += 1
                depth += 1
                defer { depth -= 1 }
                if depth > maxNesting { throw FormulaError("nested more than \(maxNesting) levels") }
                return .unary(op: op, value: try unary())
            }
            return try primary()
        }
    }

    static func namesIn(_ node: Node) -> [String] {
        var found: [String] = []
        func walk(_ n: Node) {
            switch n {
            case .name(let name): if !found.contains(name) { found.append(name) }
            case .unary(_, let v): walk(v)
            case .binary(_, let l, let r): walk(l); walk(r)
            case .ifNode(let t, let y, let o): walk(t); walk(y); walk(o)
            case .call(_, let args): args.forEach(walk)
            case .value: break
            }
        }
        walk(node)
        return found
    }

    public static func compile(_ source: String?) -> Compiled {
        guard let source else { return Compiled(tree: nil, names: [], error: "expected a formula string") }
        // 长度上限：左深二元链的求值/取名递归深度约链长一半，Swift 无法捕获栈溢出，
        // 超长直接降级（Kotlin 靠捕获 StackOverflowError 达成同等效果）。
        guard source.count <= 4096 else {
            return Compiled(tree: nil, names: [], error: "the formula is too deep to work out")
        }
        let tokens: [Token]
        do {
            tokens = try tokenize(source)
        } catch let e as FormulaError {
            return Compiled(tree: nil, names: [], error: e.message)
        } catch {
            return Compiled(tree: nil, names: [], error: "the formula is too deep to work out")
        }
        var parser = Parser(tokens: tokens)
        do {
            let tree = try parser.parse()
            return Compiled(tree: tree, names: namesIn(tree), error: nil)
        } catch let e as FormulaError {
            return Compiled(tree: nil, names: [], error: e.message)
        } catch {
            return Compiled(tree: nil, names: [], error: "the formula is too deep to work out")
        }
    }

    static func truthy(_ v: Value) -> Bool {
        switch v {
        case .string(let s): return !s.isEmpty
        case .boolean(let b): return b
        case .number(let d): return d != 0 && !d.isNaN
        }
    }

    static func strictEquals(_ a: Value, _ b: Value) -> Bool {
        if case .number(let x) = a, case .number(let y) = b {
            return x == y && !x.isNaN
        }
        return a == b
    }

    static func run(_ node: Node, _ lookup: (String) throws -> Value) throws -> Value {
        switch node {
        case .value(let v): return v
        case .name(let n): return try lookup(n)
        case .unary(let op, let inner):
            let v = try run(inner, lookup)
            switch op {
            case "!": return .boolean(!(truthy(v)))
            case "-": return .number(-(try num(v)))
            default: return .number(try num(v))
            }
        case .ifNode(let test, let yes, let no):
            return try truthy(run(test, lookup)) ? run(yes, lookup) : run(no, lookup)
        case .call(let name, let args):
            guard let fn = functions[name] else { throw FormulaError("unknown function \"\(name)\"") }
            return try fn.run(args.map { try run($0, lookup) })
        case .binary(let op, let left, let right):
            if op == "&&" {
                return try truthy(run(left, lookup)) ? run(right, lookup) : .boolean(false)
            }
            if op == "||" {
                let l = try run(left, lookup)
                return truthy(l) ? l : try run(right, lookup)
            }
            let l = try run(left, lookup)
            let r = try run(right, lookup)
            switch op {
            case "+":
                if case .string = l { return .string(show(l) + show(r)) }
                if case .string = r { return .string(show(l) + show(r)) }
                return .number(try num(l) + num(r))
            case "-": return .number(try num(l) - num(r))
            case "*": return .number(try num(l) * num(r))
            case "/": return .number(try num(l) / num(r))
            case "%": return .number(try num(l).truncatingRemainder(dividingBy: num(r)))
            case "==": return .boolean(strictEquals(l, r))
            case "!=": return .boolean(!strictEquals(l, r))
            case "<": return .boolean(try num(l) < num(r))
            case "<=": return .boolean(try num(l) <= num(r))
            case ">": return .boolean(try num(l) > num(r))
            default: return .boolean(try num(l) >= num(r))
            }
        }
    }

    /// 一个视图的作用域：输入值，加上按需求值的 let 名字；循环引用或未知名字让读它的公式失败。
    /// 非线程安全（闭包持可变缓存），由持有方收敛。
    public static func scope(_ inputs: [String: Value], _ lets: [String: Node]) -> (String) throws -> Value {
        var done: [String: Value] = [:]
        var busy: Set<String> = []
        func lookup(_ name: String) throws -> Value {
            if let v = inputs[name] { return v }
            if let v = done[name] { return v }
            guard let tree = lets[name] else { throw FormulaError("unknown name \"\(name)\"") }
            guard busy.insert(name).inserted else { throw FormulaError("\"\(name)\" refers to itself") }
            defer { busy.remove(name) }
            let v = try run(tree, lookup)
            done[name] = v
            return v
        }
        return lookup
    }

    /// 求值：成功返回值，失败返回 nil 与原因。
    public static func evaluate(_ tree: Node, _ lookup: (String) throws -> Value) -> (Value?, String?) {
        do {
            return (try run(tree, lookup), nil)
        } catch let e as FormulaError {
            return (nil, e.message)
        } catch {
            return (nil, "the formula is too deep to work out")
        }
    }
}
