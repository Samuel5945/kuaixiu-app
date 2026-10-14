package local.kuaixiu

import local.kuaixiu.data.PosterBuild
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

/** 周报里两个最容易算错的口径：跨午夜求平均，以及哪一晚才算"已经报过结果"。 */
class PosterBuildTest {

    @Test
    fun `跨午夜求平均走最短弧，不会被平到中午`() {
        assertEquals("00:00", PosterBuild.avgClock(listOf("23:50", "00:10")))
        assertEquals("00:50", PosterBuild.avgClock(listOf("00:38", "01:02")))
        assertEquals("23:55", PosterBuild.avgClock(listOf("23:50", "00:00")))
    }

    @Test
    fun `全在同一侧就是普通平均`() {
        assertEquals("01:00", PosterBuild.avgClock(listOf("00:58", "01:02")))
        assertEquals("22:15", PosterBuild.avgClock(listOf("22:00", "22:30")))
    }

    @Test
    fun `空值和非时间串被跳过，全空返回空串`() {
        assertEquals("00:45", PosterBuild.avgClock(listOf("", "00:45")))
        assertEquals("", PosterBuild.avgClock(listOf("", "abc")))
        assertEquals("", PosterBuild.avgClock(emptyList()))
    }

    // ---------- 周报右端：早上刚打完卡，昨晚必须立刻进图 ----------

    private fun at(iso: String) = ZonedDateTime.parse(iso)

    @Test
    fun `早上打完昨晚的卡，昨晚就是周报右端`() {
        assertEquals("2026-10-13",
            PosterBuild.weekCutoff(listOf("2026-10-11", "2026-10-12", "2026-10-13"), at("2026-10-14T08:30+08:00")))
    }

    @Test
    fun `晚上看周报，右端是昨晚而不是还没过的今晚`() {
        assertEquals("2026-10-13",
            PosterBuild.weekCutoff(listOf("2026-10-11", "2026-10-12", "2026-10-13"), at("2026-10-14T21:00+08:00")))
    }

    @Test
    fun `昨晚还没打卡就停在最近一次打卡，不留空条`() {
        assertEquals("2026-10-12",
            PosterBuild.weekCutoff(listOf("2026-10-11", "2026-10-12"), at("2026-10-14T08:30+08:00")))
    }

    @Test
    fun `一条没打卡也给出已结束的夜`() {
        assertEquals("2026-10-13", PosterBuild.weekCutoff(emptyList(), at("2026-10-14T21:00+08:00")))
    }
}
