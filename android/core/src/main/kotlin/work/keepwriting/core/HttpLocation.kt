package work.keepwriting.core

/**
 * HTTP Location 解析（纯字符串逻辑）：把重定向的 Location 头按请求 URL 解析成绝对地址。
 * 真实 dsh 的 /api/file 版本重定向只给查询串（"?path=…&v=…"，见 dsh-api-session-controller
 * 的 serveFile），相对路径与协议相对形态按 RFC 3986 归并；解析不了（空/基准无 scheme）返回 null。
 */
object HttpLocation {
    /** [location] 为空或 [base] 无 scheme 时返回 null；其余按相对引用规则归并。 */
    fun resolve(base: String, location: String): String? {
        val loc = location.trim()
        if (loc.isEmpty()) return null
        val lower = loc.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return loc
        val scheme = schemeOf(base) ?: return null
        if (loc.startsWith("//")) return "$scheme:$loc"
        val origin = originOf(base) ?: return null
        if (loc.startsWith("/")) return origin + loc
        if (loc.startsWith("?") || loc.startsWith("#")) {
            return base.substringBefore('?').substringBefore('#') + loc
        }
        // 普通相对路径（含 ./ ../）：基于基准路径的目录做点段归并
        val path = base.substring(origin.length).substringBefore('?').substringBefore('#')
        val dir = if (path.contains('/')) path.substringBeforeLast('/') + "/" else "/"
        return origin + mergePath(dir, loc)
    }

    private fun schemeOf(url: String): String? {
        val i = url.indexOf("://")
        if (i <= 0) return null
        return url.substring(0, i)
    }

    private fun originOf(url: String): String? {
        val scheme = schemeOf(url) ?: return null
        val rest = url.substring(scheme.length + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (end < 0) url else url.substring(0, scheme.length + 3 + end)
    }

    /** 点段归并：dir 以 "/" 结尾，rel 不带查询时逐段拼接（"." 留守、".." 退一层）。 */
    private fun mergePath(dir: String, rel: String): String {
        val segs = dir.trimEnd('/').split('/').toMutableList() // 首元素空串保留根斜杠
        for (s in rel.split('/')) when (s) {
            ".", "" -> {}
            ".." -> if (segs.size > 1) segs.removeAt(segs.size - 1) // 根目录的 ".." 停在根
            else -> segs.add(s)
        }
        return segs.joinToString("/")
    }
}
