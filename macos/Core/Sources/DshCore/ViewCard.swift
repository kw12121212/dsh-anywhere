import Foundation

/// show_view 卡片（dsbot-rich-render，与 `ViewCard.kt` 对齐）：DS-Bot 0.3.10
/// state.views[sessionId] = {id, title, view}，view 为 deep-view 0.1.2 的组件树
/// （stack/row 布局 + text/metric/table/chart/progress/callout/sources/html 展示 +
/// slider/number/choice/toggle 输入 + options/confirm/form/submit 作答，属性可为公式 {"=": "…"}，
/// 最外层可有 let）。
///
/// 校验移植 deep-view core/views.js 的 checkNode（上限：8 层、64 个组件、每层 16 个子节点、
/// 文字 2000、单元格 200、8 列 50 行、8 个选项、6 个字段；图表 categories/series 对齐、数值有限、
/// donut 单序列），另加客户端绘制上限（序列 ≤ 8、点数 ≤ 50）与整卡 64 000 字符。不通过即降级为
/// 只读摘要，不绘制。作答经 view-reply {sessionId, viewId, action, values}（values 形态与
/// validateViewAnswer 一致）。
public enum ViewCard: Sendable {
    public static let maxDepth = 8
    public static let maxNodes = 64
    public static let maxChildren = 16
    public static let maxText = 2000
    public static let maxCell = 200
    public static let maxColumns = 8
    public static let maxRows = 50
    public static let maxOptions = 8
    public static let maxFields = 6
    public static let maxSeries = 8
    public static let maxPoints = 50

    public static let chartKinds = ["bar", "hbar", "line", "area", "donut"]

    static let layoutKinds: Set<String> = ["stack", "row"]
    static let inputKinds: Set<String> = ["slider", "choice", "toggle", "number"]
    static let answerKinds: Set<String> = ["options", "confirm", "form", "submit"]
    static let tones = ["good", "warn", "bad"]
    static let calloutTones = ["info", "good", "warn", "bad"]
    static let fieldProps: Set<String> = ["id", "label", "placeholder", "value", "multiline", "readOnly"]

    static let propsByType: [String: Set<String>] = [
        "stack": ["children", "let"], "row": ["children", "let"],
        "text": ["text", "tone"], "metric": ["label", "value", "tone", "caption"],
        "table": ["columns", "rows"], "chart": ["kind", "categories", "series", "unit", "caption"],
        "progress": ["label", "percent", "value", "max", "valueLabel", "tone"],
        "callout": ["text", "tone"], "sources": ["items"], "html": ["html", "summary", "height"],
        "slider": ["id", "label", "min", "max", "step", "value", "unit"],
        "choice": ["id", "label", "options", "value"], "toggle": ["id", "label", "value"],
        "number": ["id", "label", "min", "max", "step", "value", "unit"],
        "options": ["id", "options", "multi"], "confirm": ["id", "confirmLabel", "cancelLabel"],
        "form": ["id", "fields", "submitLabel", "cancelLabel"], "submit": ["id", "label"],
    ]

    public struct Blueprint: Equatable, Sendable {
        public let sessionId: String
        public let id: String
        public let title: String?
        public let root: JsonObject
        public let interactive: Bool

        public init(sessionId: String, id: String, title: String?, root: JsonObject, interactive: Bool) {
            self.sessionId = sessionId
            self.id = id
            self.title = title
            self.root = root
            self.interactive = interactive
        }
    }

    public enum Decoded: Equatable, Sendable {
        case ok(Blueprint)

        /// 畸形或超限：只读显示标题与原因。
        case invalid(sessionId: String, id: String?, title: String?, reason: String)
    }

    /// state.views 的一项 → 可绘制的蓝图或降级。
    public static func decode(_ sessionId: String, _ entry: JsonValue?) -> Decoded {
        guard let o = entry?.objectValue else {
            return .invalid(sessionId: sessionId, id: nil, title: nil, reason: "not an object")
        }
        let title = o.string("title")?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        guard let id = o.nonEmptyString("id") else {
            return .invalid(sessionId: sessionId, id: nil, title: title, reason: "missing id")
        }
        guard let view = o.object("view") else {
            return .invalid(sessionId: sessionId, id: id, title: title, reason: "missing view")
        }
        if Json.encode(.object(view)).utf16.count > Cards.maxChars {
            return .invalid(sessionId: sessionId, id: id, title: title, reason: "view too large")
        }
        if let error = validate(view) {
            return .invalid(sessionId: sessionId, id: id, title: title, reason: error)
        }
        return .ok(Blueprint(sessionId: sessionId, id: id, title: title, root: view, interactive: interactiveOf(view)))
    }

