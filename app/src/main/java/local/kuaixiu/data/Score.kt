package local.kuaixiu.data

import local.kuaixiu.R
import local.kuaixiu.engine.PlanEngine

/** 每日评分：风屏 30 + 入睡 30 + 起床锚点 40，量纲是"偏差分钟"。
 *  起床权重最高——手册口径里"起床锚点固定比入睡时间更重要"，卡面三项细分也要按这个顺序显示。 */
object Score {

    data class Result(
        val total: Int, val winddown: Int, val sleep: Int, val wake: Int,
        /** 逐项说明，顺序固定：[0] 风屏、[1] 入睡、[2] 起床。界面按位置取，别匹配文案。 */
        val notes: List<String>,
        /** 入睡晚了多少分钟（0 = 没晚或没记）；评语按语言取文案，不能再拿中文子串去匹配。 */
        val lateMin: Int = 0,
    )

    /** 带符号偏差（分钟）：正 = 比"计划值"晚。跨午夜安全。 */
    fun diffMinutes(from: String?, to: String?): Int? = runCatching {
        val d = (PlanEngine.hhmmToMin(to!!) - PlanEngine.hhmmToMin(from!!)).mod(1440)
        if (d > 720) d - 1440 else d
    }.getOrNull()

    fun daily(
        checkin: Checkin?,
        plannedWinddown: String?,
        plannedBedtime: String?,
        wakeAnchor: String?,
    ): Result {
        val notes = mutableListOf<String>()
        var wind = 0
        var sleep = 0
        var wake = 0

        val w = checkin?.winddownAt
        when {
            w.isNullOrBlank() -> notes += KxText.s(R.string.score_winddown_missing)
            plannedWinddown == null -> { wind = 15; notes += KxText.s(R.string.score_winddown_done) }
            else -> {
                val d = diffMinutes(plannedWinddown, w)
                wind = when {
                    d == null -> 15
                    d in -10..10 -> 30
                    d in -30..30 -> 19
                    else -> 8
                }
                notes += KxText.s(
                    if ((d ?: 0) >= 0) R.string.score_winddown_late else R.string.score_winddown_early,
                    kotlin.math.abs(d ?: 0))
            }
        }

        var lateMin = 0
        val b = checkin?.bedtime
        when {
            b.isNullOrBlank() -> notes += KxText.s(R.string.score_sleep_missing)
            plannedBedtime == null -> { sleep = 15; notes += KxText.s(R.string.score_sleep_done) }
            else -> {
                val d = diffMinutes(plannedBedtime, b)
                // 早睡不是越多越好：早 30 分钟以内照满分，「困了可提前睡」；早 30–60 给过渡档；
                // 早 1 小时以上会在生物钟觉醒高峰前睡着 → 前半夜耗完睡眠压力、3-4 点早醒，反而扣得多。
                sleep = when {
                    d == null -> 15
                    d <= -60 -> 11.also { notes += KxText.s(R.string.score_sleep_early_too_much, -d) }
                    d < -30 -> 22.also { notes += KxText.s(R.string.score_sleep_early, -d) }
                    d <= 30 -> 30.also { notes += KxText.s(R.string.score_sleep_on_time) }
                    d <= 60 -> 19.also { lateMin = d; notes += KxText.s(R.string.score_sleep_late, d) }
                    d <= 120 -> 8.also { lateMin = d; notes += KxText.s(R.string.score_sleep_late, d) }
                    else -> 4.also { lateMin = d; notes += KxText.s(R.string.score_sleep_late, d) }
                }
            }
        }

        val k = checkin?.wakeupAt
        when {
            k.isNullOrBlank() -> notes += KxText.s(R.string.score_wake_missing)
            wakeAnchor == null -> { wake = 20; notes += KxText.s(R.string.score_wake_done) }
            else -> {
                val d = diffMinutes(wakeAnchor, k)
                wake = when {
                    d == null -> 20
                    kotlin.math.abs(d) <= 30 -> 40.also { notes += KxText.s(R.string.score_wake_held, d) }
                    kotlin.math.abs(d) <= 60 -> 20.also {
                        notes += KxText.s(if (d >= 0) R.string.score_wake_late else R.string.score_wake_early,
                            kotlin.math.abs(d))
                    }
                    else -> 0.also {
                        notes += KxText.s(if (d >= 0) R.string.score_wake_late else R.string.score_wake_early,
                            kotlin.math.abs(d))
                    }
                }
            }
        }
        return Result(wind + sleep + wake, wind, sleep, wake, notes, lateMin)
    }

