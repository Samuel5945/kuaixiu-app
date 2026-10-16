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

    /** 起床锚点之后还承认「今早」的余量（小时）；再晚的那些拿起属于下一晚。 */
    const val MORNING_TAIL_HOURS = 6L

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
     * @param wakeAnchor    那晚之后的起床锚点（也定「今早」是哪一天）
     * @param now           当前时刻（那晚还没过完时用它截断）
     * 今早首次亮屏 = 锚点那天 04:00 之后、窗口终点之前的第一次亮屏；夜晚事件截到它为止。
     */
    fun nightFacts(
        ctx: Context,
        winddownStart: LocalDateTime,
        wakeAnchor: LocalDateTime,
        now: LocalDateTime,
    ): NightFacts? = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // 终点不能拿锚点当边界：拿 now 会把之后几晚的事件一起读进来（真机踩过），
        // 但卡在锚点上，睡过锚点才拿起手机的那一次——恰恰最该记的那次——就没了。
        // 按「这一晚自己的早晨」收口：锚点那天最多到 +6h，再晚就属于下一晚了。
        val morningEnd = wakeAnchor.plusHours(MORNING_TAIL_HOURS)
        val end = if (morningEnd < now) morningEnd else now
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