    // MARK: - 校验（deep-view checkNode 的移植）

    private enum FormulaCheck {
        case number
        case string
        case scalar
        case tone([String])
    }

    private final class Ctx {
        var ids: Set<String> = []
        var nodes = 0
        var inputs: [String: ViewFormula.Value] = [:]
        var lets: [(String, String)] = []
        var formulas: [(source: String, path: String, kind: FormulaCheck)] = []
    }

    /// 不通过返回原因，通过返回 nil。
    public static func validate(_ view: JsonObject) -> String? {
        let ctx = Ctx()
        return checkNode(.object(view), "view", 1, ctx) ?? checkFormulas(ctx)
    }

    private static func checkNode(_ node: JsonValue?, _ path: String, _ depth: Int, _ ctx: Ctx) -> String? {
        guard let o = node?.objectValue else { return "\(path): expected a component object" }
        if depth > maxDepth { return "\(path): \(depth) levels exceeds the limit of \(maxDepth)" }
        ctx.nodes += 1
        if ctx.nodes > maxNodes { return "\(path): \(ctx.nodes) components exceeds the limit of \(maxNodes)" }
        guard let type = o.string("type"), let props = propsByType[type] else {
            return "\(path).type: unknown component"
        }
        for (key, _) in o.entries where key != "type" && !props.contains(key) {
            return "\(path).\(key): unknown property"
        }
        switch type {
        case "stack", "row":
            return checkLayout(o, path, depth, ctx)
        case "text":
            if let e = checkValue(o, "text", path, ctx, .string, required: true) { return e }
            return checkValue(o, "tone", path, ctx, .tone(["muted"]))
        case "metric":
            guard o["label"]?.stringValue != nil else { return "\(path).label: expected a string" }
            guard o["value"] != nil else { return "\(path).value: expected a string or a number" }
            if let e = checkValue(o, "value", path, ctx, .scalar) { return e }
            if let e = checkValue(o, "tone", path, ctx, .tone(tones)) { return e }
            return checkValue(o, "caption", path, ctx, .string)
        case "table":
            return checkTable(o, path, ctx)
        case "chart":
            return checkChart(o, path, ctx)
        case "progress":
            return checkProgress(o, path, ctx)
        case "callout":
            if let e = checkValue(o, "text", path, ctx, .string, required: true) { return e }
            return checkValue(o, "tone", path, ctx, .tone(calloutTones))
        case "sources":
            return checkSources(o, path)
        case "html":
            guard let html = o.string("html"), !isBlank(html) else {
                return "\(path).html: expected a non-empty string"
            }
            guard let summary = o.string("summary"), !isBlank(summary) else {
                return "\(path).summary: expected one line"
            }
            if let h = o["height"], (finite(h) ?? 0) <= 0 {
                return "\(path).height: expected a positive number"
            }
            return nil
        case "slider", "number":
            return checkNumberInput(o, type, path, ctx)
        case "choice":
            return checkChoice(o, path, ctx)
        case "toggle":
            if let e = checkInputId(o, path, ctx) { return e }
            let v = o["value"]
            if let v, v.boolValue == nil { return "\(path).value: expected true or false" }
            ctx.inputs[o.string("id")!] = .boolean(v?.boolValue == true)
            return nil
        case "options":
            return checkOptions(o, path, ctx)
        case "confirm":
            if let e = checkId(o, path, ctx) { return e }
            return optionalStrings(o, path, ["confirmLabel", "cancelLabel"])
        case "form":
            return checkForm(o, path, ctx)
        case "submit":
            if let e = checkId(o, path, ctx) { return e }
            return optionalStrings(o, path, ["label"])
        default:
            return nil
        }
    }

    private static func checkLayout(_ o: JsonObject, _ path: String, _ depth: Int, _ ctx: Ctx) -> String? {
        if let letsValue = o["let"] {
            if depth != 1 { return "\(path).let: only the outermost component can define names" }
            guard let letObj = letsValue.objectValue else { return "\(path).let: expected an object" }
            for (name, src) in letObj.entries {
                if !ViewFormula.isName(name) || name == "true" || name == "false" {
                    return "\(path).let.\(name): invalid name"
                }
                guard let s = src.stringValue else { return "\(path).let.\(name): expected a formula string" }
                ctx.lets.append((name, s))
            }
        }
        guard let children = o.array("children") else { return "\(path).children: expected an array" }
        if children.isEmpty || children.count > maxChildren {
            return "\(path).children: expected 1 to \(maxChildren) children"
        }
        for (i, child) in children.enumerated() {
            if let e = checkNode(child, "\(path).children[\(i)]", depth + 1, ctx) { return e }
        }
        return nil
    }

