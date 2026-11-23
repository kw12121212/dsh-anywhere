package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-reader-files：file-download 分块（0.3.10 实测夹具）、拼接校验、文件名清洗与渲染附带内容。 */
class FileDownloadTest {
    private fun fixture(name: String): Json.Value.Object =
        Json.parse(checkNotNull(FileDownloadTest::class.java.getResource("/dsbot/$name")).readText()) as Json.Value.Object

    private fun chunk(offset: Long, n: Int, size: Long, version: String? = "v1", done: Boolean = offset + n >= size) =
        FileDownload.Chunk("f.bin", null, ByteArray(n) { 1 }, size, version, offset + n, done)

    @Test
    fun decodesMeasuredChunks() {
        val small = assertNotNull(FileDownload.decode(fixture("file-download-chunk-small.json").members["value"]))
        assertEquals("report.md", small.name)
        assertEquals("text/markdown", small.mime)
        assertEquals(small.size, small.bytes.size.toLong())
        assertEquals(small.size, small.nextOffset)
        assertTrue(small.done)
        assertEquals(FileDownload.Assembler.Step.Done, FileDownload.Assembler().accept(small))
        // 大文件首块：bytes 在夹具里已脱敏为占位（非 base64），解码失败而不是误收
        assertNull(FileDownload.decode(fixture("file-download-chunk-large-first.json").members["value"]))
        val large = fixture("file-download-chunk-large-first.json").members["value"] as Json.Value.Object
        assertEquals(FileDownload.CHUNK_BYTES.toString(), (large.members["nextOffset"] as Json.Value.NumberValue).raw)
        assertEquals(false, (large.members["done"] as Json.Value.BoolValue).value)
        val changed = fixture("file-download-version-changed.json")
        assertEquals("bot/not-found", ((changed.members["error"] as Json.Value.Object).members["code"] as Json.Value.StringValue).value)
    }

    @Test
    fun chatItemFilesBecomePresentedFiles() {
        val raw = fixture("chat-item-files.json")
        val item = DsbotModels.decodeChatPage(Json.parse("""{"sessionId":"s","items":[${Json.encode(raw)}]}""") as Json.Value.Object).items.single()
        val rendered = ChatRender.render(DsbotModels.ChatPage("s", listOf(item), null))
        val file = assertIs<ChatRender.Item.PresentedFile>(rendered.items[1])
        assertEquals(ChatRender.Item.PresentedFile("report.md", "s-redacted", 4918, 0), file)
        assertIs<ChatRender.Item.Message>(rendered.items[0])
        // 请求体用 files[] 自己的定位符，续块带 version
        assertEquals("""{"sessionId":"s-redacted","seq":4918,"index":0,"offset":0}""", FileDownload.presented(file.sessionId, file.seq, file.index, 0, null))
        assertEquals("""{"sessionId":"s","seq":1,"index":2,"offset":524288,"version":"v"}""", FileDownload.presented("s", 1, 2, 524288, "v"))
        assertEquals("""{"sessionId":"s","attachmentId":"a","offset":0}""", FileDownload.attachment("s", "a", 0))
    }

    @Test
    fun assemblerAcceptsContiguousChunksWithSameVersion() {
        val a = FileDownload.Assembler()
        assertEquals(FileDownload.Assembler.Step.Next(4, "v1"), a.accept(chunk(0, 4, 10)))
        assertEquals(FileDownload.Assembler.Step.Next(8, "v1"), a.accept(chunk(4, 4, 10)))
        assertEquals(FileDownload.Assembler.Step.Done, a.accept(chunk(8, 2, 10)))
        assertEquals(10, a.offset)
    }

    @Test
    fun assemblerRejectsVersionChangeGapsOversizeAndMalformed() {
        val v = FileDownload.Assembler()
        v.accept(chunk(0, 4, 10))
        assertEquals(FileDownload.Assembler.Step.Fail(FileDownload.Assembler.Failure.VERSION_CHANGED), v.accept(chunk(4, 4, 10, version = "v2")))
        assertIs<FileDownload.Assembler.Step.Fail>(v.accept(chunk(4, 4, 10))) // 失败后不再接受
        val s = FileDownload.Assembler()
        s.accept(chunk(0, 4, 10))
        assertEquals(FileDownload.Assembler.Step.Fail(FileDownload.Assembler.Failure.VERSION_CHANGED), s.accept(chunk(4, 4, 12))) // 总量变了
        val gap = FileDownload.Assembler()
        gap.accept(chunk(0, 4, 10))
        assertEquals(FileDownload.Assembler.Step.Fail(FileDownload.Assembler.Failure.OUT_OF_ORDER), gap.accept(chunk(6, 2, 10))) // 跳过了 4..6
        assertEquals(
            FileDownload.Assembler.Step.Fail(FileDownload.Assembler.Failure.TOO_LARGE),
            FileDownload.Assembler(maxBytes = 5).accept(chunk(0, 4, 10)),
        )
        assertEquals(
            FileDownload.Assembler.Step.Fail(FileDownload.Assembler.Failure.MALFORMED),
            FileDownload.Assembler().accept(FileDownload.Chunk("f", null, ByteArray(0), 10, "v", 0, false)), // 空块却没完
        )
        assertEquals(
            FileDownload.Assembler.Step.Fail(FileDownload.Assembler.Failure.MALFORMED),
            FileDownload.Assembler().accept(FileDownload.Chunk("f", null, ByteArray(4), 3, "v", 4, false)), // 越过总量
        )
        assertEquals(FileDownload.Assembler.Step.Done, FileDownload.Assembler().accept(FileDownload.Chunk("f", null, ByteArray(0), 0, "v", 0, true))) // 空文件
        // done=true 但没到总量（服务端或代理截断）：不当作完成
        assertEquals(
            FileDownload.Assembler.Step.Fail(FileDownload.Assembler.Failure.MALFORMED),
            FileDownload.Assembler().accept(FileDownload.Chunk("f", null, ByteArray(4), 10, "v", 4, true)),
        )
        // done=false 但已到总量：以字节数为准完结
        assertEquals(FileDownload.Assembler.Step.Done, FileDownload.Assembler().accept(FileDownload.Chunk("f", null, ByteArray(4), 4, "v", 4, false)))
    }

