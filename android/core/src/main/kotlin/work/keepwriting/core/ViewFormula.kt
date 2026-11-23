package work.keepwriting.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 视图卡片的公式（dsbot-rich-render）：移植 deep-view 0.1.2 core/formula.js 的小表达式语言——
 * `{ "=": "price * qty" }`，读名字、调用固定函数，无属性访问、赋值与循环。值为 Double / String / Boolean，
 * 与 JS 语义一致（数字均为双精度，+ 遇字符串拼接，== 为严格相等）。
 */
object ViewFormula {
    class FormulaError(message: String) : Exception(message)

    sealed interface Node {
        data class Value(val value: Any) : Node
        data class Name(val name: String) : Node
        data class Unary(val op: String, val value: Node) : Node
        data class Binary(val op: String, val left: Node, val right: Node) : Node
        data class If(val test: Node, val yes: Node, val no: Node) : Node
        data class Call(val name: String, val args: List<Node>) : Node
    }

    data class Compiled(val tree: Node?, val names: List<String>, val error: String?)

    private class Fn(val min: Int, val max: Int, val run: (List<Any>) -> Any)

    private val FUNCTIONS: Map<String, Fn> = mapOf(
        "min" to Fn(1, Int.MAX_VALUE) { a -> a.map(::num).min() },
        "max" to Fn(1, Int.MAX_VALUE) { a -> a.map(::num).max() },
        "abs" to Fn(1, 1) { a -> kotlin.math.abs(num(a[0])) },
        "floor" to Fn(1, 1) { a -> kotlin.math.floor(num(a[0])) },
        "ceil" to Fn(1, 1) { a -> kotlin.math.ceil(num(a[0])) },
        "sqrt" to Fn(1, 1) { a -> kotlin.math.sqrt(num(a[0])) },
        "pow" to Fn(2, 2) { a -> Math.pow(num(a[0]), num(a[1])) },
        "round" to Fn(1, 2) { a -> roundTo(num(a[0]), digitsOf(a.getOrElse(1) { 0.0 })) },
        "clamp" to Fn(3, 3) { a -> minOf(maxOf(num(a[0]), num(a[1])), num(a[2])) },
        "fixed" to Fn(1, 2) { a -> val d = digitsOf(a.getOrElse(1) { 0.0 }); toFixed(roundTo(num(a[0]), d), d) },
        "format" to Fn(1, 2) { a -> grouped(num(a[0]), digitsOf(a.getOrElse(1) { 0.0 })) },
        "percent" to Fn(1, 2) { a -> grouped(num(a[0]) * 100, digitsOf(a.getOrElse(1) { 0.0 })) + "%" },
    )
    private const val MAX_NESTING = 64
    private val OPERATORS = listOf("==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "%", "<", ">", "!", "?", ":", "(", ")", ",")
    private val BINARY = listOf(listOf("||"), listOf("&&"), listOf("==", "!="), listOf("<", "<=", ">", ">="), listOf("+", "-"), listOf("*", "/", "%"))
    private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val NUMBER = Regex("^(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*")

    fun isName(value: Any?): Boolean = value is String && IDENT.matches(value)

    fun num(value: Any?): Double = when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        // 与 JS Number(string) 一致的子集：十进制（含指数）与 0x 十六进制；"NaN"、"1f" 等 Kotlin 写法不认
        is String -> jsNumber(value.trim()) ?: throw FormulaError("${quote(value)} is not a number")
        else -> throw FormulaError("${quote(value)} is not a number")
    }

    private val JS_DECIMAL = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?$")
    private val JS_HEX = Regex("^0[xX][0-9a-fA-F]+$")

    private fun jsNumber(t: String): Double? = when {
        t.isEmpty() -> null
        JS_DECIMAL.matches(t) -> t.toDouble()
        JS_HEX.matches(t) -> t.substring(2).toBigInteger(16).toDouble()
        t == "Infinity" || t == "+Infinity" -> Double.POSITIVE_INFINITY
        t == "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> null
    }

    private fun quote(value: Any?): String = when (value) {
        is String -> "\"" + value.replace("\"", "\\\"") + "\""
        null -> "null"
        else -> show(value)
    }

    private fun digitsOf(digits: Any): Int {
        val v = num(digits)
        if (v != kotlin.math.floor(v) || v < 0 || v > 10) throw FormulaError("digits must be a whole number from 0 to 10")
        return v.toInt()
    }

