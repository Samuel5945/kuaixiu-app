package local.kuaixiu.llm

import local.kuaixiu.data.KxConfig
import local.kuaixiu.engine.PlanEngine
import java.time.LocalDate
import java.time.LocalTime

/** 离线教练的回复：正文 + 可选动作（与 AI 产出的动作协议同形，交给 ActionCard 应用）。 */
data class LocalReply(val text: String, val actionJson: String? = null)

/**
 * 没有 LLM Key 时的本地教练：中文关键词/正则识别意图，产出与 [CoachPrompt] 动作协议
 * **逐字一致**的 JSON，因此 ActionCard 的执行链零改动复用。
 *
 * 纯粹是「规则匹配」：识别不了的必须诚实说不知道，绝不假装"已做了"——那正是 AI 想糊弄时的病。
 */
object LocalCoach {

    // ---- 意图关键词（顺序即优先级） ----
    private val RE_CANCEL = Regex("(删|取消|不用|去掉|别提醒|不要了)")
    private val RE_REMIND = Regex("(提醒|叫我|记一下|别忘了|别忘|记着)")
    private val RE_CHECKIN = Regex("(昨晚|睡了|入睡|几点睡|睡的)")
    private val RE_ADJUST = Regex("(目标|改成|改到|调到|调整|推迟|提前|起床|入睡时间|计划天数|重新定)")
    private val RE_IMAGE = Regex("(画一?张|画个|画一下|助眠图|晚安图|助眠画面|插画|配图)")
    private val RE_DONE = Regex("(做到了|完成了|打卡了|打过卡|做了|完成打卡)")
    private val RE_PAUSE = Regex("(暂停|先不提醒|停一下|停下)")
    private val RE_HABIT = Regex("(习惯|养成|培养)")

    /** 放不下手机/开始风屏：一句话就能记，不需要时间。 */
    private val RE_WINDDOWN = Regex("(风屏|放下手机|不玩手机|关屏幕)")

    private val HINT = "说一句话就行，比如「目标改到23:00」「昨晚1点睡的记一下」「明天8:00提醒我喝水」。"
    private val FALLBACK = """
        离线教练只认下面这几类说法（想自由聊就去「设置」配 LLM Key）：
        · 改计划：目标改到23:00 / 起床改到7点 / 计划改成28天
        · 记打卡：昨晚1点睡的，记一下 / 开始风屏了
        · 加提醒：明天8:00 提醒我喝水 / 把喝水那条提醒删了
        · 习惯：建个习惯：饭后散步，20:30，21天 / 做到了 / 暂停这个习惯
        · 问作息：这个计划科学吗 / 破功了怎么办 / 半夜醒了睡不着
    """.trimIndent()

