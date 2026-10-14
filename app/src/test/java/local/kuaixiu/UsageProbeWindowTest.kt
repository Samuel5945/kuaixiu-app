package local.kuaixiu

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.kuaixiu.data.UsageProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 夜晚窗口必须只属于被问的那一晚。真机实测：查 10-11 那晚时终点用了「现在」，
 * 于是把 10-13 的熄屏读成了 10-11 的「放下手机」，周报两根条显示同一个时刻。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageProbeWindowTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** android.jar 的编译桩里 UsageEvents.Event 只有 getter，字段名按 AOSP 反射写入。 */
    private fun event(type: Int, at: LocalDateTime): UsageEvents.Event {
        val e = UsageEvents.Event()
        fun set(name: String, v: Any) {
            UsageEvents.Event::class.java.getDeclaredField(name).apply { isAccessible = true }.set(e, v)
        }
        set("mPackage", "local.kuaixiu")
        set("mEventType", type)
        set("mTimeStamp", at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        return e
    }

    @Test
    fun `查过去那一晚时不会串到之后几晚`() {
        val usm = org.robolectric.Shadows.shadowOf(
            ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
        // 10-10 那一夜：23:50 亮屏 → 00:10 熄屏 → 次日 06:50 拿起
        usm.addEvent(event(UsageEvents.Event.SCREEN_INTERACTIVE, LocalDateTime.parse("2026-10-10T23:50")))
        usm.addEvent(event(UsageEvents.Event.SCREEN_NON_INTERACTIVE, LocalDateTime.parse("2026-10-11T00:10")))
        usm.addEvent(event(UsageEvents.Event.SCREEN_INTERACTIVE, LocalDateTime.parse("2026-10-11T06:50")))
        // 隔了几晚之后那一夜：00:20 熄屏（旧代码会把这个当成 10-10 那晚的）
        usm.addEvent(event(UsageEvents.Event.SCREEN_NON_INTERACTIVE, LocalDateTime.parse("2026-10-13T00:20")))
        usm.addEvent(event(UsageEvents.Event.SCREEN_INTERACTIVE, LocalDateTime.parse("2026-10-13T06:41")))

        val f = UsageProbe.nightFacts(
            ctx,
            LocalDateTime.parse("2026-10-10T23:00"),   // 那晚风屏开始
            LocalDateTime.parse("2026-10-11T07:00"),   // 那晚之后的起床锚点
            LocalDateTime.parse("2026-10-14T13:00"),   // 现在
        )!!
        assertEquals(LocalDateTime.parse("2026-10-11T00:10"), f.lastScreenOff)
        assertEquals(LocalDateTime.parse("2026-10-11T06:50"), f.firstMorningPickup)
    }

    /** 窗口两端都不能拿计划值当边界：入睡跨午夜、或比计划更早放下手机，都要仍然查得到。 */
    @Test
    fun `那一晚的窗口从入夜到次日起床锚点`() {
        fun win(bed: String) = local.kuaixiu.engine.PlanEngine.nightWindowOf(
            local.kuaixiu.engine.PlanDay(1, "2026-10-13", "", 1, "适应期", "", bed, "", "16:30", emptyList(), ""),
            "07:00",
        )
        val night = LocalDateTime.parse("2026-10-13T18:00")
        assertEquals(night to LocalDateTime.parse("2026-10-14T07:00"), win("01:00"))
        assertEquals(night to LocalDateTime.parse("2026-10-14T07:00"), win("22:30"))
    }
}
