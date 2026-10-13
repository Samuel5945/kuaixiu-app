package local.kuaixiu.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.kuaixiu.R
import local.kuaixiu.calendar.CalendarWriter
import local.kuaixiu.engine.PlanEngine
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable
data class Habit(
    val id: String,
    val name: String,
    val cueTime: String,      // 时间锚点 HH:MM
    val days: Int,
    val startDate: String,    // 第 1 天（ISO）
    val enabled: Boolean = true,
    val eventId: Long = 0L,   // 「快休习惯」日历里那条 RRULE 事件
)

/** 创建结果：Ok 之外都是"没建成"，QuotaFull 可以被用户确认代价后重试。 */
sealed class HabitCheck {
    data class Ok(val habit: Habit) : HabitCheck()
    data class Invalid(val reason: String) : HabitCheck()
    data class AnchorConflict(val otherName: String, val gapMin: Int) : HabitCheck()
    data class QuotaFull(val activeNames: List<String>) : HabitCheck()
}

/**
 * 习惯清单（filesDir JSON）+ 打卡（另一份 JSON，按 habitId 维度）。
 * 睡眠打卡走 Checkins，两者不共用文件，免得习惯记录混进评分。
 */
object Habits {

    /** 并行上限：第 3 个不禁止，但必须先确认代价。 */
    const val MAX_ACTIVE = 2

    /** 锚点最小间隔（分钟）：执行意图互相干扰是"少一点"的唯一实证理由，所以近邻直接拦。 */
    const val ANCHOR_MIN_GAP = 60

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    var items by mutableStateOf<List<Habit>>(emptyList())
        private set
    var doneDates by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set

    private fun habitsFile(ctx: Context) = File(ctx.filesDir, "habits.json")
    private fun checkinFile(ctx: Context) = File(ctx.filesDir, "habit_checkins.json")

