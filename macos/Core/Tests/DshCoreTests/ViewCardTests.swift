import XCTest
@testable import DshCore

/// dsbot-rich-render：show_view 卡片（deep-view 0.1.2 组件树）的校验、公式、图表、导出与作答
/// （与 `ViewCardTest.kt` 同夹具）。
final class ViewCardTests: XCTestCase {
    private func obj(_ t: String) throws -> JsonObject {
        try XCTUnwrap(Json.parse(t)?.objectValue, "不是合法 JSON 对象")
    }

    private func decode(_ view: String, title: String = "T") throws -> ViewCard.Decoded {
        ViewCard.decode("s", .object(try obj(#"{"id":"v1","title":"\#(title)","view":\#(view)}"#)))
    }

    private func reason(_ v: String) throws -> String {
        let decoded = try decode(v)
        guard case .invalid(_, _, _, let r) = decoded else {
            XCTFail("expected invalid, got \(decoded)")
            return ""
        }
        return r
    }

    private func eval(_ src: String, _ inputs: [String: ViewFormula.Value] = [:]) -> ViewFormula.Value? {
        guard let tree = ViewFormula.compile(src).tree else { return nil }
        return ViewFormula.evaluate(tree, ViewFormula.scope(inputs, [:])).0
    }

    func testFormulaSemanticsMatchDeepView() throws {
        XCTAssertEqual(ViewFormula.Value.number(7), eval("1 + 2 * 3"))
        XCTAssertEqual(ViewFormula.Value.number(9), eval("(1 + 2) * 3"))
        XCTAssertEqual(ViewFormula.Value.string("a1"), eval("'a' + 1"))
        XCTAssertEqual(ViewFormula.Value.number(30), eval("price * qty", ["price": .number(10), "qty": .number(3)]))
        XCTAssertEqual(ViewFormula.Value.string("1,234.50"), eval("format(1234.5, 2)"))
        XCTAssertEqual(ViewFormula.Value.string("12.5%"), eval("percent(0.125, 1)"))
        XCTAssertEqual(ViewFormula.Value.string("2.50"), eval("fixed(2.499, 2)"))
        XCTAssertEqual(ViewFormula.Value.number(3), eval("round(2.5)")) // JS Math.round：.5 向上
        XCTAssertEqual(ViewFormula.Value.number(5), eval("clamp(9, 0, 5)"))
        XCTAssertEqual(ViewFormula.Value.string("高"), eval("x > 3 ? '高' : '低'", ["x": .number(4)]))
        XCTAssertEqual(ViewFormula.Value.boolean(true), eval("!false && 1 == 1"))
        XCTAssertEqual(ViewFormula.Value.boolean(false), eval("1 == '1'")) // 严格相等
        XCTAssertEqual("0.3", ViewFormula.show(eval("0.1 + 0.2") ?? .boolean(false)))
        XCTAssertEqual("3", ViewFormula.show(.number(3)))
        XCTAssertEqual("Infinity", ViewFormula.show(eval("1 / 0") ?? .boolean(false)))
        // 解析失败与未知函数、引用自身
        XCTAssertTrue(ViewFormula.compile("1 +").error?.contains("ends too early") == true)
        XCTAssertTrue(ViewFormula.compile("evil(1)").error?.contains("unknown function") == true)
        XCTAssertTrue(ViewFormula.compile("'abc").error?.contains("unclosed") == true)
        let loop = ViewFormula.scope([:], ["a": try XCTUnwrap(ViewFormula.compile("a + 1").tree)])
        XCTAssertTrue(
            ViewFormula.evaluate(try XCTUnwrap(ViewFormula.compile("a").tree), loop).1?.contains("refers to itself") == true
        )
        XCTAssertTrue(
            ViewFormula.evaluate(try XCTUnwrap(ViewFormula.compile("'x' * 2").tree), loop).1?.contains("not a number") == true
        )
    }

    func testMeasuredBlueprintDecodesAndAnswers() throws {
        let entry = try TestFixtures.dsbotJson("view-blueprint-choice.json")
        guard case .ok(let bp) = ViewCard.decode("s-1", entry) else {
            return XCTFail("夹具应解码为蓝图")
        }
        XCTAssertTrue(bp.interactive)
        XCTAssertEqual(["direction", "scene", "heading", "dark"], ViewCard.inputsOf(bp.root).compactMap { $0.string("id") })
        let body = ViewCard.reply(bp, "go", .submit, ["scene": .string("scene-2")])
        let parsed = try XCTUnwrap(Json.parse(body)?.objectValue)
        XCTAssertEqual("s-1", parsed["sessionId"]?.stringValue)
        XCTAssertEqual("go", parsed["action"]?.stringValue)
        let inputs = try XCTUnwrap(parsed["values"]?.objectValue?["inputs"]?.objectValue)
        XCTAssertEqual("scene-2", inputs["scene"]?.stringValue) // 动过的取当前值
        XCTAssertEqual("direction-1", inputs["direction"]?.stringValue) // 没动过的取初始值
    }

    private let chartView = #"""
    {"type":"stack","let":{"total":"a + b"},"children":[
    {"type":"slider","id":"a","label":"A","min":0,"max":10,"value":2},
    {"type":"number","id":"b","label":"B","value":3},
    {"type":"metric","label":"合计","value":{"=":"total"}},
    {"type":"chart","kind":"line","categories":["一月","二月"],"series":[{"name":"S","values":[1,{"=":"total"}]}],"unit":"万"},
    {"type":"progress","value":{"=":"a"},"max":10},
    {"type":"table","columns":[{"key":"k","label":"名, 称"},{"key":"v","label":"值"}],"rows":[["甲",1],["乙\"x\"",{"=":"total * 2"}]]},
    {"type":"callout","text":"注意","tone":"warn"},
    {"type":"sources","items":[{"label":"官网","url":"https://example.com"}]},
    {"type":"toggle","id":"on","label":"开"},
    {"type":"options","id":"pick","options":[{"label":"甲"},{"label":"乙"}],"multi":true}]}
    """#

    func testFormulasDriveChartsMetricsProgressAndCsv() throws {
        guard case .ok(let bp) = try decode(chartView) else {
            return XCTFail("chartView 应解码为蓝图")
        }
        let nodes = ViewCard.walk(bp.root)
        let lookup = ViewCard.scope(bp.root, ["a": .number(7)])
        let metric = try XCTUnwrap(nodes.first { $0.string("type") == "metric" })
        XCTAssertEqual("10", ViewCard.text(metric["value"], lookup))
        let chartNode = try XCTUnwrap(nodes.first { $0.string("type") == "chart" })
        let chart = try XCTUnwrap(ViewCard.chart(chartNode, lookup))
        XCTAssertEqual("line", chart.kind)
        XCTAssertEqual([1.0, 10.0], chart.series.first?.values)
        XCTAssertEqual(1, chart.series.count)
        XCTAssertEqual(0.0, chart.range.0)
        XCTAssertEqual(10.0, chart.range.1)
        let progress = try XCTUnwrap(nodes.first { $0.string("type") == "progress" })
        XCTAssertEqual(70.0, ViewCard.progressPercent(progress, lookup))
        let table = try XCTUnwrap(nodes.first { $0.string("type") == "table" })
        XCTAssertEqual("\"名, 称\",值\r\n甲,1\r\n\"乙\"\"x\"\"\",20", ViewCard.tableCsv(table, lookup))
        let toggle = try XCTUnwrap(nodes.first { $0.string("type") == "toggle" })
        XCTAssertEqual(ViewFormula.Value.boolean(false), ViewCard.inputDefault(toggle))
        let body = try XCTUnwrap(
            Json.parse(ViewCard.reply(bp, "pick", .options(selected: ["甲", "乙"]), ["a": .number(7), "on": .boolean(true)]))?
                .objectValue
        )
        let values = try XCTUnwrap(body["values"]?.objectValue)
        XCTAssertEqual(#"{"a":7,"b":3,"on":true}"#, Json.encode(try XCTUnwrap(values["inputs"])))
        XCTAssertEqual(#"["甲","乙"]"#, Json.encode(try XCTUnwrap(values["selected"])))
    }

    func testMalformedOrOversizeDegrades() throws {
        XCTAssertTrue(try reason(#"{"type":"chart","categories":["a"],"series":[{"values":[1,2]}]}"#).contains("2 values for 1 categories"))
        XCTAssertTrue(try reason(#"{"type":"chart","kind":"pie","categories":["a"],"series":[{"values":[1]}]}"#).contains("unknown chart"))
        XCTAssertTrue(try reason(#"{"type":"chart","kind":"donut","categories":["a"],"series":[{"values":[1]},{"values":[2]}]}"#).contains("one series"))
        XCTAssertTrue(try reason(#"{"type":"chart","categories":["a"],"series":[{"values":["x"]}]}"#).contains("finite number"))
        let many = (1...9).map { _ in #"{"values":[1]}"# }.joined(separator: ",")
        XCTAssertTrue(try reason(#"{"type":"chart","categories":["a"],"series":[\#(many)]}"#).contains("more than 8 series"))
        let points = (1...51).map { "\"c\($0)\"" }.joined(separator: ",")
        let nums = (1...51).map(String.init).joined(separator: ",")
        XCTAssertTrue(try reason(#"{"type":"chart","categories":[\#(points)],"series":[{"values":[\#(nums)]}]}"#).contains("more than 50 points"))
        XCTAssertTrue(try reason(#"{"type":"script","src":"x"}"#).contains("unknown component"))
        XCTAssertTrue(try reason(#"{"type":"text","text":"x","onClick":"y"}"#).contains("unknown property"))
        XCTAssertTrue(try reason(#"{"type":"metric","label":"x","value":{"=":"missing * 2"}}"#).contains("unknown name"))
        XCTAssertTrue(try reason(#"{"type":"slider","id":"a","label":"A","min":5,"max":1,"value":2}"#).contains("more than min"))
        XCTAssertTrue(try reason(#"{"type":"sources","items":[{"label":"x","url":"javascript:alert(1)"}]}"#).contains("http(s)"))
        // 9 层嵌套超过 8 层；65 个组件超过 64 个
        var deep = #"{"type":"text","text":"x"}"#
        for _ in 0..<8 { deep = #"{"type":"stack","children":[\#(deep)]}"# }
        XCTAssertTrue(try reason(deep).contains("levels exceeds"))
        let kids = (1...16).map { _ in #"{"type":"stack","children":[{"type":"text","text":"a"},{"type":"text","text":"b"},{"type":"text","text":"c"}]}"# }.joined(separator: ",")
        XCTAssertTrue(try reason(#"{"type":"stack","children":[\#(kids)]}"#).contains("components exceeds"))
        guard case .invalid = ViewCard.decode("s", .object(try obj(#"{"title":"x","view":{}}"#))) else {
            return XCTFail("缺 id 应降级")
        }
        guard case .invalid = ViewCard.decode("s", Json.parse("[1]")) else {
            return XCTFail("非对象应降级")
        }
        // 降级摘要
        guard case .ok(let ok) = try decode(chartView) else {
            return XCTFail("chartView 应解码为蓝图")
        }
        XCTAssertTrue(ViewCard.summary(ok.root).contains("合计：5"))
    }

    func testFormAndConfirmReplies() throws {
        guard case .ok(let bp) = try decode(
            #"{"type":"stack","children":[{"type":"form","id":"f","fields":[{"id":"name","label":"名"}]},{"type":"confirm","id":"c"}]}"#
        ) else {
            return XCTFail("表单视图应解码为蓝图")
        }
        XCTAssertEqual(
            #"{"sessionId":"s","viewId":"v1","action":"f","values":{"fields":{"name":"张三"}}}"#,
            ViewCard.reply(bp, "f", .form(fields: [("name", "张三")]), [:])
        )
        XCTAssertEqual(
            #"{"sessionId":"s","viewId":"v1","action":"c","values":{"confirmed":false}}"#,
            ViewCard.reply(bp, "c", .confirm(confirmed: false), [:])
        )
        XCTAssertEqual(
            #"{"sessionId":"s","viewId":"v1","action":"f","values":{"canceled":true}}"#,
            ViewCard.reply(bp, "f", .formCanceled, [:])
        )
        guard case .ok(let readOnly) = try decode(#"{"type":"text","text":"只读"}"#) else {
            return XCTFail("纯文本视图应解码为蓝图")
        }
        XCTAssertFalse(readOnly.interactive)
    }

    func testFormulaEdgeSemantics() throws {
        XCTAssertEqual(ViewFormula.Value.string("c"), eval("false ? 'a' : true ? 'c' : 'd'")) // 三元右结合
        XCTAssertEqual(ViewFormula.Value.string("x"), eval("'' || 'x'")) // || 返回原值
        XCTAssertEqual(ViewFormula.Value.number(0), eval("0 || 0"))
        XCTAssertEqual(ViewFormula.Value.boolean(false), eval("!!(0 / 0)")) // NaN 为假
        XCTAssertEqual(ViewFormula.Value.number(1), eval("7 % 3"))
        XCTAssertEqual(ViewFormula.Value.number(16), eval("'0x10' * 1")) // JS Number('0x10')
        XCTAssertTrue(
            ViewFormula.evaluate(try XCTUnwrap(ViewFormula.compile("'NaN' * 1").tree), ViewFormula.scope([:], [:]))
                .1?.contains("not a number") == true
        )
        XCTAssertTrue(
            ViewFormula.compile(String(repeating: "(", count: 70) + "1" + String(repeating: ")", count: 70))
                .error?.contains("nested more than") == true
        )
        XCTAssertEqual("1e-7", ViewFormula.show(.number(1e-7)))
        XCTAssertEqual("1.5e+21", ViewFormula.show(.number(1.5e21)))
        XCTAssertEqual("123456789012", ViewFormula.show(.number(123456789012)))
        // 12 位有效数字四舍五入 + 纯小数输出（BigDecimal toPlainString 语义，不用 %g 的 e 记法）
        XCTAssertEqual("1234567890120", ViewFormula.show(.number(1234567890123.5)))
        XCTAssertEqual("123456789012000", ViewFormula.show(.number(123456789012345)))
        // fixed 先 roundTo 再 HALF_UP 定标（printf half-even 会给出 2 / 0.12）
        XCTAssertEqual(ViewFormula.Value.string("3"), eval("fixed(2.5, 0)"))
        XCTAssertEqual(ViewFormula.Value.string("0.13"), eval("fixed(0.125, 2)"))
        // 超长左深二元链：降级为 the formula is too deep（不可捕获栈溢出的替代防线）
        let chain = String(repeating: "1+", count: 3000) + "1"
        XCTAssertTrue(ViewFormula.compile(chain).error?.contains("too deep") == true)
    }

    func testSliderSnapStaysInRange() {
        XCTAssertEqual(8.0, ViewCard.snap(10.0, 0.0, 10.0, 4.0)) // 不越过 max 的最后一格
        XCTAssertEqual(4.0, ViewCard.snap(5.0, 0.0, 10.0, 4.0))
        XCTAssertEqual(0.3, ViewCard.snap(0.3, 0.0, 0.3, 0.1)) // 不出现 0.30000000000000004
        XCTAssertEqual(0.2, ViewCard.snap(0.24, 0.0, 0.3, 0.1))
        XCTAssertEqual(1.1, ViewCard.snap(1.12, 0.1, 2.0, 0.5))
        XCTAssertEqual(10.0, ViewCard.snap(99.0, 0.0, 10.0, nil))
        // Math.round 语义（.5 向 +∞）：负值半格不被 .rounded() 的远离零带偏
        XCTAssertEqual(-0.002, ViewCard.snap(-0.0025, -10.0, 10.0, nil))
    }

    func testReplyLiteralsKeepFullPrecision() throws {
        // 非整数输入值按最短往返十进制回显（不截断到 12 位有效数字）
        guard case .ok(let bp) = try decode(chartView) else {
            return XCTFail("chartView 应解码为蓝图")
        }
        let body = ViewCard.reply(bp, "pick", .options(selected: ["甲"]), ["a": .number(0.123456789012345)])
        let parsed = try XCTUnwrap(Json.parse(body)?.objectValue)
        let inputs = try XCTUnwrap(parsed["values"]?.objectValue?["inputs"]?.objectValue)
        XCTAssertEqual("0.123456789012345", inputs["a"]?.numberRaw)
    }

    func testValidationExtrasAndSingleOptionReply() throws {
        XCTAssertTrue(try reason(
            #"{"type":"stack","children":[{"type":"stack","let":{"x":"1"},"children":[{"type":"text","text":"a"}]}]}"#
        ).contains("outermost"))
        XCTAssertTrue(try reason(#"{"type":"html","html":"<b>x</b>","summary":"s","height":-1}"#).contains("height"))
        XCTAssertTrue(try reason(#"{"type":"submit","id":"s","label":3}"#).contains("label"))
        XCTAssertTrue(try reason(#"{"type":"form","id":"f","fields":[{"id":"a","label":"A","readOnly":"yes"}]}"#).contains("readOnly"))
        XCTAssertTrue(try reason(#"{"type":"choice","id":"c","label":"C","options":[{"label":"x","value":[1]}]}"#).contains("value"))
        // choice 缺省取第一个选项，toggle 缺省 false
        guard case .ok(let bp) = try decode(
            #"{"type":"stack","children":[{"type":"choice","id":"c","label":"C","options":[{"label":"甲","value":1},{"label":"乙","value":2}]},{"type":"options","id":"o","options":[{"label":"好"},{"label":"不好"}]}]}"#
        ) else {
            return XCTFail("选择视图应解码为蓝图")
        }
        let inputNodes = ViewCard.inputsOf(bp.root)
        XCTAssertEqual(1, inputNodes.count)
        XCTAssertEqual(ViewFormula.Value.number(1), ViewCard.inputDefault(try XCTUnwrap(inputNodes.first)))
        XCTAssertEqual(
            #"{"sessionId":"s","viewId":"v1","action":"o","values":{"inputs":{"c":1},"selected":["好"]}}"#,
            ViewCard.reply(bp, "o", .options(selected: ["好"]), [:])
        )
    }
}
