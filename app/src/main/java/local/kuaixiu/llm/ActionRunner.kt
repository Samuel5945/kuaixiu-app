package local.kuaixiu.llm

import android.content.Context
import local.kuaixiu.calendar.CalendarWriter
import local.kuaixiu.data.Checkins
import local.kuaixiu.data.HabitCheck
import local.kuaixiu.data.Habits
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.Time
import local.kuaixiu.engine.Plan
import org.json.JSONObject
import java.time.LocalDate

/**
 * 提醒 / 习惯的写操作单点复用：AI 动作卡（ChatScreen.ActionCard）与手动表单（EditDialogs）都走这里，
 * 免得「同名=改、锚点间隔、上限 2、按标题逐字删」这些语义写两份、早晚走样。
 */
object ActionRunner {

    fun applyReminder(ctx: Context, dateIso: String, hhmm: String, text: String, leadMin: Int): Result<Long> =
        runCatching { CalendarWriter.addReminder(ctx, dateIso, hhmm, text, leadMin) }

    /** 按标题删；返回删掉的条数。 */
    fun cancelReminder(ctx: Context, text: String): Int = CalendarWriter.deleteRemindersByText(ctx, text)

    /** 建/改习惯（同名即改）；结果交由调用侧处理 QuotaFull 的二次确认。 */
    fun applyHabitAdd(ctx: Context, name: String, cueTime: String, days: Int,
                      startDate: String, ackCost: Boolean): HabitCheck =
        Habits.create(ctx, name, cueTime, days, startDate, ackCost = ackCost)

    /**
     * 改参数后的重算口径：**以今天为新基线重开**。动作没带 currentBedtime 就用最近一次实录的
     * 入睡当新基线，起始日落到今天，阶梯从今晚重新走——沿用旧起始日会让「第 10 天」的档位
     * 对着一个从没发生过的进度表，还会把已过去的几十条事件重新写回日历。
     *
     * 字段一律 `optString(k).ifBlank { 旧值 }`：模型给 `"targetBedtime":""` 时
     * `optString(k, 旧值)` 返回的是那个空串，会把配置写坏到重算都炸。
     */
    fun applyAdjust(ctx: Context, a: JSONObject, today: LocalDate = Time.today()): Plan {
        val cfg = KxStore.config
        val lastActual = Checkins.all(ctx).lastOrNull { it.bedtime.isNotBlank() }?.bedtime.orEmpty()
        KxStore.saveConfig(ctx, cfg.copy(
            currentBedtime = a.optString("currentBedtime").ifBlank { lastActual.ifBlank { cfg.currentBedtime } },
            targetBedtime = a.optString("targetBedtime").ifBlank { cfg.targetBedtime },
            wake = a.optString("wake").ifBlank { cfg.wake },
            planDays = if (a.has("days")) a.optInt("days", cfg.planDays) else cfg.planDays,
            startDate = a.optString("startDate").ifBlank { today.toString() },
        ))
        return KxStore.regenerate(ctx)
    }
}
