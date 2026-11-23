package work.keepwriting.core

/**
 * show_view 卡片（dsbot-rich-render）：DS-Bot 0.3.10 state.views[sessionId] = {id, title, view}，view 为
 * deep-view 0.1.2 的组件树（stack/row 布局 + text/metric/table/chart/progress/callout/sources/html 展示 +
 * slider/number/choice/toggle 输入 + options/confirm/form/submit 作答，属性可为公式 {"=": "…"}，最外层可有 let）。
 *
 * 校验移植 deep-view core/views.js 的 checkNode（上限：8 层、64 个组件、每层 16 个子节点、文字 2000、
 * 单元格 200、8 列 50 行、8 个选项、6 个字段；图表 categories/series 对齐、数值有限、donut 单序列），
 * 另加客户端绘制上限（序列 ≤ 8、点数 ≤ 50）与整卡 64 000 字符。不通过即降级为只读摘要，不绘制。
 * 作答经 view-reply {sessionId, viewId, action, values}（values 形态与 validateViewAnswer 一致）。
 */
object ViewCard {
    const val MAX_DEPTH = 8
    const val MAX_NODES = 64
    const val MAX_CHILDREN = 16
    const val MAX_TEXT = 2000
    const val MAX_CELL = 200
    const val MAX_COLUMNS = 8
    const val MAX_ROWS = 50
    const val MAX_OPTIONS = 8
    const val MAX_FIELDS = 6
    const val MAX_SERIES = 8
    const val MAX_POINTS = 50

    val CHART_KINDS = listOf("bar", "hbar", "line", "area", "donut")
    private val LAYOUT = setOf("stack", "row")
    private val INPUTS = setOf("slider", "choice", "toggle", "number")
    private val ANSWERS = setOf("options", "confirm", "form", "submit")
    private val PROPS = mapOf(
        "stack" to setOf("children", "let"), "row" to setOf("children", "let"),
        "text" to setOf("text", "tone"), "metric" to setOf("label", "value", "tone", "caption"),
        "table" to setOf("columns", "rows"), "chart" to setOf("kind", "categories", "series", "unit", "caption"),
        "progress" to setOf("label", "percent", "value", "max", "valueLabel", "tone"),
        "callout" to setOf("text", "tone"), "sources" to setOf("items"), "html" to setOf("html", "summary", "height"),
        "slider" to setOf("id", "label", "min", "max", "step", "value", "unit"),
        "choice" to setOf("id", "label", "options", "value"), "toggle" to setOf("id", "label", "value"),
        "number" to setOf("id", "label", "min", "max", "step", "value", "unit"),
        "options" to setOf("id", "options", "multi"), "confirm" to setOf("id", "confirmLabel", "cancelLabel"),
        "form" to setOf("id", "fields", "submitLabel", "cancelLabel"), "submit" to setOf("id", "label"),
    )
    private val TONES = listOf("good", "warn", "bad")
    private val CALLOUT_TONES = listOf("info", "good", "warn", "bad")
    private val FIELD_PROPS = setOf("id", "label", "placeholder", "value", "multiline", "readOnly")

    data class Blueprint(val sessionId: String, val id: String, val title: String?, val root: Json.Value.Object, val interactive: Boolean)

    sealed interface Decoded {
        data class Ok(val blueprint: Blueprint) : Decoded

        /** 畸形或超限：只读显示标题与原因。 */
        data class Invalid(val sessionId: String, val id: String?, val title: String?, val reason: String) : Decoded
    }

    /** state.views 的一项 → 可绘制的蓝图或降级。 */
    fun decode(sessionId: String, entry: Json.Value?): Decoded {
        val o = entry as? Json.Value.Object ?: return Decoded.Invalid(sessionId, null, null, "not an object")
        val id = str(o, "id")
        val title = str(o, "title")?.trim()?.takeIf { it.isNotEmpty() }
        if (id.isNullOrEmpty()) return Decoded.Invalid(sessionId, null, title, "missing id")
        val view = o.members["view"] as? Json.Value.Object ?: return Decoded.Invalid(sessionId, id, title, "missing view")
        if (Json.encode(view).length > Cards.MAX_CHARS) return Decoded.Invalid(sessionId, id, title, "view too large")
        val error = try {
            validate(view)
        } catch (e: Exception) {
            "validation failed: ${e.javaClass.simpleName}"
        } catch (_: StackOverflowError) {
            "view too deep"
        }
        return if (error == null) Decoded.Ok(Blueprint(sessionId, id, title, view, interactiveOf(view))) else Decoded.Invalid(sessionId, id, title, error)
    }

