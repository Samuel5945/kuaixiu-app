package local.kuaixiu

import local.kuaixiu.engine.PlanConfig
import local.kuaixiu.engine.PlanEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanEngineTest {

    private val base = PlanConfig(
        currentBedtime = "01:15", targetBedtime = "23:00", wake = "07:00",
        startDate = "2026-10-12", planDays = 28, winddownMin = 45,
    )

    @Test
    fun `阶梯每步15分钟且第27天达成`() {
        val p = PlanEngine.generate(base)
        assertEquals(28, p.days.size)
        val seq = p.days.map { PlanEngine.hhmmToMin(it.bedtime) }
        val diffs = seq.zipWithNext { a, b -> (a - b).mod(1440) }.toSet()
        assertTrue("阶梯步长异常: $diffs", diffs.all { it == 0 || it == 15 })
        assertEquals("23:00", p.days.last().bedtime)
        assertEquals(27, p.summary.daysToTarget)
        assertEquals(135, p.summary.totalShiftMinutes)
    }

    @Test
    fun `起床锚点与阶段`() {
        val p = PlanEngine.generate(base)
        assertEquals("07:00", p.config.wake)
        assertEquals("适应期", p.days[0].stage)
        assertEquals("巩固期", p.days.last().stage)
    }

    @Test
    fun `已在目标则全程巩固`() {
        val p = PlanEngine.generate(base.copy(currentBedtime = "23:00"))
        assertTrue(p.days.all { it.stage == "巩固期" })
        assertEquals(0, p.summary.totalShiftMinutes)
    }

    @Test
    fun `推迟方向自动反转`() {
        val p = PlanEngine.generate(base.copy(currentBedtime = "22:00", targetBedtime = "23:00"))
        assertEquals("22:00", p.days[0].bedtime)
        assertEquals("23:00", p.days.last().bedtime)
    }

    @Test
    fun `咖啡因截断区间`() {
        val p = PlanEngine.generate(base)
        for (d in p.days) {
            val c = PlanEngine.hhmmToMin(d.caffeineCutoff)
            assertTrue("$d", c in (11 * 60)..(16 * 60 + 30))
        }
    }

    @Test
    fun `跨午夜入睡事件落次日`() {
        val evs = PlanEngine.eventsFor(PlanEngine.generate(base))
        // day1 bedtime 01:15 → 睡前准备 = 10-13 00:30（45 分钟结束在 01:15）
        val bed1 = evs.first { it.kind == "bed" }
        assertEquals("2026-10-13T00:30", bed1.start.toString().take(16))
        // day27 bedtime 23:00（2026-11-07）→ 睡前准备在当日 22:15
        val bed27 = evs.first { it.kind == "bed" && it.title.startsWith("第27天") }
        assertEquals("2026-11-07T22:15", bed27.start.toString().take(16))
    }

    @Test
    fun `事件总数与复盘点`() {
        val plan = PlanEngine.generate(base)
        val evs = PlanEngine.eventsFor(plan)
        assertEquals(28 * 4 + 4, evs.size)  // checkpoints 7/14/21/28
        assertEquals(4, plan.summary.checkpoints.size)
        assertTrue(plan.summary.checkpoints.containsAll(listOf(7, 14, 21, 28)))
    }

    @Test
    fun `周主题轮换`() {
        val p = PlanEngine.generate(base)
        assertEquals("锚定起床与睡前仪式", p.days[0].theme)
        assertEquals("巩固与破功预案", p.days[27].theme)
        assertTrue(LocalDate.parse(p.days[0].date) == LocalDate.parse("2026-10-12"))
    }
}
