package local.kuaixiu

import androidx.test.core.app.ApplicationProvider
import local.kuaixiu.data.Checkin
import local.kuaixiu.data.Checkins
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.PosterBuild
import local.kuaixiu.data.PosterData
import local.kuaixiu.data.Prefs
import local.kuaixiu.engine.Plan
import local.kuaixiu.engine.PlanConfig
import local.kuaixiu.engine.PlanDay
import local.kuaixiu.engine.PlanSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 周报（D）取哪几晚。真实事故：早上刚给昨晚打完卡，首页已经是「连续准时 3 天 / 100 分」，
 * 周报却永远停在昨晚之前——因为右端多减了一天。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PosterWeekTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 计划从 10-11（周日）开始，每天 00:30 睡 / 07:00 起。 */
    private val start = LocalDate.parse("2026-10-11")

    private fun plan() = Plan(
        created = "2026-10-11",
        config = PlanConfig("01:00", "00:30", "07:00", start.toString(), 28, 30),
        summary = PlanSummary(totalShiftMinutes = 30, daysToTarget = 4, checkpoints = listOf(7, 14)),
        days = (1..28).map { i ->
            val date = start.plusDays((i - 1).toLong())
            PlanDay(i, date.toString(), "", 1, "适应期", "", "00:30", "00:00", "16:30", emptyList(), "")
        },
    )

    private fun log(nights: List<String>) {
        File(ctx.filesDir, "checkins.json").delete()
        nights.forEach { d ->
            Checkins.add(ctx, Checkin(date = d, status = "on_time", bedtime = "00:25",
                winddownAt = "00:00", wakeupAt = "07:00", phoneDownAt = "00:38"))
        }
    }

    @Before
    fun setUp() {
        File(ctx.filesDir, "checkins.json").delete()
        // 只塞计划：配置走 KxConfig 默认值（wake=07:00），saveConfig 会碰 AndroidKeyStore，Robolectric 里没有
        Prefs.savePlan(ctx, plan())
        KxStore.load(ctx)
    }

    @After
    fun tearDown() {
        Prefs.savePlan(ctx, null)
        File(ctx.filesDir, "checkins.json").delete()
    }

    private fun at(iso: String) = ZonedDateTime.parse(iso)

    private fun week(nights: List<String>, now: String): PosterData {
        log(nights)
        return PosterBuild.week(ctx, at(now))!!
    }

    @Test
    fun `早上刚打完昨晚的卡，昨晚立刻进周报`() {
        val d = week(listOf("2026-10-11", "2026-10-12", "2026-10-13"), "2026-10-14T08:30+08:00")
        assertEquals(3, d.weekDays.size)
        assertEquals("10-13", d.weekDays.last().date)
        assertEquals(3, d.onTimeCount)
    }

    @Test
    fun `晚上看周报，右端是昨晚而不是还没过的今晚`() {
        val d = week(listOf("2026-10-11", "2026-10-12", "2026-10-13"), "2026-10-14T21:00+08:00")
        assertEquals("10-13", d.weekDays.last().date)
        assertEquals(3, d.onTimeCount)
    }

    @Test
    fun `昨晚还没打卡，周报停在最近一次打卡，不多一根空条`() {
        val d = week(listOf("2026-10-11", "2026-10-12"), "2026-10-14T08:30+08:00")
        assertEquals("10-12", d.weekDays.last().date)
        assertEquals(2, d.weekDays.size)
    }

    @Test
    fun `标题的达成率和横条根数永远同一个分母`() {
        val d = week(listOf("2026-10-11", "2026-10-12", "2026-10-13"), "2026-10-14T08:30+08:00")
        assertEquals("3/3", d.weekRatio)
        assertEquals(3, d.rateElapsed)
    }

    @Test
    fun `周标签只有月日，不写出年份`() {
        val d = week(listOf("2026-10-11", "2026-10-12", "2026-10-13"), "2026-10-14T08:30+08:00")
        assertEquals("10.11 – 10.13 · 第 1 周", d.weekLabel)
    }
}