    fun reply(
        text: String,
        cfg: KxConfig,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now(),
        knownReminders: List<String> = emptyList(),
        knownHabits: List<String> = emptyList(),
    ): LocalReply {
        val t = text.trim()
        if (t.isEmpty()) return LocalReply(HINT)

        // 1) 取消提醒：标题必须命中已有提醒，对不上就列清单让用户挑，不猜
        if (RE_REMIND.containsMatchIn(t) && RE_CANCEL.containsMatchIn(t)) {
            val hit = knownReminders.firstOrNull { t.contains(it) }
            return when {
                hit != null -> LocalReply("好，删掉「$hit」这条提醒 👇",
                    obj("action" to "reminder", "op" to "cancel", "text" to hit))
                knownReminders.isEmpty() -> LocalReply("现在没有待办提醒可删。")
                else -> LocalReply("要删哪一条？把标题说全我就删：\n" +
                    knownReminders.joinToString("\n") { "· $it" })
            }
        }

        // 2) 风屏：用当前时刻，无需用户报时间
        if (RE_WINDDOWN.containsMatchIn(t)) {
            return LocalReply("记一笔今晚风屏开始 👇",
                obj("action" to "checkin", "winddown" to "%02d:%02d".format(now.hour, now.minute)))
        }

        // 3) 习惯（建 / 打卡 / 暂停）——先于打卡，否则「习惯打卡」会被当成睡眠打卡
        habitIntent(t, today, knownHabits)?.let { return it }

        // 4) 记打卡
        val explicitCheckin = RE_CHECKIN.containsMatchIn(t) ||
            (t.contains("打卡") && parseTime(t, defaultPm = false) != null)
        if (explicitCheckin) {
            val time = parseTime(t, defaultPm = false)
                ?: return LocalReply("记昨晚要带上时间，比如「昨晚1点睡的，记一下」。")
            val status = when {
                Regex("(准时|按时|达标|不错|可以)").containsMatchIn(t) -> "on_time"
                Regex("(没做到|没睡|没睡着|通宵|失眠)").containsMatchIn(t) -> "skip"
                else -> if (toMin(time) <= toMin(cfg.currentBedtime)) "on_time" else "late"
            }
            return LocalReply("记一下昨晚：$time（$status）👇 点卡片按钮才生效。",
                obj("action" to "checkin", "status" to status, "bedtime" to time))
        }

        // 5) 改计划
        if (RE_ADJUST.containsMatchIn(t)) {
            val isWake = t.contains("起床") || t.contains("晨起")
            val isCurrent = t.contains("当前") || t.contains("现在") || t.contains("目前") || t.contains("基线")
            val time = parseTime(t, defaultPm = !isWake)
            val days = Regex("(\\d{1,3})\\s*天").find(t)?.groupValues?.get(1)?.toIntOrNull()
            if (time == null && days == null) {
                return LocalReply("想怎么调？比如「目标改到23:00」「起床改到7点」「计划改成28天」。")
            }
            val pairs = mutableListOf<Pair<String, Any?>>("action" to "adjust")
            if (time != null) pairs += (if (isWake) "wake" else if (isCurrent) "currentBedtime" else "targetBedtime") to time
            if (days != null) pairs += "days" to days
            val desc = buildString {
                time?.let { append("${if (isWake) "起床" else if (isCurrent) "当前入睡" else "目标入睡"} $it") }
                days?.let { if (isNotEmpty()) append("，"); append("计划 $it 天") }
            }
            return LocalReply("按你说的调：$desc 👇 点卡片按钮才生效。", obj(*pairs.toTypedArray()))
        }

        // 6) 加提醒
        if (RE_REMIND.containsMatchIn(t)) {
            val time = parseTime(t, defaultPm = false)
                ?: return LocalReply("要几点提醒？比如「明天8:00 提醒我喝水」。")
            val body = reminderBody(t)
            if (body.isBlank()) return LocalReply("提醒你做什么？比如「明天8:00 提醒我喝水」。")
            val date = parseDate(t, today) ?: today.toString()
            val lead = Regex("提前\\s*(\\d{1,3})\\s*分钟").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return LocalReply("好，$date $time 提醒你「$body」👇 点「加进日历」才写入。",
                obj("action" to "reminder", "date" to date, "time" to time, "text" to body, "leadMin" to lead))
        }

        // 7) 生图
        if (RE_IMAGE.containsMatchIn(t)) {
            return if (cfg.imageModel.isNotBlank())
                LocalReply("画一张助眠画面 👇", obj("action" to "image", "prompt" to imagePrompt(t)))
            else LocalReply("还没配生图模型：设置 → AI 教练 → 拉取模型列表 → 在生图列点选一个。")
        }

        // 8) 常见作息问题：给手册里的固定口径
        faq(t)?.let { return LocalReply(it) }

        return LocalReply(FALLBACK)
    }

    // ---------------- 习惯 ----------------