    /// 公式对象 {"=": "..."} 的源文本；非公式返回 nil（"=" 值非字符串时源为空串，编译期报 the formula is empty）。
    static func formulaSource(_ v: JsonValue) -> String? {
        guard case .object(let obj) = v, obj.count == 1, let eq = obj["="] else { return nil }
        return eq.stringValue ?? ""
    }

    private static func checkValue(
        _ o: JsonObject, _ key: String, _ path: String, _ ctx: Ctx, _ kind: FormulaCheck,
        required: Bool = false, max: Int = maxText
    ) -> String? {
        guard let v = o[key] else { return required ? "\(path).\(key): missing" : nil }
        if let src = formulaSource(v) {
            ctx.formulas.append((src, "\(path).\(key)", kind))
            return nil
        }
        switch kind {
        case .number:
            return finite(v) != nil ? nil : "\(path).\(key): expected a number"
        case .string:
            guard let text = v.stringValue else { return "\(path).\(key): expected a string" }
            return text.utf16.count > max ? "\(path).\(key): too long" : nil
        case .tone(let tones):
            guard let s = v.stringValue, tones.contains(s) else { return "\(path).\(key): unknown tone" }
            return nil
        case .scalar:
            return scalar(v) != nil ? nil : "\(path).\(key): expected a string or a number"
        }
    }

    private static func checkTable(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        guard let cols = o.array("columns") else { return "\(path).columns: expected an array" }
        if cols.count > maxColumns { return "\(path).columns: too many columns" }
        for c in cols {
            guard let co = c.objectValue else { return "\(path).columns: expected column objects" }
            for (k, _) in co.entries where k != "key" && k != "label" {
                return "\(path).columns: unknown property"
            }
            if (co.string("key") ?? "").isEmpty { return "\(path).columns: key expected" }
            if co["label"]?.stringValue == nil { return "\(path).columns: label expected" }
        }
        guard let rows = o.array("rows") else { return "\(path).rows: expected an array" }
        if rows.count > maxRows { return "\(path).rows: too many rows" }
        for (r, row) in rows.enumerated() {
            guard let cells = row.arrayValue else { return "\(path).rows[\(r)]: expected an array of cells" }
            for (c, cell) in cells.enumerated() {
                if let src = formulaSource(cell) {
                    ctx.formulas.append((src, "\(path).rows[\(r)][\(c)]", .scalar))
                } else if cellText(cell).utf16.count > maxCell {
                    return "\(path).rows[\(r)][\(c)]: too long"
                }
            }
        }
        return nil
    }

    private static func checkChart(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        let kind = o["kind"]
        if kind != nil, kind?.stringValue.map({ !chartKinds.contains($0) }) ?? true {
            return "\(path).kind: unknown chart"
        }
        guard let cats = o.array("categories"), !cats.isEmpty else {
            return "\(path).categories: expected a non-empty array"
        }
        if cats.count > maxPoints { return "\(path).categories: more than \(maxPoints) points" }
        if cats.contains(where: { $0.stringValue == nil && finite($0) == nil }) {
            return "\(path).categories: expected labels"
        }
        guard let series = o.array("series"), !series.isEmpty else {
            return "\(path).series: expected a non-empty array"
        }
        if series.count > maxSeries { return "\(path).series: more than \(maxSeries) series" }
        if kind?.stringValue == "donut", series.count > 1 {
            return "\(path).series: a donut takes one series"
        }
        for (i, s) in series.enumerated() {
            guard let so = s.objectValue else { return "\(path).series[\(i)]: expected a series object" }
            for (k, _) in so.entries where k != "name" && k != "values" {
                return "\(path).series[\(i)]: unknown property"
            }
            if so["name"] != nil, so["name"]?.stringValue == nil {
                return "\(path).series[\(i)].name: expected a string"
            }
            guard let values = so.array("values") else { return "\(path).series[\(i)].values: expected numbers" }
            if values.count != cats.count {
                return "\(path).series[\(i)].values: \(values.count) values for \(cats.count) categories"
            }
            for (j, v) in values.enumerated() {
                if let src = formulaSource(v) {
                    ctx.formulas.append((src, "\(path).series[\(i)].values[\(j)]", .number))
                } else if finite(v) == nil {
                    return "\(path).series[\(i)].values[\(j)]: expected a finite number"
                }
            }
        }
        if o["unit"] != nil, o["unit"]?.stringValue == nil { return "\(path).unit: expected a string" }
        if o["caption"] != nil, o["caption"]?.stringValue == nil { return "\(path).caption: expected a string" }
        return nil
    }

