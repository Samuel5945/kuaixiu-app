package local.kuaixiu.data

import android.content.Context
import local.kuaixiu.R
import local.kuaixiu.engine.PlanEngine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 从本地记录拼出海报数据。**不经过任何模型**——连续天数、评分、放下手机时刻
 * 都取真值，模型只被允许画那张无字底图。
 */
object PosterBuild {

    private val fmtHM = DateTimeFormatter.ofPattern("HH:mm")
    private val fmtDot = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    /** 海报上要按系统语言出：中文「10 月 13 日 · 星期一」，英文「Mon, Oct 13」。 */
    private fun textLocale(): Locale = if (KxText.english) Locale.ENGLISH else Locale.getDefault()

    private fun dateLine(night: LocalDate): String =
        night.format(DateTimeFormatter.ofPattern(KxText.s(R.string.poster_date_fmt), textLocale()))

    /** 周报每根条左边的星期：横条是按「一个字」的宽度摆的，中英都取最窄形式（一 / M）。 */
    private fun weekdayMark(iso: String, fallback: String): String =
        runCatching { LocalDate.parse(iso).dayOfWeek.getDisplayName(TextStyle.NARROW, textLocale()) }
            .getOrElse { fallback }

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
            stage = PlanEngine.phaseLabel(entry.stage),
            dateCn = dateLine(night),
            dateDot = night.format(fmtDot),
            weekLabel = "", weekDays = emptyList(), onTimeCount = 0, weekTotal = 0,
            avgPhoneDown = "", avgScore = 0, rateHit = 0, rateElapsed = 0, quote = "",
        )
    }

    /** 周报版（D）：最近 7 个计划日。 */
    fun week(ctx: Context, now: ZonedDateTime = Time.now()): PosterData? {
        val plan = KxStore.plan ?: return null
        val cfg = KxStore.config
        val all = Checkins.all(ctx)
        val byDate = all.associateBy { it.date }
        val today = now.toLocalDate()
        val reported = weekCutoff(all.map { it.date }, now)
        // 只统计"已经报过结果"的夜：今晚这一夜要明早才打卡，放进周报会拉低平均分、还多出一根 0 分空条
        val days = plan.days.filter { it.date <= reported }.takeLast(7)
        if (days.isEmpty()) return null
        val lines = days.map { d ->
            val raw = byDate[d.date]
            // 跟首页/A 版同一套口径：老打卡里风屏没记到时现场用 UsageProbe 补，
            // 否则同一夜首页显示 100 分、周报这根条只有 70 分。
            val c = if (raw != null && raw.winddownAt.isBlank()) {
                winddownOf(ctx, d)?.let { raw.copy(winddownAt = it) } ?: raw
            } else raw
            PosterLine(
                weekday = weekdayMark(d.date, d.weekday),
                date = d.date.takeLast(5),
                // 没打卡的那一晚不去探测：那晚本来就没有"放下手机"这回事
                phoneDown = if (c == null) "" else phoneDownOf(ctx, c, d),
                bedtime = c?.bedtime.orEmpty(),
                score = Score.daily(c, d.winddown, d.bedtime, cfg.wake).total,
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
            streak = Score.streak(all, Checkins.tonightDate(now)),
                scoreTotal = 0, scoreWinddown = 0, scoreSleep = 0, scoreWake = 0,
                phoneDown = "", bedtime = "", wakeup = "", wakeAnchor = cfg.wake,
                targetBedtime = todayEntry.bedtime,
                dayIndex = todayEntry.day, planDays = plan.config.planDays,
                stage = PlanEngine.phaseLabel(todayEntry.stage),
                dateCn = "", dateDot = "",
                weekLabel = KxText.s(R.string.poster_week_label, mmdd(first), mmdd(last), days.first().week),
                weekDays = lines,
                onTimeCount = onTime, weekTotal = days.size,
                avgPhoneDown = avgClock(phones),
                avgScore = if (lines.isEmpty()) 0 else lines.sumOf { it.score } / lines.size,
                // 达成率只看这周已过的夜（今晚要明早才打卡，不能算进去）——不然 2/3 会让标题的 2/2 自相矛盾
                rateHit = onTime, rateElapsed = days.size,
                quote = when {
                    onTime == days.size -> KxText.s(R.string.poster_quote_all, onTime)
                    lateDays == 1 -> KxText.s(R.string.poster_quote_one_late)
                    lateDays >= 2 -> KxText.s(R.string.poster_quote_late, lateDays)
                    else -> KxText.s(R.string.poster_quote_missing)
                },
        )
    }

    /**
     * 周报的右端：最后一个「已经过完、并且报过结果」的夜。
     *
     * 中午 12 点前 [Checkins.tonightDate] 给出的就是昨晚（人刚醒，今早可以打卡），不能再减一天，
     * 否则早上打完卡看周报，昨晚永远不在图里；中午之后它是今晚，还没发生，右端才要退到昨晚。
     * 再和最近一次打卡取更早的那个：没打卡的那一晚画进周报只会是一根 0 分空条。
     */
    internal fun weekCutoff(logged: Collection<String>, now: ZonedDateTime = Time.now()): String {
        val lastEnded = if (now.hour >= 12) Checkins.tonightDate(now).minusDays(1) else Checkins.tonightDate(now)
        val lastLogged = logged.mapNotNull { d -> runCatching { LocalDate.parse(d) }.getOrNull() }.maxOrNull()
        return (if (lastLogged == null || lastLogged > lastEnded) lastEnded else lastLogged).toString()
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
