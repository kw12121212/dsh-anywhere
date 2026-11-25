package work.keepwriting.core.spike

import work.keepwriting.core.Json

/**
 * 关于 DS Bot 与诊断探针组（dsbot-system-about）：只读登记 update-check（fresh:false，不触发新的注册表检查）、
 * repo-stars、diagnostics 与 diagnostics-log 的应答形态——只记键集、枚举值、计数与长度，不打印诊断条目与日志文本。
 * 绝不调用 update-install、dsh-update-install（会在电脑上升级用户服务）与 diagnostics-clear（会清空真实日志）。
 */
internal class ProbesSystem(
    private val post: (endpoint: String, payload: String) -> SpikeHttp.Response,
) {
    fun run() {
        updateCheck()
        stars()
        diagnostics()
        log()
    }

    private fun call(label: String, endpoint: String, payload: String): Json.Value? {
        val res = try {
            post(endpoint, payload)
        } catch (e: Exception) {
            fail(label, "请求异常 ${e.javaClass.simpleName}")
            return null
        }
        return parseOk(label, res)
    }

    private fun Json.Value.Object.s(k: String) = when (val v = members[k]) {
        is Json.Value.StringValue -> v.value
        is Json.Value.BoolValue -> v.value.toString()
        is Json.Value.NumberValue -> "<num>"
        Json.Value.NullValue, null -> "null"
        else -> "<${v.javaClass.simpleName}>"
    }

    private fun updateCheck() {
        val v = call("sys-update-check", "update-check", "{\"fresh\":false}") as? Json.Value.Object ?: return
        val dsh = v.members["dsh"] as? Json.Value.Object
        pass(
            "sys-update-check",
            "键=${v.members.keys.sorted()} current=${v.s("current")} latest=${v.s("latest")} source=${v.s("source")} " +
                "enabled=${v.s("enabled")} phase=${v.s("phase")} available=${v.s("available")} checkedAt=${v.s("checkedAt")} " +
                "repoUrl=${v.s("repoUrl")} notesUrl=${v.s("notesUrl")}; " +
                "dsh键=${dsh?.members?.keys?.sorted()} dsh.current=${dsh?.s("current")} dsh.verified=${dsh?.s("verified")} " +
                "dsh.kind=${dsh?.s("kind")} dsh.blockedBy=${dsh?.s("blockedBy")} dsh.phase=${dsh?.s("phase")} dsh.available=${dsh?.s("available")}",
        )
    }

    private fun stars() {
        val v = call("sys-repo-stars", "repo-stars", "{}") ?: return
        val shape = when (v) {
            is Json.Value.NumberValue -> "整数（${if (v.raw.toLongOrNull() != null) "可解析" else "不可解析"}）"
            Json.Value.NullValue -> "null"
            else -> v.javaClass.simpleName
        }
        pass("sys-repo-stars", "value 形态=$shape")
    }

    private fun diagnostics() {
        val v = call("sys-diagnostics", "diagnostics", "{\"client\":{\"surface\":\"android\",\"language\":\"zh\"}}") as? Json.Value.Object ?: return
        val facts = v.members["facts"] as? Json.Value.Object
        val entries = (v.members["entries"] as? Json.Value.Array)?.items?.mapNotNull { it as? Json.Value.Object }.orEmpty()
        val times = entries.mapNotNull { (it.members["time"] as? Json.Value.NumberValue)?.raw?.toLongOrNull() }
        val newestFirst = times.zipWithNext().all { (a, b) -> a >= b }
        pass(
            "sys-diagnostics",
            "键=${v.members.keys.sorted()} facts键=${facts?.members?.keys?.sorted()} total=${(v.members["total"] as? Json.Value.NumberValue)?.raw} " +
                "entries数=${entries.size} 条目键集=${entries.map { it.members.keys.sorted().joinToString(",") }.toSet()} " +
                "level分布=${entries.groupingBy { it.s("level") }.eachCount()} source分布=${entries.groupingBy { it.s("source") }.eachCount()} " +
                "新到旧=$newestFirst markdown=${if (v.members["markdown"] is Json.Value.StringValue) "字符串" else "缺失"}",
        )
    }

    private fun log() {
        val v = call("sys-diagnostics-log", "diagnostics-log", "{}") ?: return
        val text = (v as? Json.Value.StringValue)?.value
        if (text == null) {
            fail("sys-diagnostics-log", "value 不是字符串：${v.javaClass.simpleName}")
            return
        }
        pass("sys-diagnostics-log", "纯文本 长度=${text.length} 行数=${if (text.isEmpty()) 0 else text.lines().size}")
    }
}