    private static func checkProgress(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        let hasPercent = o["percent"] != nil
        let hasValue = o["value"] != nil
        if o["label"] != nil, o["label"]?.stringValue == nil { return "\(path).label: expected a string" }
        if hasPercent == hasValue { return "\(path): give percent, or value with max" }
        if hasPercent, formulaSource(o["percent"]!) == nil {
            guard let p = finite(o["percent"]) else { return "\(path).percent: expected a number" }
            if p < 0 || p > 100 { return "\(path).percent: expected 0 to 100" }
        }
        if hasValue != (o["max"] != nil) { return "\(path): value and max go together" }
        return checkValue(o, "percent", path, ctx, .number)
            ?? checkValue(o, "value", path, ctx, .number)
            ?? checkValue(o, "max", path, ctx, .number)
            ?? checkValue(o, "valueLabel", path, ctx, .scalar)
            ?? checkValue(o, "tone", path, ctx, .tone(tones))
    }

    private static func checkSources(_ o: JsonObject, _ path: String) -> String? {
        guard let items = o.array("items"), !items.isEmpty else {
            return "\(path).items: expected a non-empty array"
        }
        for item in items {
            guard let io = item.objectValue else { return "\(path).items: expected objects" }
            for (k, _) in io.entries where k != "label" && k != "url" {
                return "\(path).items: unknown property"
            }
            guard let label = io.string("label"), !isBlank(label) else { return "\(path).items: label expected" }
            guard let url = io.string("url"), isHttpUrl(url) else { return "\(path).items: url must be http(s)" }
        }
        return nil
    }

    private static func checkInputId(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        let id = o.string("id")
        guard ViewFormula.isName(id), id != "true", id != "false", let theId = id else {
            return "\(path).id: invalid input id"
        }
        if !ctx.ids.insert(theId).inserted { return "\(path).id: duplicate id" }
        if o["label"]?.stringValue == nil { return "\(path).label: expected a string" }
        return nil
    }

    private static func checkNumberInput(_ o: JsonObject, _ type: String, _ path: String, _ ctx: Ctx) -> String? {
        if let e = checkInputId(o, path, ctx) { return e }
        for k in ["min", "max", "step"] {
            if let v = o[k], finite(v) == nil { return "\(path).\(k): expected a number" }
        }
        let minV = finite(o["min"])
        let maxV = finite(o["max"])
        let step = finite(o["step"])
        if type == "slider", minV == nil || maxV == nil { return "\(path): a slider needs min and max" }
        if let minV, let maxV, maxV <= minV { return "\(path).max: expected more than min" }
        if let step, step <= 0 { return "\(path).step: expected more than 0" }
        guard let v = finite(o["value"]) else { return "\(path).value: expected a number" }
        if let minV, v < minV { return "\(path).value: out of range" }
        if let maxV, v > maxV { return "\(path).value: out of range" }
        if o["unit"] != nil, o["unit"]?.stringValue == nil { return "\(path).unit: expected a string" }
        ctx.inputs[o.string("id")!] = .number(v)
        return nil
    }

    private static func checkChoice(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        if let e = checkInputId(o, path, ctx) { return e }
        guard let opts = o.array("options"), !opts.isEmpty else {
            return "\(path).options: expected a non-empty array"
        }
        var labels: Set<String> = []
        var values: [ViewFormula.Value] = []
        for op in opts {
            guard let oo = op.objectValue else { return "\(path).options: expected objects" }
            for (k, _) in oo.entries where k != "label" && k != "value" {
                return "\(path).options: unknown property"
            }
            guard let label = oo.string("label"), !isBlank(label) else { return "\(path).options: label expected" }
            if !labels.insert(label).inserted { return "\(path).options: duplicate label" }
            if let pv = oo["value"], scalar(pv) == nil {
                return "\(path).options: value must be a string, a number or true/false"
            }
            guard let value = choiceValue(oo) else {
                return "\(path).options: value must be a string, a number or true/false"
            }
            if values.contains(value) { return "\(path).options: duplicate value" }
            values.append(value)
        }
        if let pv = o["value"], scalar(pv) == nil {
            return "\(path).value: expected one of the options' values"
        }
        let chosen: ViewFormula.Value?
        if let pv = o["value"], let s = scalar(pv) {
            chosen = s
        } else if let first = opts.first?.objectValue {
            chosen = choiceValue(first)
        } else {
            chosen = nil
        }
        guard let chosen, values.contains(chosen) else {
            return "\(path).value: expected one of the options' values"
        }
        ctx.inputs[o.string("id")!] = chosen
        return nil
    }

