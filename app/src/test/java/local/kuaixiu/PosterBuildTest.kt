package local.kuaixiu

import local.kuaixiu.data.PosterBuild
import org.junit.Assert.assertEquals
import org.junit.Test

/** 周报里"平均放下手机"要跨午夜求平均，最容易算错。 */
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
}
