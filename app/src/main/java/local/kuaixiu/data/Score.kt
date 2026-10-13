package local.kuaixiu.data

import local.kuaixiu.engine.PlanEngine

/** 每日评分：风屏 30 + 入睡 30 + 起床锚点 40，量纲是"偏差分钟"。
 *  起床权重最高——手册口径里"起床锚点固定比入睡时间更重要"，卡面三项细分也要按这个顺序显示。 */
object Score {

    data class Result(val total: Int, val winddown: Int, val sleep: Int, val wake: Int, val notes: List<String>)

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
            w.isNullOrBlank() -> notes += "风屏未记录"
            plannedWinddown == null -> { wind = 15; notes += "风屏已执行" }
            else -> {
                val d = diffMinutes(plannedWinddown, w)
                wind = when {
                    d == null -> 15
                    d in -10..10 -> 30
                    d in -30..30 -> 19
                    else -> 8
                }
                notes += "风屏${if ((d ?: 0) >= 0) "晚" else "早"} ${kotlin.math.abs(d ?: 0)} 分钟"
            }
        }

        val b = checkin?.bedtime
        when {
            b.isNullOrBlank() -> notes += "入睡未记录"
            plannedBedtime == null -> { sleep = 15; notes += "入睡已记录" }
            else -> {
                val d = diffMinutes(plannedBedtime, b)
                // 早睡不是越多越好：早 30 分钟以内照满分，「困了可提前睡」；早 30–60 给过渡档；
                // 早 1 小时以上会在生物钟觉醒高峰前睡着 → 前半夜耗完睡眠压力、3-4 点早醒，反而扣得多。
                sleep = when {
                    d == null -> 15
                    d <= -60 -> 11.also { notes += "比计划早睡 ${-d} 分钟（过早上床）" }
                    d < -30 -> 22.also { notes += "比计划早睡 ${-d} 分钟" }
                    d <= 30 -> 30.also { notes += "入睡准时" }
                    d <= 60 -> 19.also { notes += "晚睡 $d 分钟" }
                    d <= 120 -> 8.also { notes += "晚睡 $d 分钟" }
                    else -> 4.also { notes += "晚睡 $d 分钟" }
                }
            }
        }

        val k = checkin?.wakeupAt
        when {
            k.isNullOrBlank() -> notes += "起床未记录"
            wakeAnchor == null -> { wake = 20; notes += "起床已记录" }
            else -> {
                val d = diffMinutes(wakeAnchor, k)
                wake = when {
                    d == null -> 20
                    kotlin.math.abs(d) <= 30 -> 40.also { notes += "起床锚点守住（$d 分钟）" }
                    kotlin.math.abs(d) <= 60 -> 20.also { notes += "起床偏 ${if (d >= 0) "晚" else "早"} ${kotlin.math.abs(d)} 分钟" }
                    else -> 0.also { notes += "起床偏 ${if (d >= 0) "晚" else "早"} ${kotlin.math.abs(d)} 分钟" }
                }
            }
        }
        return Result(wind + sleep + wake, wind, sleep, wake, notes)
    }

    /** 卡面用的三项满分，别在 UI 里写死数字。 */
    const val MAX_WINDDOWN = 30
    const val MAX_SLEEP = 30
    const val MAX_WAKE = 40

    /** 风格化评语（App 内评分卡 / 未来复盘用）。 */
    fun voice(style: String, r: Result): String = when (style) {
        "strict" -> (r.notes.firstOrNull { it.contains("晚睡") } ?: "记录不全就是执行不全。") +
            "今晚目标不变，风屏提前 10 分钟开始。"
        "data" -> r.notes.joinToString("；").ifBlank { "暂无数据" }
        "humor" -> if (r.total >= 80) "这分数可以拿去炫耀了，今晚保持。" 
            else "分数已经在表达不满了——今晚早点让手机退休。"
        "auto" -> (if (r.total >= 80) "保持。" else "今晚从准时风屏开始。") +
            (r.notes.firstOrNull()?.let { " $it" } ?: "")
        else -> (if (r.total >= 80) "漂亮，保持这个节奏。" else "没关系，今晚从准时风屏开始就好。")
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
