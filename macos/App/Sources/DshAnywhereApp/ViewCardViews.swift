import Charts
import DshCore
import SwiftUI

/// show_view 卡渲染（macos-cards-dock）：ViewCard.decode 成功的蓝图按组件树原生绘制
///（stack/row、text/metric/table/chart/progress/callout/sources/html + slider/number/choice/toggle
/// 输入 + options/confirm/form/submit 作答），公式按当前输入求值；Swift Charts 画
/// bar/hbar/line/area/donut；畸形、超限或未知段由停靠区降级为只读摘要，不进本视图。
struct ViewCardRender: View {
    let blueprint: ViewCard.Blueprint
    /// 作答出口：(view-reply 请求体)。
    let submit: (String) -> Void

    /// 输入当前值（按输入组件 id；未动的取初始值，作答时由 ViewCard.reply 兜底）。
    @State private var inputs: [String: ViewFormula.Value] = [:]

    private func setInput(_ id: String, _ value: ViewFormula.Value) {
        inputs[id] = value
    }

    var body: some View {
        let ctx = RenderCtx(
            lookup: ViewCard.scope(blueprint.root, inputs),
            input: { inputs[$0] },
            setInput: { id, v in inputs[id] = v },
            answer: { action, ans in
                submit(ViewCard.reply(blueprint, action, ans, inputs))
            }
        )
        DeepNodeView(node: blueprint.root, ctx: ctx)
            .id(blueprint.id) // 换卡重置输入与局部状态
    }
}

/// 渲染上下文：公式作用域 + 输入读写 + 作答出口。
struct RenderCtx {
    let lookup: (String) throws -> ViewFormula.Value
    let input: (String) -> ViewFormula.Value?
    let setInput: (String, ViewFormula.Value) -> Void
    let answer: (String, ViewCard.Answer) -> Void
}

/// 组件树节点（deep-view 0.1.2 的 17 种组件）。
struct DeepNodeView: View {
    let node: JsonObject
    let ctx: RenderCtx

    var body: some View {
        switch node.string("type") ?? "" {
        case "stack":
            VStack(alignment: .leading, spacing: 10) {
                childViews
            }
        case "row":
            HStack(alignment: .top, spacing: 12) {
                childViews
            }
        case "text":
            TextView(node: node, ctx: ctx)
        case "metric":
            MetricView(node: node, ctx: ctx)
        case "table":
            TableView(node: node, ctx: ctx)
        case "chart":
            DeepChartView(chart: ViewCard.chart(node, ctx.lookup))
        case "progress":
            ProgressSectionView(node: node, ctx: ctx)
        case "callout":
            CalloutView(node: node, ctx: ctx)
        case "sources":
            SourcesView(node: node)
        case "html":
            let sandbox = SandboxHtmlView(
                html: node.string("html") ?? "",
                declaredHeight: ViewCard.finite(node["height"])
            )
            sandbox
                .frame(height: sandbox.height)
                .clipShape(RoundedRectangle(cornerRadius: 6))
        case "slider":
            SliderInputView(node: node, ctx: ctx)
        case "number":
            NumberInputView(node: node, ctx: ctx)
        case "choice":
            ChoiceInputView(node: node, ctx: ctx)
        case "toggle":
            ToggleInputView(node: node, ctx: ctx)
        case "options":
            OptionsAnswerView(node: node, ctx: ctx)
        case "confirm":
            ConfirmAnswerView(node: node, ctx: ctx)
        case "form":
            FormAnswerView(node: node, ctx: ctx)
        case "submit":
            Button(node.string("label") ?? I18n.tr("提交", "Submit")) {
                ctx.answer(node.string("id") ?? "", .submit)
            }
            .controlSize(.large)
        default:
            EmptyView() // 校验已排除未知组件
        }
    }

    @ViewBuilder
    private var childViews: some View {
        ForEach(Array((node.array("children") ?? []).enumerated()), id: \.offset) { _, child in
            if let o = child.objectValue {
                DeepNodeView(node: o, ctx: ctx)
            }
        }
    }
}

