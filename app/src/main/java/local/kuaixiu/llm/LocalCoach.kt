package local.kuaixiu.llm

import local.kuaixiu.R
import local.kuaixiu.data.KxConfig
import local.kuaixiu.data.KxText
import local.kuaixiu.data.Score
import local.kuaixiu.data.Time
import java.time.LocalDate
import java.time.LocalTime

/** 离线教练的回复：正文 + 可选动作（与 AI 产出的动作协议同形，交给 ActionCard 应用）。 */
data class LocalReply(val text: String, val actionJson: String? = null)

/**
 * 没有 LLM Key 时的本地教练：中英关键词/正则识别意图，产出与 [CoachPrompt] 动作协议
 * **逐字一致**的 JSON，因此 ActionCard 的执行链零改动复用。
 *
 * 纯粹是「规则匹配」：识别不了的必须诚实说不知道，绝不假装"已做了"——那正是 AI 想糊弄时的病。
 * 识别中英一起认（中文界面里说英文也接得住）；回复文案走资源，按系统语言出中英。
 */
object LocalCoach {

    // ---- 意图关键词（顺序即优先级） ----
    private val I = RegexOption.IGNORE_CASE
    private val RE_CANCEL = Regex("(删|取消|不用|去掉|别提醒|不要了|delete|remove|cancel|no longer|forget about)", I)
    private val RE_REMIND = Regex("(提醒|叫我|记一下|别忘了|别忘|记着|remind|note that|don'?t forget|remember to)", I)
    private val RE_CHECKIN = Regex("(昨晚|睡了|入睡|几点睡|睡的|last night|fell asleep|went to bed|slept)", I)
    private val RE_ADJUST = Regex(
        "(目标|改成|改到|调到|调整|推迟|提前|起床|入睡时间|计划天数|重新定" +
            "|target|change|adjust|move|push|postpone|bedtime|wake|plan length)", I)
    /** 「入睡时间改到23点」既是「入睡」又是改参数——带改动词就不能当打卡。 */
    private val RE_CHANGE_VERB = Regex(
        "(改成|改到|改为|改一下|调到|调成|调整|推迟|提前|重新定" +
            "|change|adjust|move|push|postpone|retarget)", I)
    private val RE_PLAN_NOUN = Regex(
        "(目标|入睡|起床|晨起|计划天数|基线|当前|现在|目前|bedtime|wake|target|plan length)", I)
    private val RE_IMAGE = Regex("(画一?张|画个|画一下|助眠图|晚安图|助眠画面|插画|配图|draw|generate|picture|illustration|image)", I)
    private val RE_DONE = Regex("(做到了|完成了|打卡了|打过卡|做了|完成打卡|\\bdone\\b|did it|checked in|finished)", I)
    private val RE_PAUSE = Regex("(暂停|先不提醒|停一下|停下|pause|stop|suspend)", I)
    private val RE_HABIT = Regex("(习惯|养成|培养|\\bhabit)", I)
    private val RE_HABIT_ADD = Regex("(建|开|加|新|养|培养|弄|搞|来个|来一个|设一个|\\bnew\\b|\\bstart\\b|\\badd\\b|\\bcreate\\b|\\bbuild\\b|\\bmake\\b)", I)

    /** 放不下手机/开始风屏：一句话就能记，不需要时间。 */
    private val RE_WINDDOWN = Regex("(风屏|放下手机|不玩手机|关屏幕|wind[ -]?down|phone down|put my phone away)", I)