    private fun habitIntent(t: String, today: LocalDate, knownHabits: List<String>): LocalReply? {
        val named = knownHabits.firstOrNull { t.contains(it) }
        val mentionsHabit = RE_HABIT.containsMatchIn(t)

        // 已有习惯时，"做到了/暂停"这类光杆命令也该处理（多个则追问是哪个），
        // 否则「做到了」这种快捷说法会白白落到兜底
        if (RE_PAUSE.containsMatchIn(t) && (mentionsHabit || named != null || knownHabits.isNotEmpty())) {
            val name = resolveHabitName(named, knownHabits)
                ?: return LocalReply("暂停哪个习惯？" + listOrAsk(knownHabits))
            return LocalReply("暂停「$name」，日历先不响（已打的天数留着）👇",
                obj("action" to "habit", "op" to "pause", "name" to name))
        }
        if (RE_DONE.containsMatchIn(t) && (mentionsHabit || named != null || knownHabits.isNotEmpty())) {
            val name = resolveHabitName(named, knownHabits)
                ?: return LocalReply("给哪个习惯打卡？" + listOrAsk(knownHabits))
            return LocalReply("记「$name」今天做到了 👇",
                obj("action" to "habit", "op" to "done", "name" to name))
        }
        // 建习惯要有明确的动词，否则「习惯养成得怎么样」这类提问会被误建成新习惯
        if (mentionsHabit && Regex("(建|开|加|新|养|培养|弄|搞|来个|来一个|设一个)").containsMatchIn(t)) {
            val name = habitName(t)
                ?: return LocalReply("习惯叫什么？比如「建个习惯：饭后散步，20:30，21天」。")
            val cue = parseTime(t, defaultPm = false)
                ?: return LocalReply("「$name」每天几点做？给我一个固定锚点，比如「饭后散步，20:30，21天」。")
            val days = Regex("(\\d{1,3})\\s*天").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 21
            val start = parseDate(t, today) ?: today.toString()
            return LocalReply("开这个习惯：$name，每天 $cue（连响 $days 天）👇",
                obj("action" to "habit", "op" to "add", "name" to name,
                    "cueTime" to cue, "days" to days, "startDate" to start))
        }
        return null
    }

    /** 从句子里抠出习惯名：取「习惯/养成/培养」之后、第一个标点之前的那截。含疑问词的当提问，不算名字。 */
    private fun habitName(t: String): String? {
        val m = Regex("(?:习惯|养成|培养)[:：]?\\s*(.+)").find(t) ?: return null
        var s = m.groupValues[1].split(Regex("[，,、。；;\\n]")).firstOrNull().orEmpty()
        s = s.replace(Regex("^(一个|个|的)"), "")
        s = s.replace(Regex("(每天|每日).*$"), "")
        s = s.trim()
        if (s.isBlank() || s.any { it in "吗什么如何？?怎" }) return null
        return s
    }

    private fun resolveHabitName(named: String?, knownHabits: List<String>): String? =
        named ?: knownHabits.singleOrNull()

    private fun listOrAsk(knownHabits: List<String>): String =
        if (knownHabits.isEmpty()) "还没有进行中的习惯，先用「建个习惯：…，20:30，21天」开一个。"
        else "现有：" + knownHabits.joinToString("、") + "。"

    // ---------------- FAQ（口径来自 early-sleep/reference/coaching-playbook.md） ----------------

