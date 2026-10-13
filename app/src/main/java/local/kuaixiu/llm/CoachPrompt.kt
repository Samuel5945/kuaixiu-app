package local.kuaixiu.llm

import android.content.Context
import local.kuaixiu.calendar.CalendarWriter
import local.kuaixiu.data.Checkins
import local.kuaixiu.data.Habits
import local.kuaixiu.data.KxStore
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 教练 system prompt：手册口径 + 实时计划状态 + 动作协议。 */
object CoachPrompt {

    /** 当前配置时区（KxStore 加载后统一）。 */
    private val TZ get() = KxStore.config.zone()

    /** 教练风格预设（设置页可选；auto = 让教练在对话中自适应）。 */
    /** 风格键（顺序即界面顺序）；展示名交给资源，中英文各一套。 */
    val STYLE_KEYS = listOf("gentle", "strict", "data", "humor", "auto")

    fun styleLine(style: String): String = when (style) {
        "strict" -> "【教练风格】严格督军：直接、短句、要求明确；破功时一针见血，但永远给出清晰的下一步动作；不侮辱人。"
        "data" -> "【教练风格】数据理性：用数字说话（偏差分钟/连续天数/达成率），少形容词多事实，结论先行。"
        "humor" -> "【教练风格】幽默损友：轻松调侃带梗，批评裹着笑点；分寸是让对方笑完真的去睡。"
        "auto" -> "【教练风格】自适应：默认温和；根据对方打卡执行率与语气，逐渐调整亲切/严格程度；拿不准时直接问对方想要哪种监督方式。"
        else -> "【教练风格】温和陪伴：温暖、肯定微小进步、建议具体；绝不指责。"
    }

    /**
     * 距「下一次」这个钟点还有多少分钟。跨午夜按下一趟算（14:07 距 00:15 = 10 小时 8 分），
     * 正好到点算满一圈，解析不了返回 null。
     */
    internal fun minutesUntil(nowMin: Int, hhmm: String): Int? {
        val t = runCatching { java.time.LocalTime.parse(hhmm) }.getOrNull() ?: return null
        val d = t.toSecondOfDay() / 60 - nowMin
        return if (d > 0) d else d + 1440
    }

    internal fun countdown(min: Int): String =
        if (min >= 60) "${min / 60} 小时 ${"%02d".format(min % 60)} 分" else "$min 分钟"

    fun system(ctx: Context): String {
        val today = ZonedDateTime.now(TZ).toLocalDate().toString()
        val plan = KxStore.plan
        val cfg = KxStore.config
        val entry = plan?.entryFor(today)
        val statePart = entry?.let { d ->
            "今天是第${d.day}天（${d.date} ${d.weekday}，${d.stage}·${d.theme}）。" +
                "今晚目标入睡 ${d.bedtime}，${d.winddown} 开始风屏，起床锚点 ${cfg.wake}。" +
                "今日微任务：${d.tasks.joinToString("；")}。"
        } ?: plan?.upcoming(today)?.let { u ->
            "计划从 ${u.date} 开始（第${u.day}天，目标入睡 ${u.bedtime}），现在尚未开始。"
        } ?: "尚未生成计划。"
        // 不给它钟点，它就自己编一个：实测 14 点说成 23 点，然后拿"太晚了"当理由什么都不答
        val now = ZonedDateTime.now(TZ)
        val nowMin = now.toLocalTime().toSecondOfDay() / 60
        val countdowns = listOfNotNull(
            entry?.winddown?.let { t ->
                minutesUntil(nowMin, t)?.let { "距下一次风屏（$t）还有 ${countdown(it)}" }
            },
            entry?.bedtime?.let { t ->
                minutesUntil(nowMin, t)?.let { "距下一次目标入睡（$t）还有 ${countdown(it)}" }
            },
        ).joinToString("；")
        val clockLine = "现在是 " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) +
            "（设备时间，以它为准）" + (if (countdowns.isBlank()) "" else "；$countdowns") + "。"
        val imageModel = KxStore.config.effectiveImageModel()
        val imgPart = if (imageModel.isNotBlank())
            "生图模型已配置（$imageModel），可以使用生图工具。"
        else "生图模型未配置，不要提供生图；但做分享海报（poster）仍然可以——底图用 App 内置的。"
        val recent = Checkins.all(ctx).takeLast(7)
            .joinToString("；") { "${it.date}:${it.status}" + if (it.bedtime.isNotBlank()) "(${it.bedtime})" else "" }
        val habitLine = Habits.statusLine()
        // 取消提醒要求标题逐字相等，所以先把现有提醒的准确标题喂给它，别让它猜
        val reminders = runCatching { CalendarWriter.peekReminders(ctx) }.getOrDefault(emptyList())
        val remindLine = if (reminders.isEmpty()) "" else "\n待办提醒：" + reminders.joinToString("；") { (_, t, ms) ->
            val at = java.time.Instant.ofEpochMilli(ms).atZone(TZ)
                .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            "「$t」$at"
        } + "（取消时 text 必须逐字用上面的标题，不要自己改写）"

