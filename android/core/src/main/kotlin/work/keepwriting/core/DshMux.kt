package work.keepwriting.core

/**
 * dsh /api/remote.mux 流多路复用承载的 RPC 封装（dsbot-attachments）：一次
 * [WsClient.Session] 上调用一个 endpoint（unary 请求-应答）。wire 形态
 * （0.3.5 dsh-api-remotes 目录 + 真实服务器实测）：
 *  上行 {type:"open",streamId,endpoint:"ns/method",payload:{args:{request:…}}}；
 *  下行 {type:"item",streamId,value}* → {type:"end"} 或 {type:"error",error:{code,message,details}}；
 *  客户端中途放弃发 {type:"cancel",streamId}。
 */
class DshMux(private val session: WsClient.Session) : AutoCloseable by session {

    sealed interface Result {
        /** 正常 end：unary 应答取最后一个 item 的 value（JSON 文本，无 item 为 null）。 */
        data class Ok(val value: String?) : Result
        data class Err(val code: String, val message: String) : Result
    }

    private var nextStreamId = 1

    /** 调用一个 endpoint；argsJson 为 request 对象的 JSON 文本。 */
    fun call(endpoint: String, requestJson: String): Result {
        val streamId = "a${nextStreamId++}"
        session.sendText(
            "{\"type\":\"open\",\"streamId\":${JsonString.of(streamId)}," +
                "\"endpoint\":${JsonString.of(endpoint)},\"payload\":{\"args\":{\"request\":$requestJson}}}",
        )
        var lastValue: String? = null
        while (true) {
            val text = session.receiveText() ?: return Result.Err("gateway/closed", "websocket closed before reply")
            val obj = Json.parse(text) as? Json.Value.Object
                ?: return Result.Err("gateway/protocol", "non-object frame on mux")
            val type = (obj.members["type"] as? Json.Value.StringValue)?.value ?: continue
            val frameStream = (obj.members["streamId"] as? Json.Value.StringValue)?.value
            if (frameStream != streamId) continue // 其他流（并发场景当前不产生）
            when (type) {
                "item" -> lastValue = obj.members["value"]?.let { Json.encode(it) }
                "end" -> return Result.Ok(lastValue)
                "error" -> {
                    val err = obj.members["error"] as? Json.Value.Object
                    val code = (err?.members?.get("code") as? Json.Value.StringValue)?.value ?: "gateway/internal"
                    val message = (err?.members?.get("message") as? Json.Value.StringValue)?.value ?: ""
                    return Result.Err(code, message)
                }
            }
        }
    }

    /** 未终态放弃当前流（服务端允许静默丢弃）。 */
    fun cancelAll() {
        // 当前实现一次会话一个流，且 call 返回即终态；保留入口给后续并发扩展
    }
}

/** 附件链路的纯构造/解码（dsbot-attachments）：上传应答与 prompt content blocks。 */
object AttachmentMessages {

    /** HTTP unary RPC 信封（0.3.5 实测：POST /api/<channel>/<method>，client-request 形态）。 */
    fun clientRequest(rpcId: String, method: String, requestJson: String): String =
        "{\"type\":\"client-request\",\"rpcId\":${JsonString.of(rpcId)},\"method\":${JsonString.of(method)}," +
            "\"payload\":{\"args\":{\"request\":$requestJson}}}"

    /** server-response 解析：rpcId 回传 + result（Ok 带 value JSON 文本 / Err 带 code+message）。 */
    fun parseServerResponse(bodyText: String): DshMux.Result? {
        val root = Json.parse(bodyText) as? Json.Value.Object ?: return null
        if ((root.members["type"] as? Json.Value.StringValue)?.value != "server-response") return null
        val result = root.members["result"] as? Json.Value.Object ?: return null
        return when ((result.members["ok"] as? Json.Value.BoolValue)?.value) {
            true -> DshMux.Result.Ok(result.members["value"]?.let { Json.encode(it) })
            else -> {
                val err = result.members["error"] as? Json.Value.Object
                DshMux.Result.Err(
                    code = (err?.members?.get("code") as? Json.Value.StringValue)?.value ?: "gateway/internal",
                    message = (err?.members?.get("message") as? Json.Value.StringValue)?.value ?: "",
                )
            }
        }
    }

    /** uploadFileBinary 应答 value：{receiptId, file:{attachmentId,name,bytes[,width,height,mediaType]}} */
    data class UploadResult(val receiptId: String, val attachmentId: String, val name: String?, val bytes: Long?)

    /** 解析 uploadFileBinary 应答（外层 {ok,value|error} 信封由 DsbotApi.parseResponse 处理后传
     *  value）。0.3.5 实测：图片上传 value.file 为 image 键（标准化图片引用，带 width/height/
     *  mediaType），普通文件为 file 键——两者都带 attachmentId/name/bytes，兼容取用。 */
    fun decodeUpload(value: Json.Value): UploadResult? {
        val obj = value as? Json.Value.Object ?: return null
        val receiptId = (obj.members["receiptId"] as? Json.Value.StringValue)?.value ?: return null
        val file = (obj.members["file"] ?: obj.members["image"]) as? Json.Value.Object ?: return null
        val attachmentId = (file.members["attachmentId"] as? Json.Value.StringValue)?.value ?: return null
        return UploadResult(
            receiptId = receiptId,
            attachmentId = attachmentId,
            name = (file.members["name"] as? Json.Value.StringValue)?.value,
            bytes = (file.members["bytes"] as? Json.Value.NumberValue)?.raw?.toLongOrNull(),
        )
    }

    /** prompt content blocks：上传回执的附件一律 {type:"file",receiptId}（0.3.5 网页端同款），文本非空时附加。 */
    fun promptContent(receiptIds: List<String>, text: String): String {
        if (receiptIds.isEmpty() && text.isEmpty()) return ""
        return buildString {
            receiptIds.forEach { append("{\"type\":\"file\",\"receiptId\":${JsonString.of(it)}},") }
            if (text.isNotEmpty()) append("{\"type\":\"text\",\"text\":${JsonString.of(text)}}")
            else deleteCharAt(length - 1) // 只有附件时去掉尾逗号
        }
    }

    /** session/prompt 的 request JSON。 */
    fun promptRequest(requestId: String, sessionId: String, receiptIds: List<String>, text: String): String =
        "{\"requestId\":${JsonString.of(requestId)},\"sessionId\":${JsonString.of(sessionId)}," +
            "\"mode\":\"queue\",\"content\":[${promptContent(receiptIds, text)}]}"

    /** session/attachment 的 request JSON（图片读取：引用对象按条目内原样透传）。 */
    fun attachmentRequest(sessionId: String, attachmentJson: String): String =
        "{\"sessionId\":${JsonString.of(sessionId)},\"attachment\":$attachmentJson}"
}
