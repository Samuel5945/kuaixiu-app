package local.kuaixiu.data

import android.content.Context
import local.kuaixiu.engine.PlanEngine
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 从本地记录拼出海报数据。**不经过任何模型**——连续天数、评分、放下手机时刻
 * 都取真值，模型只被允许画那张无字底图。
 */
object PosterBuild {

    private val fmtHM = DateTimeFormatter.ofPattern("HH:mm")
    private val fmtDateCn = DateTimeFormatter.ofPattern("M 月 d 日 · EEEE")
    private val fmtDot = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    /**
     * 单夜版（A/B/C）：**最近一次打卡的那一夜**，不是"今晚"。
     * 早上打卡时 tonightDate() 已经是昨晚，但傍晚再看 tonightDate() 就是今晚（还没发生、没数据），
     * 按今晚取会得到一张全是「—」的空海报。
     */
    fun tonight(ctx: Context): PosterData? {
        val plan = KxStore.plan ?: return null
        val cfg = KxStore.config
        val all = Checkins.all(ctx)
        val nightIso = all.maxByOrNull { it.date }?.date ?: Checkins.tonightDate().minusDays(1).toString()
        val night = LocalDate.parse(nightIso)
        val entry = plan.entryFor(nightIso) ?: plan.upcoming(nightIso) ?: return null
        val raw = all.firstOrNull { it.date == nightIso }
        // 跟首页评分卡同一套口径：风屏没记到时用 UsageProbe 现场补上，
        // 不然海报算出来会比 App 里低 30 分（风屏 0 分），同一夜两个分数
        val c = if (raw != null && raw.winddownAt.isBlank()) {
            winddownOf(ctx, entry)?.let { raw.copy(winddownAt = it) } ?: raw
        } else raw
        val r = Score.daily(c, entry.winddown, entry.bedtime, cfg.wake)
        return PosterData(
            streak = Score.streak(all, night),
            scoreTotal = r.total, scoreWinddown = r.winddown, scoreSleep = r.sleep, scoreWake = r.wake,
            phoneDown = phoneDownOf(ctx, c, entry),
            bedtime = c?.bedtime.orEmpty(),
            wakeup = c?.wakeupAt.orEmpty(),
            wakeAnchor = cfg.wake,
            targetBedtime = entry.bedtime,
            dayIndex = entry.day,
            planDays = plan.config.planDays,
            stage = entry.stage,
            dateCn = night.format(fmtDateCn),
            dateDot = night.format(fmtDot),
            weekLabel = "", weekDays = emptyList(), onTimeCount = 0, weekTotal = 0,
            avgPhoneDown = "", avgScore = 0, rateHit = 0, rateElapsed = 0, quote = "",
        )
    }

