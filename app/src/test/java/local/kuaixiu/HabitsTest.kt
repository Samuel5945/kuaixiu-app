package local.kuaixiu

import local.kuaixiu.data.Habit
import local.kuaixiu.data.HabitCheck
import local.kuaixiu.data.Habits
import local.kuaixiu.data.Score
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 习惯模块的纯逻辑：并行上限、锚点间隔、进度与连续天数。 */
class HabitsTest {

    private val t = LocalDate.of(2026, 10, 12)

    private fun habit(id: String, name: String, cue: String, days: Int = 21,
                      start: String = "2026-10-12", enabled: Boolean = true) =
        Habit(id = id, name = name, cueTime = cue, days = days, startDate = start, enabled = enabled)

    private fun check(existing: List<Habit>, name: String, cue: String, days: Int = 21,
                      start: String = "2026-10-12", ack: Boolean = false) =
        Habits.check(existing, name, cue, days, start, ackCost = ack, today = t)

    @Test fun `第一个习惯直接通过`() {
        val r = check(emptyList(), "饭后散步", "20:30")
        assertTrue(r is HabitCheck.Ok)
        val h = (r as HabitCheck.Ok).habit
        assertEquals("饭后散步", h.name)
        assertEquals("20:30", h.cueTime)
        assertEquals(21, h.days)
        assertTrue(h.enabled)
        assertEquals(0L, h.eventId)
    }

    @Test fun `两个并行是上限，第三个要先确认代价`() {
        val onAir = listOf(habit("a", "散步", "20:30"), habit("b", "拉伸", "08:00"))
        val r = check(onAir, "刷牙后冥想", "22:30")
        assertTrue(r is HabitCheck.QuotaFull)
        assertEquals(listOf("散步", "拉伸"), (r as HabitCheck.QuotaFull).activeNames)
        // 用户看过代价并坚持 → 放行，不静默失败也不硬拦
        assertTrue(check(onAir, "刷牙后冥想", "22:30", ack = true) is HabitCheck.Ok)
    }

    @Test fun `锚点过近直接拦，60 分钟是边界`() {
        val onAir = listOf(habit("a", "散步", "20:30"))
        val near = check(onAir, "洗碗", "20:50")
        assertTrue(near is HabitCheck.AnchorConflict)
        assertEquals("散步", (near as HabitCheck.AnchorConflict).otherName)
        assertEquals(20, near.gapMin)
        assertTrue(check(onAir, "洗碗", "21:30") is HabitCheck.Ok)   // 正好 60 分钟
    }

    @Test fun `跨午夜锚点按最短间隔算`() {
        val onAir = listOf(habit("a", "睡前刷牙", "23:50"))
        val r = check(onAir, "晨起喝水", "00:20")
        assertTrue(r is HabitCheck.AnchorConflict)
        assertEquals(30, (r as HabitCheck.AnchorConflict).gapMin)
    }

    @Test fun `已停用的习惯既不占额度也不抢锚点`() {
        val paused = listOf(habit("a", "散步", "20:30", enabled = false), habit("b", "拉伸", "08:00"))
        assertTrue(check(paused, "冥想", "20:40") is HabitCheck.Ok)
    }

    @Test fun `跑完的计划期不占额度`() {
        val done = listOf(habit("a", "散步", "20:30", days = 7, start = "2026-09-01"),
                          habit("b", "拉伸", "08:00"))
        assertTrue(check(done, "冥想", "22:00") is HabitCheck.Ok)
    }

    @Test fun `坏输入一律 Invalid 而不是写坏日历`() {
        assertTrue(check(emptyList(), "", "20:30") is HabitCheck.Invalid)
        assertTrue(check(emptyList(), "散步", "20:70").isInvalid())
        assertTrue(check(emptyList(), "散步", "晚上八点半").isInvalid())
        assertTrue(check(emptyList(), "散步", "20:30", days = 0).isInvalid())
        assertTrue(check(emptyList(), "散步", "20:30", start = "12-10-2026").isInvalid())
    }

    @Test fun `口语化时间归一后再落库`() {
        val r = check(emptyList(), "刷牙", "8:05") as HabitCheck.Ok
        assertEquals("08:05", r.habit.cueTime)
        val r2 = check(emptyList(), "刷牙", " 20:30 ") as HabitCheck.Ok
        assertEquals("20:30", r2.habit.cueTime)
    }

    private fun HabitCheck.isInvalid() = this is HabitCheck.Invalid

    @Test fun `第几天与计划期结束`() {
        val h = habit("a", "散步", "20:30", days = 21, start = "2026-10-12")
        assertEquals(1, Habits.dayOf(h, t))
        assertEquals(2, Habits.dayOf(h, t.plusDays(1)))
        assertEquals(21, Habits.dayOf(h, t.plusDays(20)))
        assertEquals(21, Habits.dayOf(h, t.plusDays(30)))   // 超期不再往上蹦
        assertFalse(Habits.expired(h, t.plusDays(20)))
        assertTrue(Habits.expired(h, t.plusDays(21)))
        val later = habit("b", "拉伸", "08:00", start = "2026-10-15")
        assertEquals(0, Habits.dayOf(later, t))
    }

