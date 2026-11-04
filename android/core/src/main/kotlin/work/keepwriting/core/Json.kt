package work.keepwriting.core

/**
 * 极简严格 JSON 解析：只覆盖配置文件需要的值形态（对象、字符串、数字、布尔、null、
 * 数组），拒绝尾随内容与语法错误——与桌面端 JSON.parse 的接受集合对齐，使共享向量
 * 的「内容损坏」判定两端一致。不引第三方依赖（core 保持纯 JVM 无外部库）。
 */
object Json {
    /** 嵌套深度上限：远高于真实配置（深度 2），拦截病态深嵌套输入，避免递归
     *  StackOverflow 冒出读取方（桌面端 JSON.parse 对应输入抛 RangeError → invalid）。 */
    const val MAX_DEPTH = 512

    sealed interface Value {
        data class Object(val members: Map<String, Value>) : Value
        data class Array(val items: List<Value>) : Value
        data class StringValue(val value: String) : Value
        data class NumberValue(val raw: String) : Value
        data class BoolValue(val value: Boolean) : Value
        data object NullValue : Value
    }

    /** 解析完整 JSON 文档；任何语法错误、超深嵌套或尾随非空白内容返回 null。 */
    fun parse(text: String): Value? {
        val p = Parser(text)
        val value = p.parseValue(depth = 0) ?: return null
        p.skipWhitespace()
        return if (p.atEnd) value else null
    }

    private class Parser(private val text: String) {
        private var pos = 0

        val atEnd: Boolean get() = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun parseValue(depth: Int): Value? {
            if (depth > MAX_DEPTH) return null
            skipWhitespace()
            if (atEnd) return null
            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()?.let { Value.StringValue(it) }
                't' -> expect("true")?.let { Value.BoolValue(true) }
                'f' -> expect("false")?.let { Value.BoolValue(false) }
                'n' -> expect("null")?.let { Value.NullValue }
                else -> if (c == '-' || c.isDigit()) parseNumber() else null
            }
        }

        private fun expect(word: String): Boolean? {
            if (text.startsWith(word, pos)) {
                pos += word.length
                return true
            }
            return null
        }

        private fun parseObject(depth: Int): Value.Object? {
            pos++ // '{'
            val members = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (!atEnd && text[pos] == '}') {
                pos++
                return Value.Object(members)
            }
            while (true) {
                skipWhitespace()
                if (atEnd || text[pos] != '"') return null
                val key = parseString() ?: return null
                skipWhitespace()
                if (atEnd || text[pos] != ':') return null
                pos++
                val value = parseValue(depth + 1) ?: return null
                members[key] = value
                skipWhitespace()
                if (atEnd) return null
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return Value.Object(members)
                    }
                    else -> return null
                }
            }
        }

        private fun parseArray(depth: Int): Value.Array? {
            pos++ // '['
            val items = mutableListOf<Value>()
            skipWhitespace()
            if (!atEnd && text[pos] == ']') {
                pos++
                return Value.Array(items)
            }
            while (true) {
                val value = parseValue(depth + 1) ?: return null
                items.add(value)
                skipWhitespace()
                if (atEnd) return null
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return Value.Array(items)
                    }
                    else -> return null
                }
            }
        }

        private fun parseString(): String? {
            pos++ // '"'
            val out = StringBuilder()
            while (!atEnd) {
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return out.toString()
                    }
                    '\\' -> {
                        pos++
                        if (atEnd) return null
                        when (val esc = text[pos]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) return null
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: return null
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> return null
                        }
                        pos++
                    }
                    else -> {
                        if (c.code < 0x20) return null
                        out.append(c)
                        pos++
                    }
                }
            }
            return null
        }

        private fun parseNumber(): Value.NumberValue? {
            // 与 JSON 规范（及 JSON.parse）对齐：-? (0 | [1-9][0-9]*) (\.[0-9]+)?
            // ([eE][+-]?[0-9]+)? —— 每一段都必须至少消费一位数字，禁止前导零。
            val start = pos
            if (!atEnd && text[pos] == '-') pos++
            val intStart = pos
            when {
                !atEnd && text[pos] == '0' -> {
                    pos++
                    // 前导零非法：0 后不得再跟数字（JSON.parse 同样拒绝 01）。
                    if (!atEnd && text[pos].isDigit()) return null
                }
                !atEnd && text[pos] in '1'..'9' -> while (!atEnd && text[pos].isDigit()) pos++
                else -> return null
            }
            if (pos == intStart) return null
            if (!atEnd && text[pos] == '.') {
                pos++
                val fracStart = pos
                while (!atEnd && text[pos].isDigit()) pos++
                if (pos == fracStart) return null
            }
            if (!atEnd && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd && (text[pos] == '+' || text[pos] == '-')) pos++
                val expStart = pos
                while (!atEnd && text[pos].isDigit()) pos++
                if (pos == expStart) return null
            }
            return Value.NumberValue(text.substring(start, pos))
        }
    }
}
