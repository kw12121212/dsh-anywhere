package work.keepwriting.core

/**
 * 远端 DS-Bot/dsh 版本读取与基准比对（dsbot-api-client，来源字段是 spike 实测的
 * update-check value.current 与 value.dsh.current）：连接时读取远端版本，与已测
 * 基准（DS-Bot 0.3.6、dsh 0.2.2-rc.1）不一致时提示但尽力可用，读不到按未知降级。
 * 语义化比较支持预发布段（0.2.2-rc.1 < 0.2.2），规则与 ds-bot update.js 的
 * newer() 对齐。
 */
object DsbotVersions {
    /** 已测基准（tech/dsbot-api 的 version-baseline-and-degrade 登记；0.3.6 复验见
     *  android/core/src/spike/FINDINGS.md）。 */
    val BASELINE_DS_BOT = "0.3.6"
    val BASELINE_DSH = "0.2.2-rc.1"

    data class Remote(val dsBot: String?, val dsh: String?)

    enum class Verdict { MATCHED, MISMATCHED, UNKNOWN }

    /** 从 update-check 的 value 解码两个版本字段（缺字段即 null → 未知）。 */
    fun fromUpdateCheck(value: Json.Value): Remote {
        val obj = value as? Json.Value.Object
        val dsh = (obj?.members?.get("dsh") as? Json.Value.Object)?.let {
            (it.members["current"] as? Json.Value.StringValue)?.value
        }
        return Remote(
            dsBot = (obj?.members?.get("current") as? Json.Value.StringValue)?.value,
            dsh = dsh,
        )
    }

    /** 与基准比对：任一为 null → UNKNOWN；全部相等 → MATCHED；否则 MISMATCHED。 */
    fun againstBaseline(remote: Remote, dsBotBaseline: String = BASELINE_DS_BOT, dshBaseline: String = BASELINE_DSH): Verdict {
        if (remote.dsBot == null || remote.dsh == null) return Verdict.UNKNOWN
        return if (remote.dsBot == dsBotBaseline && remote.dsh == dshBaseline) Verdict.MATCHED else Verdict.MISMATCHED
    }

    /** 语义化比较：a > b 返回正数、相等 0、小于负数；解析失败按字符串序（稳定不抛错）。 */
    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: return a.compareTo(b)
        val pb = parse(b) ?: return a.compareTo(b)
        for (i in 0..2) if (pa.nums[i] != pb.nums[i]) return pa.nums[i].compareTo(pb.nums[i])
        // 预发布段：无 > 有（1.0.0 > 1.0.0-rc.1）；都有按点分段数值+字典序
        val x = pa.pre; val y = pb.pre
        if (x == null && y == null) return 0
        if (x == null) return 1
        if (y == null) return -1
        val xs = x.split('.'); val ys = y.split('.')
        for (i in 0 until maxOf(xs.size, ys.size)) {
            val xa = xs.getOrNull(i); val ya = ys.getOrNull(i)
            if (xa == null) return -1
            if (ya == null) return 1
            val xn = xa.toIntOrNull(); val yn = ya.toIntOrNull()
            if (xn != null && yn != null) {
                if (xn != yn) return xn.compareTo(yn)
            } else {
                val c = xa.compareTo(ya)
                if (c != 0) return c
            }
        }
        return 0
    }

    private data class Parsed(val nums: IntArray, val pre: String?)

    private fun parse(v: String): Parsed? {
        val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([\w.]+))?(?:\+[\w.]+)?$""").find(v.trim()) ?: return null
        return Parsed(intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()),
            m.groupValues[4].ifEmpty { null })
    }
}
