package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-avatars：魔数与上限（与上游 avatar.js 一致）、data URL 往返、头像库/导入应答解码、方形裁剪与请求体。 */
class AvatarLibraryTest {
    private fun obj(t: String) = Json.parse(t) as Json.Value.Object
    private fun fixture(name: String): Json.Value =
        (Json.parse(checkNotNull(AvatarLibraryTest::class.java.getResource("/dsbot/$name")).readText(Charsets.UTF_8)) as Json.Value.Object).members["value"]!!

    private fun jpeg(size: Int) = ByteArray(size).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }

    @Test
    fun limitsMatchUpstream() {
        // (160000 - "data:image/jpeg;base64,".length) / 4 * 3
        assertEquals(23, "data:image/jpeg;base64,".length)
        assertEquals(119_982, AvatarLibrary.MAX_BYTES)
        val ok = assertIs<AvatarLibrary.Encoded.Ok>(AvatarLibrary.encode(jpeg(AvatarLibrary.MAX_BYTES)))
        assertTrue(ok.url.length <= AvatarLibrary.MAX_URL)
        assertEquals("image/jpeg", ok.mime)
        assertEquals(AvatarLibrary.Problem.TOO_LARGE, (AvatarLibrary.encode(jpeg(AvatarLibrary.MAX_BYTES + 1)) as AvatarLibrary.Encoded.Bad).problem)
    }

    @Test
    fun magicNumbers() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        assertEquals("image/png", AvatarLibrary.mimeOf(png))
        assertEquals("image/jpeg", AvatarLibrary.mimeOf(jpeg(4)))
        assertEquals("image/gif", AvatarLibrary.mimeOf("GIF89a....".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("image/gif", AvatarLibrary.mimeOf("GIF87a".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("image/webp", AvatarLibrary.mimeOf("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(AvatarLibrary.mimeOf("<svg></svg>".toByteArray()))
        assertNull(AvatarLibrary.mimeOf(ByteArray(0)))
        assertEquals(AvatarLibrary.Problem.NOT_IMAGE, (AvatarLibrary.encode("hello".toByteArray()) as AvatarLibrary.Encoded.Bad).problem)
        for (p in AvatarLibrary.Problem.values()) assertTrue(AvatarLibrary.problemText(p).isNotEmpty())
    }

    @Test
    fun dataUrlRoundTrip() {
        val bytes = jpeg(300).also { it[100] = 42 }
        val url = (AvatarLibrary.encode(bytes) as AvatarLibrary.Encoded.Ok).url
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertContentEquals(bytes, AvatarLibrary.decode(url))
        assertNull(AvatarLibrary.decode(null))
        assertNull(AvatarLibrary.decode("https://x/y.png"))
        assertNull(AvatarLibrary.decode("data:image/png,raw"))
        assertNull(AvatarLibrary.decode("data:image/png;base64,@@@"))
    }

    @Test
    fun libraryPages() {
        val empty = AvatarLibrary.decodePage(fixture("avatar-library-empty.json"))
        assertTrue(empty.items.isEmpty())
        assertEquals(0, empty.total)
        assertFalse(empty.hasMore)
        val page = AvatarLibrary.decodePage(
            obj(
                """{"items":[
                  {"id":"aa","name":"猫","mime":"image/jpeg","bytes":1200,"createdAt":1795000000000,"image":"data:image/jpeg;base64,/9j/"},
                  {"id":"bb","name":"缺图"},{"image":"data:image/png;base64,AA"},7],
                  "total":60,"next":48}""",
            ),
        )
        assertEquals(listOf("aa"), page.items.map { it.id })
        assertEquals(1795000000000, page.items[0].createdAt)
        assertEquals(1200, page.items[0].bytes)
        assertTrue(page.hasMore)
        assertEquals(48, page.next)
        assertTrue(AvatarLibrary.decodePage(Json.Value.NullValue).items.isEmpty())
    }

    @Test
    fun importResults() {
        val r = AvatarLibrary.decodeImport(
            obj(
                """{"imported":[{"id":"a1","name":"一"},{"id":"a2","name":"二"}],
                   "duplicates":[{"id":"a0","name":"旧"}],
                   "failed":[{"name":"坏图","message":"The avatar must be a PNG, JPEG, WebP, or GIF image."},{"name":"无原因"}]}""",
            ),
        )
        assertEquals(listOf("一", "二"), r.imported)
        assertEquals(listOf("旧"), r.duplicates)
        assertEquals("The avatar must be a PNG, JPEG, WebP, or GIF image.", r.failed[0].message)
        assertTrue(r.failed[1].message.isNotEmpty())
        assertEquals("新增 2 张，重复 1 张，失败 2 张", AvatarLibrary.importSummary(r))
        val none = AvatarLibrary.decodeImport(null)
        assertTrue(none.imported.isEmpty() && none.failed.isEmpty())
    }

    @Test
    fun squareCrop() {
        // 横图：默认居中、边长 = 短边
        assertEquals(AvatarLibrary.Square(200, 0, 600), AvatarLibrary.cropSquare(1000, 600, 1f, 0.5f, 0.5f))
        // 放大 2 倍：边长减半，中心不变
        assertEquals(AvatarLibrary.Square(350, 150, 300), AvatarLibrary.cropSquare(1000, 600, 2f, 0.5f, 0.5f))
        // 中心推到边外：夹在图内
        assertEquals(AvatarLibrary.Square(0, 0, 600), AvatarLibrary.cropSquare(1000, 600, 1f, -1f, 0f))
        assertEquals(AvatarLibrary.Square(400, 0, 600), AvatarLibrary.cropSquare(1000, 600, 1f, 2f, 1f))
        // 竖图与缩放夹取（<1 当 1、>4 当 4）
        assertEquals(AvatarLibrary.Square(0, 300, 600), AvatarLibrary.cropSquare(600, 1200, 0.5f, 0.5f, 0.5f))
        assertEquals(150, AvatarLibrary.cropSquare(600, 1200, 9f, 0.5f, 0.5f)!!.size)
        assertNull(AvatarLibrary.cropSquare(0, 10, 1f, 0.5f, 0.5f))
    }

    @Test
    fun requestBodies() {
        assertEquals("""{"offset":48}""", AvatarLibrary.Requests.library(48))
        assertEquals(
            """{"items":[{"name":"猫\"1\"","image":"data:image/jpeg;base64,AA=="},{"name":"b","image":"data:image/png;base64,BB"}]}""",
            AvatarLibrary.Requests.import(listOf("猫\"1\"" to "data:image/jpeg;base64,AA==", "b" to "data:image/png;base64,BB")),
        )
        assertEquals("""{"id":"abc"}""", AvatarLibrary.Requests.delete("abc"))
        assertNotNull(Json.parse(AvatarLibrary.Requests.import(emptyList())))
        // 设照片保留形象；移除照片只留形象，无形象时清除
        assertEquals("""{"id":"b1","avatar":{"shape":"star","image":"data:image/png;base64,AA"}}""", BotManage.Requests.updatePhoto("b1", "star", "data:image/png;base64,AA"))
        assertEquals("""{"id":"b1","avatar":{"image":"data:x"}}""", BotManage.Requests.updatePhoto("b1", null, "data:x"))
        assertEquals("""{"id":"b1","avatar":{"shape":"star"}}""", BotManage.Requests.removePhoto("b1", "star"))
        assertEquals("""{"id":"b1","avatar":null}""", BotManage.Requests.removePhoto("b1", null))
    }

    @Test
    fun profileCarriesPhoto() {
        val state = obj("""{"bots":[{"id":"p","avatar":{"shape":"moon","image":"data:image/png;base64,AA"}},{"id":"r","avatar":{"imageRef":"x"}}]}""")
        val p = BotManage.profileOf(state, "p")!!
        assertEquals("data:image/png;base64,AA", p.photo)
        assertTrue(p.hasPhoto)
        // 引用形态：知道有照片，但没有内嵌图
        val r = BotManage.profileOf(state, "r")!!
        assertTrue(r.hasPhoto)
        assertNull(r.photo)
    }
}
