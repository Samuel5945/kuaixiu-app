package local.kuaixiu

import local.kuaixiu.data.Checkin
import local.kuaixiu.data.Score
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 连续准时天数的口径：当晚还没记不算断，记成迟到/没做到才算断。 */
class ScoreTest {

    private fun ck(date: String, status: String) = Checkin(date = date, status = status)

    // 00:23 那一晚：当晚归属的计划日是 10-12（还没记）
    private val pendingNight = LocalDate.of(2026, 10, 12)
    // 同一天下午 20:00 时：当晚已经是 10-13
    private val pendingToday = LocalDate.of(2026, 10, 13)

    @Test
    fun `当晚还没记时连续不清零`() {
        assertEquals(1, Score.streak(listOf(ck("2026-10-11", "on_time")), pendingNight))
    }

    @Test
    fun `当晚记成迟到当场归零`() {
        val list = listOf(ck("2026-10-11", "on_time"), ck("2026-10-12", "late"))
        assertEquals(0, Score.streak(list, pendingNight))
    }

    @Test
    fun `隔了两晚就是真断`() {
        assertEquals(0, Score.streak(listOf(ck("2026-10-11", "on_time")), pendingToday))
    }

    @Test
    fun `连续多天照常累计`() {
        val list = listOf(
            ck("2026-10-10", "on_time"),
            ck("2026-10-11", "on_time"),
            ck("2026-10-12", "on_time"),
        )
        assertEquals(3, Score.streak(list, pendingNight))
    }

    @Test
    fun `没有记录就是零`() {
        assertEquals(0, Score.streak(emptyList(), pendingNight))
    }
}