    private static func checkOptions(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        if let e = checkId(o, path, ctx) { return e }
        if let m = o["multi"], m.boolValue == nil { return "\(path).multi: expected a boolean" }
        guard let opts = o.array("options") else { return "\(path).options: expected an array" }
        if opts.isEmpty || opts.count > maxOptions {
            return "\(path).options: expected 1 to \(maxOptions) options"
        }
        var labels: Set<String> = []
        for op in opts {
            guard let oo = op.objectValue else { return "\(path).options: expected objects" }
            for (k, _) in oo.entries where k != "label" && k != "description" {
                return "\(path).options: unknown property"
            }
            guard let label = oo.string("label"), !isBlank(label) else { return "\(path).options: label expected" }
            if !labels.insert(label).inserted { return "\(path).options: duplicate label" }
            if let d = oo["description"], d.stringValue == nil {
                return "\(path).options: description must be a string"
            }
        }
        return nil
    }

    private static func optionalStrings(_ o: JsonObject, _ path: String, _ keys: [String]) -> String? {
        for k in keys where o[k] != nil && o[k]?.stringValue == nil {
            return "\(path).\(k): expected a string"
        }
        return nil
    }

    private static func checkForm(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        if let e = checkId(o, path, ctx) { return e }
        if let e = optionalStrings(o, path, ["submitLabel", "cancelLabel"]) { return e }
        guard let fields = o.array("fields") else { return "\(path).fields: expected an array" }
        if fields.count > maxFields { return "\(path).fields: too many fields" }
        var seen: Set<String> = []
        for f in fields {
            guard let fo = f.objectValue else { return "\(path).fields: expected objects" }
            for (k, _) in fo.entries where !fieldProps.contains(k) {
                return "\(path).fields: unknown property"
            }
            guard let id = fo.string("id"), !id.isEmpty, id != "__proto__" else {
                return "\(path).fields: id expected"
            }
            if !seen.insert(id).inserted { return "\(path).fields: duplicate id" }
            if fo["label"]?.stringValue == nil { return "\(path).fields: label expected" }
            if let e = optionalStrings(fo, "\(path).fields", ["placeholder"]) { return e }
            for k in ["multiline", "readOnly"] where fo[k] != nil && fo[k]?.boolValue == nil {
                return "\(path).fields.\(k): expected a boolean"
            }
            if let v = fo["value"] {
                guard let s = v.stringValue, s.utf16.count <= maxText else { return "\(path).fields: value must be text" }
            }
        }
        return nil
    }

    private static func checkId(_ o: JsonObject, _ path: String, _ ctx: Ctx) -> String? {
        guard let id = o.string("id"), !id.isEmpty, id != "__proto__" else {
            return "\(path).id: expected a non-empty string"
        }
        if !ctx.ids.insert(id).inserted { return "\(path).id: duplicate id" }
        return nil
    }

    private static func checkFormulas(_ ctx: Ctx) -> String? {
        var known: Set<String> = Set(ctx.inputs.keys)
        known.formUnion(ctx.lets.map(\.0))
        var lets: [(String, ViewFormula.Node)] = []
        for (name, src) in ctx.lets {
            if ctx.inputs[name] != nil { return "let \(name): already an input id" }
            let c = ViewFormula.compile(src)
            if let error = c.error { return "let \(name): \(error)" }
            if let bad = c.names.first(where: { !known.contains($0) }) {
                return "let \(name): unknown name \(bad)"
            }
            guard let tree = c.tree else { return "let \(name): the formula is empty" }
            lets.append((name, tree))
        }
        let lookup = ViewFormula.scope(ctx.inputs, Dictionary(uniqueKeysWithValues: lets))
        for (name, tree) in lets {
            if let error = ViewFormula.evaluate(tree, lookup).1 { return "let \(name): \(error)" }
        }
        for (src, path, kind) in ctx.formulas {
            let c = ViewFormula.compile(src)
            if let error = c.error { return "\(path): \(error)" }
            if let bad = c.names.first(where: { !known.contains($0) }) {
                return "\(path): unknown name \(bad)"
            }
            guard let tree = c.tree else { return "\(path): the formula is empty" }
            let (value, error) = ViewFormula.evaluate(tree, lookup)
            if let error { return "\(path): \(error)" }
            let ok: Bool
            switch (kind, value) {
            case (.number, .number(let d)): ok = d.isFinite
            case (.tone(let tones), .string(let s)): ok = tones.contains(s)
            case (_, .string), (_, .boolean): ok = true
            case (_, .number(let d)): ok = d.isFinite
            default: ok = false
            }
            if !ok { return "\(path): the formula gives the wrong kind of value" }
        }
        return nil
    }

    // MARK: - 求值与绘制数据

    private static func interactiveOf(_ node: JsonObject) -> Bool {
        walk(node).contains { answerKinds.contains($0.string("type") ?? "") }
    }