    /** JS Math.round(value * scale) / scale（Math.round：.5 向正无穷）。 */
    private fun roundTo(value: Double, digits: Int): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return kotlin.math.floor(value * scale + 0.5) / scale
    }

    private fun toFixed(value: Double, digits: Int): String =
        if (!value.isFinite()) show(value) else BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

    private fun grouped(value: Double, digits: Int): String {
        if (!value.isFinite()) return show(value)
        val text = toFixed(kotlin.math.abs(roundTo(value, digits)), digits)
        val whole = text.substringBefore('.')
        val part = text.substringAfter('.', "")
        val sign = if (value < 0 && (whole + part).toBigDecimal().signum() != 0) "-" else ""
        return sign + whole.reversed().chunked(3).joinToString(",").reversed() + if (part.isNotEmpty()) ".$part" else ""
    }

    /** 值的文字形式：数字去掉浮点噪声（0.1 + 0.2 显示 0.3），整数不带小数点。 */
    fun show(value: Any?): String = when (value) {
        is Double -> when {
            value.isNaN() -> "NaN"
            value == Double.POSITIVE_INFINITY -> "Infinity"
            value == Double.NEGATIVE_INFINITY -> "-Infinity"
            else -> BigDecimal(value).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros().let {
                val abs = it.abs()
                when {
                    it.signum() == 0 -> "0"
                    // JS 对 ≥1e21 与 <1e-6 用指数形式（如 1e-7、1.5e+21）
                    abs >= BigDecimal("1e21") || abs < BigDecimal("1e-6") -> {
                        val exp = it.precision() - it.scale() - 1
                        val mantissa = it.movePointLeft(exp).stripTrailingZeros().toPlainString()
                        mantissa + "e" + (if (exp >= 0) "+" else "-") + kotlin.math.abs(exp)
                    }
                    else -> it.toPlainString()
                }
            }
        }
        is Number -> show(value.toDouble())
        null -> ""
        else -> value.toString()
    }

    private data class Token(val kind: String, val value: Any, val at: Int)

    private fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var at = 0
        while (at < source.length) {
            val c = source[at]
            if (c.isWhitespace()) { at++; continue }
            val rest = source.substring(at)
            NUMBER.find(rest)?.let { m ->
                tokens.add(Token("number", m.value.toDouble(), at)); at += m.value.length
                return@let
            } ?: NAME.find(rest)?.let { m ->
                tokens.add(Token("name", m.value, at)); at += m.value.length
            } ?: if (c == '"' || c == '\'') {
                var end = at + 1
                val text = StringBuilder()
                while (end < source.length && source[end] != c) {
                    if (source[end] == '\\' && end + 1 < source.length) { text.append(source[end + 1]); end += 2; continue }
                    text.append(source[end]); end++
                }
                if (end >= source.length) throw FormulaError("unclosed string at ${at + 1}")
                tokens.add(Token("string", text.toString(), at)); at = end + 1
            } else {
                val op = OPERATORS.firstOrNull { source.startsWith(it, at) } ?: throw FormulaError("unexpected \"$c\" at ${at + 1}")
                tokens.add(Token("op", op, at)); at += op.length
            }
        }
        return tokens
    }

    private fun parse(source: String): Node {
        val tokens = tokenize(source)
        var index = 0
        var depth = 0
        fun peek() = tokens.getOrNull(index)
        fun isOp(v: String) = peek()?.let { it.kind == "op" && it.value == v } == true
        fun expect(v: String) {
            if (!isOp(v)) throw FormulaError(peek()?.let { "expected \"$v\" at ${it.at + 1}" } ?: "expected \"$v\" at the end")
            index++
        }
        lateinit var ternary: () -> Node
        fun <T> nest(read: () -> T): T {
            if (++depth > MAX_NESTING) throw FormulaError("nested more than $MAX_NESTING levels")
            val node = read()
            depth--
            return node
        }
        lateinit var unary: () -> Node
        fun binary(level: Int): Node {
            if (level == BINARY.size) return unary()
            var left = binary(level + 1)
            while (peek()?.kind == "op" && peek()!!.value in BINARY[level]) {
                val op = peek()!!.value as String
                index++
                left = Node.Binary(op, left, binary(level + 1))
            }
            return left
        }
        fun primary(): Node {
            val t = peek() ?: throw FormulaError("the formula ends too early")
            index++
            if (t.kind == "number" || t.kind == "string") return Node.Value(t.value)
            if (t.kind == "op" && t.value == "(") {
                val inner = ternary()
                expect(")")
                return inner
            }
            if (t.kind != "name") throw FormulaError("unexpected \"${t.value}\" at ${t.at + 1}")
            val name = t.value as String
            if (!isOp("(")) return when (name) {
                "true" -> Node.Value(true)
                "false" -> Node.Value(false)
                else -> Node.Name(name)
            }
            val fn = FUNCTIONS[name] ?: throw FormulaError("unknown function \"$name\"; allowed: ${FUNCTIONS.keys.joinToString(", ")}")
            index++
            val args = mutableListOf<Node>()
            if (!isOp(")")) {
                args.add(ternary())
                while (isOp(",")) { index++; args.add(ternary()) }
            }
            expect(")")
            if (args.size < fn.min || args.size > fn.max) throw FormulaError("$name() takes a different number of arguments; got ${args.size}")
            return Node.Call(name, args)
        }
        unary = {
            if (isOp("-") || isOp("!") || isOp("+")) {
                val op = peek()!!.value as String
                index++
                nest { Node.Unary(op, unary()) }
            } else primary()
        }
        ternary = {
            nest {
                val test = binary(0)
                if (!isOp("?")) test
                else {
                    index++
                    val yes = ternary()
                    expect(":")
                    Node.If(test, yes, ternary())
                }
            }
        }
        if (tokens.isEmpty()) throw FormulaError("the formula is empty")
        val tree = ternary()
        if (index < tokens.size) throw FormulaError("unexpected \"${peek()!!.value}\" at ${peek()!!.at + 1}")
        return tree
    }

    private fun namesIn(node: Node, found: MutableSet<String> = linkedSetOf()): Set<String> {
        when (node) {
            is Node.Name -> found.add(node.name)
            is Node.Unary -> namesIn(node.value, found)
            is Node.Binary -> { namesIn(node.left, found); namesIn(node.right, found) }
            is Node.If -> { namesIn(node.test, found); namesIn(node.yes, found); namesIn(node.no, found) }
            is Node.Call -> node.args.forEach { namesIn(it, found) }
            is Node.Value -> {}
        }
        return found
    }

    fun compile(source: Any?): Compiled {
        if (source !is String) return Compiled(null, emptyList(), "expected a formula string")
        return try {
            val tree = parse(source)
            Compiled(tree, namesIn(tree).toList(), null)
        } catch (e: FormulaError) {
            Compiled(null, emptyList(), e.message)
        } catch (_: StackOverflowError) {
            Compiled(null, emptyList(), "the formula is too deep to work out")
        }
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        is String -> v.isNotEmpty()
        is Boolean -> v
        is Double -> v != 0.0 && !v.isNaN()
        null -> false
        else -> true
    }

    private fun strictEquals(a: Any?, b: Any?): Boolean = when {
        a is Double && b is Double -> a == b && !a.isNaN()
        else -> a == b
    }

    private fun run(node: Node, lookup: (String) -> Any): Any = when (node) {
        is Node.Value -> node.value
        is Node.Name -> lookup(node.name)
        is Node.Unary -> {
            val v = run(node.value, lookup)
            when (node.op) {
                "!" -> !truthy(v)
                "-" -> -num(v)
                else -> num(v)
            }
        }
        is Node.If -> if (truthy(run(node.test, lookup))) run(node.yes, lookup) else run(node.no, lookup)
        is Node.Call -> FUNCTIONS.getValue(node.name).run(node.args.map { run(it, lookup) })
        is Node.Binary -> when (node.op) {
            "&&" -> if (truthy(run(node.left, lookup))) run(node.right, lookup) else false
            "||" -> run(node.left, lookup).let { if (truthy(it)) it else run(node.right, lookup) }
            else -> {
                val l = run(node.left, lookup)
                val r = run(node.right, lookup)
                when (node.op) {
                    "+" -> if (l is String || r is String) show(l) + show(r) else num(l) + num(r)
                    "-" -> num(l) - num(r)
                    "*" -> num(l) * num(r)
                    "/" -> num(l) / num(r)
                    "%" -> num(l) % num(r)
                    "==" -> strictEquals(l, r)
                    "!=" -> !strictEquals(l, r)
                    "<" -> num(l) < num(r)
                    "<=" -> num(l) <= num(r)
                    ">" -> num(l) > num(r)
                    else -> num(l) >= num(r)
                }
            }
        }
    }

    /** 一个视图的作用域：输入值，加上按需求值的 let 名字；循环引用或未知名字让读它的公式失败。 */
    fun scope(inputs: Map<String, Any>, lets: Map<String, Node>): (String) -> Any {
        val done = HashMap<String, Any>()
        val busy = HashSet<String>()
        lateinit var lookup: (String) -> Any
        lookup = { name ->
            inputs[name] ?: done[name] ?: run {
                val tree = lets[name] ?: throw FormulaError("unknown name \"$name\"")
                if (!busy.add(name)) throw FormulaError("\"$name\" refers to itself")
                try {
                    run(tree, lookup).also { done[name] = it }
                } finally {
                    busy.remove(name)
                }
            }
        }
        return lookup
    }

    /** 求值：成功返回值，失败返回 null 与原因。 */
    fun evaluate(tree: Node, lookup: (String) -> Any): Pair<Any?, String?> = try {
        run(tree, lookup) to null
    } catch (e: FormulaError) {
        null to e.message
    } catch (_: StackOverflowError) {
        null to "the formula is too deep to work out"
    }
}
