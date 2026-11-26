package work.keepwriting.core

/**
 * 电脑操控设置（dsbot-computer-settings）：
 * 解码 state.computer（与 computer-set-enabled / computer-provider-retry 返回的同形视图）、
 * Bot 操控级别与应用授权、操控组件（provider）状态与宿主支持判定、computer-system-status 权限，
 * 并构造 computer-* 请求体。
 * 复刻 DS-Bot 0.3.10 src/host/computer/index.js（computerView/computerEndpoint）、computer/catalog.js（LEVELS）
 * 与 client.js src/client/computer.js（levelId/PROVIDER_TEXT/providerWorks/ComputerPage/BotComputerCard）
 * （tech/dsbot-api 的 computer-settings-endpoints-0310）。所有解码对缺失与类型不符容错，不抛异常。
 */
object ComputerSettings {
    /** 操控级别（catalog.js LEVELS 顺序）；缺省或未知值按 [BACKGROUND]（index.js levelOf / client.js levelId）。 */
    enum class Level(val wire: String) {
        OFF("off"),
        VIEW("view"),
        BACKGROUND("background"),
        FOREGROUND("foreground"),
        ;

        companion object {
            fun of(raw: String?): Level = entries.firstOrNull { it.wire == raw } ?: BACKGROUND
        }
    }

    /** 操控组件状态（index.js setProviderStatus 的 state）；未知值按 [ERROR]（client.js PROVIDER_TEXT 回落 error）。 */
    enum class ProviderState(val wire: String) {
        OFF("off"),
        LOADING("loading"),
        READY("ready"),
        ERROR("error"),
        MISSING("missing"),
        OCCUPIED("occupied"),
        UNSUPPORTED("unsupported"),
        ;

        companion object {
            fun of(raw: String?): ProviderState = entries.firstOrNull { it.wire == raw } ?: ERROR
        }
    }

    data class Provider(
        val state: ProviderState,
        val name: String?,
        val message: String?,
        /** missing 时服务端给出的安装命令文本（只显示，不执行）。 */
        val installCommand: String?,
    ) {
        /** 组件可用或加载中（client.js providerWorks）。 */
        val works: Boolean get() = state == ProviderState.READY || state == ProviderState.LOADING

        /** 可重试（client.js：error/missing/occupied 显示 Retry）。 */
        val retryable: Boolean get() = state == ProviderState.ERROR || state == ProviderState.MISSING || state == ProviderState.OCCUPIED

        /** 已就绪（Bot 获得电脑工具）；未就绪时（含加载中）级别区提示 Bot 暂无电脑工具。 */
        val ready: Boolean get() = state == ProviderState.READY

        /**
         * 组件无法装入：未安装（missing），或宿主没有包加载器——上游 importPackage 抛 bot/no-loader，
         * provider 落为 error 且 message 恰为 [NO_LOADER_MESSAGE]（index.js 139 行常量，整串相等比较，不做子串匹配）。
         */
        val cannotLoad: Boolean get() = state == ProviderState.MISSING || (state == ProviderState.ERROR && message == NO_LOADER_MESSAGE)
    }

    /** 上游 computer/index.js importPackage 在宿主无包加载器时的固定错误文案。 */
    const val NO_LOADER_MESSAGE = "This DSH cannot load packages."


    data class AppGrant(val key: String, val name: String)

    data class BotComputer(val level: Level, val apps: List<AppGrant>)

    data class View(
        val enabled: Boolean,
        val provider: Provider,
        val macos: Boolean,
        /** 只有 {enabled:false}、无 provider 与 bots 的最小视图（channel.js：computer 模块缺失时的回落）。 */
        val minimal: Boolean,
        val bots: Map<String, BotComputer>,
    ) {
        /**
         * 宿主不支持电脑操控（设计决策 host-support-by-provider-state）：最小视图或组件为平台不支持；
         * 开启后组件无法装入（未安装或宿主无包加载器，见 [Provider.cannotLoad]）同样视为此宿主不支持（仍显示原因并保留关闭开关）；
         * 其他 error 与 occupied 属可重试的组件错误，不判为不支持。
         */
        val hostUnsupported: Boolean
            get() = minimal || provider.state == ProviderState.UNSUPPORTED || (enabled && provider.cannotLoad)

        /** macOS 权限区显隐（client.js：macos 且组件 ready）。 */
        val showPermissions: Boolean get() = enabled && macos && provider.state == ProviderState.READY
    }

    data class BotRow(val id: String, val name: String, val level: Level, val apps: List<AppGrant>)

    /** 系统权限键（computer-permission 只接受这两个）。 */
    const val PERM_ACCESSIBILITY = "accessibility"
    const val PERM_SCREEN_RECORDING = "screenRecording"
    val PERMISSIONS = listOf(PERM_ACCESSIBILITY, PERM_SCREEN_RECORDING)

    data class Permission(val key: String, val granted: Boolean)

    private fun Json.Value.Object.str(k: String) = (members[k] as? Json.Value.StringValue)?.value
    private fun Json.Value.Object.obj(k: String) = members[k] as? Json.Value.Object

    /** 远端 state 是否含电脑操控视图（团队页入口门控，设计决策 data-presence-gating）。 */
    fun has(state: Json.Value.Object?): Boolean = state?.members?.get("computer") is Json.Value.Object

    /** 只读宿主原因（state.readOnly 非空时返回）。 */
    fun readOnlyReason(state: Json.Value.Object?): String? = state?.str("readOnly")?.takeIf { it.isNotBlank() }