    /// 先序遍历（布局下钻）。
    public static func walk(_ node: JsonObject) -> [JsonObject] {
        var out: [JsonObject] = []
        func go(_ n: JsonValue?) {
            guard let o = n?.objectValue else { return }
            out.append(o)
            if let t = o.string("type"), layoutKinds.contains(t), let children = o.array("children") {
                children.forEach(go)
            }
        }
        go(.object(node))
        return out
    }

    public static func inputsOf(_ root: JsonObject) -> [JsonObject] {
        walk(root).filter { inputKinds.contains($0.string("type") ?? "") }
    }

    public static func choiceValue(_ option: JsonObject) -> ViewFormula.Value? {
        if let v = option["value"], let s = scalar(v) { return s }
        return option.string("label").map(ViewFormula.Value.string)
    }

    /// 输入的初始值：toggle 默认 false，choice 默认第一个选项。
    public static func inputDefault(_ node: JsonObject) -> ViewFormula.Value? {
        switch node.string("type") {
        case "toggle":
            return .boolean(node["value"]?.boolValue == true)
        case "choice":
            if let v = node["value"], let s = scalar(v) { return s }
            if let first = node.array("options")?.first?.objectValue { return choiceValue(first) }
            return nil
        default:
            guard let f = finite(node["value"]) else { return nil }
            return .number(f)
        }
    }

    /// 当前输入值下的作用域（未给的输入取初始值）。
    public static func scope(_ root: JsonObject, _ values: [String: ViewFormula.Value] = [:]) -> (String) throws -> ViewFormula.Value {
        var inputs: [String: ViewFormula.Value] = [:]
        for n in inputsOf(root) {
            guard let id = n.string("id") else { continue }
            if let v = values[id] ?? inputDefault(n) { inputs[id] = v }
        }
        var lets: [String: ViewFormula.Node] = [:]
        if case .object(let letObj)? = root["let"] {
            for (name, src) in letObj.entries {
                if let tree = compiled(src.stringValue).tree { lets[name] = tree }
            }
        }
        return ViewFormula.scope(inputs, lets)
    }

    /// 属性值：字面量照原样（转成公式值），公式按作用域求值，失败取 fallback。
    public static func resolve(
        _ value: JsonValue?, _ lookup: (String) throws -> ViewFormula.Value,
        _ fallback: ViewFormula.Value? = nil
    ) -> ViewFormula.Value? {
        guard let value else { return fallback }
        guard let src = formulaSource(value) else { return scalar(value) ?? fallback }
        guard let tree = compiled(src).tree else { return fallback }
        let (v, error) = ViewFormula.evaluate(tree, lookup)
        return error == nil ? v : fallback
    }

    /// 公式编译缓存（拖动滑块时每次重组都要求值，避免反复解析）；插入序上限 256，锁保护。
    private final class CompileCache: @unchecked Sendable {
        private let lock = NSLock()
        private var store: [String: ViewFormula.Compiled] = [:]
        private var order: [String] = []

        func get(_ src: String, make: (String) -> ViewFormula.Compiled) -> ViewFormula.Compiled {
            lock.lock()
            defer { lock.unlock() }
            if let hit = store[src] { return hit }
            let made = make(src)
            store[src] = made
            order.append(src)
            if order.count > 256 {
                store[order.removeFirst()] = nil
            }
            return made
        }
    }

    private static let compileCache = CompileCache()

    static func compiled(_ src: String?) -> ViewFormula.Compiled {
        guard let src else { return ViewFormula.compile(nil) }
        return compileCache.get(src) { ViewFormula.compile($0) }
    }

    public static func text(_ value: JsonValue?, _ lookup: (String) throws -> ViewFormula.Value) -> String {
        ViewFormula.show(resolve(value, lookup, .string("")) ?? .string(""))
    }

    public struct Series: Equatable, Sendable {
        public let name: String?
        public let values: [Double]

        public init(name: String?, values: [Double]) {
            self.name = name
            self.values = values
        }
    }

    public struct Chart: Equatable, Sendable {
        public let kind: String
        public let categories: [String]
        public let series: [Series]
        public let unit: String?
        public let caption: String?

        public init(kind: String, categories: [String], series: [Series], unit: String?, caption: String?) {
            self.kind = kind
            self.categories = categories
            self.series = series
            self.unit = unit
            self.caption = caption
        }

        /// 所有值的范围（含 0，便于柱状从基线起）。
        public var range: (Double, Double) {
            let all = series.flatMap(\.values)
            return (min(0, all.min() ?? 0), max(0, all.max() ?? 0))
        }
    }