    @Test fun `连续天数按自然日往前数，断了就停`() {
        val d = t.toString()
        val prev = t.minusDays(1).toString()
        val prev2 = t.minusDays(2).toString()
        val gap = t.minusDays(3).toString()
        assertEquals(3, Score.streakOn(setOf(d, prev, prev2), t))
        // 今天还没打卡不算断，从昨天起算
        assertEquals(2, Score.streakOn(setOf(prev, prev2), t))
        // 中间缺一天即断，今天算上只有 1
        assertEquals(1, Score.streakOn(setOf(d, prev2), t))
        assertEquals(1, Score.streakOn(setOf(prev, gap), t))
        assertEquals(0, Score.streakOn(emptySet(), t))
    }

    @Test fun `达成率只算已走过的天数，封顶计划天数`() {
        val start = LocalDate.of(2026, 10, 5)
        val marks = (0..6).map { start.plusDays(it.toLong()).toString() }
            .filter { it != start.plusDays(2).toString() && it != start.plusDays(4).toString() }
            .toSet()
        // 10-05 到 10-12 共走了 8 天（含今天），打了 5 天
        assertEquals(5 to 8, Score.completion(marks, start, 21, t))
        // 还没开始：0/0
        assertEquals(0 to 0, Score.completion(marks, LocalDate.of(2026, 11, 1), 21, t))
        // 超期按计划天数封顶，不会算出 22/21
        assertEquals(2 to 3, Score.completion(setOf(start.toString(), start.plusDays(1).toString()),
            start, 3, t))
    }

    @Test fun `睡眠连续天数通用化后口径不变`() {
        val d = t.toString()
        val prev = t.minusDays(1).toString()
        val prev2 = t.minusDays(2).toString()
        fun c(date: String, status: String) = local.kuaixiu.data.Checkin(date = date, status = status)
        // 今天已记迟睡 → 当场归零（沿用旧实现，不是退回昨天继续数）
        assertEquals(0, Score.streak(listOf(c(prev, "on_time"), c(prev2, "on_time"), c(d, "late")), t))
        assertEquals(3, Score.streak(listOf(c(d, "on_time"), c(prev, "on_time"), c(prev2, "on_time")), t))
        // 今天还没记 → 从昨天起算
        assertEquals(2, Score.streak(listOf(c(prev, "on_time"), c(prev2, "on_time")), t))
        // 中间那晚没做到 → 只剩今天这一天
        assertEquals(1, Score.streak(listOf(c(d, "on_time"), c(prev, "skip"), c(prev2, "on_time")), t))
    }

    @Test fun `同名 add 就是改参数，不算冲突也不占额度`() {
        val one = listOf(habit("a", "晚饭后散步10分钟", "19:30", days = 21, start = "2026-10-12"))
        val r = check(one, "晚饭后散步10分钟", "19:30", 21, "2026-10-13")
        assertTrue(r is HabitCheck.Ok)
        assertEquals("a", (r as HabitCheck.Ok).habit.id)      // 复用同一条，不新增
        assertEquals("2026-10-13", r.habit.startDate)
        // 锚点和自己重叠不算重叠
        assertTrue(check(one, "晚饭后散步10分钟", "19:30", 21, "2026-10-12") is HabitCheck.Ok)
        // 两个满额时改已有的那个照样放行，新建第 3 个才要确认代价
        val two = one + habit("b", "晨起拉伸", "08:00")
        assertTrue(check(two, "晚饭后散步10分钟", "19:30", 21, "2026-10-13") is HabitCheck.Ok)
        assertTrue(check(two, "睡前冥想", "22:30", 21) is HabitCheck.QuotaFull)
    }

    @Test fun `状态行只报进行中的习惯`() {
        val on = habit("a", "饭后散步", "20:30", days = 21, start = "2026-10-10")
        val over = habit("b", "老习惯", "08:00", days = 7, start = "2026-09-01")
        val paused = habit("c", "暂停的", "15:00", enabled = false)
        val line = Habits.statusLine(listOf(on, over, paused), t)
        assertTrue(line.contains("饭后散步 第 3/21 天（锚点 20:30）"))
        assertTrue(line.contains("连续 0 天，今日未打卡"))
        assertFalse(line.contains("老习惯"))
        assertFalse(line.contains("暂停的"))
        // 明天才开始的习惯不该报"第 0 天"
        val later = habit("d", "明天再说", "21:00", start = "2026-10-13")
        val l2 = Habits.statusLine(listOf(later), t)
        assertTrue(l2.contains("还没开始（2026-10-13 起"))
        assertFalse(l2.contains("第 0/"))
        assertEquals("", Habits.statusLine(listOf(over, paused), t))
    }
}
