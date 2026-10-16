package local.kuaixiu

import local.kuaixiu.engine.PlanDay
import local.kuaixiu.ui.morningProbeStart
import local.kuaixiu.ui.morningWakeValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 起床是 40 分、权重最高的一项，不能因为「昨晚已打卡，表单收起」或「昨晚不在计划里」
 * 就连查都不查。这里钉住收起/展开共用的两条取值规则；收起卡画成第几行要真机看。
 */
class MorningWakeTest {

    private val fmt = DateTimeFormatter.ofPattern("HH:mm")
    private val night = LocalDate.parse("2026-10-13")

    @Test
    fun `昨晚没有档位时起点退到那晚入夜`() {
        // 「改参数=以今天重开」之后昨晚落在计划外，以前是直接 return，整块探测不跑
        assertEquals(LocalDateTime.parse("2026-10-13T18:00"), morningProbeStart(night, null, 45))
    }

    @Test
    fun `有档位时用那晚风屏开始且跨午夜自动落位`() {
        val entry = PlanDay(1, "2026-10-13", "", 1, "适应期", "", "00:30", "23:45", "16:30", emptyList(), "")
        assertEquals(LocalDateTime.parse("2026-10-13T23:45"), morningProbeStart(night, entry, 45))
    }

    @Test
    fun `收起态的起床值已记的优先其次探测`() {
        val probed = LocalDateTime.parse("2026-10-14T08:40")
        // 早上自己选过的起床时间，不能被下一次探测盖掉
        assertEquals("07:05", morningWakeValue("07:05", probed, fmt))
        assertEquals("08:40", morningWakeValue("", probed, fmt))
        assertEquals("", morningWakeValue("", null, fmt))   // 空串 = 界面改口说「没探测到」
    }
}
