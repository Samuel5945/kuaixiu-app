package local.kuaixiu.engine

import local.kuaixiu.R
import local.kuaixiu.data.KxText
import local.kuaixiu.data.Time
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 快休计划引擎（从 scripts/plan_engine.py 移植，规则一一对应，见 reference/science-rules.md）：
 * 起床锚点固定；入睡每 3 天向目标前移 15 分钟（先移动再落位，最后一天恰好到达）；
 * 咖啡因截断 = 睡前 8 小时（限 11:00–16:30）；跨午夜入睡(<12:00)属次日历日。
 */
object PlanEngine {

    /** 阶段值是数据（与 PC 端 plan.json 同一套取值，也被单测断言），只有显示时才翻。 */
    const val STAGE_ADAPTING = "适应期"
    const val STAGE_CONSOLIDATING = "巩固期"

    fun phaseLabel(stage: String): String = KxText.s(
        if (stage == STAGE_CONSOLIDATING) R.string.phase_consolidating else R.string.phase_adapting)

    /** 星期交给 CLDR 显示：中文「周二」、英文「Tue」，不再自建中文表。 */
    fun weekdayOf(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    // 周主题 / 行动清单 / 小提示是内容文案，生成计划时按当前语言烘进 plan；
    // 换系统语言后点「重算计划并写入日历」即刷新。
    fun themeFor(week: Int): String = KxText.s(themeRes(week))

    fun tasksFor(week: Int): List<String> = when (week) {
        2 -> listOf(R.string.task_2_1, R.string.task_2_2, R.string.task_2_3, R.string.task_2_4, R.string.task_2_5)
        3 -> listOf(R.string.task_3_1, R.string.task_3_2, R.string.task_3_3, R.string.task_3_4, R.string.task_3_5)
        4 -> listOf(R.string.task_4_1, R.string.task_4_2, R.string.task_4_3, R.string.task_4_4, R.string.task_4_5)
        else -> listOf(R.string.task_1_1, R.string.task_1_2, R.string.task_1_3, R.string.task_1_4, R.string.task_1_5)
    }.map { KxText.s(it) }

    fun tipFor(index: Int): String = KxText.s(TIP_IDS[index % TIP_IDS.size])

    private fun themeRes(week: Int): Int = when (week) {
        2 -> R.string.theme_2
        3 -> R.string.theme_3
        4 -> R.string.theme_4
        else -> R.string.theme_1
    }

    private val TIP_IDS = listOf(
        R.string.tip_1, R.string.tip_2, R.string.tip_3, R.string.tip_4, R.string.tip_5, R.string.tip_6)

    fun hhmmToMin(s: String): Int {
        val parts = s.trim().split(":")
        val v = parts[0].toInt() * 60 + parts[1].toInt()
        require(v in 0..1439) { KxText.s(R.string.plan_err_time, s) }
        return v
    }

    fun minToHHMM(m: Int): String {
        val v = m.mod(1440)
        return "%02d:%02d".format(v / 60, v % 60)
    }

    /** 每天的入睡时间（分钟）。先移动再落位：第 stepDays 天起生效，恰好 days 天走完全部步数。 */
    fun ladder(current: String, target: String, days: Int, stepDays: Int = 3, stepMin: Int = 15): List<Int> {
        val cur = hhmmToMin(current)
        val tgt = hhmmToMin(target)
        if (cur == tgt) return List(days) { tgt }
        // 目标在当前"钟面之后"≤12h 认为是推迟，否则提前
        val later = (tgt - cur).mod(1440) <= 720
        val step = if (later) stepMin else -stepMin
        var v = cur
        return List(days) { d ->
            if (v != tgt && d % stepDays == stepDays - 1) {
                val nv = (v + step).mod(1440)
                // 朝行进方向的剩余距离 >720 说明这一步跨过了目标，收在目标上
                val dist = if (later) (tgt - nv).mod(1440) else (nv - tgt).mod(1440)
                v = if (dist > 720) tgt else nv
            }
            v
        }
    }

    fun caffeineCutoff(bedtimeMin: Int): Int =
        (bedtimeMin - 480).coerceIn(11 * 60, 16 * 60 + 30)

    /** 宽容日期解析：ISO / 2026.10.11 / 2026/10/11 / 10/11（补当前年）等，失败回退今天。 */
    fun parseDateLenient(s: String, today: LocalDate = Time.today()): LocalDate {
        val t = s.trim()
        if (t.isBlank()) return today
        return runCatching { LocalDate.parse(t) }
            .recoverCatching { LocalDate.parse(t.replace('/', '-').replace('.', '-')) }
            .getOrElse {
                val m = Regex("^(\\d{1,4})[-/.](\\d{1,2})[-/.]?(\\d{0,2})$").find(t)
                when {
                    m == null -> today
                    m.groupValues[3].isEmpty() ->
                        LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
                    else -> {
                        val y = m.groupValues[1].toInt()
                        LocalDate.of(if (y < 100) 2000 + y else y,
                                     m.groupValues[2].toInt(), m.groupValues[3].toInt())
                    }
                }
            }
    }

    /** 跨午夜入睡（<12:00）落在次日。返回事件发生的日期。 */
    fun bedtimeDate(date: LocalDate, bedtimeMin: Int): LocalDate =
        if (bedtimeMin < 720) date.plusDays(1) else date

    /** 某晚风屏开始的时刻（= 入睡时刻 - 风屏时长，跨午夜自动落位）。 */
    fun winddownStartOf(day: PlanDay, winddownMin: Int): LocalDateTime {
        val date = LocalDate.parse(day.date)
        val b = hhmmToMin(day.bedtime)
        return bedtimeDate(date, b).atTime(b / 60, b % 60).minusMinutes(winddownMin.toLong())
    }

    fun generate(cfg: PlanConfig, today: LocalDate = Time.today()): Plan {
        val start = parseDateLenient(cfg.startDate, today)
        val targetMin = hhmmToMin(cfg.targetBedtime)
        val bedtimes = ladder(cfg.currentBedtime, cfg.targetBedtime, cfg.planDays)
        val windMin = cfg.winddownMin

        val days = bedtimes.mapIndexed { i, b ->
            val n = i + 1
            val date = start.plusDays(i.toLong())
            val week = minOf(4, i / 7 + 1)
            val stage = if (b == targetMin) STAGE_CONSOLIDATING else STAGE_ADAPTING
            val pool = tasksFor(week)
            PlanDay(
                day = n,
                date = date.toString(),
                weekday = weekdayOf(date),
                week = week,
                stage = stage,
                theme = themeFor(week),
                bedtime = minToHHMM(b),
                winddown = minToHHMM(b - windMin),
                caffeineCutoff = minToHHMM(caffeineCutoff(b)),
                tasks = List(3) { k -> pool[(i * 3 + k) % pool.size] },
                tip = tipFor(i),
            )
        }

        var shift = (hhmmToMin(cfg.currentBedtime) - targetMin).mod(1440)
        if (shift > 720) shift = 1440 - shift
        val checkpoints = sortedSetOf(7, 14, 21, 28, cfg.planDays)
            .filter { it in 1..cfg.planDays }

        return Plan(
            created = Time.now().toLocalDateTime().toString(),
            config = cfg,
            summary = PlanSummary(
                totalShiftMinutes = shift,
                daysToTarget = days.firstOrNull { hhmmToMin(it.bedtime) == targetMin }?.day,
                checkpoints = checkpoints,
            ),
            days = days,
        )
    }

    /** 展开为日历事件（含跨午夜处理与提醒提前量），与 PC 版 ics_writer 同构。 */
    fun eventsFor(plan: Plan): List<KxEvent> {
        val cfg = plan.config
        val wakeMin = hhmmToMin(cfg.wake)
        val windMin = cfg.winddownMin
        val evs = mutableListOf<KxEvent>()

        for (d in plan.days) {
            val date = LocalDate.parse(d.date)
            val bedStart = winddownStartOf(d, windMin)
            val stage = phaseLabel(d.stage)

            evs += KxEvent(
                kind = "bed",
                title = KxText.s(R.string.ev_bed_title, d.day, d.bedtime),
                description = KxText.s(R.string.ev_bed_desc, stage, d.theme, d.bedtime,
                    d.tasks.mapIndexed { i, t -> "  ${i + 1}. $t" }.joinToString("\n"), d.tip),
                start = bedStart, durationMin = windMin, alarmMin = 5,
            )
            evs += KxEvent(
                kind = "wake",
                title = KxText.s(R.string.ev_wake_title, d.day),
                description = KxText.s(R.string.ev_wake_desc, stage, d.theme),
                start = date.atTime(wakeMin / 60, wakeMin % 60), durationMin = 15, alarmMin = 5,
            )
            val cMin = hhmmToMin(d.caffeineCutoff)
            evs += KxEvent(
                kind = "caff",
                title = KxText.s(R.string.ev_caff_title, d.day),
                description = KxText.s(R.string.ev_caff_desc, stage, d.theme),
                start = date.atTime(cMin / 60, cMin % 60), durationMin = 15, alarmMin = 0,
            )
            evs += KxEvent(
                kind = "wrap",
                title = KxText.s(R.string.ev_wrap_title, d.day),
                description = KxText.s(R.string.ev_wrap_desc, stage, d.theme),
                start = date.atTime(20, 0), durationMin = 30, alarmMin = 10,
            )
            if (d.day in plan.summary.checkpoints) {
                evs += KxEvent(
                    kind = "review",
                    title = KxText.s(R.string.ev_review_title, d.day),
                    description = KxText.s(R.string.ev_review_desc),
                    start = date.atTime(21, 0), durationMin = 20, alarmMin = 5,
                )
            }
        }
        return evs
    }
}