    private fun faq(t: String): String? {
        fun hit(vararg ks: String) = ks.any { t.contains(it) }
        return when {
            hit("科学吗", "科学不", "靠谱吗", "有依据", "有用吗", "有效吗", "科学") ->
                "诚实打分：固定起床锚点、咖啡因截断、晨间光照、睡前仪式是 CBT-I 的硬证据，计划骨架就是这些；" +
                    "「15 分钟 × 每 3 天」渐进前移是时差调整的临床惯例、不是硬法则，价值在于每步小到不可能失败。" +
                    "微任务/打卡/复盘是行为设计，帮执行、不改生理。我不是医生——长期入睡困难、严重打鼾、白天不可控困倦请去睡眠门诊。"

            hit("第一天", "和平常一样", "没效果", "没变化", "一样") ->
                "这是刻意的。第 1 天真正的变化不是入睡时刻，而是起床锚点（雷打不动）和睡前风屏仪式。" +
                    "生物钟认定 1:30 才是「夜晚」，第一天就 23:00 上床 = 躺 2.5 小时清醒地熬。阶梯：前 2 天维持现状 → " +
                    "第 3 天起每 3 天提前 15 分钟 → 最后一天达成目标。"

            hit("早点睡", "提前睡", "困了", "9点睡", "10点睡") ->
                "bedtime 是上限不是任务——困了可以提前睡，不困可以推迟 15–30 分钟，唯一不许动的是起床时间。" +
                    "但注意「过早上床」：对惯于 1:30 睡的人，21–23 点正值生物钟觉醒高峰，9 点多睡着 → 前半夜耗完睡眠压力 → 3–4 点早醒。" +
                    "接住困意：先做低刺激活动（暖光/纸质书/拉伸）撑到 22:30 再上床；真早醒了别碰手机别开灯，黑暗躺到起床锚点。"

            hit("几点睡着", "准吗", "怎么知道", "自动记录", "测得准") ->
                "没有手环时入睡时刻本质测不准，这是生理测量问题、不是 App 缺陷。App 用系统用量统计查「昨晚最后一次亮屏/熄屏」" +
                    "作为上界估计（最后亮屏 00:58 → 估计 01:00）。放下手机后的记忆不可信，系统日志不会撒谎。复盘看趋势和上界，别抠分钟。"

            hit("半夜醒", "早醒", "3点醒", "4点醒", "醒了睡不") ->
                "黑暗中躺 20 分钟仍清醒 → 换个姿势继续躺，计划执行期别起来干活/刷手机。" +
                    "连续多晚 3–4 点醒且白天崩溃 → 这属于 CBT-I 刺激控制领域，建议就医，同时把目标放缓（每周 15 分钟）。"

            hit("破功", "熬夜", "没做到", "加班", "又晚睡", "失败", "断了") ->
                "不指责——计划是活的，破功了改参数重来，不叫失败。单晚破功：什么都不用改，今天照常（锚点是起床），当晚按计划档位继续。" +
                    "连续多晚或生活节奏变了：调整计划（放宽 2–3 天再收回来）。别「干脆重来」，改参数比重启心理成本低得多。"

            hit("周末", "补觉", "睡懒觉", "报复性") ->
                "周末作息浮动 ≤ ±30 分钟。社交时差会一次清掉两周进度。实在困，允许午睡 ≤ 20 分钟且不晚于 15:00。"

            hit("复盘", "周报", "总结", "这周怎么样", "这周如何") ->
                "每 7 天的复盘点见计划里的「周复盘」事件。看首页的昨晚评分/连续天数就是最简版复盘；" +
                    "想更细就把这段打卡趋势念给我（或配 Key 让我读数据），然后一起决定怎么调。"

            hit("太慢", "太快", "进度", "变化太慢") ->
                "太慢：可加大步长（如 20 分钟/3 天），会出现 1:10 这类零头时间；太快（躺下超 30 分钟睡不着连续 2 晚）：退回上一档 3 天再继续。"

            else -> null
        }
    }

    // ---------------- 时间 / 日期解析（公开以便单测） ----------------

    private val RE_HHMM = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")
    // 修饰词前不能是「昨/今/明」——否则「昨晚1点」的「晚」会被误当「晚上」，把 01:00 算成 13:00
    private val RE_POINT = Regex(
        "(?<![昨今明])(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|晚|早)?\\s*" +
            "([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})\\s*点\\s*" +
            "(半|[0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})?\\s*分?")