    fun reply(
        text: String,
        cfg: KxConfig,
        today: LocalDate = Time.today(),
        now: LocalTime = Time.now().toLocalTime(),
        knownReminders: List<String> = emptyList(),
        knownHabits: List<String> = emptyList(),
        /** 昨晚那一档的计划入睡；首页打卡用的就是它，缺省才退回初始基线。 */
        plannedBedtime: String? = null,
    ): LocalReply {
        val t = text.trim()
        if (t.isEmpty()) return LocalReply(KxText.s(R.string.coach_hint))

        // 1) 取消提醒：标题必须命中已有提醒，对不上就列清单让用户挑，不猜
        if (RE_REMIND.containsMatchIn(t) && RE_CANCEL.containsMatchIn(t)) {
            val hit = knownReminders.firstOrNull { t.contains(it) }
            return when {
                hit != null -> LocalReply(KxText.s(R.string.coach_reminder_cancelled, hit),
                    obj("action" to "reminder", "op" to "cancel", "text" to hit))
                knownReminders.isEmpty() -> LocalReply(KxText.s(R.string.coach_no_reminders))
                else -> LocalReply(KxText.s(R.string.coach_which_reminder) + "\n" +
                    knownReminders.joinToString("\n") { KxText.s(R.string.coach_bullet, it) })
            }
        }

        // 2) 风屏：用当前时刻，无需用户报时间。句子里出现「提醒/习惯」时这是别人的关键词
        //    （「明天下午3点提醒我放下手机」「建个习惯：晚上不玩手机」不是叫我现在就放下手机）
        if (RE_WINDDOWN.containsMatchIn(t) && !RE_REMIND.containsMatchIn(t) &&
            !RE_HABIT.containsMatchIn(t)) {
            return LocalReply(KxText.s(R.string.coach_winddown),
                obj("action" to "checkin", "winddown" to "%02d:%02d".format(now.hour, now.minute)))
        }

        // 3) 习惯（建 / 打卡 / 暂停）——先于打卡，否则「习惯打卡」会被当成睡眠打卡
        habitIntent(t, today, knownHabits)?.let { return it }

        // 4) 改计划。带「入睡」的说法两边都沾（「昨晚提前睡了」既是打卡又含「提前」），
        //    所以先把新参数真解析出来才算改计划；解析不出但像打卡，就往下走第 5 步
        if (RE_ADJUST.containsMatchIn(t) || adjustIntent(t)) {
            adjust(t)?.let { return it }
            if (!looksLikeCheckin(t)) return LocalReply(KxText.s(R.string.coach_adjust_ask))
        }

        // 5) 记打卡——「入睡时间改到23点」这类在上面就被改计划接走了
        if (looksLikeCheckin(t)) {
            val time = parseBedtime(t)
                ?: return LocalReply(KxText.s(R.string.coach_need_time))
            // 达标线用今晚档位（没有才退回初始基线），且必须跨午夜安全：
            // 基线 23:45 时「00:30 睡的」是晚了 45 分钟，不是「比 23:45 的数字小所以准时」
            val target = plannedBedtime?.takeIf { it.isNotBlank() } ?: cfg.currentBedtime
            val status = when {
                Regex("(准时|按时|达标|不错|可以|on time|\\bokay\\b|good|fine)", I).containsMatchIn(t) -> "on_time"
                Regex("(没做到|没睡|没睡着|通宵|失眠|couldn'?t sleep|no sleep|all night)", I).containsMatchIn(t) -> "skip"
                else -> if ((Score.diffMinutes(target, time) ?: 0) > 30) "late" else "on_time"
            }
            return LocalReply(KxText.s(R.string.coach_checkin, time, status),
                obj("action" to "checkin", "status" to status, "bedtime" to time))
        }

        // 6) 加提醒
        if (RE_REMIND.containsMatchIn(t)) {
            val time = parseTime(t, defaultPm = false)
                ?: return LocalReply(KxText.s(R.string.coach_remind_time))
            val body = reminderBody(t)
            if (body.isBlank()) return LocalReply(KxText.s(R.string.coach_remind_what))
            val date = parseDate(t, today) ?: today.toString()
            val lead = Regex("提前\\s*(\\d{1,3})\\s*分钟").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return LocalReply(KxText.s(R.string.coach_reminder_added, date, time, body),
                obj("action" to "reminder", "date" to date, "time" to time, "text" to body, "leadMin" to lead))
        }

        // 7) 生图
        if (RE_IMAGE.containsMatchIn(t)) {
            return if (cfg.effectiveImageModel().isNotBlank())
                LocalReply(KxText.s(R.string.coach_draw), obj("action" to "image", "prompt" to imagePrompt(t)))
            else LocalReply(KxText.s(R.string.coach_no_image_model))
        }

        // 8) 常见作息问题：给手册里的固定口径
        faq(t)?.let { return LocalReply(it) }

        return LocalReply(KxText.s(R.string.coach_fallback))
    }