    /** 卡面用的三项满分，别在 UI 里写死数字。 */
    const val MAX_WINDDOWN = 30
    const val MAX_SLEEP = 30
    const val MAX_WAKE = 40

    /** 风格化评语（App 内评分卡 / 未来复盘用）。 */
    fun voice(style: String, r: Result): String = when (style) {
        "strict" -> if (r.lateMin > 0) KxText.s(R.string.score_voice_strict_late, r.lateMin)
                   else KxText.s(R.string.score_voice_strict_blind)
        "data" -> r.notes.joinToString(KxText.s(R.string.score_notes_sep))
            .ifBlank { KxText.s(R.string.score_no_data) }
        "humor" -> KxText.s(
            if (r.total >= 80) R.string.score_voice_humor_good else R.string.score_voice_humor_bad)
        "auto" -> KxText.s(
            if (r.total >= 80) R.string.score_voice_auto_good else R.string.score_voice_auto_bad) +
            (r.notes.firstOrNull()?.let { " $it" } ?: "")
        else -> KxText.s(
            if (r.total >= 80) R.string.score_voice_gentle_good else R.string.score_voice_gentle_bad)
    }

    /**
     * 连续准时天数。[pending] = 当前这一晚归属的计划日（默认按真实时间算：当天 12 点前算昨晚）。
     * **不能因为"当晚还没记"就把连续清零**——当晚要次日早上才记，所以只允许最近一次准时和
     * 当晚之间空一晚；再早就是真断；那一晚若已记成迟到/没做到，照样断。
     */
    fun streak(
        checkins: List<Checkin>,
        pending: java.time.LocalDate = Checkins.tonightDate(),
    ): Int {
        val rows = checkins.mapNotNull { c ->
            runCatching { java.time.LocalDate.parse(c.date) }.getOrNull()?.let { it to c }
        }
        // 当晚已记迟睡/没做到 → 当场归零，不能沿用之前的连续
        rows.firstOrNull { (d, _) -> d == pending }?.let { if (it.second.status != "on_time") return 0 }
        val done = rows.filter { (_, c) -> c.status == "on_time" }.map { (d, _) -> d }.toSet()
        val last = done.maxOrNull() ?: return 0
        if (last.isBefore(pending.minusDays(1))) return 0
        // 最近一次准时之后、当晚之前记成非准时的，也算断
        if (rows.any { (d, c) -> d > last && d <= pending && c.status != "on_time" }) return 0
        return streakOn(done.map { it.toString() }.toSet(), last)
    }

    /** 连续天数通用核心：睡眠用 on_time 日期集合，习惯用 habitId 的打卡日期集合。 */
    fun streakOn(done: Set<String>, today: java.time.LocalDate = Time.today()): Int {
        var d = if (today.toString() in done) today else today.minusDays(1)
        var n = 0
        while (d.toString() in done) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    /** 达成率：已走过的天数里打卡占比（未开始/已超期分别夹到边界）。 */
    fun completion(done: Set<String>, startDate: java.time.LocalDate, planDays: Int,
                   today: java.time.LocalDate = Time.today()): Pair<Int, Int> {
        val elapsed = if (today.isBefore(startDate)) 0
            else kotlin.math.min(planDays, java.time.temporal.ChronoUnit.DAYS
                .between(startDate, today).toInt() + 1)
        val hits = (0 until elapsed).count { startDate.plusDays(it.toLong()).toString() in done }
        return hits to elapsed
    }
}