    private val CN = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '两' to 2, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private fun cnNum(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) return t.toIntOrNull()
        if ('十' in t) {
            val parts = t.split('十')
            val tens = if (parts[0].isEmpty()) 1 else CN[parts[0].first()] ?: return null
            val ones = if (parts.size > 1 && parts[1].isNotEmpty()) CN[parts[1].first()] ?: return null else 0
            return tens * 10 + ones
        }
        return if (t.length == 1) CN[t[0]] else null
    }

    /**
     * 从任意句子抠出时间 → "HH:MM"。
     * [defaultPm]：没有早/晚等修饰、且数字落在 1–11 时是否按傍晚算（改入睡=true，起床/打卡/提醒=false）。
     */
    fun parseTime(raw: String, defaultPm: Boolean = false): String? {
        RE_HHMM.find(raw)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val mi = m.groupValues[2].toIntOrNull() ?: return@let
            if (h in 0..23 && mi in 0..59) return "%02d:%02d".format(h, mi)
        }
        RE_POINT.find(raw)?.let { m ->
            val mod = m.groupValues[1]
            var h = cnNum(m.groupValues[2]) ?: return@let
            val mi = when {
                m.groupValues[3] == "半" -> 30
                m.groupValues[3].isNotEmpty() -> cnNum(m.groupValues[3]) ?: 0
                else -> 0
            }
            if (h !in 0..24 || mi !in 0..59) return@let
            when {
                mod in listOf("下午", "傍晚", "晚上", "夜里", "晚") -> if (h in 1..11) h += 12
                mod == "中午" -> if (h in 1..11) h = 12
                mod in listOf("凌晨", "早上", "早晨", "上午", "早") -> Unit
                defaultPm && h in 1..11 -> h += 12
            }
            if (h == 24) h = 0
            if (h !in 0..23) return@let
            return "%02d:%02d".format(h, mi)
        }
        return null
    }

    /** 从任意句子抠出日期 → ISO。识别不了返回 null（由调用侧默认今天）。 */
    fun parseDate(raw: String, today: LocalDate = LocalDate.now()): String? {
        Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(raw)?.let {
            return runCatching {
                LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }.getOrNull()?.toString()
        }
        Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]").find(raw)?.let {
            return runCatching {
                LocalDate.of(today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }.getOrNull()?.toString()
        }
        Regex("(下)?周([一二三四五六日天])").find(raw)?.let { m ->
            val c = m.groupValues[2][0]
            val target = if (c == '天') 6 else "一二三四五六日".indexOf(c)
            if (target >= 0) {
                var ahead = ((target - (today.dayOfWeek.value - 1)) + 7) % 7
                if (m.groupValues[1] == "下") ahead += 7
                return today.plusDays(ahead.toLong()).toString()
            }
        }
        return when {
            raw.contains("大后天") -> today.plusDays(3)
            raw.contains("后天") -> today.plusDays(2)
            raw.contains("明天") || raw.contains("明晚") || raw.contains("明早") -> today.plusDays(1)
            raw.contains("今天") || raw.contains("今晚") || raw.contains("今早") -> today
            else -> null
        }?.toString()
    }

    // ---------------- 小工具 ----------------

    private fun toMin(hhmm: String) = PlanEngine.hhmmToMin(hhmm)

    /** 提醒正文：剥掉日期/时间/祈使词，剩下的就是要提醒的事。 */
    private fun reminderBody(t: String): String = t
        .replace(Regex("(今天|今晚|明天|明晚|后天|大后天|下?周[一二三四五六日天])"), "")
        .replace(RE_HHMM, "").replace(RE_POINT, "")
        .replace(Regex("提前\\s*\\d{1,3}\\s*分钟"), "")
        .replace(Regex("(提醒我|提醒一下|提醒|叫我|帮我记一下|记一下|别忘了|别忘|记着)"), "")
        .replace(Regex("[，,。；;：:、\\s]"), "")
        .trim()

    /** 生图 prompt：剥掉祈使词，补上助眠风格后缀。 */
    private fun imagePrompt(t: String): String {
        val s = t
            .replace(Regex("(帮我|给我|生成一张|生成|来一张|画一张|画个|画一下|画|助眠画面|助眠图|晚安图|插画|一张图|图片|图)"), "")
            .replace(Regex("[，,。；;：:、\\s]"), "")
            .ifBlank { "深邃夜空与群山剪影" }
        return "$s，安静柔和的助眠插画风格，柔和低饱和色调，无文字"
    }

    /** 手搓小 JSON：LocalCoach 保持纯函数（不依赖 android 的 org.json，便于 JVM 单测）。 */
    private fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            val jv = when (v) {
                null -> "null"
                is Int, is Long, is Boolean -> v.toString()
                else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
            "\"$k\":$jv"
        }
}