// MARK: - 展示组件

private struct TextView: View {
    let node: JsonObject
    let ctx: RenderCtx

    var body: some View {
        Text(ViewCard.text(node["text"], ctx.lookup))
            .font(.callout)
            .foregroundStyle(node.string("tone") == "muted" ? Color.secondary : Color.primary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private struct MetricView: View {
    let node: JsonObject
    let ctx: RenderCtx

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(ViewCard.text(node["label"], ctx.lookup))
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(ViewCard.text(node["value"], ctx.lookup))
                .font(.title3.weight(.semibold))
                .foregroundStyle(toneColor)
            if let caption = node["caption"] {
                Text(ViewCard.text(caption, ctx.lookup))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var toneColor: Color {
        switch node.string("tone") {
        case "good": return .green
        case "warn": return .orange
        case "bad": return .red
        default: return .primary
        }
    }
}

private struct TableView: View {
    let node: JsonObject
    let ctx: RenderCtx

    private var columns: [JsonObject] {
        (node.array("columns") ?? []).compactMap(\.objectValue)
    }

    var body: some View {
        let cols = columns
        let rows = node.array("rows") ?? []
        Grid(alignment: .topLeading, horizontalSpacing: 14, verticalSpacing: 5) {
            GridRow {
                ForEach(Array(cols.enumerated()), id: \.offset) { _, c in
                    Text(c.string("label") ?? "")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                if let cells = row.arrayValue {
                    GridRow {
                        ForEach(Array(cols.enumerated()), id: \.offset) { i, _ in
                            Text(i < cells.count ? ViewCard.tableCell(cells[i], ctx.lookup) : "")
                                .font(.callout)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

private struct ProgressSectionView: View {
    let node: JsonObject
    let ctx: RenderCtx

    var body: some View {
        let percent = ViewCard.progressPercent(node, ctx.lookup)
        VStack(alignment: .leading, spacing: 3) {
            if let label = node["label"] {
                Text(ViewCard.text(label, ctx.lookup))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            ProgressView(value: percent, total: 100)
            HStack(spacing: 6) {
                if let valueLabel = node["valueLabel"] {
                    Text(ViewCard.text(valueLabel, ctx.lookup))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(Int(percent.rounded()))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct CalloutView: View {
    let node: JsonObject
    let ctx: RenderCtx

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            RoundedRectangle(cornerRadius: 1.5)
                .fill(tint)
                .frame(width: 3)
            Text(ViewCard.text(node["text"], ctx.lookup))
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(8)
        .background(tint.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
    }

    private var tint: Color {
        switch node.string("tone") {
        case "good": return .green
        case "warn": return .orange
        case "bad": return .red
        default: return .blue
        }
    }
}

private struct SourcesView: View {
    let node: JsonObject

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array((node.array("items") ?? []).enumerated()), id: \.offset) { _, item in
                if let o = item.objectValue, let label = o.string("label"), let urlRaw = o.string("url"),
                   let url = URL(string: urlRaw) {
                    Button {
                        NSWorkspace.shared.open(url)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "safari")
                            Text(label)
                            Text(urlRaw).font(.caption2).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                        }
                    }
                    .buttonStyle(.link)
                }
            }
        }
    }
}

// MARK: - 图表（Swift Charts）

/// 图表段：bar/hbar 分组柱、line 折线、area 面积、donut 圆环；donut 图例带值，其余显示单位与说明。
private struct DeepChartView: View {
    let chart: ViewCard.Chart?

    var body: some View {
        if let chart {
            VStack(alignment: .leading, spacing: 5) {
                chartBody(chart)
                    .frame(height: chart.kind == "hbar" ? max(120, CGFloat(chart.categories.count) * 28) : 180)
                if chart.kind == "donut" {
                    donutLegend(chart)
                } else if let unit = chart.unit {
                    Text(I18n.tr("单位：\(unit)", "Unit: \(unit)"))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                if let caption = chart.caption {
                    Text(caption)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            EmptyView()
        }
    }

    @ViewBuilder
    private func chartBody(_ chart: ViewCard.Chart) -> some View {
        Chart {
            ForEach(Array(chart.series.enumerated()), id: \.offset) { seriesIndex, series in
                let name = series.name ?? I18n.tr("系列 \(seriesIndex + 1)", "Series \(seriesIndex + 1)")
                ForEach(Array(chart.categories.enumerated()), id: \.offset) { i, category in
                    let value = i < series.values.count ? series.values[i] : 0
                    switch chart.kind {
                    case "hbar":
                        BarMark(
                            x: .value(I18n.tr("值", "Value"), value),
                            y: .value(I18n.tr("类别", "Category"), category)
                        )
                        .foregroundStyle(by: .value(I18n.tr("系列", "Series"), name))
                    case "line":
                        LineMark(
                            x: .value(I18n.tr("类别", "Category"), category),
                            y: .value(I18n.tr("值", "Value"), value)
                        )
                        .foregroundStyle(by: .value(I18n.tr("系列", "Series"), name))
                        .interpolationMethod(.monotone)
                        PointMark(
                            x: .value(I18n.tr("类别", "Category"), category),
                            y: .value(I18n.tr("值", "Value"), value)
                        )
                        .foregroundStyle(by: .value(I18n.tr("系列", "Series"), name))
                    case "area":
                        AreaMark(
                            x: .value(I18n.tr("类别", "Category"), category),
                            y: .value(I18n.tr("值", "Value"), value)
                        )
                        .foregroundStyle(by: .value(I18n.tr("系列", "Series"), name))
                        .interpolationMethod(.monotone)
                        .opacity(0.35)
                        LineMark(
                            x: .value(I18n.tr("类别", "Category"), category),
                            y: .value(I18n.tr("值", "Value"), value)
                        )
                        .foregroundStyle(by: .value(I18n.tr("系列", "Series"), name))
                        .interpolationMethod(.monotone)
                    case "donut":
                        SectorMark(
                            angle: .value(I18n.tr("值", "Value"), value),
                            innerRadius: .ratio(0.55),
                            angularInset: 1
                        )
                        .foregroundStyle(by: .value(I18n.tr("类别", "Category"), category))
                        .annotation(position: .overlay) {
                            if value > 0 {
                                Text(category)
                                    .font(.caption2)
                                    .foregroundStyle(.white)
                            }
                        }
                    default: // bar
                        BarMark(
                            x: .value(I18n.tr("类别", "Category"), category),
                            y: .value(I18n.tr("值", "Value"), value)
                        )
                        .foregroundStyle(by: .value(I18n.tr("系列", "Series"), name))
                    }
                }
            }
        }
    }

    /// donut 图例：类别 + 值 + 单位（与 Android Canvas 版同口径）。
    private func donutLegend(_ chart: ViewCard.Chart) -> some View {
        let values = chart.series.first?.values ?? []
        return FlexibleWrap(items: chart.categories.enumerated().compactMap { i, c in
            i < values.count ? "\(c) \(ViewFormula.show(.number(values[i])))\(chart.unit ?? "")" : nil
        })
    }
}

/// 简单流式换行容器（donut 图例）。
private struct FlexibleWrap: View {
    let items: [String]

    var body: some View {
        var width: CGFloat = 0
        var height: CGFloat = 0
        return GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    Text(item)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .padding(.trailing, 10)
                        .alignmentGuide(.leading) { d in
                            if abs(width - d.width) > geo.size.width {
                                width = 0
                                height -= d.height
                            }
                            let result = width
                            if item == items.last {
                                width = 0
                            } else {
                                width -= d.width
                            }
                            return result
                        }
                        .alignmentGuide(.top) { _ in
                            let result = height
                            if item == items.last {
                                height = 0
                            }
                            return result
                        }
                }
            }
        }
        .frame(height: CGFloat((items.count / 3 + 1)) * 18)
    }
}

// MARK: - 输入组件

private struct SliderInputView: View {
    let node: JsonObject
    let ctx: RenderCtx

    private var id: String { node.string("id") ?? "" }
    private var minV: Double { ViewCard.finite(node["min"]) ?? 0 }
    private var maxV: Double { ViewCard.finite(node["max"]) ?? 100 }
    private var step: Double? { ViewCard.finite(node["step"]) }

    private var current: Double {
        if case .number(let d)? = ctx.input(id) { return d }
        if case .number(let d)? = ViewCard.inputDefault(node) { return d }
        return minV
    }

    var body: some View {
        let value = current
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(node.string("label") ?? id).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(displayNumber(value) + (node.string("unit").map { " \($0)" } ?? ""))
                    .font(.callout.monospacedDigit())
            }
            Slider(
                value: Binding(
                    get: { current },
                    set: { raw in
                        ctx.setInput(id, .number(ViewCard.snap(raw, minV, maxV, step)))
                    }
                ),
                in: minV...maxV
            )
        }
    }

    private func displayNumber(_ d: Double) -> String {
        ViewFormula.show(.number(d))
    }
}

private struct NumberInputView: View {
    let node: JsonObject
    let ctx: RenderCtx

    private var id: String { node.string("id") ?? "" }
    private var minV: Double? { ViewCard.finite(node["min"]) }
    private var maxV: Double? { ViewCard.finite(node["max"]) }
    private var step: Double? { ViewCard.finite(node["step"]) }

    private var current: Double {
        if case .number(let d)? = ctx.input(id) { return d }
        if case .number(let d)? = ViewCard.inputDefault(node) { return d }
        return 0
    }

    /// 数值输入的取整：带步长时吸附 min + k·step 并夹到边界，去掉浮点噪声（保留 3 位小数）。
    private func normalize(_ raw: Double) -> Double {
        if let step, step > 0, let minV {
            if let maxV, maxV >= minV {
                return ViewCard.snap(raw, minV, maxV, step)
            }
            let k = ((raw - minV) / step).rounded()
            return max(((minV + k * step) * 1000).rounded() / 1000, minV)
        }
        var out = (raw * 1000).rounded() / 1000
        if let minV { out = max(out, minV) }
        if let maxV { out = min(out, maxV) }
        return out
    }

    var body: some View {
        let value = current
        HStack(spacing: 8) {
            Text(node.string("label") ?? id).font(.caption).foregroundStyle(.secondary)
            Spacer()
            Button {
                ctx.setInput(id, .number(normalize(value - (step ?? 1))))
            } label: {
                Image(systemName: "minus.circle")
            }
            .buttonStyle(.borderless)
            Text(ViewFormula.show(.number(value)))
                .font(.callout.monospacedDigit())
                .frame(minWidth: 48)
            Button {
                ctx.setInput(id, .number(normalize(value + (step ?? 1))))
            } label: {
                Image(systemName: "plus.circle")
            }
            .buttonStyle(.borderless)
            if let unit = node.string("unit") {
                Text(unit).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private struct ChoiceInputView: View {
    let node: JsonObject
    let ctx: RenderCtx

    private var id: String { node.string("id") ?? "" }

    private var options: [JsonObject] {
        (node.array("options") ?? []).compactMap(\.objectValue)
    }

    private var current: ViewFormula.Value? {
        ctx.input(id) ?? ViewCard.inputDefault(node)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(node.string("label") ?? id).font(.caption).foregroundStyle(.secondary)
            ForEach(Array(options.enumerated()), id: \.offset) { _, opt in
                let value = ViewCard.choiceValue(opt)
                Button {
                    if let value {
                        ctx.setInput(id, value)
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: current == value ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(current == value ? Color.accentColor : Color.secondary)
                        Text(opt.string("label") ?? "")
                            .foregroundStyle(.primary)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct ToggleInputView: View {
    let node: JsonObject
    let ctx: RenderCtx

    private var id: String { node.string("id") ?? "" }

    private var isOn: Bool {
        if case .boolean(let b)? = ctx.input(id) { return b }
        if case .boolean(let b)? = ViewCard.inputDefault(node) { return b }
        return false
    }

    var body: some View {
        Toggle(node.string("label") ?? id, isOn: Binding(
            get: { isOn },
            set: { ctx.setInput(id, .boolean($0)) }
        ))
        .toggleStyle(.switch)
    }
}

// MARK: - 作答组件

private struct OptionsAnswerView: View {
    let node: JsonObject
    let ctx: RenderCtx

    private var multi: Bool { node["multi"]?.boolValue == true }
    private var options: [(label: String, description: String?)] {
        (node.array("options") ?? []).compactMap { o in
            guard let obj = o.objectValue, let label = obj.string("label") else { return nil }
            return (label, obj.string("description"))
        }
    }

    @State private var picked: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(options, id: \.label) { opt in
                Button {
                    if multi {
                        if picked.contains(opt.label) {
                            picked.remove(opt.label)
                        } else {
                            picked.insert(opt.label)
                        }
                    } else {
                        // 单选即交
                        ctx.answer(node.string("id") ?? "", .options(selected: [opt.label]))
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: multi ? (picked.contains(opt.label) ? "checkmark.square.fill" : "square") : "circle")
                            .foregroundStyle(multi && picked.contains(opt.label) ? Color.accentColor : Color.secondary)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(opt.label).foregroundStyle(.primary)
                            if let d = opt.description {
                                Text(d).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
            if multi {
                Button(I18n.tr("提交", "Submit")) {
                    ctx.answer(node.string("id") ?? "", .options(selected: options.map(\.label).filter { picked.contains($0) }))
                }
                .disabled(picked.isEmpty)
            }
        }
    }
}

private struct ConfirmAnswerView: View {
    let node: JsonObject
    let ctx: RenderCtx

    var body: some View {
        HStack {
            Button(node.string("confirmLabel") ?? I18n.tr("确认", "Confirm")) {
                ctx.answer(node.string("id") ?? "", .confirm(confirmed: true))
            }
            Button(node.string("cancelLabel") ?? I18n.tr("取消", "Cancel")) {
                ctx.answer(node.string("id") ?? "", .confirm(confirmed: false))
            }
        }
    }
}

private struct FormAnswerView: View {
    let node: JsonObject
    let ctx: RenderCtx

    private struct Field: Identifiable {
        let id: String
        let label: String
        let placeholder: String?
        let multiline: Bool
        let readOnly: Bool
        let initial: String
    }

    private var fields: [Field] {
        (node.array("fields") ?? []).compactMap { f in
            guard let o = f.objectValue, let id = o.string("id") else { return nil }
            return Field(
                id: id,
                label: o.string("label") ?? id,
                placeholder: o.string("placeholder"),
                multiline: o["multiline"]?.boolValue == true,
                readOnly: o["readOnly"]?.boolValue == true,
                initial: o.string("value") ?? ""
            )
        }
    }

    @State private var values: [String: String] = [:]
    @State private var loaded = false

    var body: some View {
        let list = fields
        VStack(alignment: .leading, spacing: 8) {
            ForEach(list) { field in
                VStack(alignment: .leading, spacing: 2) {
                    Text(field.label).font(.caption).foregroundStyle(.secondary)
                    if field.multiline {
                        TextField(field.placeholder ?? "", text: binding(field), axis: .vertical)
                            .lineLimit(2...5)
                            .disabled(field.readOnly)
                    } else {
                        TextField(field.placeholder ?? "", text: binding(field))
                            .disabled(field.readOnly)
                    }
                }
            }
            HStack {
                Button(node.string("submitLabel") ?? I18n.tr("提交", "Submit")) {
                    ctx.answer(
                        node.string("id") ?? "",
                        .form(fields: list.map { ($0.id, values[$0.id] ?? $0.initial) })
                    )
                }
                Button(node.string("cancelLabel") ?? I18n.tr("取消", "Cancel")) {
                    ctx.answer(node.string("id") ?? "", .formCanceled)
                }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            for f in list where values[f.id] == nil {
                values[f.id] = f.initial
            }
        }
    }

    private func binding(_ field: Field) -> Binding<String> {
        Binding(
            get: { values[field.id] ?? field.initial },
            set: { values[field.id] = $0 }
        )
    }
}