    // ---- 校验（deep-view checkNode 的移植） ----

    private class Ctx {
        val ids = HashSet<String>()
        var nodes = 0
        val inputs = LinkedHashMap<String, Any>()
        val lets = LinkedHashMap<String, String>()
        val formulas = mutableListOf<Triple<String, String, Any>>() // source, path, kind
    }

    /** 不通过返回原因，通过返回 null。 */
    fun validate(view: Json.Value.Object): String? {
        val ctx = Ctx()
        return checkNode(view, "view", 1, ctx) ?: checkFormulas(ctx)
    }

    private fun checkNode(node: Json.Value?, path: String, depth: Int, ctx: Ctx): String? {
        val o = node as? Json.Value.Object ?: return "$path: expected a component object"
        if (depth > MAX_DEPTH) return "$path: $depth levels exceeds the limit of $MAX_DEPTH"
        if (++ctx.nodes > MAX_NODES) return "$path: ${ctx.nodes} components exceeds the limit of $MAX_NODES"
        val type = str(o, "type")
        val props = PROPS[type] ?: return "$path.type: unknown component"
        o.members.keys.firstOrNull { it != "type" && it !in props }?.let { return "$path.$it: unknown property" }
        return when (type) {
            "stack", "row" -> checkLayout(o, path, depth, ctx)
            "text" -> checkValue(o, "text", path, ctx, "string", required = true) ?: checkValue(o, "tone", path, ctx, listOf("muted"))
            "metric" -> if (str(o, "label") == null && o.members["label"] !is Json.Value.StringValue) "$path.label: expected a string"
            else if (o.members["value"] == null) "$path.value: expected a string or a number"
            else checkValue(o, "value", path, ctx, "scalar") ?: checkValue(o, "tone", path, ctx, TONES) ?: checkValue(o, "caption", path, ctx, "string")
            "table" -> checkTable(o, path, ctx)
            "chart" -> checkChart(o, path, ctx)
            "progress" -> checkProgress(o, path, ctx)
            "callout" -> checkValue(o, "text", path, ctx, "string", required = true) ?: checkValue(o, "tone", path, ctx, CALLOUT_TONES)
            "sources" -> checkSources(o, path)
            "html" -> if (str(o, "html").isNullOrBlank()) "$path.html: expected a non-empty string"
            else if (str(o, "summary").isNullOrBlank()) "$path.summary: expected one line"
            else if (o.members["height"] != null && (finite(o.members["height"]) ?: 0.0) <= 0) "$path.height: expected a positive number"
            else null
            "slider", "number" -> checkNumberInput(o, type, path, ctx)
            "choice" -> checkChoice(o, path, ctx)
            "toggle" -> checkInputId(o, path, ctx) ?: run {
                val v = o.members["value"]
                if (v != null && v !is Json.Value.BoolValue) "$path.value: expected true or false"
                else { ctx.inputs[str(o, "id")!!] = (v as? Json.Value.BoolValue)?.value == true; null }
            }
            "options" -> checkOptions(o, path, ctx)
            "confirm" -> checkId(o, path, ctx) ?: optionalStrings(o, path, "confirmLabel", "cancelLabel")
            "form" -> checkForm(o, path, ctx)
            "submit" -> checkId(o, path, ctx) ?: optionalStrings(o, path, "label")
            else -> null
        }
    }