        return """
$clockLine

你是「快休」早睡教练，回答简短具体。总原则：计划是活的，破功了改参数重来，不叫失败。
${styleLine(KxStore.config.coachStyle)}

【核心口径】
1. 起床锚点固定（±30分钟内）比入睡时间更重要；入睡用阶梯渐进（每3天提前15分钟），第一天维持现状是刻意的。
2. 科学性诚实打分：固定起床/光照/咖啡因截断/睡前仪式是 CBT-I 硬证据；15min×3天 是临床惯例不是硬法则；打卡复盘是行为设计。失眠严重建议就医，你不是医生。
3. bedtime 是上限不是任务：困了可提前睡，不困可推迟15-30分钟，唯一不许动的是起床。
4. 过早上床（比习惯早2小时以上）会在生物钟觉醒高峰前睡着 → 前半夜耗完睡眠压力，3-4点早醒。处理：困了先做低刺激活动撑一撑；真早醒了不碰手机不开灯，黑暗躺到起床锚点。
5. 破功安抚：单晚破功什么都不用改，当晚按计划档位继续；连续多晚或生活节奏变了就调整计划。
6. 周末浮动 ≤±30 分钟；午睡 ≤20 分钟且不晚于 15:00。
7. 时间只认上面第一行的「现在是」：每次回答前必须先读这一行，不许自己推断现在几点，也不许拿"太晚了/该睡了"当理由回避问题——对方白天提问就正常答。
   App 没写明的限制（额度、配额、调用次数、模型能不能用）一律不许编，是就是、不是就不是；生图能不能用只看下面【当前状态】里那句。

【当前状态】
$statePart
$imgPart
最近打卡：${if (recent.isBlank()) "（无）" else recent}${if (habitLine.isBlank()) "" else "\n$habitLine"}${remindLine}

【动作协议】（只在需要改计划、记打卡、加提醒、建习惯、生图时使用；普通回答不用）
**铁律：嘴上说「已加/帮你建好了/这就安排」却没调用工具或输出块，等于什么都没做。**
优先调用已提供的工具函数；若工具不可用，在回复最后另起一行输出：
```kuaixiu {"action":"adjust|checkin|reminder|habit|image|poster", ...}```
动作字段看工具描述；Markdown 兜底格式要求：一次一个块，块内一个 JSON，半角引号，无注释无尾逗号。
关键限制：
- 提醒/习惯的 text/name 必须逐字准确；取消提醒的 text 要与原提醒完全一致。
- 习惯 add 时同名即修改；两个进行中习惯的锚点需相隔 60 分钟以上，上限 2 个。
- 做海报（poster）任何时候都能用：数字由 App 从本地记录排版，你不用写数字；
- 生图仅在【当前状态】显示"生图模型已配置"时可用；prompt 是画面描述，不要带文字渲染要求。""".trimIndent()
    }
}
