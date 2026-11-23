package work.keepwriting.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** dsbot-outbox-retry：发送箱对账与照片尺寸换算。 */
class OutboxTest {
    private fun row(seq: Long?, text: String) = Outbox.UserRow(seq, text)

    @Test
    fun serverRowRemovesSendingEntry() {
        val o = Outbox()
        val e = o.add("h:bot:a", "你好", "你好", emptyList(), window = 10)
        assertEquals(listOf(e.id), o.reconcile("h:bot:a", listOf(row(5, "旧"), row(11, "你好"))))
        assertEquals(emptyList(), o.entries("h:bot:a"))
    }

    @Test
    fun oldSameTextRowOutsideWindowDoesNotMatch() {
        val o = Outbox()
        o.add("k", "好的", "好的", emptyList(), window = 20)
        assertEquals(emptyList(), o.reconcile("k", listOf(row(3, "好的"), row(20, "好的")))) // seq ≤ 窗口
        assertEquals(1, o.entries("k").size)
    }

    @Test
    fun rowsWithoutSeqNeverMatch() {
        val o = Outbox()
        o.add("k", "hi", "hi", emptyList(), window = -1)
        assertEquals(emptyList(), o.reconcile("k", listOf(row(null, "hi"))))
        assertEquals(1, o.entries("k").size)
    }

    @Test
    fun rapidIdenticalSendsMatchFirstInFirstOut() {
        val o = Outbox()
        val e1 = o.add("k", "好的", "好的", emptyList(), window = 7)
        val e2 = o.add("k", "好的", "好的", emptyList(), window = 7)
        // 先只到一条：只消掉第一条
        assertEquals(listOf(e1.id), o.reconcile("k", listOf(row(8, "好的"))))
        assertEquals(listOf(e2.id), o.entries("k").map { it.id })
        // 第二条到达（刷新结果含两条）：第一条服务端行已无本地行对应，第二条消掉 e2
        assertEquals(listOf(e2.id), o.reconcile("k", listOf(row(8, "好的"), row(9, "好的"))))
        assertEquals(emptyList(), o.entries("k"))
    }

    @Test
    fun twoPendingSameTextNeedTwoServerRows() {
        val o = Outbox()
        o.add("k", "ok", "ok", emptyList(), window = 1)
        o.add("k", "ok", "ok", emptyList(), window = 1)
        assertEquals(1, o.reconcile("k", listOf(row(2, "ok"))).size) // 一条服务端行只确认一个本地行
        assertEquals(1, o.entries("k").size)
    }

    @Test
    fun failedEntryAlsoRemovedWhenDelivered() {
        val o = Outbox()
        val e = o.add("k", "报告", "报告", emptyList(), window = 0)
        o.setState(e.id, Outbox.State.FAILED)
        assertEquals(Outbox.State.FAILED, o.get(e.id)!!.state)
        assertEquals(listOf(e.id), o.reconcile("k", listOf(row(1, "报告"))))
        assertNull(o.get(e.id))
        assertNull(o.setState(e.id, Outbox.State.SENDING)) // 已移除
    }

    @Test
    fun failedEntryDoesNotStealConfirmationOfLaterSend() {
        // 「继续」先失败（未重试），用户重新输入「继续」发送成功：服务端行应确认后者，失败行保留
        val o = Outbox()
        val failed = o.add("k", "继续", "继续", emptyList(), window = 10)
        o.setState(failed.id, Outbox.State.FAILED)
        val sent = o.add("k", "继续", "继续", emptyList(), window = 20)
        o.setState(sent.id, Outbox.State.SENT)
        assertEquals(listOf(sent.id), o.reconcile("k", listOf(row(21, "继续"))))
        assertEquals(listOf(failed.id), o.entries("k").map { it.id })
        // 若服务端后来又出现一条（失败那条其实也送达了），失败行随之消掉
        assertEquals(listOf(failed.id), o.reconcile("k", listOf(row(21, "继续"), row(22, "继续"))))
    }

    @Test
    fun groupRowsUseServerTimeAsOrder() {
        // 群聊用户行没有 seq（真实 room-chat 实测：{id, kind:user, text, time}），以服务端 time 为顺序值
        val o = Outbox()
        o.add("h:room:r", "开工", "开工", emptyList(), window = 1_795_000_000_000)
        assertEquals(emptyList(), o.reconcile("h:room:r", listOf(row(1_794_999_999_000, "开工")))) // 窗口前的旧消息
        assertEquals(1, o.reconcile("h:room:r", listOf(row(1_795_000_000_500, "开工"))).size)
    }

    @Test
    fun emptyTextEntryMatchesOnlyEmptyText() {
        val o = Outbox()
        o.add("k", "", "[附件] a.png", listOf("r"), window = 0)
        assertEquals(emptyList(), o.reconcile("k", listOf(row(1, "hi"))))
        assertEquals(1, o.reconcile("k", listOf(row(2, "  "))).size)
    }

    @Test
    fun whitespaceDifferencesStillMatch() {
        val o = Outbox()
        o.add("k", "  第一行\n第二行  ", "x", emptyList(), window = 0)
        assertEquals(1, o.reconcile("k", listOf(row(1, "第一行 第二行"))).size)
        assertEquals("a b", Outbox.normalize(" a \t\n b "))
    }

    @Test
    fun chatsAreIndependent() {
        val o = Outbox()
        val a = o.add("A", "hi", "hi", emptyList(), window = 0)
        val b = o.add("B", "hi", "hi", listOf("r1"), window = 0)
        assertEquals(emptyList(), o.reconcile("A", emptyList()))
        assertEquals(listOf(b.id), o.reconcile("B", listOf(row(1, "hi"))))
        assertEquals(listOf(a.id), o.entries("A").map { it.id })
        assertEquals(emptyList(), o.entries("B"))
        assertEquals(emptyList(), o.reconcile("missing", listOf(row(1, "hi"))))
    }

    @Test
    fun retryAndDelete() {
        val o = Outbox()
        val e = o.add("k", "t", "t [附件] a.png", listOf("r1", "r2"), window = 3)
        o.setState(e.id, Outbox.State.FAILED)
        val back = o.setState(e.id, Outbox.State.SENDING)!!
        assertEquals(Outbox.State.SENDING, back.state)
        assertEquals(listOf("r1", "r2"), back.receipts) // 重试原样带回执
        assertTrue(o.remove(e.id))
        assertFalse(o.remove(e.id))
        assertEquals(emptyList(), o.entries("k"))
    }

    @Test
    fun imageSizingScalesLongEdge() {
        assertEquals(2048 to 1536, ImageSizing.target(4000, 3000))
        assertEquals(1536 to 2048, ImageSizing.target(3000, 4000))
        assertEquals(2048 to 1, ImageSizing.target(10000, 3)) // 极端比例至少 1 像素
        assertNull(ImageSizing.target(2048, 1000)) // 恰好上限不缩
        assertNull(ImageSizing.target(800, 600)) // 过小不放大
        assertNull(ImageSizing.target(0, 600))
        assertNull(ImageSizing.target(-1, -1))
    }

    @Test
    fun imageSampleSizeKeepsAtLeastTarget() {
        assertEquals(1, ImageSizing.sampleSize(3000, 2000)) // 3000/2 < 2048
        assertEquals(2, ImageSizing.sampleSize(4096, 3072))
        assertEquals(4, ImageSizing.sampleSize(9000, 6000)) // 9000/4=2250 ≥ 2048，/8 不够
        assertEquals(1, ImageSizing.sampleSize(800, 600))
    }
}