    /** 周报版（D）：最近 7 个计划日。 */
    fun week(ctx: Context): PosterData? {
        val plan = KxStore.plan ?: return null
        val cfg = KxStore.config
        val all = Checkins.all(ctx)
        val byDate = all.associateBy { it.date }
        val today = Time.today()
        val reported = Checkins.tonightDate().minusDays(1).toString()
        // 只统计"已经报过结果"的夜：今晚这一夜要明早才打卡，放进周报会拉低平均分、还多出一根 0 分空条
        val days = plan.days.filter { it.date <= today.toString() && it.date <= reported }.takeLast(7)
        if (days.isEmpty()) return null
        val lines = days.map { d ->
            val c = byDate[d.date]
            val r = Score.daily(c, d.winddown, d.bedtime, cfg.wake)
            PosterLine(
                weekday = d.weekday.takeLast(1),
                date = d.date.removePrefix("20").replace('-', '-'),
                phoneDown = c?.phoneDownAt.orEmpty(),
                bedtime = c?.bedtime.orEmpty(),
                score = r.total,
                status = c?.status.orEmpty(),
            )
        }
        val onTime = days.count { byDate[it.date]?.status == "on_time" }
        val phones = lines.mapNotNull { it.phoneDown }.filter { it.isNotBlank() }
        // 页脚/目标用「今天」这一档，不是这一周最后一条已报的夜
        val todayEntry = plan.entryFor(today.toString()) ?: days.last()
        val first = days.first().date
        val last = days.last().date
        val lateDays = lines.count { it.status == "late" }
        return PosterData(
            streak = Score.streak(all, Checkins.tonightDate()),
                scoreTotal = 0, scoreWinddown = 0, scoreSleep = 0, scoreWake = 0,
                phoneDown = "", bedtime = "", wakeup = "", wakeAnchor = cfg.wake,
                targetBedtime = todayEntry.bedtime,
                dayIndex = todayEntry.day, planDays = plan.config.planDays, stage = todayEntry.stage,
                dateCn = "", dateDot = "",
                weekLabel = "${mmdd(first)} – ${mmdd(last)} · 第 ${days.first().week} 周",
                weekDays = lines,
                onTimeCount = onTime, weekTotal = days.size,
                avgPhoneDown = avgClock(phones),
                avgScore = if (lines.isEmpty()) 0 else lines.sumOf { it.score } / lines.size,
                // 达成率只看这周已过的夜（今晚要明早才打卡，不能算进去）——不然 2/3 会让标题的 2/2 自相矛盾
                rateHit = onTime, rateElapsed = days.size,
                quote = when {
                    onTime == days.size -> "$onTime 晚一晚没落下，节奏已经稳了，保持。"
                    lateDays == 1 -> "只有一晚晚了一回——单晚破功什么都不用改，接着来就行。"
                    lateDays >= 2 -> "这周有 $lateDays 晚偏晚：连续多晚就要调计划档位，别硬扛。"
                    else -> "记录不全就是执行不全，先把打卡补齐。"
                },
        )
    }

    /** 放下手机时刻：优先用打卡里存下的（自动探测），没有就现算昨晚的。 */
    private fun phoneDownOf(ctx: Context, c: Checkin?, entry: local.kuaixiu.engine.PlanDay): String {
        c?.phoneDownAt?.takeIf { it.isNotBlank() }?.let { return it }
        return factsOf(ctx, entry)?.let { UsageProbe.estimateOnset(it)?.format(fmtHM) } ?: ""
    }

    /** 风屏开始（最后熄屏）：跟首页评分卡一样现场补，老打卡里没有这个字段。 */
    private fun winddownOf(ctx: Context, entry: local.kuaixiu.engine.PlanDay): String? =
        factsOf(ctx, entry)?.lastScreenOff?.format(fmtHM)

    private fun factsOf(ctx: Context, entry: local.kuaixiu.engine.PlanDay): UsageProbe.NightFacts? {
        if (!UsageProbe.hasAccess(ctx)) return null
        return runCatching {
            UsageProbe.nightFacts(
                ctx,
                PlanEngine.winddownStartOf(entry, KxStore.config.winddownMin),
                Time.zoned(LocalDate.parse(entry.date), LocalTime.parse(KxStore.config.wake)).toLocalDateTime(),
                Time.now().toLocalDateTime(),
            )
        }.getOrNull()
    }

    /** 一串 HH:MM 求平均（跨午夜按最短弧），空则返回空串。 */
    internal fun avgClock(times: List<String>): String {
        val mins = times.mapNotNull { runCatching { LocalTime.parse(it).toSecondOfDay() / 60 }.getOrNull() }
        if (mins.isEmpty()) return ""
        // 以第一个为参考做"最短弧"展开，避免 23:50 和 00:10 被平均成 12:00
        val base = mins.first()
        val unwrapped = mins.map { m ->
            var v = m - base
            if (v > 720) v -= 1440
            if (v < -720) v += 1440
            v
        }
        val avg = ((base + unwrapped.average()).mod(1440.0)).toInt()
        return "%02d:%02d".format(avg / 60, avg % 60)
    }

    private fun mmdd(iso: String): String = iso.removePrefix("20").replace('-', '.')
}
