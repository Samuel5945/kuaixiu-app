package local.kuaixiu.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import local.kuaixiu.engine.Plan
import java.time.LocalDate

private val CALENDAR_PERMS = arrayOf(
    android.Manifest.permission.READ_CALENDAR,
    android.Manifest.permission.WRITE_CALENDAR,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(Unit) { perms.launch(CALENDAR_PERMS) }

    val plan = KxStore.plan
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (plan == null) {
            item { EmptyCard() }
        } else {
            // 顺序：昨晚成绩 → 今晚行动（风屏/打卡）→ 待办提醒 → 习惯 → 今日参考 → 未来 → 低频操作
            item { ScoreCard() }
            item { CheckinCard() }
            item { ReminderCard() }
            item { HabitCard() }
            item { TodayCard(plan) }
            item { UpcomingCard(plan) }
            item { SyncCard() }
        }
        item {
            Text(
                "提醒由系统日历触发；荣耀日历里勾选显示「快休早睡」即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("还没有计划", style = MaterialTheme.typography.titleLarge)
            Text(
                "到「设置」确认作息参数（当前入睡/目标/起床），保存后自动生成 28 天计划并写入日历（默认今晚开始）。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                runCatching { KxStore.syncCalendar(ctx) }
                    .onSuccess { Toast.makeText(ctx, "已生成并写入 $it 个事件", Toast.LENGTH_LONG).show() }
                    .onFailure { android.util.Log.e("Kuaixiu", "sync failed", it); Toast.makeText(ctx, "失败: ${it::class.simpleName}: ${it.message}", Toast.LENGTH_LONG).show() }
            }) { Text("立即生成计划") }
        }
    }
}

