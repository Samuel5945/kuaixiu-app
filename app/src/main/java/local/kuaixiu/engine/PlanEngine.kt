package local.kuaixiu.engine

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 快休计划引擎（从 scripts/plan_engine.py 移植，规则一一对应，见 reference/science-rules.md）：
 * 起床锚点固定；入睡每 3 天向目标前移 15 分钟（先移动再落位，最后一天恰好到达）；
 * 咖啡因截断 = 睡前 8 小时（限 11:00–16:30）；跨午夜入睡(<12:00)属次日历日。
 */
object PlanEngine {

    val WEEKDAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    val THEMES = mapOf(
        1 to "锚定起床与睡前仪式",
        2 to "光照与咖啡因",
        3 to "屏幕与卧室环境",
        4 to "巩固与破功预案",
    )

    val TASK_POOL = mapOf(
        1 to listOf(
            "固定起床时间到！昨晚几点睡的？给自己打个分（1-5分）",
            "起床后 30 分钟内拉开窗帘/到窗边待 10 分钟",
            "睡前把手机放到离床 2 米外",
            "睡前洗漱 + 5 分钟简单拉伸，固定仪式顺序",
            "列出明天最重要的 1 件事，写完就不再想",
        ),
        2 to listOf(
            "白天光照 ≥ 30 分钟（散步/晒太阳都算）",
            "14:00 后不再碰咖啡/浓茶/可乐",
            "晚饭不吃太晚太饱，睡前 2 小时结束进食",
            "今晚不喝酒（酒精毁掉深睡眠）",
            "下午困了就起身走 5 分钟，别靠咖啡续命",
        ),
        3 to listOf(
            "睡前 45 分钟进入暖光模式：大灯换小灯",
            "把明早要用的东西提前收拾好，减少早起决策",
            "卧室调凉一点（18–22°C 更易入睡）",
            "睡前把手机设为勿扰，通知清零",
            "躺下后不刷手机；睡不着就 4-7-8 呼吸法",
        ),
        4 to listOf(
            "连续 3 天在目标时间前 15 分钟上床，巩固成果",
            "想出这周最容易破功的场景，写下一个应对动作",
            "周末作息浮动不超过 ±30 分钟",
            "把成功的一周告诉一个朋友（社会承诺）",
            "给自己定一个 28 天完成奖励",
        ),
    )

    val TIP_POOL = listOf(
        "手机放离床 2 米外，刷手机欲望直接减半",
        "早起一杯温水 + 10 分钟晨光照射，当晚入睡会更顺利",
        "躺下睡不着？试试 4-7-8 呼吸法：吸气 4 秒-憋 7 秒-呼气 8 秒",
        "睡前 30 分钟做拉伸或写日记，比刷手机助眠 10 倍",
        "周末也保持 ±30 分钟作息浮动，别报复性熬夜",
        "固定仪式的顺序比时长更重要，让身体学会'这套动作=要睡了'",
    )

    fun hhmmToMin(s: String): Int {
        val parts = s.trim().split(":")
        val v = parts[0].toInt() * 60 + parts[1].toInt()
        require(v in 0..1439) { "非法时间: $s" }
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
    fun parseDateLenient(s: String, today: LocalDate = LocalDate.now()): LocalDate {
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

    fun generate(cfg: PlanConfig, today: LocalDate = LocalDate.now()): Plan {
        val start = parseDateLenient(cfg.startDate, today)
        val targetMin = hhmmToMin(cfg.targetBedtime)
        val bedtimes = ladder(cfg.currentBedtime, cfg.targetBedtime, cfg.planDays)
        val windMin = cfg.winddownMin

        val days = bedtimes.mapIndexed { i, b ->
            val n = i + 1
            val date = start.plusDays(i.toLong())
            val week = minOf(4, i / 7 + 1)
            val stage = if (b == targetMin) "巩固期" else "适应期"
            PlanDay(
                day = n,
                date = date.toString(),
                weekday = WEEKDAY_CN[date.dayOfWeek.value - 1],
                week = week,
                stage = stage,
                theme = THEMES[week]!!,
                bedtime = minToHHMM(b),
                winddown = minToHHMM(b - windMin),
                caffeineCutoff = minToHHMM(caffeineCutoff(b)),
                tasks = List(3) { k -> TASK_POOL[week]!![(i * 3 + k) % TASK_POOL[week]!!.size] },
                tip = TIP_POOL[i % TIP_POOL.size],
            )
        }

        var shift = (hhmmToMin(cfg.currentBedtime) - targetMin).mod(1440)
        if (shift > 720) shift = 1440 - shift
        val checkpoints = sortedSetOf(7, 14, 21, 28, cfg.planDays)
            .filter { it in 1..cfg.planDays }

        return Plan(
            created = LocalDateTime.now().toString(),
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
            val stage = d.stage

            evs += KxEvent(
                kind = "bed",
                title = "第${d.day}天 | 睡前准备 目标${d.bedtime}入睡",
                description = "【${stage}·${d.theme}】\n目标入睡时间：${d.bedtime}\n\n行动清单：\n" +
                    d.tasks.mapIndexed { i, t -> "  ${i + 1}. $t" }.joinToString("\n") +
                    "\n\n小提示：${d.tip}",
                start = bedStart, durationMin = windMin, alarmMin = 5,
            )
            evs += KxEvent(
                kind = "wake",
                title = "第${d.day}天 | 起床啦",
                description = "【${stage}·${d.theme}】固定起床时间到！昨晚几点睡的？给自己打个分（1-5分）",
                start = date.atTime(wakeMin / 60, wakeMin % 60), durationMin = 15, alarmMin = 5,
            )
            val cMin = hhmmToMin(d.caffeineCutoff)
            evs += KxEvent(
                kind = "caff",
                title = "第${d.day}天 | 咖啡因截断",
                description = "【${stage}·${d.theme}】从现在起不碰咖啡/浓茶/可乐——离入睡已不足 8 小时。",
                start = date.atTime(cMin / 60, cMin % 60), durationMin = 15, alarmMin = 0,
            )
            evs += KxEvent(
                kind = "wrap",
                title = "第${d.day}天 | 今晚收尾规划",
                description = "【${stage}·${d.theme}】列出今晚最多 3 件收尾事项，做完就允许自己休息。",
                start = date.atTime(20, 0), durationMin = 30, alarmMin = 10,
            )
            if (d.day in plan.summary.checkpoints) {
                evs += KxEvent(
                    kind = "review",
                    title = "第${d.day}天 | 周复盘",
                    description = "这周早睡达成几天？最大障碍是什么？下周怎么调整？\n坚持住，习惯正在形成！",
                    start = date.atTime(21, 0), durationMin = 20, alarmMin = 5,
                )
            }
        }
        return evs
    }
}