    fun load(ctx: Context) {
        items = runCatching {
            val f = habitsFile(ctx)
            if (f.exists()) json.decodeFromString<List<Habit>>(f.readText()) else emptyList()
        }.getOrDefault(emptyList())
        doneDates = runCatching {
            val f = checkinFile(ctx)
            if (f.exists()) json.decodeFromString<List<HabitMark>>(f.readText())
                .groupBy { it.habitId }.mapValues { e -> e.value.map { it.date }.toSet() }
            else emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun saveHabits(ctx: Context, list: List<Habit>) {
        items = list
        habitsFile(ctx).writeText(json.encodeToString(list))
    }

    private fun saveMarks(ctx: Context, marks: List<HabitMark>) {
        doneDates = marks.groupBy { it.habitId }.mapValues { e -> e.value.map { it.date }.toSet() }
        checkinFile(ctx).writeText(json.encodeToString(marks))
    }

    @Serializable
    private data class HabitMark(val habitId: String, val date: String)

    val active: List<Habit> get() = items.filter { it.enabled }

    fun findByName(text: String): Habit? {
        val t = text.trim()
        if (t.isBlank()) return null
        return items.firstOrNull { it.name == t }
            ?: items.firstOrNull { it.name.contains(t) || t.contains(it.name) }
    }

    /** 今天是这个习惯的第几天：未开始 0，最后一天后仍是 days。 */
    fun dayOf(h: Habit, today: LocalDate = Time.today()): Int = runCatching {
        val s = LocalDate.parse(h.startDate)
        if (today.isBefore(s)) 0
        else (ChronoUnit.DAYS.between(s, today).toInt() + 1).coerceAtMost(h.days)
    }.getOrDefault(0)

    /** 计划期是否已走完（走完后不再提醒，只留复盘/删除）。 */
    fun expired(h: Habit, today: LocalDate = Time.today()): Boolean = runCatching {
        !today.isBefore(LocalDate.parse(h.startDate).plusDays(h.days.toLong()))
    }.getOrDefault(false)

    fun streakOf(h: Habit, today: LocalDate = Time.today()): Int =
        Score.streakOn(doneDates[h.id].orEmpty(), today)

    fun completionOf(h: Habit, today: LocalDate = Time.today()): Pair<Int, Int> =
        Score.completion(doneDates[h.id].orEmpty(), LocalDate.parse(h.startDate), h.days, today)

    /** 教练给的口语化时间统一成 HH:MM，否则日历那侧 LocalTime.parse 会炸。 */
    private fun normalizeCue(s: String): String? = runCatching {
        val p = s.trim().split(":")
        if (p.size != 2) return null
        val h = p[0].trim().toInt()
        val m = p[1].trim().toInt()
        if (h !in 0..23 || m !in 0..59) null else PlanEngine.minToHHMM(h * 60 + m)
    }.getOrNull()

    /** 只查冲突，不写任何东西（教练卡片先问一遍，用户点确认才落盘）。 */
    fun check(existing: List<Habit>, name: String, cueTime: String, days: Int,
              startDate: String, activeLimit: Int = MAX_ACTIVE, ackCost: Boolean = false,
              today: LocalDate = Time.today()): HabitCheck {
        if (name.isBlank()) return HabitCheck.Invalid(KxText.s(R.string.habit_err_name_empty))
        val cue = normalizeCue(cueTime) ?: return HabitCheck.Invalid(KxText.s(R.string.habit_err_cue_fmt))
        val start = runCatching { LocalDate.parse(startDate.trim()) }.getOrNull()
            ?: return HabitCheck.Invalid(KxText.s(R.string.habit_err_date_fmt))
        if (days <= 0) return HabitCheck.Invalid(KxText.s(R.string.habit_err_days))
        // 同名 = 改这个习惯的参数（教练没有 edit 动作，"改成从明天开始"只会再发一次 add）
        val twin = existing.firstOrNull { it.name.trim() == name.trim() }
        val others = existing.filter { it.enabled && it.id != twin?.id && !expired(it, today) }
        others.forEach { o ->
            val gap = kotlin.math.abs(Score.diffMinutes(o.cueTime, cue) ?: 0)
            if (gap < ANCHOR_MIN_GAP) return HabitCheck.AnchorConflict(o.name, gap)
        }
        if (twin == null && !ackCost && others.size >= activeLimit) {
            return HabitCheck.QuotaFull(others.map { it.name })
        }
        return HabitCheck.Ok(Habit(
            id = twin?.id ?: "h" + java.util.UUID.randomUUID().toString().take(8),
            name = name.trim(), cueTime = cue, days = days.coerceAtMost(365),
            startDate = start.toString(), enabled = true,
        ))
    }

    /** 建习惯：校验通过才写日历（一条 RRULE 事件）。同名则改参数并重写那条事件。 */
    fun create(ctx: Context, name: String, cueTime: String, days: Int, startDate: String,
               ackCost: Boolean = false): HabitCheck {
        val res = check(items, name, cueTime, days, startDate, ackCost = ackCost)
        if (res !is HabitCheck.Ok) return res
        val h = res.habit
        val twin = items.any { it.id == h.id }
        if (twin) runCatching {
            items.first { it.id == h.id }.eventId.takeIf { it != 0L }?.let { CalendarWriter.deleteHabitEvent(ctx, it) }
        }
        val evtId = CalendarWriter.addHabitEvent(ctx, h.name, h.cueTime, h.startDate, h.days)
        val saved = h.copy(eventId = evtId)
        saveHabits(ctx, if (twin) items.map { if (it.id == h.id) saved else it } else items + saved)
        return HabitCheck.Ok(saved)
    }

    /** 今天打卡（重复点不产生第二条）。 */
    fun markDone(ctx: Context, id: String, date: String = Time.today().toString()) {
        val marks = checkinMarks(ctx)
        if (marks.any { it.habitId == id && it.date == date }) return
        saveMarks(ctx, marks + HabitMark(id, date))
    }

    fun isDoneToday(id: String, today: String = Time.today().toString()): Boolean =
        doneDates[id]?.contains(today) == true

    private fun checkinMarks(ctx: Context): List<HabitMark> = runCatching {
        val f = checkinFile(ctx)
        if (f.exists()) json.decodeFromString<List<HabitMark>>(f.readText()) else emptyList()
    }.getOrDefault(emptyList())

    /**
     * 暂停 = 删日历事件、留记录；恢复 = 从今天重启、只补剩下的天数。
     * 中断本身不影响养成，所以不惩罚：已打过的卡全部保留。
     */
    fun setEnabled(ctx: Context, id: String, on: Boolean): HabitCheck {
        val h = items.firstOrNull { it.id == id }
            ?: return HabitCheck.Invalid(KxText.s(R.string.habit_err_not_found))
        if (on) {
            val onAir = items.filter { it.enabled && it.id != id && !expired(it) }
            onAir.forEach { o ->
                val gap = kotlin.math.abs(Score.diffMinutes(o.cueTime, h.cueTime) ?: 0)
                if (gap < ANCHOR_MIN_GAP) return HabitCheck.AnchorConflict(o.name, gap)
            }
            if (onAir.size >= MAX_ACTIVE) return HabitCheck.QuotaFull(onAir.map { it.name })
            val elapsed = dayOf(h).coerceAtLeast(1)
            val left = (h.days - elapsed + 1).coerceAtLeast(1)
            val today = Time.today().toString()
            val evtId = CalendarWriter.addHabitEvent(ctx, h.name, h.cueTime, today, left)
            saveHabits(ctx, items.map {
                if (it.id == id) it.copy(enabled = true, startDate = today, days = left, eventId = evtId) else it
            })
        } else {
            if (h.eventId != 0L) runCatching { CalendarWriter.deleteHabitEvent(ctx, h.eventId) }
            saveHabits(ctx, items.map { if (it.id == id) it.copy(enabled = false, eventId = 0L) else it })
        }
        return HabitCheck.Ok(items.first { it.id == id })
    }

    /** 删除习惯：日历事件、记录、打卡一起清掉，不留残留。 */
    fun remove(ctx: Context, id: String): Boolean {
        val h = items.firstOrNull { it.id == id } ?: return false
        if (h.eventId != 0L) runCatching { CalendarWriter.deleteHabitEvent(ctx, h.eventId) }
        saveHabits(ctx, items.filterNot { it.id == id })
        saveMarks(ctx, checkinMarks(ctx).filterNot { it.habitId == id })
        return true
    }

    /** 状态行注入 system prompt 用：进行中习惯的第 n/N 天 + 连续 m 天。 */
    fun statusLine(onAir: List<Habit> = items, today: LocalDate = Time.today()): String {
        val live = onAir.filter { it.enabled && !expired(it, today) }
        if (live.isEmpty()) return ""
        return "进行中的习惯：" + live.joinToString("；") { h ->
            val n = dayOf(h, today)
            "${h.name} " + (if (n == 0) "还没开始（${h.startDate} 起，锚点 ${h.cueTime}）"
                else "第 $n/${h.days} 天（锚点 ${h.cueTime}），连续 ${streakOf(h, today)} 天" +
                    (if (isDoneToday(h.id, today.toString())) "，今日已打卡" else "，今日未打卡"))
        }
    }
}
