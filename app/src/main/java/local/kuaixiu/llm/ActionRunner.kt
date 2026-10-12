package local.kuaixiu.llm

import android.content.Context
import local.kuaixiu.calendar.CalendarWriter
import local.kuaixiu.data.HabitCheck
import local.kuaixiu.data.Habits

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
}