    /** 从 state 解码 computer；不含该字段返回 null。 */
    fun view(state: Json.Value.Object?): View? = state?.obj("computer")?.let(::decode)

    /** 解码 computer 视图（state.computer 或写端点返回值）。 */
    fun decode(raw: Json.Value.Object): View {
        val enabled = (raw.members["enabled"] as? Json.Value.BoolValue)?.value == true
        val providerRaw = raw.obj("provider")
        val botsRaw = raw.obj("bots")
        val minimal = providerRaw == null && botsRaw == null
        // client.js：computer.provider ?? { state: enabled ? "loading" : "off" }
        val provider = if (providerRaw == null) {
            Provider(if (enabled) ProviderState.LOADING else ProviderState.OFF, null, null, null)
        } else {
            Provider(
                state = ProviderState.of(providerRaw.str("state")),
                name = providerRaw.str("name")?.takeIf { it.isNotBlank() },
                message = providerRaw.str("message")?.takeIf { it.isNotBlank() },
                installCommand = providerRaw.obj("install")?.str("command")?.takeIf { it.isNotBlank() },
            )
        }
        val bots = botsRaw?.members.orEmpty().mapNotNull { (id, v) ->
            val o = v as? Json.Value.Object ?: return@mapNotNull null
            id to BotComputer(Level.of(o.str("level")), grants(o.obj("apps")))
        }.toMap()
        return View(
            enabled = enabled,
            provider = provider,
            macos = (raw.members["macos"] as? Json.Value.BoolValue)?.value == true,
            minimal = minimal,
            bots = bots,
        )
    }

    /** 写端点返回值若为 computer 视图则解码，否则 null（set-level/revoke-app 返回 true）。 */
    fun decodeValue(value: Json.Value?): View? = (value as? Json.Value.Object)?.takeIf { it.members.containsKey("enabled") }?.let(::decode)

    /** apps {appKey → {name, id?, grantedAt}} → 授权列表（保持服务端顺序；无名称时显示 appKey）。 */
    private fun grants(apps: Json.Value.Object?): List<AppGrant> =
        apps?.members.orEmpty().mapNotNull { (key, v) ->
            if (key.isBlank()) return@mapNotNull null
            val name = (v as? Json.Value.Object)?.str("name")?.takeIf { it.isNotBlank() } ?: key
            AppGrant(key, name)
        }

    /**
     * 按 state.bots 顺序列出 Bot 的级别与授权：优先取 computer.bots[id]，缺失时回落 Bot 自身的
     * computer.level / computer.apps（client.js 读 roster 的 bot.computer），都缺按后台、无授权。
     */
    fun botRows(state: Json.Value.Object?, view: View?): List<BotRow> =
        ((state?.members?.get("bots") as? Json.Value.Array)?.items ?: emptyList())
            .mapNotNull { it as? Json.Value.Object }
            .mapNotNull { o ->
                val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val fromView = view?.bots?.get(id)
                val own = o.obj("computer")
                BotRow(
                    id = id,
                    name = o.str("name")?.takeIf { it.isNotBlank() } ?: id,
                    level = fromView?.level ?: Level.of(own?.str("level")),
                    apps = fromView?.apps ?: grants(own?.obj("apps")),
                )
            }

    /** 单个 Bot 的行（Bot 详情页电脑操控分节）。 */
    fun botRow(state: Json.Value.Object?, botId: String): BotRow? = botRows(state, view(state)).firstOrNull { it.id == botId }

    /**
     * 解码 computer-system-status 应答：null 或非对象表示宿主没有系统权限服务（返回 null）；
     * 每项 perms[key].state == "granted" 或 perms[key] == true 视为已允许（client.js permRow）。
     */
    fun permissions(value: Json.Value?): List<Permission>? {
        val o = value as? Json.Value.Object ?: return null
        return PERMISSIONS.map { key ->
            val granted = when (val v = o.members[key]) {
                is Json.Value.BoolValue -> v.value
                is Json.Value.StringValue -> v.value == "granted"
                is Json.Value.Object -> when (val s = v.members["state"]) {
                    is Json.Value.StringValue -> s.value == "granted"
                    is Json.Value.BoolValue -> s.value
                    else -> false
                }
                else -> false
            }
            Permission(key, granted)
        }
    }

    /** 请求体（DS-Bot 0.3.10 computer/index.js computerEndpoint 契约）。 */
    object Requests {
        private fun q(s: String) = JsonString.of(s)

        fun setEnabled(enabled: Boolean): String = "{\"enabled\":$enabled}"

        fun setLevel(botId: String, level: Level): String = "{\"botId\":${q(botId)},\"level\":${q(level.wire)}}"

        fun revokeApp(botId: String, appKey: String): String = "{\"botId\":${q(botId)},\"appKey\":${q(appKey)}}"

        fun providerRetry(): String = "{}"

        fun systemStatus(): String = "{}"

        /** 只发送上游接受的两个权限键，其他值回落 accessibility（与上游一致）。 */
        fun permission(key: String): String = "{\"permission\":${q(if (key in PERMISSIONS) key else PERM_ACCESSIBILITY)}}"
    }

    const val SET_ENABLED = "computer-set-enabled"
    const val SET_LEVEL = "computer-set-level"
    const val REVOKE_APP = "computer-revoke-app"
    const val PROVIDER_RETRY = "computer-provider-retry"
    const val SYSTEM_STATUS = "computer-system-status"
    const val PERMISSION = "computer-permission"
}