    /// 图表绘制数据：公式求值后非有限的点取 0（校验时已保证初始输入下有限）。
    public static func chart(_ node: JsonObject, _ lookup: (String) throws -> ViewFormula.Value) -> Chart? {
        guard let cats = node.array("categories"), let seriesRaw = node.array("series") else { return nil }
        var seriesOut: [Series] = []
        for s in seriesRaw {
            guard let so = s.objectValue, let rawValues = so.array("values") else { continue }
            let values = rawValues.map { v -> Double in
                if case .number(let d)? = resolve(v, lookup), d.isFinite { return d }
                return 0
            }
            seriesOut.append(Series(name: so.string("name"), values: values))
        }
        return Chart(
            kind: node.string("kind") ?? "bar",
            categories: cats.map { ViewFormula.show(scalar($0) ?? .string("")) },
            series: seriesOut,
            unit: node.string("unit"),
            caption: node.string("caption")
        )
    }

    /// 进度百分比（0–100）。
    public static func progressPercent(_ node: JsonObject, _ lookup: (String) throws -> ViewFormula.Value) -> Double {
        let p: Double
        if node["percent"] != nil {
            p = asNumber(resolve(node["percent"], lookup, .number(0)))
        } else {
            let maxV = asNumber(resolve(node["max"], lookup, .number(0)))
            if maxV > 0 {
                p = asNumber(resolve(node["value"], lookup, .number(0))) / maxV * 100
            } else {
                p = 0
            }
        }
        return min(max(p, 0), 100)
    }

    /// resolve 结果只认数字（公式给出字符串时按 0，与 Kotlin as? Double 一致）。
    private static func asNumber(_ v: ViewFormula.Value?) -> Double {
        if case .number(let d)? = v { return d }
        return 0
    }

    /// 表格导出 CSV（RFC 4180：含逗号、引号、换行的单元格加引号，引号写双）。
    public static func tableCsv(_ node: JsonObject, _ lookup: (String) throws -> ViewFormula.Value) -> String {
        let cols = (node.array("columns") ?? []).compactMap(\.objectValue)

        func cell(_ s: String) -> String {
            if s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") {
                return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
            }
            return s
        }