@Composable
private fun TodayCard(plan: Plan) {
    val today = LocalDate.now().toString()
    val d = plan.entryFor(today) ?: plan.upcoming(today) ?: plan.days.last()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "第 ${d.day} 天（${d.date} ${d.weekday}）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "🌙 目标入睡 ${d.bedtime}",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("💤 ${d.winddown} 开始风屏（${plan.config.winddownMin} 分钟）  ☀️ 起床 ${plan.config.wake}")
            Text("主题：${d.stage} · ${d.theme}")
            d.tasks.forEach { Text("· $it") }
            Text(
                "小提示：${d.tip}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shiftDone = plan.summary.totalShiftMinutes - shiftRemaining(plan, d.bedtime)
            Text(
                "进度：第 ${d.day}/${plan.days.size} 天 · 已提前 $shiftDone 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            plan.summary.daysToTarget?.let {
                Text(
                    "目标 ${plan.config.targetBedtime} 将在第 $it 天达成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun ReminderCard() {
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
    LaunchedEffect(Unit) { reload() }
    if (items.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("待办提醒", style = MaterialTheme.typography.titleMedium)
            items.forEach { (id, title, startMs) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title)
                        val at = java.time.Instant.ofEpochMilli(startMs)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
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
                                Toast.makeText(ctx, if (ok) "已删除" else "删除失败",
                                    Toast.LENGTH_SHORT).show()
                                if (ok) reload()
                            }
                        }
                    }) { Text("删除") }
                }
            }
            Text("到点由系统日历弹通知，不依赖本 App 后台。",
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
    val today = LocalDate.now()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("习惯", style = MaterialTheme.typography.titleMedium)
            items.forEach { HabitRow(it, today) }
            Text(
                "到点由系统日历在「快休习惯」里提醒，一次写入连响 N 天；重算作息计划不会动它。",
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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.name, modifier = Modifier.weight(1f),
                 style = MaterialTheme.typography.titleSmall)
            if (!h.enabled) {
                TextButton(onClick = {
                    runCatching { Habits.setEnabled(ctx, h.id, on = true) }.onSuccess { r ->
                        when (r) {
                            is HabitCheck.Ok -> Toast.makeText(ctx, "已恢复，从今天起再走 ${r.habit.days} 天",
                                Toast.LENGTH_LONG).show()
                            is HabitCheck.QuotaFull -> Toast.makeText(ctx,
                                "「${r.activeNames.joinToString("、")}」还在跑，先暂停一个再恢复", Toast.LENGTH_LONG).show()
                            is HabitCheck.AnchorConflict -> Toast.makeText(ctx,
                                "锚点和「${r.otherName}」只差 ${r.gapMin} 分钟，恢复会被拦下", Toast.LENGTH_LONG).show()
                            is HabitCheck.Invalid -> Toast.makeText(ctx, r.reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("恢复") }
            } else {
                TextButton(onClick = {
                    runCatching { Habits.setEnabled(ctx, h.id, on = false) }.onSuccess {
                        Toast.makeText(ctx, "已暂停，日历不再提醒（已打卡的天数留着）", Toast.LENGTH_LONG).show()
                    }
                }) { Text("暂停") }
            }
            TextButton(onClick = {
                runCatching { Habits.remove(ctx, h.id) }
                    .onSuccess { Toast.makeText(ctx, "已删除「${h.name}」，日历事件一并清掉", Toast.LENGTH_LONG).show() }
            }) { Text("删除") }
        }
        Text(
            when {
                !h.enabled -> "暂停中 · 锚点 ${h.cueTime}"
                day == 0 -> "还没开始 · ${h.startDate} 起每天 ${h.cueTime}"
                expired -> "已满 ${h.days} 天 · 锚点 ${h.cueTime} · 达成 ${hits.first}/${hits.second}"
                else -> "第 $day/${h.days} 天 · 锚点 ${h.cueTime} · 连续 ${Habits.streakOf(h, today)} 天" +
                    " · 达成 ${hits.first}/${hits.second}"
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
                Text("今日已打卡 ✓", color = MaterialTheme.colorScheme.secondary,
                     style = MaterialTheme.typography.titleSmall)
            } else {
                Button(onClick = {
                    Habits.markDone(ctx, h.id)
                    Toast.makeText(ctx, "记上了，第 $day 天 🔥", Toast.LENGTH_SHORT).show()
                }) { Text("今天做到了，打卡") }
            }
        }
    }
}

@Composable
private fun SyncCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                runCatching { KxStore.syncCalendar(ctx) }
                    .onSuccess { Toast.makeText(ctx, "已写入 $it 个事件到系统日历", Toast.LENGTH_LONG).show() }
                    .onFailure { android.util.Log.e("Kuaixiu", "sync failed", it); Toast.makeText(ctx, "失败: ${it::class.simpleName}: ${it.message}", Toast.LENGTH_LONG).show() }
            }) { Text("同步到日历") }
            Text(
                "改过作息后点一次即可；重复点会删旧插新，不会产生重复事件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScoreCard() {
    val ctx = LocalContext.current
    val night = LocalDate.now().minusDays(1).toString()
    val c0 = Checkins.all(ctx).firstOrNull { it.date == night }
    val e = KxStore.plan?.entryFor(night)
    if (c0 == null && e == null) return
    // 风屏自动补录：没手点过的夜晚，用"昨晚最后熄屏"作为实际风屏时刻（手机自己记得）
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    var autoWinddown: String? = null
    if (c0?.winddownAt.isNullOrBlank() && e != null && UsageProbe.hasAccess(ctx)) {
        val facts = runCatching {
            UsageProbe.nightFacts(
                ctx, PlanEngine.winddownStartOf(e, KxStore.config.winddownMin),
                java.time.LocalDateTime.of(LocalDate.parse(e.date), java.time.LocalTime.parse(KxStore.config.wake)),
                java.time.LocalDateTime.now(),
            )
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
                "☀️ 昨晚评分 ${r.total}/100" +
                    (if (streak > 0) "  🔥 连续准时 $streak 天" else "") +
                    (nextTier?.let { "（$it 天徽章还差 ${it - streak} 天）" } ?: "（满级徽章 🏆）"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(Score.voice(KxStore.config.coachStyle, r), style = MaterialTheme.typography.bodyMedium)
            val notes = if (autoWinddown != null && c0?.winddownAt.isNullOrBlank())
                r.notes.filterNot { it.contains("风屏") }.joinToString("；")
                    .ifBlank { "暂无明细" } + "；风屏已自动记录（最后亮屏 $autoWinddown）"
            else r.notes.joinToString("；").ifBlank { "暂无明细" }
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
private fun CheckinCard() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember { mutableStateOf("on_time") }
    val night = LocalDate.now().minusDays(1)
    val nightEntry = KxStore.plan?.entryFor(night.toString())
    // 预设目标入睡：昨晚在计划内用昨晚档位；计划外（含计划前夜晚）用当晚实际基准
    val presetTarget = nightEntry?.bedtime ?: KxStore.config.currentBedtime
    var bedtime by remember(night) { mutableStateOf(presetTarget) }
    var note by remember { mutableStateOf("") }
    var showPick by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // —— 今晚风屏（过程打卡：现在就能记）——
            val tonight = Checkins.tonightDate()
            val tonightEntry = KxStore.plan?.entryFor(tonight.toString())
            var winddownLogged by remember { mutableStateOf(
                Checkins.all(ctx).firstOrNull { it.date == tonight.toString() }?.winddownAt) }
            if (tonightEntry != null) {
                Text("🌙 今晚风屏 · 计划 ${tonightEntry.winddown} 开始",
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "放下手机开始仪式时点一下，记录的是执行过程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val logged = winddownLogged
                if (logged.isNullOrBlank()) {
                    Button(onClick = {
                        val now = java.time.LocalTime.now()
                        val t = "%02d:%02d".format(now.hour, now.minute)
                        Checkins.logWinddown(ctx, tonight.toString(), t)
                        if (KxStore.config.pcUploadCheckin) {
                            val c = Checkins.all(ctx).firstOrNull { it.date == tonight.toString() }
                                ?: Checkin(tonight.toString())
                            scope.launch(Dispatchers.IO) {
                                runCatching { Checkins.upload(ctx, KxStore.config.pcUrl, c) }
                            }
                        }
                        winddownLogged = t
                        Toast.makeText(ctx, "已记录风屏 $t ✅", Toast.LENGTH_SHORT).show()
                    }) { Text("开始风屏了，记录一下") }
                } else {
                    Text("已记录 $logged ✅", color = MaterialTheme.colorScheme.secondary,
                         style = MaterialTheme.typography.titleSmall)
                }
                // 助眠图：已配生图模型时提供，竖屏壁纸比例
                if (KxStore.config.imageModel.isNotBlank()) {
                    var genBusy by remember { mutableStateOf(false) }
                    var genErr by remember { mutableStateOf<String?>(null) }
                    var genFile by remember { mutableStateOf<java.io.File?>(null) }
                    var savedUri by remember { mutableStateOf<android.net.Uri?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            genBusy = true; genErr = null
                            scope.launch(Dispatchers.IO) {
                                val dayIdx = tonightEntry.day - 1
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
                            Text(if (genBusy) "绘制中…" else "🎨 生成今晚助眠图")
                        }
                        if (genFile != null && savedUri == null) {
                            OutlinedButton(onClick = {
                                genFile?.let {
                                    savedUri = local.kuaixiu.data.ImageGen.saveToGallery(ctx, it)
                                    if (savedUri != null) Toast.makeText(ctx, "已保存到相册", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("存相册") }
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
                                contentDescription = "今晚助眠图",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                            )
                        }
                    }
                }
            }

            androidx.compose.material3.HorizontalDivider()

            // —— 昨晚入睡（结果打卡：次日早上记；入睡估计来自系统最后亮屏）——
            Text("😴 昨晚入睡", style = MaterialTheme.typography.titleMedium)
            nightEntry?.bedtime?.let {
                Text("昨晚计划入睡 $it", style = MaterialTheme.typography.bodySmall)
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
                val anchor = java.time.LocalDateTime.of(java.time.LocalDate.now(),
                    java.time.LocalTime.parse(KxStore.config.wake))
                facts = UsageProbe.nightFacts(ctx, wd, anchor, java.time.LocalDateTime.now())
            }
            val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            facts?.let { f2 ->
                val inMorning = java.time.LocalTime.now().hour in 4..12
                val pickup = if (inMorning) f2.firstMorningPickup else null
                val lastOn = f2.lastInteractive?.takeIf { pickup == null || it < pickup }
                if (lastOn != null) {
                    Text("📱 昨晚最后亮屏 ${lastOn.format(fmt)}（放下手机）",
                        style = MaterialTheme.typography.bodySmall)
                } else if (pickup != null) {
                    Text("📱 昨晚风屏后未再亮屏 ✅（直到今早拿起）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
                pickup?.let {
                    val anchorT = java.time.LocalTime.parse(KxStore.config.wake)
                    val dev = java.time.Duration.between(anchorT, java.time.LocalTime.from(it)).toMinutes()
                    val devTxt = if (dev >= 0) "+$dev 分钟" else "$dev 分钟"
                    Text("☀️ 今早首次拿起 ${it.format(fmt)}（锚点 ${KxStore.config.wake} $devTxt）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (kotlin.math.abs(dev) <= 30) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.error,
                    )
                }
                LaunchedEffect(f2) { /* 预填改由下方 chips 显式选择 */ }
            }
            if (usageDenied) {
                androidx.compose.material3.TextButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("授予「使用情况访问」权限，自动记录入睡/起床事实 →") }
            }
            Text(
                "早上醒来补记最准；睡前的此刻没法预知，所以放在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("on_time" to "准时入睡", "late" to "迟睡了", "skip" to "没做到").forEach { (k, label) ->
                    FilterChip(
                        selected = status == k,
                        onClick = { status = k },
                        label = { Text(label) },
                    )
                }
            }
            val target = nightEntry?.bedtime ?: KxStore.config.currentBedtime
            run {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = bedtime == target, onClick = { bedtime = target },
                        label = { Text("目标 $target") })
                    facts?.let { f2 ->
                        UsageProbe.estimateOnset(f2)?.let { est ->
                            val estS = est.format(fmt)
                            FilterChip(selected = bedtime == estS, onClick = { bedtime = estS },
                                label = { Text("📱 $estS") })
                        }
                    }
                    FilterChip(selected = false, onClick = {
                        bedtime = local.kuaixiu.engine.PlanEngine.minToHHMM(
                            local.kuaixiu.engine.PlanEngine.hhmmToMin(target) + 15)
                    }, label = { Text("+15") })
                    FilterChip(selected = false, onClick = {
                        bedtime = local.kuaixiu.engine.PlanEngine.minToHHMM(
                            local.kuaixiu.engine.PlanEngine.hhmmToMin(target) + 30)
                    }, label = { Text("+30") })
                    FilterChip(selected = false, onClick = { showPick = true }, label = { Text("自选…") })
                }
                if (showPick) {
                    val init = runCatching { java.time.LocalTime.parse(bedtime) }
                        .getOrElse { java.time.LocalTime.of(23, 0) }
                    val st = androidx.compose.material3.rememberTimePickerState(
                        initialHour = init.hour, initialMinute = init.minute, is24Hour = true)
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showPick = false },
                        confirmButton = {
                            TextButton(onClick = {
                                bedtime = "%02d:%02d".format(st.hour, st.minute); showPick = false
                            }) { Text("确定") }
                        },
                        dismissButton = { TextButton(onClick = { showPick = false }) { Text("取消") } },
                        text = { Column(Modifier.heightIn(max = 420.dp)) {
                            androidx.compose.material3.TimePicker(state = st)
                        } },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bedtime, onValueChange = { bedtime = it },
                    label = { Text("实际入睡") }, placeholder = { Text("23:45") },
                    modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注（可选）") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            Row {
                Button(onClick = {
                    val wakeup = facts?.firstMorningPickup?.format(fmt) ?: ""
                    val c = Checkin(night.toString(), status, bedtime.trim(), note.trim(), wakeupAt = wakeup)
                    Checkins.add(ctx, c)
                    if (KxStore.config.pcUploadCheckin) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { Checkins.upload(ctx, KxStore.config.pcUrl, c) }
                        }
                    }
                    bedtime = ""; note = ""
                    Toast.makeText(ctx, "已打卡 ✅", Toast.LENGTH_SHORT).show()
                }) { Text("保存打卡") }
            }

            val recent = Checkins.all(ctx).takeLast(5)
            if (recent.isNotEmpty()) {
                Text(
                    "最近：" + recent.joinToString("；") {
                        (if (it.winddownAt.isNotBlank()) "风屏${it.winddownAt} " else "") +
                            "${it.date.drop(5)} ${it.status}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UpcomingCard(plan: Plan) {
    val today = LocalDate.now().toString()
    val upcoming = plan.days.filter { it.date > today }.take(5)
    if (upcoming.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("接下来几天", style = MaterialTheme.typography.titleMedium)
            upcoming.forEach {
                Text("${it.date.drop(5)} ${it.weekday}  目标 ${it.bedtime}  （${it.stage}）")
            }
        }
    }
}
