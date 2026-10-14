package local.kuaixiu.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.kuaixiu.R
import local.kuaixiu.data.Checkin
import local.kuaixiu.data.Checkins
import local.kuaixiu.data.Habit
import local.kuaixiu.data.HabitCheck
import local.kuaixiu.data.Habits
import local.kuaixiu.data.ImageGen
import local.kuaixiu.data.UsageProbe
import local.kuaixiu.engine.PlanEngine
import local.kuaixiu.data.Score
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.Time
import local.kuaixiu.engine.Plan
import local.kuaixiu.engine.PlanDay
import java.time.LocalDate
import java.time.LocalTime

private val CALENDAR_PERMS = arrayOf(
    android.Manifest.permission.READ_CALENDAR,
    android.Manifest.permission.WRITE_CALENDAR,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun calGrantedNow() = CALENDAR_PERMS.all {
        ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var calGranted by remember { mutableStateOf(calGrantedNow()) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calGranted = calGrantedNow()
    }
    // 回到本页再核一次：用户可能刚在系统设置里授过权
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) calGranted = calGrantedNow()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (!calGranted) perms.launch(CALENDAR_PERMS) }

    val plan = KxStore.plan
    // 三张时间卡按「下一次该发生的那一刻」排序——用绝对日期时间，不是只看 HH:MM，
    // 否则「3 天后的 09:00 提醒」会排到「今天 22:00 的习惯」前面。（remember 不能写在 LazyColumn 里）
    var nextRemind by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        val at = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { local.kuaixiu.calendar.CalendarWriter.peekReminders(ctx).firstOrNull()?.third }
                .onFailure { android.util.Log.w("Kuaixiu", "提醒排序读取失败: ${it.message}") }
                .getOrNull()
        }
        nextRemind = at
    }
    val today = Time.today()
    fun atMs(date: LocalDate, hhmm: String): Long? = runCatching {
        java.time.LocalDateTime.of(date, LocalTime.parse(hhmm))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    // 习惯：没到开始日 → 排到开始那天；今天已打卡 → 排到明天；今天到点没打卡 →
    // 用今天那一刻（已经过去的时间戳）自然排最前，代表"欠着"
    val nextHabit = Habits.items.filter { !Habits.expired(it) }.mapNotNull { h ->
        val start = runCatching { LocalDate.parse(h.startDate) }.getOrNull() ?: return@mapNotNull null
        when {
            start.isAfter(today) -> atMs(start, h.cueTime)
            Habits.isDoneToday(h.id) -> atMs(today.plusDays(1), h.cueTime)
            else -> atMs(today, h.cueTime)
        }
    }.minOrNull()
    // 入睡/风屏时间属于「当晚」，00:00–11:59 的要落到次日凌晨（00:30 是明天凌晨，不是今早）
    fun bedtimeMs(planDate: LocalDate, hhmm: String): Long? {
        val t = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: return null
        return atMs(if (t.hour < 12) planDate.plusDays(1) else planDate, hhmm)
    }
    // 风屏/早睡卡用哪一天：跟卡片内容保持一致（中午 12 点前「今晚」算昨晚）。
    // 排序和显示必须是同一天，否则会出现「显示 00:30 就要开始、却排在 19:30 习惯后面」。
    // 计划第一天/还没开始时落不到档位，退到计划里最靠前的一档顶上，别让卡片消失。
    val tonightEntry = plan?.entryFor(Checkins.tonightDate().toString())
        ?: plan?.upcoming(today.toString())
    val sleepAt = tonightEntry?.let { bedtimeMs(LocalDate.parse(it.date), it.winddown) }
    var quickRev by remember { mutableStateOf(0) }   // 手动加提醒后，把提醒卡顶着重载一次
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (plan == null) {
            item { EmptyCard() }
        } else {
            // 顺序：昨晚成绩 → 今晚行动（风屏/打卡）→ 提醒/习惯/早睡按到点时间 → 未来 → 低频操作
            item { ScoreCard() }
            // 提醒 / 习惯 / 早睡（风屏行动卡）按「下一次到点」排序：最近要做的排前面
            listOfNotNull(
                nextRemind?.let { it to "提醒" },
                nextHabit?.let { it to "习惯" },
                sleepAt?.let { it to "早睡" },
            ).sortedBy { it.first }.forEach { (_, which) ->
                when (which) {
                    "提醒" -> item { ReminderCard(quickRev) }
                    "习惯" -> item { HabitCard() }
                    else -> item { CheckinCard(tonightEntry) }   // 今晚风屏：到点就要点「开始风屏了」
                }
            }
            // 计划轴这两张必须挨着，别被习惯卡插开：今日参考 → 接下来几天
            item { TodayCard(plan) }
            item { UpcomingCard(plan) }
        }
        // 常显：不经过教练也能手动加提醒 / 建习惯（两张原有的卡在空列表时不渲染，塞不进去）
        item { QuickAddCard(onSaved = { quickRev++ }) }
        // 没授权日历权限时才提示 + 给按钮；授权过就整块不出现
        if (!calGranted) {
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.home_cal_permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = {
                        val act = ctx as? android.app.Activity
                        // 还能弹系统弹窗就弹；已被永久拒绝只能去应用设置里开
                        val canAsk = act != null &&
                            CALENDAR_PERMS.any { act.shouldShowRequestPermissionRationale(it) }
                        if (canAsk) {
                            perms.launch(CALENDAR_PERMS)
                        } else {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", ctx.packageName, null),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }) { Text(stringResource(R.string.home_cal_permission_btn)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.home_empty_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                runCatching { KxStore.syncCalendar(ctx) }
                    .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.home_plan_written, it), Toast.LENGTH_LONG).show() }
                    .onFailure { android.util.Log.e("Kuaixiu", "sync failed", it); Toast.makeText(ctx, ctx.getString(R.string.home_sync_failed, it::class.simpleName ?: "null", it.message ?: "null"), Toast.LENGTH_LONG).show() }
            }) { Text(stringResource(R.string.home_generate_now)) }
        }
    }
}