    private fun checkLayout(o: Json.Value.Object, path: String, depth: Int, ctx: Ctx): String? {
        (o.members["let"])?.let { lets ->
            if (depth != 1) return "$path.let: only the outermost component can define names"
            val obj = lets as? Json.Value.Object ?: return "$path.let: expected an object"
            for ((name, src) in obj.members) {
                if (!ViewFormula.isName(name) || name == "true" || name == "false") return "$path.let.$name: invalid name"
                val s = (src as? Json.Value.StringValue)?.value ?: return "$path.let.$name: expected a formula string"
                ctx.lets[name] = s
            }
        }
        val children = (o.members["children"] as? Json.Value.Array)?.items ?: return "$path.children: expected an array"
        if (children.isEmpty() || children.size > MAX_CHILDREN) return "$path.children: expected 1 to $MAX_CHILDREN children"
        children.forEachIndexed { i, c -> checkNode(c, "$path.children[$i]", depth + 1, ctx)?.let { return it } }
        return null
    }

    private fun isFormula(v: Json.Value?): Boolean =
        v is Json.Value.Object && v.members.size == 1 && v.members.containsKey("=")

    private fun checkValue(o: Json.Value.Object, key: String, path: String, ctx: Ctx, kind: Any, required: Boolean = false, max: Int = MAX_TEXT): String? {
        val v = o.members[key] ?: return if (required) "$path.$key: missing" else null
        if (isFormula(v)) {
            ctx.formulas.add(Triple(((v as Json.Value.Object).members["="] as? Json.Value.StringValue)?.value ?: "", "$path.$key", kind))
            return null
        }
        return when (kind) {
            "number" -> if (finite(v) != null) null else "$path.$key: expected a number"
            "string" -> when (val text = (v as? Json.Value.StringValue)?.value) {
                null -> "$path.$key: expected a string"
                else -> if (text.length > max) "$path.$key: too long" else null
            }
            is List<*> -> if ((v as? Json.Value.StringValue)?.value in kind) null else "$path.$key: unknown tone"
            else -> if (scalar(v) != null) null else "$path.$key: expected a string or a number"
        }
    }

    private fun checkTable(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        val cols = (o.members["columns"] as? Json.Value.Array)?.items ?: return "$path.columns: expected an array"
        if (cols.size > MAX_COLUMNS) return "$path.columns: too many columns"
        for (c in cols) {
            val co = c as? Json.Value.Object ?: return "$path.columns: expected column objects"
            if (co.members.keys.any { it != "key" && it != "label" }) return "$path.columns: unknown property"
            if (str(co, "key").isNullOrEmpty()) return "$path.columns: key expected"
            if (co.members["label"] !is Json.Value.StringValue) return "$path.columns: label expected"
        }
        val rows = (o.members["rows"] as? Json.Value.Array)?.items ?: return "$path.rows: expected an array"
        if (rows.size > MAX_ROWS) return "$path.rows: too many rows"
        rows.forEachIndexed { r, row ->
            val cells = (row as? Json.Value.Array)?.items ?: return "$path.rows[$r]: expected an array of cells"
            cells.forEachIndexed { c, cell ->
                if (isFormula(cell)) ctx.formulas.add(Triple(((cell as Json.Value.Object).members["="] as? Json.Value.StringValue)?.value ?: "", "$path.rows[$r][$c]", "scalar"))
                else if (cellText(cell).length > MAX_CELL) return "$path.rows[$r][$c]: too long"
            }
        }
        return null
    }

