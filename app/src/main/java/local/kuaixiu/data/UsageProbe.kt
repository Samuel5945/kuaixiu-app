package local.kuaixiu.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 系统用量统计探测：把手机客观记得的事实直接取出来——
 * 昨晚最后亮屏/熄屏（放下手机）、今早首次亮屏（醒来拿起手机）。
 * 需要 PACKAGE_USAGE_STATS（appop，adb appops set 或设置页授予）。
 */
object UsageProbe {

    data class NightFacts(
        val lastInteractive: LocalDateTime?,   // 昨晚最后亮屏
        val lastScreenOff: LocalDateTime?,     // 昨晚最后熄屏
        val firstMorningPickup: LocalDateTime?, // 今早首次亮屏（起床代理）
    )

    fun hasAccess(ctx: Context): Boolean = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        usm.queryEvents(System.currentTimeMillis() - 60_000, System.currentTimeMillis())
        true
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        true
    }

    /**
     * 一趟查询拿齐夜晚事实。
     * @param winddownStart 昨晚风屏开始时刻（窗口起点）
     * @param wakeAnchor    今早起锚点时刻（用于切分"夜里"与"今早"）
     * @param now           当前时刻（窗口终点）
     * 今早首次亮屏 = 今天 04:00 之后、now 之前的第一次亮屏；夜晚事件截到它为止。
     */
    fun nightFacts(
        ctx: Context,
        winddownStart: LocalDateTime,
        wakeAnchor: LocalDateTime,
        now: LocalDateTime,
    ): NightFacts? = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val s = winddownStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val e = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val evs = usm.queryEvents(s, e)
        val ev = UsageEvents.Event()
        val on = mutableListOf<LocalDateTime>()
        val off = mutableListOf<LocalDateTime>()
        val zone = ZoneId.systemDefault()
        while (evs.hasNextEvent()) {
            evs.getNextEvent(ev)
            val t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ev.timeStamp), zone)
            when {
                Build.VERSION.SDK_INT >= 30 && ev.eventType == UsageEvents.Event.SCREEN_INTERACTIVE -> on += t
                Build.VERSION.SDK_INT >= 30 && ev.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> off += t
                ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED -> on += t
            }
        }
        val morningFloor = LocalDateTime.of(now.toLocalDate(), LocalTime.of(4, 0))
        val firstPickup = on.filter { it >= morningFloor && it <= now }.minOrNull()
        val nightEnd = firstPickup ?: now
        NightFacts(
            lastInteractive = on.filter { it <= nightEnd }.maxOrNull(),
            lastScreenOff = off.filter { it <= nightEnd }.maxOrNull(),
            firstMorningPickup = firstPickup,
        )
    } catch (e: SecurityException) {
        null
    }

    /** 估计入睡时刻：夜晚窗口内最后一次熄屏；异常时用最后亮屏 +2 分钟。 */
    fun estimateOnset(f: NightFacts): LocalDateTime? {
        val on = f.lastInteractive
        val off = f.lastScreenOff
        return when {
            off != null && (on == null || !off.isBefore(on)) -> off
            on != null -> on.plusMinutes(2)
            else -> null
        }
    }
}