    // ---------------- 习惯 ----------------

    private fun habitIntent(t: String, today: LocalDate, knownHabits: List<String>): LocalReply? {
        val named = knownHabits.firstOrNull { t.contains(it) }
        val mentionsHabit = RE_HABIT.containsMatchIn(t)
        // 光杆命令（「做到了」「暂停」）确实该认习惯，但带睡眠词的是在报昨晚：
        // 「昨晚做到了11点睡」不能被习惯接走，否则睡眠打卡永远进不来
        val bareOk = knownHabits.isNotEmpty() && !RE_CHECKIN.containsMatchIn(t)

        if (RE_PAUSE.containsMatchIn(t) && (mentionsHabit || named != null || bareOk)) {
            val name = resolveHabitName(named, knownHabits)
                ?: return LocalReply(KxText.s(R.string.coach_pause_which) + listOrAsk(knownHabits))
            return LocalReply(KxText.s(R.string.coach_habit_paused, name),
                obj("action" to "habit", "op" to "pause", "name" to name))
        }
        if (RE_DONE.containsMatchIn(t) && (mentionsHabit || named != null || bareOk)) {
            val name = resolveHabitName(named, knownHabits)
                ?: return LocalReply(KxText.s(R.string.coach_done_which) + listOrAsk(knownHabits))
            return LocalReply(KxText.s(R.string.coach_habit_done, name),
                obj("action" to "habit", "op" to "done", "name" to name))
        }
        // 建习惯要有明确的动词，否则「习惯养成得怎么样」这类提问会被误建成新习惯
        if (mentionsHabit && RE_HABIT_ADD.containsMatchIn(t)) {
            val name = habitName(t)
                ?: return LocalReply(KxText.s(R.string.coach_habit_name))
            val cue = parseTime(t, defaultPm = false)
                ?: return LocalReply(KxText.s(R.string.coach_habit_cue, name))
            val days = findDays(t) ?: 21
            val start = parseDate(t, today) ?: today.toString()
            return LocalReply(KxText.n(R.plurals.coach_habit_added, days, name, cue, days),
                obj("action" to "habit", "op" to "add", "name" to name,
                    "cueTime" to cue, "days" to days, "startDate" to start))
        }
        return null
    }

    /** 从句子里抠出习惯名：取「习惯/habit」之后、第一个标点之前的那截。含疑问词的当提问，不算名字。 */
    private fun habitName(t: String): String? {
        val m = Regex("(?:习惯|养成|培养|\\bhabit)[:：]?\\s*(.+)", I).find(t) ?: return null
        var s = m.groupValues[1].split(Regex("[，,、。；;\\n]")).firstOrNull().orEmpty()
        s = s.replace(Regex("^(一个|个|的)"), "")
        s = s.replace(Regex("(每天|每日).*$"), "")
        s = s.replace(Regex("(?i)^\\s*(to|a|an|the|called|named|\\*)\\s+"), "")
        s = s.trim()
        if (s.isBlank() || s.any { it in "吗什么如何？?怎" }) return null
        if (Regex("(?i)\\b(how|what|why|whether|is it|does)\\b").containsMatchIn(s)) return null
        return s
    }

    private fun resolveHabitName(named: String?, knownHabits: List<String>): String? =
        named ?: knownHabits.singleOrNull()

    private fun listOrAsk(knownHabits: List<String>): String =
        if (knownHabits.isEmpty()) KxText.s(R.string.coach_no_habits)
        else KxText.s(R.string.coach_existing,
            knownHabits.joinToString(KxText.s(R.string.common_or)))

    // ---------------- FAQ（口径来自 early-sleep/reference/coaching-playbook.md） ----------------

