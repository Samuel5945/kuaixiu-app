package local.kuaixiu

import local.kuaixiu.llm.CoachPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 教练提示词里的倒计时：跨午夜那一趟最容易算错，单独测。 */
class CoachPromptClockTest {

    private fun mins(h: Int, m: Int) = h * 60 + m

    @Test
    fun `白天算到夜里那趟，不绕回当天`() {
        // 14:07 → 下一次 00:15 是明天凌晨，10 小时 08 分
        assertEquals(608, CoachPrompt.minutesUntil(mins(14, 7), "00:15"))
        assertEquals(255, CoachPrompt.minutesUntil(mins(20, 0), "00:15"))
    }

    @Test
    fun `同一天内不跨午夜就是直减`() {
        assertEquals(30, CoachPrompt.minutesUntil(mins(22, 30), "23:00"))
        assertEquals(1, CoachPrompt.minutesUntil(mins(22, 59), "23:00"))
    }

    @Test
    fun `正好到点算满一圈，不出 0 和负数`() {
        assertEquals(1440, CoachPrompt.minutesUntil(mins(0, 15), "00:15"))
        assertEquals(1440, CoachPrompt.minutesUntil(mins(23, 0), "23:00"))
    }

    @Test
    fun `坏时间返回 null，不炸提示词`() {
        assertNull(CoachPrompt.minutesUntil(mins(14, 0), "25:00"))
        assertNull(CoachPrompt.minutesUntil(mins(14, 0), ""))
        assertNull(CoachPrompt.minutesUntil(mins(14, 0), "23点"))
    }

    @Test
    fun `倒计时文案`() {
        assertEquals("10 小时 08 分", CoachPrompt.countdown(608))
        assertEquals("1 小时 00 分", CoachPrompt.countdown(60))
        assertEquals("30 分钟", CoachPrompt.countdown(30))
    }
}
