package local.kuaixiu.data

import local.kuaixiu.engine.PlanEngine

/** 每日评分：风屏 40 + 入睡 40 + 起床锚点 20，量纲是"偏差分钟"。 */
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
            plannedWinddown == null -> { wind = 20; notes += "风屏已执行" }
            else -> {
                val d = diffMinutes(plannedWinddown, w)
                wind = when {
                    d == null -> 20
                    d in -10..10 -> 40
                    d in -30..30 -> 25
                    else -> 10
                }
                notes += "风屏${if ((d ?: 0) >= 0) "晚" else "早"} ${kotlin.math.abs(d ?: 0)} 分钟"
            }
        }

        val b = checkin?.bedtime
        when {
            b.isNullOrBlank() -> notes += "入睡未记录"
            plannedBedtime == null -> { sleep = 20; notes += "入睡已记录" }
            else -> {
                val d = diffMinutes(plannedBedtime, b)
                sleep = when {
                    d == null -> 20
                    d <= -60 -> 15.also { notes += "比计划早睡 ${-d} 分钟（过早上床）" }
                    d <= 30 -> 40.also { notes += "入睡准时" }
                    d <= 60 -> 25.also { notes += "晚睡 $d 分钟" }
                    d <= 120 -> 10.also { notes += "晚睡 $d 分钟" }
                    else -> 5.also { notes += "晚睡 $d 分钟" }
                }
            }
        }

        val k = checkin?.wakeupAt
        when {
            k.isNullOrBlank() -> notes += "起床未记录"
            wakeAnchor == null -> { wake = 10; notes += "起床已记录" }
            else -> {
                val d = diffMinutes(wakeAnchor, k)
                wake = when {
                    d == null -> 10
                    kotlin.math.abs(d) <= 30 -> 20.also { notes += "起床锚点守住（$d 分钟）" }
                    kotlin.math.abs(d) <= 60 -> 10.also { notes += "起床偏 ${if (d >= 0) "晚" else "早"} ${kotlin.math.abs(d)} 分钟" }
                    else -> 0.also { notes += "起床偏 ${if (d >= 0) "晚" else "早"} ${kotlin.math.abs(d)} 分钟" }
                }
            }
        }
        return Result(wind + sleep + wake, wind, sleep, wake, notes)
    }

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

    /** 连续准时天数：从最近一次记录往前数连续 on_time 的自然日（缺记录即断）。 */
    fun streak(checkins: List<Checkin>, today: java.time.LocalDate = java.time.LocalDate.now()): Int {
        // 今晚已经记了迟睡/没做到 → 当场归零，不能沿用昨天的连续
        val row = checkins.firstOrNull { it.date == today.toString() }
        if (row != null && row.status != "on_time") return 0
        return streakOn(checkins.filter { it.status == "on_time" }.map { it.date }.toSet(), today)
    }

    /** 连续天数通用核心：睡眠用 on_time 日期集合，习惯用 habitId 的打卡日期集合。 */
    fun streakOn(done: Set<String>, today: java.time.LocalDate = java.time.LocalDate.now()): Int {
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
                   today: java.time.LocalDate = java.time.LocalDate.now()): Pair<Int, Int> {
        val elapsed = if (today.isBefore(startDate)) 0
            else kotlin.math.min(planDays, java.time.temporal.ChronoUnit.DAYS
                .between(startDate, today).toInt() + 1)
        val hits = (0 until elapsed).count { startDate.plusDays(it.toLong()).toString() in done }
        return hits to elapsed
    }
}