    private fun faq(t: String): String? {
        fun hit(vararg ks: String) = ks.any { t.contains(it, ignoreCase = true) }
        return when {
            hit("科学吗", "科学不", "靠谱吗", "有依据", "有用吗", "有效吗", "科学",
                "scientific", "evidence", "does this work", "sound") ->
                KxText.s(R.string.faq_science)

            hit("第一天", "和平常一样", "没效果", "没变化", "一样",
                "day 1", "first day", "no change", "same as usual") ->
                KxText.s(R.string.faq_day1)

            hit("早点睡", "提前睡", "困了", "9点睡", "10点睡",
                "sleep earlier", "fell asleep early", "too early", "get sleepy") ->
                KxText.s(R.string.faq_early)

            hit("几点睡着", "准吗", "怎么知道", "自动记录", "测得准",
                "how do you know", "accurate", "measure", "track my sleep") ->
                KxText.s(R.string.faq_measure)

            hit("半夜醒", "早醒", "3点醒", "4点醒", "醒了睡不",
                "wake at 3", "wake at 4", "woke up", "3am", "4am", "can'?t get back to sleep") ->
                KxText.s(R.string.faq_night_wake)

            hit("破功", "熬夜", "没做到", "加班", "又晚睡", "失败", "断了",
                "broke my streak", "streak broke", "failed", "up late", "overtime", "gave up") ->
                KxText.s(R.string.faq_slip)

            hit("周末", "补觉", "睡懒觉", "报复性",
                "weekend", "catch up on sleep", "sleep in", "revenge") ->
                KxText.s(R.string.faq_weekend)

            hit("复盘", "周报", "总结", "这周怎么样", "这周如何",
                "review", "weekly", "how was my week", "recap") ->
                KxText.s(R.string.faq_review)

            hit("太慢", "太快", "进度", "变化太慢",
                "too slow", "too fast", "progress", "faster") ->
                KxText.s(R.string.faq_pace)

            else -> null
        }
    }

    // ---------------- 时间 / 日期解析（公开以便单测） ----------------

