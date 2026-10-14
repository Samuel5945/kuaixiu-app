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
     * 一趟查询拿齐夜晚事实。**窗口只属于被问的那一晚**：
     * @param winddownStart 那晚风屏开始时刻（窗口起点）
     * @param wakeAnchor    那晚之后的起床锚点（窗口终点，也是「今早」的日期）
     * @param now           当前时刻（那晚还没过完时用它截断）
     * 今早首次亮屏 = 起床锚点那天 04:00 之后、窗口终点之前的第一次亮屏；夜晚事件截到它为止。
     */
    fun nightFacts(
        ctx: Context,
        winddownStart: LocalDateTime,
        wakeAnchor: LocalDateTime,
        now: LocalDateTime,
    ): NightFacts? = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // 终点必须是那晚的起床锚点：拿 now 当终点会把之后几晚的事件一起读进来，
        // 于是「10-11 那晚的放下手机」实际是最近一晚的（真机实测踩过）。
        val end = if (wakeAnchor < now) wakeAnchor else now
        val s = winddownStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val e = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
        val morningFloor = LocalDateTime.of(wakeAnchor.toLocalDate(), LocalTime.of(4, 0))
        val firstPickup = on.filter { it >= morningFloor && it <= end }.minOrNull()
        // 夜晚窗口严格截止于首次拿起之前——拿起事件本身不算"昨晚最后亮屏"
        val nightEnd = firstPickup ?: end
        NightFacts(
            lastInteractive = on.filter { it < nightEnd }.maxOrNull(),
            lastScreenOff = off.filter { it < nightEnd }.maxOrNull(),
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