        var lines = [cols.map { cell($0.string("label") ?? "") }.joined(separator: ",")]
        for row in node.array("rows") ?? [] {
            let cells = row.arrayValue ?? []
            lines.append(
                cols.indices.map { i in cell(i < cells.count ? tableCell(cells[i], lookup) : "") }
                    .joined(separator: ",")
            )
        }
        return lines.joined(separator: "\r\n")
    }

    /// 表格单元格文字：公式求值，非字符串字面量按 JSON 文本显示（与 deep-view cellText 一致）。
    public static func tableCell(_ cell: JsonValue, _ lookup: (String) throws -> ViewFormula.Value) -> String {
        if formulaSource(cell) != nil {
            return ViewFormula.show(resolve(cell, lookup, .string("")) ?? .string(""))
        }
        return cellText(cell)
    }

    private static func cellText(_ v: JsonValue?) -> String {
        switch v {
        case .string(let s): return s
        case .none: return ""
        default: return Json.encode(v!)
        }
    }

    /// 降级摘要：标题加各组件一句（与网页端 viewSummary 同口径的简化版）。
    public static func summary(_ root: JsonObject) -> String {
        let lookup = scope(root)
        return walk(root).compactMap { n -> String? in
            let line: String?
            switch n.string("type") {
            case "text", "callout":
                line = text(n["text"], lookup)
            case "metric":
                line = "\(n.string("label") ?? "")：\(text(n["value"], lookup))"
            case "html":
                line = n.string("summary")
            default:
                line = nil
            }
            guard let line, !isBlank(line) else { return nil }
            return line
        }
        .prefix(8)
        .joined(separator: "\n")
    }

    /// 滑块取值吸附到 min + k·step（k 不超过不越过 max 的最后一格），按步长与 min 的小数位去掉
    /// 浮点噪声，结果一定在 [min, max]——服务端 validateInputs 会拒绝越界值。无步长时保留 3 位小数。
    public static func snap(_ v: Double, _ min: Double, _ max: Double, _ step: Double?) -> Double {
        guard let step, step > 0 else {
            // Math.round 语义：.5 向 +∞（.rounded() 是远离零，负值半格会差一）。
            return Swift.min(Swift.max(((v * 1000) + 0.5).rounded(.down) / 1000, min), max)
        }
        let last = ((max - min) / step + 1e-9).rounded(.down)
        let k = Swift.min(Swift.max(((v - min) / step + 0.5).rounded(.down), 0), last)
        let decimals = Swift.max(Swift.max(decimalsOf(step), decimalsOf(min)), 0)
        let scale = Foundation.pow(10, Double(decimals))
        let scaled = (min + k * step) * scale
        // setScale(…, HALF_UP)：.5 远离零。
        let snapped = (abs(scaled) + 0.5).rounded(.down) * (scaled < 0 ? -1 : 1) / scale
        return Swift.min(Swift.max(snapped, min), max)
    }

    /// 数字文本的小数位（最短往返表示去尾零；科学记法按 BigDecimal(str).stripTrailingZeros().scale()
    /// 等价换算：scale = 有效位数 - 1 - 十进制指数）。返回值可为负（整数拖尾零），由调用方与 0 取 max。
    private static func decimalsOf(_ d: Double) -> Int {
        guard d.isFinite, d != 0 else { return 0 }
        let s = String(abs(d))
        if let eIdx = s.firstIndex(of: "e") {
            let exp = Int(s[s.index(after: eIdx)...]) ?? 0
            var digits = s[..<eIdx].replacingOccurrences(of: ".", with: "")
            while digits.hasSuffix("0") { digits.removeLast() }
            guard !digits.isEmpty else { return 0 }
            return digits.count - 1 - exp
        }
        guard let dot = s.firstIndex(of: ".") else { return 0 }
        var frac = String(s[s.index(after: dot)...])
        while frac.hasSuffix("0") { frac.removeLast() }
        return frac.count
    }

    // MARK: - 作答

    public indirect enum Answer: Sendable {
        case submit
        case options(selected: [String])
        case confirm(confirmed: Bool)
        case form(fields: [(String, String)])
        case formCanceled
    }

    /// view-reply 请求体：{sessionId, viewId, action, values}；values 带作答组件自己的值，
    /// 视图有输入时带 inputs（全部输入的当前值，未动过的取初始值）。
    public static func reply(_ bp: Blueprint, _ action: String, _ answer: Answer, _ inputs: [String: ViewFormula.Value]) -> String {
        var parts: [String] = []
        let inputNodes = inputsOf(bp.root)
        if !inputNodes.isEmpty {
            let fields = inputNodes.compactMap { n -> String? in
                guard let id = n.string("id") else { return nil }
                guard let v = inputs[id] ?? inputDefault(n) else { return nil }
                return JsonString.of(id) + ":" + literal(v)
            }
            parts.append("\"inputs\":{" + fields.joined(separator: ",") + "}")
        }
        switch answer {
        case .options(let selected):
            parts.append("\"selected\":[" + selected.map(JsonString.of).joined(separator: ",") + "]")
        case .confirm(let confirmed):
            parts.append("\"confirmed\":\(confirmed)")
        case .form(let fields):
            parts.append("\"fields\":{" + fields.map { JsonString.of($0.0) + ":" + JsonString.of($0.1) }.joined(separator: ",") + "}")
        case .formCanceled:
            parts.append("\"canceled\":true")
        case .submit:
            break
        }
        let values = "{" + parts.joined(separator: ",") + "}"
        return "{\"sessionId\":\(JsonString.of(bp.sessionId)),\"viewId\":\(JsonString.of(bp.id)),\"action\":\(JsonString.of(action)),\"values\":\(values)}"
    }

    private static func literal(_ v: ViewFormula.Value) -> String {
        switch v {
        case .boolean(let b): return b ? "true" : "false"
        case .number(let d):
            if d == d.rounded(.down), abs(d) < 1e15, d.isFinite { return String(Int64(d)) }
            // 非整数用最短往返十进制（与 Java Double.toString 同数字），不截断有效位。
            return String(d)
        case .string(let s): return JsonString.of(s)
        }
    }

    // MARK: - 小工具

    /// 有限数字字面量。
    public static func finite(_ v: JsonValue?) -> Double? {
        guard let raw = v?.numberRaw, let d = Double(raw), d.isFinite else { return nil }
        return d
    }

    /// 标量字面量 → 公式值（字符串、布尔、有限数字），其他为 nil。
    public static func scalar(_ v: JsonValue?) -> ViewFormula.Value? {
        switch v {
        case .string(let s): return .string(s)
        case .bool(let b): return .boolean(b)
        case .number(let raw):
            guard let d = Double(raw), d.isFinite else { return nil }
            return .number(d)
        default: return nil
        }
    }

    /// https?:// 后跟非空白字符（对应 ^https?://\S+$，大小写不敏感）。
    static func isHttpUrl(_ s: String) -> Bool {
        let lower = s.lowercased()
        let prefix: String?
        if lower.hasPrefix("https://") { prefix = "https://" } else if lower.hasPrefix("http://") { prefix = "http://" } else { prefix = nil }
        guard let prefix else { return false }
        let rest = s.dropFirst(prefix.count)
        return !rest.isEmpty && !rest.contains(where: \.isWhitespace)
    }

    static func isBlank(_ s: String) -> Bool {
        s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