    private val RE_HHMM = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")
    /** 说话人已明确交代是白天还是清早 —— 这时不再替他把钟点归到夜里。 */
    private val RE_DAY_MOD = Regex("(中午|正午|上午|早上|早晨|\\bnoon\\b|\\bmorning\\b|\\bam\\b|\\bpm\\b)", I)
    // 英文写法：11pm / 9:30 am / at 8。紧跟「天/days」的裸数字是计划天数，不是钟点
    // （否则「计划改成0天」会被抠成 00:00 的目标入睡）
    private val RE_EN_TIME = Regex(
        "(\\d{1,2})(?:\\s*[:.]\\s*(\\d{2}))?\\s*(am|pm)?(?!\\s*(?:[天日]|\\bdays?\\b))", I)
    // 修饰词前不能是「昨/今/明」——否则「昨晚1点」的「晚」会被误当「晚上」，把 01:00 算成 13:00
    private val RE_POINT = Regex(
        "(?<![昨今明])(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|晚|早)?\\s*" +
            "([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})\\s*点\\s*" +
            "(半|[0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})?\\s*分?")
    private val RE_DAYS = Regex("(\\d{1,3})\\s*(?:天|\\bdays?\\b)", I)
    private val RE_EN_WEEKDAY = Regex(
        "\\b(next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", I)
    private val EN_DAY_NUM = mapOf("monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4,
        "friday" to 5, "saturday" to 6, "sunday" to 7)

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
     * 计划天数：中文「28天」与英文「28 days」同一套。0/负数/超过一年都不认——
     * `planDays=0` 会让 plan.days 变空，日历被清空、首页取档位直接炸。
     */
    private fun findDays(t: String): Int? =
        RE_DAYS.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..365 }

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
        RE_EN_TIME.find(raw)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return@let
            val mi = m.groupValues[2].toIntOrNull() ?: 0
            when (m.groupValues[3].lowercase()) {
                "pm" -> if (h in 1..11) h += 12
                "am" -> if (h == 12) h = 0
                else -> if (defaultPm && h in 1..11) h += 12
            }
            if (h in 0..23 && mi in 0..59) return "%02d:%02d".format(h, mi)
        }
        return null
    }

    /**
     * 入睡时刻：`parseTime` 为了不把「昨晚1点」的「晚」当修饰词，特意让 昨晚X点 不带上下午倾向，
     * 于是「昨晚11点」会停在 11:00（上午）。打卡要的是钟面直觉——8–11 点是晚上，12 点是午夜。
     * 说清了中午/早上，或写死了 HH:MM，就照字面不猜。
     */
    fun parseBedtime(t: String): String? {
        val raw = parseTime(t, defaultPm = false) ?: return null
        if (!RE_CHECKIN.containsMatchIn(t) || RE_DAY_MOD.containsMatchIn(t)) return raw
        val h = raw.take(2).toIntOrNull() ?: return raw
        val mm = raw.substring(3)
        return when {
            h in 8..11 -> "%02d:%s".format(h + 12, mm)
            h == 12 -> "00:$mm"
            else -> raw
        }
    }

    /** 从任意句子抠出日期 → ISO。识别不了返回 null（由调用侧默认今天）。 */
    fun parseDate(raw: String, today: LocalDate = Time.today()): String? {
        Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(raw)?.let {
            return runCatching {
                LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }.getOrNull()?.toString()
        }
        Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]").find(raw)?.let {
            return runCatching {
                LocalDate.of(today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }.getOrNull()?.let { d ->
                // 12 月说「1月5日提醒我」是要将来的那一头，补到明年，别落回今年的过去
                if (d.isBefore(today)) d.plusYears(1) else d
            }?.toString()
        }
        Regex("(下)?周([一二三四五六日天])").find(raw)?.let { m ->
            val c = m.groupValues[2][0]
            val target = if (c == '天') 6 else "一二三四五六日".indexOf(c)
            if (target >= 0) {
                var ahead = ((target - (today.dayOfWeek.value - 1)) + 7) % 7
                // 「周一」在周一说，指的是下一个周一（口径同英文 monday，别说成已经过去的今天）
                if (m.groupValues[1] == "下") ahead += 7 else if (ahead == 0) ahead = 7
                return today.plusDays(ahead.toLong()).toString()
            }
        }
        RE_EN_WEEKDAY.find(raw)?.let { m ->
            val target = EN_DAY_NUM[m.groupValues[2].lowercase()] ?: return@let
            // 口径跟中文「下周一」一致：当天不算，下一个同一天起算，带 next 再往后一整周
            var ahead = ((target - today.dayOfWeek.value) + 7) % 7
            if (ahead == 0) ahead = 7 else if (m.groupValues[1].isNotEmpty()) ahead += 7
            return today.plusDays(ahead.toLong()).toString()
        }
        return when {
            raw.contains("大后天") || Regex("\\bday after tomorrow\\b", I).containsMatchIn(raw) -> today.plusDays(3)
            raw.contains("后天") -> today.plusDays(2)
            raw.contains("明天") || raw.contains("明晚") || raw.contains("明早") ||
                Regex("\\btomorrow\\b", I).containsMatchIn(raw) -> today.plusDays(1)
            raw.contains("今天") || raw.contains("今晚") || raw.contains("今早") ||
                Regex("\\b(today|tonight|this (morning|evening|night))\\b", I).containsMatchIn(raw) -> today
            else -> null
        }?.toString()
    }

    // ---------------- 小工具 ----------------

    /** 既提到计划参数、又有明确改动词，才算「改计划」，否则「昨晚提前睡了」会被抢走。 */
    private fun adjustIntent(t: String) =
        RE_PLAN_NOUN.containsMatchIn(t) && RE_CHANGE_VERB.containsMatchIn(t)

    private fun looksLikeCheckin(t: String) =
        RE_CHECKIN.containsMatchIn(t) ||
            (t.contains("打卡") && parseTime(t, defaultPm = false) != null)

    /**
     * 解析「改计划」：一句里可以同时给两个时间（「入睡改到23点，起床改到7点」），
     * 必须按分句各认各的字段，否则整句里的「起床」会把 23:00 错塞进 wake，把计划写坏。
     * 一个参数都没解析出来时返回 null，由调用侧决定是追问还是放给打卡。
     */
    private fun adjust(t: String): LocalReply? {
        val times = linkedMapOf<String, String>()
        for (clause in t.split(Regex("[，,、;；\\n]+"))) {
            // 「昨晚11点睡的，目标改到10点」这种混着说的，只认带参数名或改动词的那个分句
            if (!RE_PLAN_NOUN.containsMatchIn(clause) && !RE_CHANGE_VERB.containsMatchIn(clause)) continue
            val wake = isWake(clause)
            val time = parseTime(clause, defaultPm = !wake) ?: continue
            times[if (wake) "wake" else if (isCurrent(clause)) "currentBedtime" else "targetBedtime"] = time
        }
        val days = findDays(t)
        if (times.isEmpty() && days == null) return null
        val res = mapOf("wake" to R.string.coach_adj_wake,
                        "currentBedtime" to R.string.coach_adj_current,
                        "targetBedtime" to R.string.coach_adj_target)
        val parts = times.map { (k, v) -> KxText.s(res.getValue(k), v) }.toMutableList()
        days?.let { parts += KxText.s(R.string.coach_adj_days, it) }
        val pairs = mutableListOf<Pair<String, Any?>>("action" to "adjust")
        times.forEach { (k, v) -> pairs += k to v }
        days?.let { pairs += "days" to it }
        return LocalReply(
            KxText.s(R.string.coach_adjust, parts.joinToString(KxText.s(R.string.coach_join_sep))),
            obj(*pairs.toTypedArray()))
    }

    private fun isWake(clause: String) =
        clause.contains("起床") || clause.contains("晨起") || Regex("\\bwake", I).containsMatchIn(clause)

    private fun isCurrent(clause: String) =
        clause.contains("当前") || clause.contains("现在") || clause.contains("目前") ||
            clause.contains("基线") || Regex("\\bcurrent", I).containsMatchIn(clause)

    /** 提醒正文：剥掉日期/时间/祈使词，剩下的就是要提醒的事。 */
    private fun reminderBody(t: String): String {
        val s = t
            .replace(Regex("(今天|今晚|明天|明晚|后天|大后天|下?周[一二三四五六日天])"), " ")
            .replace(RE_HHMM, " ").replace(RE_POINT, " ")
            .replace(Regex("提前\\s*\\d{1,3}\\s*分钟"), " ")
            .replace(Regex("(提醒我|提醒一下|提醒|叫我|帮我记一下|记一下|别忘了|别忘|记着)"), " ")
            .replace(Regex("(?i)\\b(remind me to|remind me|remind|note that|don'?t forget to|don'?t forget|remember to)\\b"), " ")
            .replace(RE_EN_TIME, " ")
            .replace(Regex("\\b(mon|monday|tue|tuesday|wed|wednesday|thu|thursday|fri|friday|sat|saturday|sun|sunday|tomorrow|today|tonight|next|on|at)\\b", I), " ")
        return normalizePhrase(s)
    }

    /**
     * 中文里空白是噪音（"提醒我 喝水" → "喝水"），英文里空白是词界（"drink water" 不能压成 "drinkwater"）。
     * 所以按**这段文字本身**有没有汉字判断：中文界面里说英文、英文界面里说中文都得对。
     */
    private fun normalizePhrase(s: String): String =
        if (s.any { it in '\u4E00'..'\u9FFF' }) s.replace(Regex("[，,。；;：:、\\s]"), "").trim()
        else s.replace(Regex("[，,。；;：:、]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** 生图 prompt：剥掉祈使词，补上助眠风格后缀。 */
    private fun imagePrompt(t: String): String {
        val s = normalizePhrase(
            t.replace(Regex("(帮我|给我|生成一张|生成|来一张|画一张|画个|画一下|画|助眠画面|助眠图|晚安图|插画|一张图|图片|图)"), " ")
                .replace(Regex("(?i)^\\s*(please\\s+)?(draw|generate|create|make|paint|render)\\s+(me\\s+)?(a|an|the)?\\s*"), " ")
                .replace(Regex("(?i)\\b(wind-?down|sleep|bedtime|good night|picture|image|illustration|art|of|please)\\b"), " ")
                .replace(Regex("[，,。；;：:]"), " ")
        ).ifBlank { KxText.s(R.string.img_prompt_default) }
        return s + KxText.s(R.string.img_prompt_suffix)
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