    @Test
    fun decodeRejectsBadShapes() {
        assertNull(FileDownload.decode(Json.parse("""{"bytes":"!!","size":1,"nextOffset":1}""")))
        assertNull(FileDownload.decode(Json.parse("""{"bytes":"YQ==","nextOffset":1}""")))
        assertNull(FileDownload.decode(Json.parse("[1]")))
        assertEquals("file", FileDownload.decode(Json.parse("""{"bytes":"YQ==","size":1,"nextOffset":1}"""))!!.name)
    }

    @Test
    fun safeNameBlocksTraversalAndControlChars() {
        assertEquals("passwd", FileDownload.safeName("../../etc/passwd"))
        assertEquals("evil.txt", FileDownload.safeName("C:\\Users\\x\\evil.txt"))
        assertEquals("a_b.md", FileDownload.safeName("a..b.md"))
        assertEquals("file", FileDownload.safeName(".."))
        assertEquals("file", FileDownload.safeName(""))
        assertEquals("file", FileDownload.safeName(null))
        assertEquals("file.bashrc", FileDownload.safeName(".bashrc"))
        assertEquals("ab.pdf", FileDownload.safeName("a\u0000\nb.pdf"))
        assertEquals("ab.md", FileDownload.safeName("a<>:\"|?*b.md"))
        val long = FileDownload.safeName("x".repeat(300) + ".pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertEquals("报告 2026.md", FileDownload.safeName("报告 2026.md"))
        assertEquals("invoicefdp.exe", FileDownload.safeName("invoice\u202Efdp.exe")) // 双向控制符去掉，不再伪装成 .pdf
    }

    @Test
    fun isTextByExtensionOrMime() {
        assertTrue(FileDownload.isText("a.md"))
        assertTrue(FileDownload.isText("A.JSON"))
        assertTrue(FileDownload.isText("x.bin", "text/plain"))
        assertFalse(FileDownload.isText("a.pdf", "application/pdf"))
        assertFalse(FileDownload.isText("noext"))
        assertFalse(FileDownload.isText(null))
    }

    @Test
    fun fileTextDecode() {
        assertEquals("""{"file":{"attachmentId":"a"}}""", FileText.request("""{"attachmentId":"a"}"""))
        assertEquals(FileText.Result("全文", false), FileText.decode(Json.parse("""{"text":"全文","whole":false}""")))
        assertEquals(FileText.Result("x", true), FileText.decode(Json.parse("""{"text":"x"}""")))
        assertNull(FileText.decode(Json.parse("""{"whole":true}""")))
    }

    @Test
    fun imageRefsAndWholeAndGallery() {
        val page = DsbotModels.decodeChatPage(
            Json.parse(
                """{"sessionId":"s","items":[
                  {"kind":"user","text":"看图","imageRefs":[{"attachmentId":"sha256:a","mime":"image/png","name":"1.png"},{"mime":"x"},{"attachmentId":"sha256:b","name":"2.jpg"}],"images":3},
                  {"kind":"bot","text":"开头…","whole":{"length":9000,"file":{"attachmentId":"f1","name":"long.txt"}}},
                  {"kind":"bot","text":"无全文","whole":{"length":9,"file":{"name":"no-id"}}},
                  {"kind":"divider","files":[{"name":"x.md","sessionId":"s","seq":3,"index":1},{"name":"bad"}]}]}""",
            ) as Json.Value.Object,
        )
        val items = ChatRender.render(page).items
        assertEquals(listOf("Message", "Image", "Image", "Message", "Message", "PresentedFile"), items.map { it::class.simpleName })
        val img = items[2] as ChatRender.Item.Image
        assertEquals("2.jpg", img.name)
        assertTrue(img.attachmentRefJson!!.contains("sha256:b"))
        val long = items[3] as ChatRender.Item.Message
        assertTrue(long.wholeFileJson!!.contains("\"f1\""))
        assertNull((items[4] as ChatRender.Item.Message).wholeFileJson) // 无 attachmentId 不给「阅读全文」
        assertEquals(ChatRender.Item.PresentedFile("x.md", "s", 3, 1), items[5])
        val (gallery, at) = ChatRender.gallery(items, img)
        assertEquals(2, gallery.size)
        assertEquals(1, at)
        val loose = ChatRender.Item.Image("u", "n")
        assertEquals(listOf(loose) to 0, ChatRender.gallery(items, loose))
        // 群聊共用：成员消息带全文、附带的交付文件也显示
        val room = RoomChat.renderRoom(
            RoomChat.decodeRoomPage(Json.parse("""{"roomId":"r","items":[{"kind":"bot","botId":"b","name":"A","text":"t","whole":{"length":5000,"file":{"attachmentId":"w"}}}]}""")!!),
        )
        assertTrue((room.single() as ChatRender.Item.Message).wholeFileJson!!.contains("\"w\""))
    }
}