    private fun checkChart(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        val kind = o.members["kind"]
        if (kind != null && (kind as? Json.Value.StringValue)?.value !in CHART_KINDS) return "$path.kind: unknown chart"
        val cats = (o.members["categories"] as? Json.Value.Array)?.items
        if (cats.isNullOrEmpty()) return "$path.categories: expected a non-empty array"
        if (cats.size > MAX_POINTS) return "$path.categories: more than $MAX_POINTS points"
        if (cats.any { it !is Json.Value.StringValue && finite(it) == null }) return "$path.categories: expected labels"
        val series = (o.members["series"] as? Json.Value.Array)?.items
        if (series.isNullOrEmpty()) return "$path.series: expected a non-empty array"
        if (series.size > MAX_SERIES) return "$path.series: more than $MAX_SERIES series"
        if ((kind as? Json.Value.StringValue)?.value == "donut" && series.size > 1) return "$path.series: a donut takes one series"
        series.forEachIndexed { i, s ->
            val so = s as? Json.Value.Object ?: return "$path.series[$i]: expected a series object"
            if (so.members.keys.any { it != "name" && it != "values" }) return "$path.series[$i]: unknown property"
            if (so.members["name"] != null && so.members["name"] !is Json.Value.StringValue) return "$path.series[$i].name: expected a string"
            val values = (so.members["values"] as? Json.Value.Array)?.items ?: return "$path.series[$i].values: expected numbers"
            if (values.size != cats.size) return "$path.series[$i].values: ${values.size} values for ${cats.size} categories"
            values.forEachIndexed { j, v ->
                if (isFormula(v)) ctx.formulas.add(Triple(((v as Json.Value.Object).members["="] as? Json.Value.StringValue)?.value ?: "", "$path.series[$i].values[$j]", "number"))
                else if (finite(v) == null) return "$path.series[$i].values[$j]: expected a finite number"
            }
        }
        if (o.members["unit"] != null && o.members["unit"] !is Json.Value.StringValue) return "$path.unit: expected a string"
        if (o.members["caption"] != null && o.members["caption"] !is Json.Value.StringValue) return "$path.caption: expected a string"
        return null
    }

    private fun checkProgress(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        val hasPercent = o.members["percent"] != null
        val hasValue = o.members["value"] != null
        if (o.members["label"] != null && o.members["label"] !is Json.Value.StringValue) return "$path.label: expected a string"
        if (hasPercent == hasValue) return "$path: give percent, or value with max"
        if (hasPercent && !isFormula(o.members["percent"])) {
            val p = finite(o.members["percent"]) ?: return "$path.percent: expected a number"
            if (p < 0 || p > 100) return "$path.percent: expected 0 to 100"
        }
        if (hasValue != (o.members["max"] != null)) return "$path: value and max go together"
        return checkValue(o, "percent", path, ctx, "number") ?: checkValue(o, "value", path, ctx, "number") ?: checkValue(o, "max", path, ctx, "number")
            ?: checkValue(o, "valueLabel", path, ctx, "scalar") ?: checkValue(o, "tone", path, ctx, TONES)
    }

