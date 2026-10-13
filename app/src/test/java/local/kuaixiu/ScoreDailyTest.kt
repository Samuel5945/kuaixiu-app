package local.kuaixiu

import local.kuaixiu.data.Checkin
import local.kuaixiu.data.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日评分的档位口径：风屏 30 + 入睡 30 + 起床 40。
 * 起床权重最高（手册口径：起床锚点比入睡更重要）；早睡有过渡档，只有早 1 小时以上才重罚。
 */
class ScoreDailyTest {

    private val winddown = "22:15"
    private val bedtime = "23:00"
    private val wake = "07:00"

    private fun day(
        winddownAt: String = "",
        bedtimeAt: String = "",
        wakeupAt: String = "",
        planned: Boolean = true,
    ) = Score.daily(
        Checkin(date = "2026-10-12", status = "on_time",
            bedtime = bedtimeAt, winddownAt = winddownAt, wakeupAt = wakeupAt),
        if (planned) winddown else null,
        if (planned) bedtime else null,
        if (planned) wake else null,
    )

    @Test
    fun `三项都准时就是满分`() {
        val r = day("22:15", "23:00", "07:00")
        assertEquals(100, r.total)
        assertEquals(listOf(30, 30, 40), listOf(r.winddown, r.sleep, r.wake))
    }

    @Test
    fun `风屏档位`() {
        assertEquals(30, day("22:05", "23:00", "07:00").winddown)   // 早 10 分钟，仍满分
        assertEquals(19, day("22:35", "23:00", "07:00").winddown)   // 晚 20 分钟
        assertEquals(8, day("23:05", "23:00", "07:00").winddown)    // 晚 50 分钟
    }

    @Test
    fun `早睡 30 分钟内不罚，30 到 60 给过渡档，超 1 小时才重罚`() {
        assertEquals(30, day("22:15", "22:30", "07:00").sleep)   // 早 30 分钟整：满分
        assertEquals(22, day("22:15", "22:15", "07:00").sleep)   // 早 45 分钟
        assertEquals(11, day("22:15", "21:30", "07:00").sleep)   // 早 90 分钟：过早上床
        assertTrue(day("22:15", "21:30", "07:00").notes.any { it.contains("过早上床") })
    }

    @Test
    fun `晚睡档位`() {
        assertEquals(30, day("22:15", "23:30", "07:00").sleep)   // 晚 30 分钟仍满分
        assertEquals(19, day("22:15", "23:45", "07:00").sleep)   // 晚 45 分钟
        assertEquals(8, day("22:15", "00:30", "07:00").sleep)    // 晚 90 分钟（跨午夜）
        assertEquals(4, day("22:15", "02:00", "07:00").sleep)    // 晚 180 分钟
    }

    @Test
    fun `起床权重最高，晚一小时以上归零`() {
        assertEquals(40, day("22:15", "23:00", "06:40").wake)    // 早 20 分钟
        assertEquals(40, day("22:15", "23:00", "07:30").wake)    // 晚 30 分钟整
        assertEquals(20, day("22:15", "23:00", "07:45").wake)    // 晚 45 分钟
        assertEquals(0, day("22:15", "23:00", "08:30").wake)     // 晚 90 分钟
    }

    @Test
    fun `没记录是 0 分并写明`() {
        val r = day("", "", "")
        assertEquals(0, r.total)
        assertTrue(r.notes.any { it.contains("风屏未记录") })
        assertTrue(r.notes.any { it.contains("入睡未记录") })
        assertTrue(r.notes.any { it.contains("起床未记录") })
    }

    @Test
    fun `还没生成计划时给一半分`() {
        val r = day("22:15", "23:00", "07:00", planned = false)
        assertEquals(listOf(15, 15, 20), listOf(r.winddown, r.sleep, r.wake))
    }
}