@Composable
private fun TodayCard(plan: Plan) {
    val today = Time.today().toString()
    val d = plan.entryFor(today) ?: plan.upcoming(today) ?: plan.days.last()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.home_day_header, d.day, PlanEngine.phaseLabel(d.stage),
                    d.date.drop(5), d.weekday),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.home_target_bedtime, d.bedtime),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.home_winddown_wake, d.winddown, plan.config.wake))
            Text(stringResource(R.string.home_theme, d.theme))
            d.tasks.forEach { Text(stringResource(R.string.home_task_line, it)) }
            Text(
                stringResource(R.string.home_tip, d.tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shiftDone = plan.summary.totalShiftMinutes - shiftRemaining(plan, d.bedtime)
            val toTarget = plan.summary.daysToTarget
                ?.let { stringResource(R.string.home_target_reach, plan.config.targetBedtime, it) } ?: ""
            Text(
                stringResource(R.string.home_progress, d.day, plan.days.size, shiftDone, toTarget),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun shiftRemaining(plan: Plan, bedtime: String): Int {
    val target = local.kuaixiu.engine.PlanEngine.hhmmToMin(plan.config.targetBedtime)
    val cur = local.kuaixiu.engine.PlanEngine.hhmmToMin(bedtime)
    return (cur - target).mod(1440)
}

/**
 * 待办提醒卡：教练加的一次性提醒列在这儿，带删除。
 * 厂商日历不给 sync-adapter 本地账户的事件提供删除入口，所以删除必须我们自己给。
 */
@Composable
private fun ReminderCard(refreshKey: Int = 0) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var items by remember {
        mutableStateOf<List<Triple<Long, String, Long>>>(emptyList())
    }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val list = runCatching {
                local.kuaixiu.calendar.CalendarWriter.upcomingReminders(ctx)
            }.onFailure { android.util.Log.w("Kuaixiu", "提醒列表读取失败: ${it.message}") }
                .getOrDefault(emptyList())
            kotlinx.coroutines.withContext(Dispatchers.Main) { items = list }
        }
    }
    LaunchedEffect(refreshKey) { reload() }
    if (items.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.home_reminders_title), style = MaterialTheme.typography.titleMedium)
            items.forEach { (id, title, startMs) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title)
                        val at = java.time.Instant.ofEpochMilli(startMs)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern(
                                stringResource(R.string.home_reminder_date_format)))
                        Text(at, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val ok = runCatching {
                                local.kuaixiu.calendar.CalendarWriter.deleteReminder(ctx, id)
                            }.onFailure { android.util.Log.w("Kuaixiu", "提醒删除失败: ${it.message}") }
                                .getOrDefault(false)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, ctx.getString(
                                    if (ok) R.string.home_reminder_deleted else R.string.home_reminder_delete_failed),
                                    Toast.LENGTH_SHORT).show()
                                if (ok) reload()
                            }
                        }
                    }) { Text(stringResource(R.string.home_delete)) }
                }
            }
            Text(stringResource(R.string.home_reminders_footnote),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 习惯卡：点阵看 N 天进度，今天做到了就点一下，暂停/删除的入口也在这儿。
 * 事件在独立日历「快休习惯」里，重算作息计划（删旧插新）碰不到它。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitCard() {
    val items = Habits.items
    if (items.isEmpty()) return
    val today = Time.today()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_habits_title), style = MaterialTheme.typography.titleMedium)
            items.forEach { HabitRow(it, today) }
            Text(
                stringResource(R.string.home_habits_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitRow(h: Habit, today: LocalDate) {
    val ctx = LocalContext.current
    val done = Habits.doneDates[h.id].orEmpty()
    val day = Habits.dayOf(h, today)
    val expired = Habits.expired(h, today)
    val doneToday = Habits.isDoneToday(h.id, today.toString())
    val hits = Habits.completionOf(h, today)
    // 「还在跑」那几个习惯名之间的分隔符：跟着语言走（中文顿号、英文逗号）
    val sep = stringResource(R.string.common_or)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.name, modifier = Modifier.weight(1f),
                 style = MaterialTheme.typography.titleSmall)
            if (!h.enabled) {
                TextButton(onClick = {
                    runCatching { Habits.setEnabled(ctx, h.id, on = true) }.onSuccess { r ->
                        when (r) {
                            is HabitCheck.Ok -> Toast.makeText(ctx,
                                ctx.getString(R.string.home_habit_resumed, r.habit.days),
                                Toast.LENGTH_LONG).show()
                            is HabitCheck.QuotaFull -> Toast.makeText(ctx,
                                ctx.getString(R.string.home_habit_quota_resume,
                                    r.activeNames.joinToString(sep)), Toast.LENGTH_LONG).show()
                            is HabitCheck.AnchorConflict -> Toast.makeText(ctx,
                                ctx.getString(R.string.home_habit_conflict_resume, r.otherName, r.gapMin),
                                Toast.LENGTH_LONG).show()
                            is HabitCheck.Invalid -> Toast.makeText(ctx, r.reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(stringResource(R.string.home_habit_resume)) }
            } else {
                TextButton(onClick = {
                    runCatching { Habits.setEnabled(ctx, h.id, on = false) }.onSuccess {
                        Toast.makeText(ctx, ctx.getString(R.string.home_habit_paused_toast), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.home_habit_pause)) }
            }
            TextButton(onClick = {
                runCatching { Habits.remove(ctx, h.id) }
                    .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.home_habit_removed_toast, h.name), Toast.LENGTH_LONG).show() }
            }) { Text(stringResource(R.string.home_delete)) }
        }
        Text(
            when {
                !h.enabled -> stringResource(R.string.home_habit_status_paused, h.cueTime)
                day == 0 -> stringResource(R.string.home_habit_status_upcoming, h.startDate, h.cueTime)
                expired -> stringResource(R.string.home_habit_status_expired, h.days, h.cueTime, hits.first, hits.second)
                else -> stringResource(
                    R.string.home_habit_status_running,
                    day, h.days, h.cueTime, Habits.streakOf(h, today), hits.first, hits.second,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val start = runCatching { LocalDate.parse(h.startDate) }.getOrElse { today }
            val todayS = today.toString()
            val shape = androidx.compose.foundation.shape.CircleShape
            for (i in 0 until h.days) {
                val d = start.plusDays(i.toLong()).toString()
                val c = when {
                    d in done -> MaterialTheme.colorScheme.primary
                    !h.enabled || expired -> MaterialTheme.colorScheme.surfaceVariant
                    d == todayS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    d < todayS -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(
                    Modifier.size(11.dp)
                        .background(c, shape)
                        .then(if (d == todayS && h.enabled && !expired)
                                  Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                              else Modifier),
                )
            }
        }
        if (h.enabled && !expired && day > 0) {
            if (doneToday) {
                Text(stringResource(R.string.home_habit_done_today), color = MaterialTheme.colorScheme.secondary,
                     style = MaterialTheme.typography.titleSmall)
            } else {
                Button(onClick = {
                    Habits.markDone(ctx, h.id)
                    Toast.makeText(ctx, ctx.getString(R.string.home_habit_check_toast, day), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.home_habit_check_btn)) }
            }
        }
    }
}

@Composable
private fun ScoreCard() {
    val ctx = LocalContext.current
    val night = Time.today().minusDays(1).toString()
    val c0 = Checkins.all(ctx).firstOrNull { it.date == night }
    val e = KxStore.plan?.entryFor(night)
    if (c0 == null && e == null) return
    // 风屏自动补录：没手点过的夜晚，用"昨晚最后熄屏"作为实际风屏时刻（手机自己记得）
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    var autoWinddown: String? = null
    if (c0?.winddownAt.isNullOrBlank() && e != null && UsageProbe.hasAccess(ctx)) {
        val facts = runCatching {
            val (from, to) = PlanEngine.nightWindowOf(e, KxStore.config.wake)
            UsageProbe.nightFacts(ctx, from, to, Time.now().toLocalDateTime())
        }.getOrNull()
        autoWinddown = facts?.lastScreenOff?.format(fmt)
    }
    val c = if (autoWinddown != null)
        (c0 ?: Checkin(date = night)).copy(winddownAt = autoWinddown)
    else c0
    val r = Score.daily(c, e?.winddown, e?.bedtime, KxStore.config.wake)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val streak = Score.streak(Checkins.all(ctx))
            val nextTier = listOf(3, 7, 14, 28).firstOrNull { it > streak }
            Text(
                stringResource(R.string.home_score_title, r.total) +
                    (if (streak > 0) pluralStringResource(R.plurals.home_score_streak, streak, streak) else "") +
                    (nextTier?.let { stringResource(R.string.home_score_badge_next, it, it - streak) }
                        ?: stringResource(R.string.home_score_badge_max)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(Score.voice(KxStore.config.coachStyle, r), style = MaterialTheme.typography.bodyMedium)
            // 扣分项一眼可见：三项细分 + 各自满分（权重跟手册口径一致：起床最重）
            Text(
                stringResource(
                    R.string.home_score_parts,
                    r.winddown, Score.MAX_WINDDOWN, r.sleep, Score.MAX_SLEEP, r.wake, Score.MAX_WAKE,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val wdAuto = autoWinddown
            val noDetails = stringResource(R.string.home_no_details)
            val sep = stringResource(R.string.score_notes_sep)
            // 风屏是自动记录的时候，用它替掉第一条扣分项（Score 保证 notes[0] 恒为风屏项，
            // 不能拿中文子串匹配——文案本地化后就对不上了）
            val notes = if (wdAuto != null && c0?.winddownAt.isNullOrBlank())
                r.notes.drop(1).joinToString(sep).ifBlank { noDetails } +
                    stringResource(R.string.home_winddown_auto_note, wdAuto)
            else r.notes.joinToString(sep).ifBlank { noDetails }
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CheckinCard(tonightEntry: PlanDay?) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val night = Time.today().minusDays(1)
    val nightEntry = KxStore.plan?.entryFor(night.toString())
    // 预设目标入睡：昨晚在计划内用昨晚档位；计划外（含计划前夜晚）用当晚实际基准
    val presetTarget = nightEntry?.bedtime ?: KxStore.config.currentBedtime
    var bedtime by remember(night) { mutableStateOf(presetTarget) }
    var note by remember { mutableStateOf("") }
    var showPick by remember { mutableStateOf(false) }
    // 早上起床：空 = 用自动探测的首次拿起；非空 = 手选覆盖（海报和「起床 40 分」都按它算）
    var wakeOverride by remember { mutableStateOf("") }
    var showWakePick by remember { mutableStateOf(false) }
    var posterFile by remember { mutableStateOf<java.io.File?>(null) }   // 打卡后自动出的那张海报
    // 风屏块从「今晚」归属翻转（中午）起一直挂着，风屏开始后 6 小时才收走：
    // 它要一直在列表里按到点时间参与排序，而不是到点前 1 小时才冒出来。
    val nowLdt = Time.now().toLocalDateTime()
    val winddownDay: PlanDay? = tonightEntry?.takeIf {
        val at = PlanEngine.winddownStartOf(it, KxStore.config.winddownMin)
        nowLdt.isBefore(at.plusHours(6))
    }
    // 昨晚的结果记过了就收成一行，别让整张表单常年占着地方
    val nightRec = Checkins.all(ctx).firstOrNull { it.date == night.toString() }
    var editing by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // —— 今晚风屏（过程打卡：现在就能记）——
            // 用哪一天由外层统一算好传进来（排序和显示必须是同一天）
            val tonightIso = tonightEntry?.date ?: Checkins.tonightDate().toString()
            var winddownLogged by remember { mutableStateOf(
                Checkins.all(ctx).firstOrNull { it.date == tonightIso }?.winddownAt) }
            if (winddownDay != null) {
                Text(stringResource(R.string.home_tonight_winddown, winddownDay.winddown),
                    style = MaterialTheme.typography.titleMedium)
                val logged = winddownLogged
                if (logged.isNullOrBlank()) {
                    Button(onClick = {
                        val now = Time.now().toLocalTime()
                        val t = "%02d:%02d".format(now.hour, now.minute)
                        Checkins.logWinddown(ctx, tonightIso, t)
                        if (KxStore.config.pcUploadCheckin) {
                            val c = Checkins.all(ctx).firstOrNull { it.date == tonightIso }
                                ?: Checkin(tonightIso)
                            scope.launch(Dispatchers.IO) {
                                runCatching { Checkins.upload(ctx, KxStore.config.pcUrl, c) }
                            }
                        }
                        winddownLogged = t
                        Toast.makeText(ctx, ctx.getString(R.string.home_winddown_logged_toast, t), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.home_winddown_start_btn)) }
                } else {
                    Text(stringResource(R.string.home_winddown_logged, logged), color = MaterialTheme.colorScheme.secondary,
                         style = MaterialTheme.typography.titleSmall)
                }
                // 助眠图：已配生图模型或文本模型支持生图时提供，竖屏壁纸比例
                if (KxStore.config.effectiveImageModel().isNotBlank()) {
                    var genBusy by remember { mutableStateOf(false) }
                    var genErr by remember { mutableStateOf<String?>(null) }
                    var genFile by remember { mutableStateOf<java.io.File?>(null) }
                    var savedUri by remember { mutableStateOf<android.net.Uri?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            genBusy = true; genErr = null
                            scope.launch(Dispatchers.IO) {
                                val dayIdx = winddownDay.day - 1
                                val f = runCatching {
                                    local.kuaixiu.data.ImageGen.generate(
                                        ctx, KxStore.config,
                                        local.kuaixiu.data.ImageGen.sleepPrompt(dayIdx),
                                        local.kuaixiu.data.ImageGen.PORTRAIT_SIZE)
                                }
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    f.onSuccess { genFile = it }
                                        .onFailure { genErr = it.message }
                                    genBusy = false
                                }
                            }
                        }, enabled = !genBusy) {
                            Text(stringResource(if (genBusy) R.string.home_image_generating else R.string.home_image_gen_btn))
                        }
                        if (genFile != null && savedUri == null) {
                            OutlinedButton(onClick = {
                                genFile?.let {
                                    savedUri = local.kuaixiu.data.ImageGen.saveToGallery(ctx, it)
                                    if (savedUri != null) Toast.makeText(ctx, ctx.getString(R.string.home_image_saved_toast), Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(stringResource(R.string.home_image_save_btn)) }
                        }
                    }
                    genErr?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    genFile?.let { f ->
                        val bmp = remember(f.absolutePath) {
                            runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
                        }
                        if (bmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.home_image_desc),
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            )
                        }
                    }
                }
            }

            if (winddownDay != null) androidx.compose.material3.HorizontalDivider()

            // —— 昨晚入睡（结果打卡：次日早上记；入睡估计来自系统最后亮屏）——
            if (nightRec?.status?.isNotBlank() == true && !editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.home_last_night_checked,
                            stringResource(
                                when (nightRec.status) {
                                    "on_time" -> R.string.home_status_on_time
                                    "late" -> R.string.home_status_late
                                    else -> R.string.home_status_missed
                                }
                            ),
                            if (nightRec.bedtime.isBlank()) "" else " ${nightRec.bedtime}",
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = {
                        nightRec.bedtime.takeIf { it.isNotBlank() }?.let { bedtime = it }
                        editing = true
                    }) { Text(stringResource(R.string.home_edit_btn)) }
                }
                return@Column   // 已打卡就一行收尾，不再往下画整张表单
            }
            var usageDenied by remember { mutableStateOf(false) }
            var facts by remember { mutableStateOf<UsageProbe.NightFacts?>(null) }
            LaunchedEffect(night) {
                val entry = nightEntry ?: return@LaunchedEffect
                if (!UsageProbe.hasAccess(ctx)) {
                    usageDenied = true
                    return@LaunchedEffect
                }
                val wd = PlanEngine.winddownStartOf(entry, KxStore.config.winddownMin)
                val anchor = java.time.LocalDateTime.of(Time.today(),
                    java.time.LocalTime.parse(KxStore.config.wake))
                facts = UsageProbe.nightFacts(ctx, wd, anchor, Time.now().toLocalDateTime())
            }
            val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

            // —— 早上起床（放在「昨晚入睡」上面）：默认自动探测，可手选覆盖 ——
            val autoWake = facts?.firstMorningPickup
            val wakeShown = wakeOverride.ifBlank { autoWake?.format(fmt).orEmpty() }
            Text(stringResource(R.string.home_wake_title), style = MaterialTheme.typography.titleMedium)
            val unsetText = stringResource(R.string.home_unset)
            val wakeDesc = stringResource(R.string.home_wake_desc, wakeShown.ifBlank { unsetText })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = wakeShown, onValueChange = {},
                        readOnly = true, singleLine = true,
                        label = { Text(stringResource(R.string.home_wake_label), maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(
                        Modifier.matchParentSize()
                            .clickable(onClickLabel = stringResource(R.string.home_pick_wake)) { showWakePick = true }
                            .semantics { contentDescription = wakeDesc },
                    )
                }
                if (wakeOverride.isNotBlank()) {
                    TextButton(onClick = { wakeOverride = "" }) { Text(stringResource(R.string.home_use_auto)) }
                }
            }
            // 跟「昨晚最后亮屏」对应的那行：系统探测的原始事实，不随手选改变
            autoWake?.let { w ->
                val devTxt = Score.diffMinutes(KxStore.config.wake, w.format(fmt))?.let { d ->
                    if (d >= 0) stringResource(R.string.home_target_diff_over, d)
                    else stringResource(R.string.home_target_diff_under, d)
                }
                Text(stringResource(R.string.home_first_pickup, w.format(fmt)) +
                    (devTxt?.let { stringResource(R.string.home_parenthetical, it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (showWakePick) {
                TimePickerDialog(
                    wakeOverride.ifBlank { autoWake?.format(fmt) ?: KxStore.config.wake },
                    onDismiss = { showWakePick = false },
                    onConfirm = { wakeOverride = it; showWakePick = false },
                )
            }
            Text(stringResource(R.string.home_sleep_title), style = MaterialTheme.typography.titleMedium)
            val bedDesc = stringResource(R.string.home_actual_bedtime_desc, bedtime)
            // 跟「起床时间」同一款排版：整宽字段，点整块开时间盘
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = bedtime, onValueChange = {},
                    readOnly = true, singleLine = true,
                    label = { Text(stringResource(R.string.home_actual_bedtime_label), maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    Modifier.matchParentSize()
                        .clickable(onClickLabel = stringResource(R.string.home_pick_bedtime)) { showPick = true }
                        .semantics { contentDescription = bedDesc },
                )
            }
            facts?.let { f2 ->
                val pickup = f2.firstMorningPickup
                val lastOn = f2.lastInteractive?.takeIf { pickup == null || it < pickup }
                if (lastOn != null) {
                    // 放下手机的目标 = 昨晚计划的风屏开始时刻；计划外的一夜没有目标就只报事实
                    val devTxt = nightEntry?.let { e ->
                        Score.diffMinutes(
                            PlanEngine.winddownStartOf(e, KxStore.config.winddownMin).toLocalTime().format(fmt),
                            lastOn.format(fmt),
                        )?.let { d ->
                            if (d >= 0) stringResource(R.string.home_target_diff_over, d)
                            else stringResource(R.string.home_target_diff_under, d)
                        }
                    }
                    Text(stringResource(R.string.home_last_screen_on, lastOn.format(fmt)) +
                        (devTxt?.let { stringResource(R.string.home_parenthetical, it) }
                            ?: stringResource(R.string.home_phone_down)),
                        style = MaterialTheme.typography.bodySmall)
                } else if (pickup != null) {
                    Text(stringResource(R.string.home_no_more_screen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
                LaunchedEffect(f2) { /* 预填改由下方 chips 显式选择 */ }
            }
            if (usageDenied) {
                androidx.compose.material3.TextButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.home_usage_access_btn)) }
            }
            // 状态不手选，保存时按「实际入睡 vs 目标」自检；时间就靠上面那个字段
            val target = nightEntry?.bedtime ?: KxStore.config.currentBedtime
            if (showPick) {
                TimePickerDialog(
                    bedtime,
                    onDismiss = { showPick = false },
                    onConfirm = { bedtime = it; showPick = false },
                )
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text(stringResource(R.string.home_note_label), maxLines = 1) },
                placeholder = { Text(stringResource(R.string.home_note_optional)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {
                    // 状态自检：比目标晚 30 分钟以内算准时，再晚算迟睡
                    val d = Score.diffMinutes(target, bedtime.trim())
                    val autoStatus = if (d != null && d > 30) "late" else "on_time"
                    // 手选优先；没手选就用自动探测的首次拿起
                    val wakeup = wakeOverride.ifBlank { facts?.firstMorningPickup?.format(fmt) ?: "" }
                    // 放下手机时刻自动探测（熄屏优先，否则最后交互 +2 分钟）：手填的入睡不可靠，周报按这个算
                    val phoneDown = facts?.let { UsageProbe.estimateOnset(it)?.format(fmt) } ?: ""
                    val c = Checkin(night.toString(), autoStatus, bedtime.trim(), note.trim(),
                        wakeupAt = wakeup, phoneDownAt = phoneDown)
                    Checkins.add(ctx, c)
                    if (KxStore.config.pcUploadCheckin) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { Checkins.upload(ctx, KxStore.config.pcUrl, c) }
                        }
                    }
                    note = ""
                    editing = false   // 记完收成一行；bedtime 留着，点「改一下」时还是这条记录
                    Toast.makeText(ctx, ctx.getString(R.string.home_checkin_saved_toast), Toast.LENGTH_SHORT).show()
                    // 打卡后自动出一张 A 版海报（内置底图秒出）：看一眼，再决定存/分享
                    local.kuaixiu.KxScope.app.launch {
                        runCatching {
                            local.kuaixiu.data.PosterFlow.make(ctx,
                                local.kuaixiu.data.PosterTemplate.A,
                                local.kuaixiu.data.PosterMode.LOCAL_ONLY)
                        }.onSuccess { f ->
                            kotlinx.coroutines.withContext(Dispatchers.Main) { posterFile = f }
                        }.onFailure { e ->
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, ctx.getString(R.string.home_poster_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text(stringResource(R.string.home_checkin_save_btn)) }
                // 展开后能收回去（有记录可回时才给，否则收起来就没内容了）
                if (nightRec != null) {
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.home_collapse)) }
                }
            }

            val recent = Checkins.all(ctx).takeLast(5)
            if (recent.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.home_recent,
                        recent.joinToString("；") {
                            // joinToString 的转换 lambda 里不能调 composable，走 ctx.getString
                            (if (it.winddownAt.isNotBlank())
                                 ctx.getString(R.string.home_recent_winddown, it.winddownAt) else "") +
                                "${it.date.drop(5)} ${it.status}"
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // 打卡后自动出的海报：先看一眼，再决定存/分享
    posterFile?.let { f -> PosterResultDialog(file = f, onDismiss = { posterFile = null }) }
}

@Composable
private fun UpcomingCard(plan: Plan) {
    val today = Time.today().toString()
    val upcoming = plan.days.filter { it.date > today }.take(5)
    if (upcoming.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_upcoming_title), style = MaterialTheme.typography.titleMedium)
            upcoming.forEach {
                Text(stringResource(R.string.home_upcoming_row, it.date.drop(5), it.weekday,
                    PlanEngine.phaseLabel(it.stage), it.bedtime))
            }
        }
    }
}

/**
 * 常显的手动添加入口。教练页要配 Key 才能用「加提醒/建习惯」的动作卡，
 * 这里给出不依赖任何 AI 的直连入口（写库逻辑与动作卡同一套，见 ActionRunner）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddCard(onSaved: () -> Unit) {
    var addReminder by remember { mutableStateOf(false) }
    var addHabit by remember { mutableStateOf(false) }
    var poster by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_manual_title), style = MaterialTheme.typography.titleMedium)
            // 三个按钮一行放不下：Row 会把最后一个压成竖排单字，用 FlowRow 让它整颗换行
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addReminder = true }) { Text(stringResource(R.string.home_manual_reminder)) }
                OutlinedButton(onClick = { addHabit = true }) { Text(stringResource(R.string.home_manual_habit)) }
                OutlinedButton(onClick = { poster = true }) { Text(stringResource(R.string.home_manual_poster)) }
            }
            Text(
                stringResource(R.string.home_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (addReminder) ReminderDialog(onDismiss = { addReminder = false; onSaved() })
    if (addHabit) HabitDialog(onDismiss = { addHabit = false })
    if (poster) PosterDialog(onDismiss = { poster = false })
}