    private fun checkSources(o: Json.Value.Object, path: String): String? {
        val items = (o.members["items"] as? Json.Value.Array)?.items
        if (items.isNullOrEmpty()) return "$path.items: expected a non-empty array"
        for (it in items) {
            val io = it as? Json.Value.Object ?: return "$path.items: expected objects"
            if (io.members.keys.any { k -> k != "label" && k != "url" }) return "$path.items: unknown property"
            if (str(io, "label").isNullOrBlank()) return "$path.items: label expected"
            if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(str(io, "url") ?: "")) return "$path.items: url must be http(s)"
        }
        return null
    }

    private fun checkInputId(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        val id = str(o, "id")
        if (!ViewFormula.isName(id) || id == "true" || id == "false") return "$path.id: invalid input id"
        if (!ctx.ids.add(id!!)) return "$path.id: duplicate id"
        if (o.members["label"] !is Json.Value.StringValue) return "$path.label: expected a string"
        return null
    }

    private fun checkNumberInput(o: Json.Value.Object, type: String, path: String, ctx: Ctx): String? {
        checkInputId(o, path, ctx)?.let { return it }
        for (k in listOf("min", "max", "step")) if (o.members[k] != null && finite(o.members[k]) == null) return "$path.$k: expected a number"
        val min = finite(o.members["min"])
        val max = finite(o.members["max"])
        val step = finite(o.members["step"])
        if (type == "slider" && (min == null || max == null)) return "$path: a slider needs min and max"
        if (min != null && max != null && max <= min) return "$path.max: expected more than min"
        if (step != null && step <= 0) return "$path.step: expected more than 0"
        val v = finite(o.members["value"]) ?: return "$path.value: expected a number"
        if ((min != null && v < min) || (max != null && v > max)) return "$path.value: out of range"
        if (o.members["unit"] != null && o.members["unit"] !is Json.Value.StringValue) return "$path.unit: expected a string"
        ctx.inputs[str(o, "id")!!] = v
        return null
    }

    private fun checkChoice(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        checkInputId(o, path, ctx)?.let { return it }
        val opts = (o.members["options"] as? Json.Value.Array)?.items
        if (opts.isNullOrEmpty()) return "$path.options: expected a non-empty array"
        val labels = HashSet<String>()
        val values = mutableListOf<Any>()
        for (op in opts) {
            val oo = op as? Json.Value.Object ?: return "$path.options: expected objects"
            if (oo.members.keys.any { it != "label" && it != "value" }) return "$path.options: unknown property"
            val label = str(oo, "label")?.takeIf { it.isNotBlank() } ?: return "$path.options: label expected"
            if (!labels.add(label)) return "$path.options: duplicate label"
            if (oo.members["value"] != null && scalar(oo.members["value"]) == null) return "$path.options: value must be a string, a number or true/false"
            val value = choiceValue(oo) ?: return "$path.options: value must be a string, a number or true/false"
            if (values.any { it == value }) return "$path.options: duplicate value"
            values.add(value)
        }
        if (o.members["value"] != null && scalar(o.members["value"]) == null) return "$path.value: expected one of the options' values"
        val chosen = o.members["value"]?.let { scalar(it) } ?: choiceValue(opts[0] as Json.Value.Object)
        if (values.none { it == chosen }) return "$path.value: expected one of the options' values"
        ctx.inputs[str(o, "id")!!] = chosen!!
        return null
    }

    private fun checkOptions(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        checkId(o, path, ctx)?.let { return it }
        if (o.members["multi"] != null && o.members["multi"] !is Json.Value.BoolValue) return "$path.multi: expected a boolean"
        val opts = (o.members["options"] as? Json.Value.Array)?.items ?: return "$path.options: expected an array"
        if (opts.isEmpty() || opts.size > MAX_OPTIONS) return "$path.options: expected 1 to $MAX_OPTIONS options"
        val labels = HashSet<String>()
        for (op in opts) {
            val oo = op as? Json.Value.Object ?: return "$path.options: expected objects"
            if (oo.members.keys.any { it != "label" && it != "description" }) return "$path.options: unknown property"
            val label = str(oo, "label")?.takeIf { it.isNotBlank() } ?: return "$path.options: label expected"
            if (!labels.add(label)) return "$path.options: duplicate label"
            if (oo.members["description"] != null && oo.members["description"] !is Json.Value.StringValue) return "$path.options: description must be a string"
        }
        return null
    }

    private fun optionalStrings(o: Json.Value.Object, path: String, vararg keys: String): String? =
        keys.firstOrNull { o.members[it] != null && o.members[it] !is Json.Value.StringValue }?.let { "$path.$it: expected a string" }

    private fun checkForm(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        checkId(o, path, ctx)?.let { return it }
        optionalStrings(o, path, "submitLabel", "cancelLabel")?.let { return it }
        val fields = (o.members["fields"] as? Json.Value.Array)?.items ?: return "$path.fields: expected an array"
        if (fields.size > MAX_FIELDS) return "$path.fields: too many fields"
        val seen = HashSet<String>()
        for (f in fields) {
            val fo = f as? Json.Value.Object ?: return "$path.fields: expected objects"
            if (fo.members.keys.any { it !in FIELD_PROPS }) return "$path.fields: unknown property"
            val id = str(fo, "id")?.takeIf { it.isNotEmpty() && it != "__proto__" } ?: return "$path.fields: id expected"
            if (!seen.add(id)) return "$path.fields: duplicate id"
            if (fo.members["label"] !is Json.Value.StringValue) return "$path.fields: label expected"
            optionalStrings(fo, "$path.fields", "placeholder")?.let { return it }
            for (k in listOf("multiline", "readOnly")) if (fo.members[k] != null && fo.members[k] !is Json.Value.BoolValue) return "$path.fields.$k: expected a boolean"
            (fo.members["value"])?.let { if ((it as? Json.Value.StringValue)?.value?.length?.let { n -> n <= MAX_TEXT } != true) return "$path.fields: value must be text" }
        }
        return null
    }

    private fun checkId(o: Json.Value.Object, path: String, ctx: Ctx): String? {
        val id = str(o, "id")?.takeIf { it.isNotEmpty() && it != "__proto__" } ?: return "$path.id: expected a non-empty string"
        if (!ctx.ids.add(id)) return "$path.id: duplicate id"
        return null
    }

    private fun checkFormulas(ctx: Ctx): String? {
        val known = ctx.inputs.keys + ctx.lets.keys
        val lets = LinkedHashMap<String, ViewFormula.Node>()
        for ((name, src) in ctx.lets) {
            if (name in ctx.inputs) return "let $name: already an input id"
            val c = ViewFormula.compile(src)
            c.error?.let { return "let $name: $it" }
            c.names.firstOrNull { it !in known }?.let { return "let $name: unknown name $it" }
            lets[name] = c.tree!!
        }
        val lookup = ViewFormula.scope(ctx.inputs, lets)
        for ((name, tree) in lets) ViewFormula.evaluate(tree, lookup).second?.let { return "let $name: $it" }
        for ((src, path, kind) in ctx.formulas) {
            val c = ViewFormula.compile(src)
            c.error?.let { return "$path: $it" }
            c.names.firstOrNull { it !in known }?.let { return "$path: unknown name $it" }
            val (value, error) = ViewFormula.evaluate(c.tree!!, lookup)
            error?.let { return "$path: $it" }
            val ok = when (kind) {
                "number" -> value is Double && value.isFinite()
                is List<*> -> value in kind
                else -> value is String || value is Boolean || (value is Double && value.isFinite())
            }
            if (!ok) return "$path: the formula gives the wrong kind of value"
        }
        return null
    }

    // ---- 求值与绘制数据 ----

    private fun interactiveOf(node: Json.Value.Object): Boolean = walk(node).any { str(it, "type") in ANSWERS }

    /** 先序遍历（布局下钻）。 */
    fun walk(node: Json.Value.Object): List<Json.Value.Object> {
        val out = mutableListOf<Json.Value.Object>()
        fun go(n: Json.Value?) {
            val o = n as? Json.Value.Object ?: return
            out.add(o)
            if (str(o, "type") in LAYOUT) (o.members["children"] as? Json.Value.Array)?.items?.forEach(::go)
        }
        go(node)
        return out
    }

    fun inputsOf(root: Json.Value.Object): List<Json.Value.Object> = walk(root).filter { str(it, "type") in INPUTS }

    fun choiceValue(option: Json.Value.Object): Any? = option.members["value"]?.let { scalar(it) } ?: str(option, "label")

    /** 输入的初始值：toggle 默认 false，choice 默认第一个选项。 */
    fun inputDefault(node: Json.Value.Object): Any? = when (str(node, "type")) {
        "toggle" -> (node.members["value"] as? Json.Value.BoolValue)?.value == true
        "choice" -> node.members["value"]?.let { scalar(it) }
            ?: ((node.members["options"] as? Json.Value.Array)?.items?.firstOrNull() as? Json.Value.Object)?.let(::choiceValue)
        else -> finite(node.members["value"])
    }

    /** 当前输入值下的作用域（未给的输入取初始值）。 */
    fun scope(root: Json.Value.Object, values: Map<String, Any> = emptyMap()): (String) -> Any {
        val inputs = LinkedHashMap<String, Any>()
        for (n in inputsOf(root)) {
            val id = str(n, "id") ?: continue
            (values[id] ?: inputDefault(n))?.let { inputs[id] = it }
        }
        val lets = LinkedHashMap<String, ViewFormula.Node>()
        (root.members["let"] as? Json.Value.Object)?.members?.forEach { (name, src) ->
            compiled((src as? Json.Value.StringValue)?.value).tree?.let { lets[name] = it }
        }
        return ViewFormula.scope(inputs, lets)
    }

    /** 属性值：字面量照原样（转成 Kotlin 值），公式按作用域求值，失败取 [fallback]。 */
    fun resolve(value: Json.Value?, lookup: (String) -> Any, fallback: Any? = null): Any? {
        if (value == null) return fallback
        if (!isFormula(value)) return scalar(value) ?: fallback
        val src = ((value as Json.Value.Object).members["="] as? Json.Value.StringValue)?.value
        val tree = compiled(src).tree ?: return fallback
        val (v, error) = ViewFormula.evaluate(tree, lookup)
        return if (error == null) v else fallback
    }

    /** 公式编译缓存（拖动滑块时每次重组都要求值，避免反复解析）。 */
    private val compileCache = object : LinkedHashMap<String, ViewFormula.Compiled>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ViewFormula.Compiled>?) = size > 256
    }

    private fun compiled(src: String?): ViewFormula.Compiled {
        if (src == null) return ViewFormula.compile(null)
        synchronized(compileCache) { compileCache[src] }?.let { return it }
        return ViewFormula.compile(src).also { c -> synchronized(compileCache) { compileCache[src] = c } }
    }

    fun text(value: Json.Value?, lookup: (String) -> Any): String = ViewFormula.show(resolve(value, lookup, ""))

    data class Series(val name: String?, val values: List<Double>)

    data class Chart(val kind: String, val categories: List<String>, val series: List<Series>, val unit: String?, val caption: String?) {
        /** 所有值的范围（含 0，便于柱状从基线起）。 */
        val range: Pair<Double, Double>
            get() {
                val all = series.flatMap { it.values }
                return minOf(0.0, all.minOrNull() ?: 0.0) to maxOf(0.0, all.maxOrNull() ?: 0.0)
            }
    }

    /** 图表绘制数据：公式求值后非有限的点取 0（校验时已保证初始输入下有限）。 */
    fun chart(node: Json.Value.Object, lookup: (String) -> Any): Chart? {
        val cats = (node.members["categories"] as? Json.Value.Array)?.items ?: return null
        val series = (node.members["series"] as? Json.Value.Array)?.items?.mapNotNull { s ->
            val so = s as? Json.Value.Object ?: return@mapNotNull null
            val values = (so.members["values"] as? Json.Value.Array)?.items?.map { v ->
                (resolve(v, lookup) as? Double)?.takeIf { it.isFinite() } ?: 0.0
            } ?: return@mapNotNull null
            Series(str(so, "name"), values)
        } ?: return null
        return Chart(
            kind = str(node, "kind") ?: "bar",
            categories = cats.map { ViewFormula.show(scalar(it)) },
            series = series,
            unit = str(node, "unit"),
            caption = str(node, "caption"),
        )
    }

    /** 进度百分比（0–100）。 */
    fun progressPercent(node: Json.Value.Object, lookup: (String) -> Any): Double {
        val p = if (node.members["percent"] != null) (resolve(node.members["percent"], lookup, 0.0) as? Double) ?: 0.0
        else {
            val max = (resolve(node.members["max"], lookup, 0.0) as? Double) ?: 0.0
            if (max > 0) ((resolve(node.members["value"], lookup, 0.0) as? Double) ?: 0.0) / max * 100 else 0.0
        }
        return p.coerceIn(0.0, 100.0)
    }

    /** 表格导出 CSV（RFC 4180：含逗号、引号、换行的单元格加引号，引号写双）。 */
    fun tableCsv(node: Json.Value.Object, lookup: (String) -> Any): String {
        val cols = (node.members["columns"] as? Json.Value.Array)?.items.orEmpty().mapNotNull { it as? Json.Value.Object }
        fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val lines = mutableListOf(cols.joinToString(",") { cell(str(it, "label") ?: "") })
        (node.members["rows"] as? Json.Value.Array)?.items.orEmpty().forEach { row ->
            val cells = (row as? Json.Value.Array)?.items.orEmpty()
            lines.add(cols.indices.joinToString(",") { i -> cell(cells.getOrNull(i)?.let { tableCell(it, lookup) } ?: "") })
        }
        return lines.joinToString("\r\n")
    }

    /** 表格单元格文字：公式求值，非字符串字面量按 JSON 文本显示（与 deep-view cellText 一致）。 */
    fun tableCell(cell: Json.Value, lookup: (String) -> Any): String =
        if (isFormula(cell)) ViewFormula.show(resolve(cell, lookup, "")) else cellText(cell)

    private fun cellText(v: Json.Value?): String = when (v) {
        is Json.Value.StringValue -> v.value
        null -> ""
        else -> Json.encode(v)
    }

    /** 降级摘要：标题加各组件一句（与网页端 viewSummary 同口径的简化版）。 */
    fun summary(root: Json.Value.Object): String {
        val lookup = scope(root)
        return walk(root).mapNotNull { n ->
            when (str(n, "type")) {
                "text", "callout" -> text(n.members["text"], lookup)
                "metric" -> "${str(n, "label")}：${text(n.members["value"], lookup)}"
                "html" -> str(n, "summary")
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.take(8).joinToString("\n")
    }

    /**
     * 滑块取值吸附到 min + k·step（k 不超过不越过 max 的最后一格），按步长与 min 的小数位去掉浮点噪声，
     * 结果一定在 [min, max]——服务端 validateInputs 会拒绝越界值。无步长时保留 3 位小数。
     */
    fun snap(v: Double, min: Double, max: Double, step: Double?): Double {
        if (step == null || step <= 0) return (Math.round(v * 1000) / 1000.0).coerceIn(min, max)
        val last = kotlin.math.floor((max - min) / step + 1e-9)
        val k = Math.round((v - min) / step).toDouble().coerceIn(0.0, last)
        val decimals = maxOf(
            java.math.BigDecimal(step.toString()).stripTrailingZeros().scale(),
            java.math.BigDecimal(min.toString()).stripTrailingZeros().scale(),
            0,
        )
        return java.math.BigDecimal(min + k * step).setScale(decimals, java.math.RoundingMode.HALF_UP).toDouble().coerceIn(min, max)
    }

    // ---- 作答 ----

    sealed interface Answer {
        object Submit : Answer
        data class Options(val selected: List<String>) : Answer
        data class Confirm(val confirmed: Boolean) : Answer
        data class Form(val fields: Map<String, String>) : Answer
        object FormCanceled : Answer
    }

    /**
     * view-reply 请求体：{sessionId, viewId, action, values}；values 带作答组件自己的值，视图有输入时带
     * inputs（全部输入的当前值，未动过的取初始值）。
     */
    fun reply(bp: Blueprint, action: String, answer: Answer, inputs: Map<String, Any>): String {
        val values = StringBuilder("{")
        val parts = mutableListOf<String>()
        val inputNodes = inputsOf(bp.root)
        if (inputNodes.isNotEmpty()) {
            parts.add("\"inputs\":{" + inputNodes.mapNotNull { n ->
                val id = str(n, "id") ?: return@mapNotNull null
                val v = inputs[id] ?: inputDefault(n) ?: return@mapNotNull null
                JsonString.of(id) + ":" + literal(v)
            }.joinToString(",") + "}")
        }
        when (answer) {
            is Answer.Options -> parts.add("\"selected\":[" + answer.selected.joinToString(",") { JsonString.of(it) } + "]")
            is Answer.Confirm -> parts.add("\"confirmed\":${answer.confirmed}")
            is Answer.Form -> parts.add("\"fields\":{" + answer.fields.entries.joinToString(",") { JsonString.of(it.key) + ":" + JsonString.of(it.value) } + "}")
            Answer.FormCanceled -> parts.add("\"canceled\":true")
            Answer.Submit -> {}
        }
        values.append(parts.joinToString(",")).append('}')
        return "{\"sessionId\":${JsonString.of(bp.sessionId)},\"viewId\":${JsonString.of(bp.id)},\"action\":${JsonString.of(action)},\"values\":$values}"
    }

    private fun literal(v: Any): String = when (v) {
        is Boolean -> v.toString()
        is Double -> if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Number -> literal(v.toDouble())
        else -> JsonString.of(v.toString())
    }

    // ---- 小工具 ----

    fun str(o: Json.Value.Object, key: String): String? = (o.members[key] as? Json.Value.StringValue)?.value

    fun finite(v: Json.Value?): Double? = (v as? Json.Value.NumberValue)?.raw?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** 标量字面量 → Kotlin 值（字符串、布尔、有限数字），其他为 null。 */
    fun scalar(v: Json.Value?): Any? = when (v) {
        is Json.Value.StringValue -> v.value
        is Json.Value.BoolValue -> v.value
        is Json.Value.NumberValue -> v.raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}
