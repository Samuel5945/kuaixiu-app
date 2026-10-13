package local.kuaixiu.engine

import kotlinx.serialization.Serializable

@Serializable
data class PlanConfig(
    val currentBedtime: String,   // HH:MM，计划起点的入睡时间
    val targetBedtime: String,    // HH:MM，目标入睡
    val wake: String,             // HH:MM，起床锚点（全程固定）
    val startDate: String,        // ISO yyyy-MM-dd
    val planDays: Int,
    val winddownMin: Int,
    val enforcement: String = "gentle",
)

@Serializable
data class PlanSummary(
    val theme: String = "快休科学早睡计划",
    val totalShiftMinutes: Int,
    val daysToTarget: Int?,
    val checkpoints: List<Int>,
)

@Serializable
data class PlanDay(
    val day: Int,
    val date: String,        // ISO
    val weekday: String,
    val week: Int,
    val stage: String,       // 适应期 / 巩固期
    val theme: String,
    val bedtime: String,
    val winddown: String,
    val caffeineCutoff: String,
    val tasks: List<String>,
    val tip: String,
)

@Serializable
data class Plan(
    val version: Int = 2,
    val generator: String = "kuaixiu-app",
    val created: String,
    val config: PlanConfig,
    val summary: PlanSummary,
    /** 生成这份计划时 App 的语言（zh/en）。空 = 不是 App 生成的（PC 推来的），换语言时不重算。 */
    val lang: String = "",
    val days: List<PlanDay>,
) {
    /** 今天在计划中的档位；不在则 null。 */
    fun entryFor(dateIso: String): PlanDay? = days.firstOrNull { it.date == dateIso }

    /** 下一个未开始的档位（含今天）。 */
    fun upcoming(dateIso: String): PlanDay? = days.firstOrNull { it.date >= dateIso }
}

/** 一个日历事件（引擎产出的中间形态，CalendarWriter 负责落库）。 */
data class KxEvent(
    val kind: String,          // bed / wake / caff / wrap / review
    val title: String,
    val description: String,
    val start: java.time.LocalDateTime,
    val durationMin: Int,
    val alarmMin: Int,         // 提前多少分钟提醒
)
