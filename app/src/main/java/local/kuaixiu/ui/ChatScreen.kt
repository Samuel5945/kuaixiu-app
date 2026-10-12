package local.kuaixiu.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.kuaixiu.data.ChatLog
import local.kuaixiu.data.HabitCheck
import local.kuaixiu.data.Habits
import local.kuaixiu.data.ImageGen
import local.kuaixiu.data.KxStore
import local.kuaixiu.llm.CoachPrompt
import local.kuaixiu.llm.LlmClient
import org.json.JSONObject
import java.time.LocalDate

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    ChatStore.load(ctx)
    val msgs = ChatStore.msgs
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menuIdx by remember { mutableStateOf<Int?>(null) }   // 长按气泡弹出的复制/删除菜单
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 动作卡是列表里的独立一行，不塞进气泡：长回复会把按钮顶出视口，看着就像「没有卡片」
    val rows = rowsOf(msgs)

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding().padding(12.dp)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows) { r ->
                val m = msgs[r.idx]
                val aj = if (r.card) m.actionJson else null
                if (aj != null) {
                    ActionCard(aj, onImage = { prompt ->
                        val genIdx = msgs.size
                        ChatStore.addTransient(ChatMsg("assistant", "🎨 生图中…（约半分钟）"))
                        // 应用级作用域：切页签/锁屏不中断，完成后原地变成图片并落盘
                        local.kuaixiu.KxScope.app.launch {
                            runCatching { ImageGen.generate(ctx, KxStore.config, prompt) }
                                .onSuccess { f ->
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        ChatStore.replace(ctx, genIdx,
                                            ChatMsg("assistant", "🎨 ${prompt}", imagePath = f.absolutePath),
                                            persisted = true)
                                    }
                                }
                                .onFailure { e ->
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        ChatStore.replace(ctx, genIdx,
                                            ChatMsg("assistant", "生图失败：${e.message}"), persisted = true)
                                    }
                                }
                        }
                    })
                } else {
                val isUser = m.role == "user"
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 18.dp, topEnd = 18.dp,
                            bottomStart = if (isUser) 18.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 18.dp,
                        ),
                        color = if (isUser) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 1080.dp).fillMaxWidth(0.92f)
                            .pointerInput(r.idx) {
                                detectTapGestures(onLongPress = { menuIdx = r.idx })
                            },
                    ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.text)
                        m.imagePath?.let { path ->
                            val bmp = remember(path) {
                                runCatching {
                                    val bounds = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeFile(path, bounds)
                                    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1024)
                                    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                                    android.graphics.BitmapFactory.decodeFile(path, o)
                                }.getOrNull()
                            }
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "生成的图片",
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                )
                            }
                        }
                    }
                    }
                }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("和教练聊聊…") },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                maxLines = 4,
            )
            Button(
                onClick = {
                    val q = input.trim()
                    if (q.isEmpty() || busy) return@Button
                    if (KxStore.config.llmKey.isBlank()) {
                        Toast.makeText(ctx, "先到「设置」里填 LLM API Key", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    input = ""
                    ChatStore.add(ctx, "user", q)
                    busy = true
                    val placeholderIdx = msgs.size
                    msgs.add(ChatMsg("assistant", "…"))  // 瞬态占位，不入盘
                    // 应用级作用域：切页签/锁屏流式不中断
                    local.kuaixiu.KxScope.app.launch {
                        runCatching {
                            val history = msgs.filter {
                                !it.text.startsWith("我是你的早睡教练") && it.text != "…"
                            }.takeLast(12).map { it.role to it.text }
                            val sys = CoachPrompt.system(ctx)
                            LlmClient(KxStore.config.llmBaseUrl, KxStore.config.llmKey)
                                .chatStreaming(KxStore.config.llmModel, listOf("system" to sys) + history) { full ->
                                    if (placeholderIdx < msgs.size) {
                                        msgs[placeholderIdx] = ChatMsg("assistant", full)
                                    }
                                }
                        }.onSuccess { reply ->
                            val action = LlmClient.parseAction(reply)
                            val shown = LlmClient.stripAction(reply)
                            if (placeholderIdx < msgs.size) {
                                msgs[placeholderIdx] = ChatMsg("assistant", shown.ifBlank { "（建议如下）" }, action?.toString())
                            }
                            ChatLog.append(ctx, "assistant", shown.ifBlank { "（建议如下）" },
                                actionJson = action?.toString() ?: "")
                            busy = false
                        }.onFailure { e ->
                            if (placeholderIdx < msgs.size) {
                                msgs[placeholderIdx] = ChatMsg("assistant", "出错了：${e.message}\n检查设置页的 base_url / key / model。")
                            }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            ) { Text("发送") }
        }
    }

    // 长按气泡 → 复制 / 删除
    menuIdx?.let { i ->
        val m = msgs.getOrNull(i)
        if (m == null) { menuIdx = null; return@let }
        AlertDialog(
            onDismissRequest = { menuIdx = null },
            title = { Text(if (m.role == "user") "我说的" else "教练说的") },
            text = {
                Text(m.text, maxLines = 6,
                     overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                    menuIdx = null
                    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ChatStore.remove(ctx, i)
                    menuIdx = null
                    Toast.makeText(ctx, "已删除这条", Toast.LENGTH_SHORT).show()
                }) { Text("删除") }
            },
        )
    }
}

@Composable
private fun ActionCard(actionJson: String, onImage: (String) -> Unit) {
    val ctx = LocalContext.current
    var applied by remember { mutableStateOf(false) }
    val action = runCatching { JSONObject(actionJson) }.getOrNull() ?: return
    val kind = action.optString("action")
    val habit = if (kind == "habit") parseHabit(action) else null
    // 第 3 个并行习惯不硬拦，但要用户看过代价再点第二次
    var ackCost by remember { mutableStateOf(false) }
    val habitCheck = habit?.takeIf { it.op == "add" }?.let {
        Habits.check(Habits.items, it.name, it.cue, it.days, it.start)
    }
    val habitBlocked = habitCheck is HabitCheck.AnchorConflict || habitCheck is HabitCheck.Invalid
    // 同名 add 就是"改这个习惯"（协议里没有 edit 动作，教练说"改成明天开始"只会再发一次 add）
    val habitUpdate = habit != null && habit.op == "add" &&
        Habits.items.any { it.name.trim() == habit.name.trim() }
    val actionable = kind in setOf("adjust", "checkin", "image", "reminder", "habit")
    Card(Modifier.fillMaxWidth(),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (kind) {
                "adjust" -> {
                    val labelMap = mapOf(
                        "currentBedtime" to "当前入睡", "targetBedtime" to "目标入睡",
                        "wake" to "起床锚点", "days" to "天数", "startDate" to "开始日期")
                    val parts = buildList {
                        labelMap.forEach { (k, label) ->
                            if (action.has(k)) add("$label → ${action.optString(k)}")
                        }
                    }
                    Text("📐 教练建议调整计划：", style = MaterialTheme.typography.titleSmall)
                    Text(parts.joinToString("  "))
                }
                "checkin" -> {
                    Text("✅ 教练帮你记一笔打卡：${action.optString("status")}" +
                        (if (action.optString("bedtime").isNotBlank()) "（${action.optString("bedtime")}）" else ""))
                }
                "image" -> {
                    Text("🎨 教练想画一张助眠画面：", style = MaterialTheme.typography.titleSmall)
                    Text(action.optString("prompt"))
                    if (applied) {
                        Text("🎨 已交给画师，成品马上贴在下面 ↓", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                }
                "reminder" -> {
                    val when_ = listOf(action.optString("date"), action.optString("time"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                    val lead = if (action.optInt("leadMin") > 0) "，提前 ${action.optInt("leadMin")} 分钟" else ""
                    if (action.optString("op") == "cancel") {
                        Text("🗑 教练帮你删掉提醒：${action.optString("text", "")}")
                    } else {
                        Text("⏰ 教练帮你加一个提醒：${action.optString("text", "提醒")}" +
                            (if (when_.isNotBlank()) "（$when_$lead）" else ""))
                    }
                }
                "habit" -> {
                    val h = habit ?: return@Column
                    when (h.op) {
                        "done" -> Text("✅ 教练帮你记一笔习惯打卡：${h.name}（今天）")
                        "pause" -> Text("⏸ 教练帮你暂停习惯：${h.name}（日历先不响，已打的天数留着）")
                        else -> {
                            Text(
                                if (habitUpdate) "🌱 教练要改这个习惯的参数：${h.name}"
                                else "🌱 教练想帮你开一个习惯：${h.name}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text((if (habitUpdate) "改成：" else "") +
                                "${h.start} 起，每天 ${h.cue} 系统日历提醒，连响 ${h.days} 天")
                            when (val r = habitCheck) {
                                is HabitCheck.AnchorConflict -> Text(
                                    "锚点和「${r.otherName}」只差 ${r.gapMin} 分钟——挨太近的两个执行意图会互相抵消，" +
                                        "先错开时间再来。",
                                    color = MaterialTheme.colorScheme.error)
                                is HabitCheck.QuotaFull -> Text(
                                    if (ackCost) "你已经看过代价，再点一次就写进日历。"
                                    else "「${r.activeNames.joinToString("、")}」还在跑。同时开第 3 个，" +
                                        "研究里成功率明显下降，建议先跑稳这两个。",
                                    color = MaterialTheme.colorScheme.tertiary)
                                is HabitCheck.Invalid -> Text(r.reason,
                                    color = MaterialTheme.colorScheme.error)
                                null, is HabitCheck.Ok -> {}
                            }
                        }
                    }
                }
                else -> {
                    // 不认识的动作也要看得见，否则卡片凭空消失，谁都不知道模型给了什么
                    Text("⚠️ App 还不认识这个动作：${kind.ifBlank { "（块里没有 action 字段）" }}",
                         style = MaterialTheme.typography.titleSmall)
                    Text(action.toString(), style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (actionable) Button(onClick = {
                if (applied) return@Button
                when (kind) {
                    "adjust" -> {
                        val cfg = KxStore.config
                        KxStore.saveConfig(ctx, cfg.copy(
                            currentBedtime = action.optString("currentBedtime", cfg.currentBedtime),
                            targetBedtime = action.optString("targetBedtime", cfg.targetBedtime),
                            wake = action.optString("wake", cfg.wake).ifBlank { cfg.wake },
                            planDays = if (action.has("days")) action.optInt("days", cfg.planDays) else cfg.planDays,
                            startDate = action.optString("startDate", "").ifBlank { "" },
                        ))
                        runCatching {
                            KxStore.regenerate(ctx)
                            local.kuaixiu.calendar.CalendarWriter.syncPlan(ctx, KxStore.plan!!)
                        }.onSuccess {
                            Toast.makeText(ctx, "计划已更新并写入日历（$it 个事件）", Toast.LENGTH_LONG).show()
                            applied = true
                        }.onFailure {
                            Toast.makeText(ctx, "日历写入失败: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    "checkin" -> {
                        if (action.has("winddown") && action.optString("winddown").isNotBlank()) {
                            local.kuaixiu.data.Checkins.logWinddown(
                                ctx, local.kuaixiu.data.Checkins.tonightDate().toString(),
                                action.optString("winddown"))
                        }
                        if (action.has("status") && action.optString("status").isNotBlank()) {
                            val c = local.kuaixiu.data.Checkin(
                                date = LocalDate.now().minusDays(1).toString(),
                                status = action.optString("status", "late"),
                                bedtime = action.optString("bedtime", ""),
                                note = action.optString("note", ""),
                            )
                            local.kuaixiu.data.Checkins.add(ctx, c)
                        }
                        Toast.makeText(ctx, "已打卡 ✅", Toast.LENGTH_SHORT).show()
                        applied = true
                    }
                    "reminder" -> {
                        if (action.optString("op") == "cancel") {
                            val t = action.optString("text")
                            runCatching {
                                local.kuaixiu.calendar.CalendarWriter.deleteRemindersByText(ctx, t)
                            }.onSuccess { n ->
                                Toast.makeText(ctx, if (n > 0) "已删掉 $n 条「$t」" else "没找到「$t」这条提醒",
                                    Toast.LENGTH_SHORT).show()
                                applied = n > 0
                            }.onFailure {
                                Toast.makeText(ctx, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val today = LocalDate.now()
                            val d = action.optString("date").ifBlank { today.toString() }
                            val t = action.optString("time", "09:00")
                            runCatching {
                                local.kuaixiu.calendar.CalendarWriter.addReminder(
                                    ctx, d, t, action.optString("text", "提醒"),
                                    action.optInt("leadMin", 0))
                            }.onSuccess {
                                Toast.makeText(ctx, "已加进日历「快休提醒」✅", Toast.LENGTH_LONG).show()
                                applied = true
                            }.onFailure {
                                Toast.makeText(ctx, "提醒写入失败：${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "habit" -> {
                        val h = habit ?: return@Button
                        when (h.op) {
                            "done" -> {
                                val target = Habits.findByName(h.name)
                                if (target == null) {
                                    Toast.makeText(ctx, "没找到「${h.name}」这个习惯", Toast.LENGTH_SHORT).show()
                                } else if (Habits.dayOf(target) == 0) {
                                    Toast.makeText(ctx, "「${target.name}」还没开始（${target.startDate} 起），" +
                                        "今天先不记", Toast.LENGTH_LONG).show()
                                } else {
                                    Habits.markDone(ctx, target.id)
                                    Toast.makeText(ctx, "「${target.name}」第 ${Habits.dayOf(target)} 天已打卡 ✅",
                                        Toast.LENGTH_SHORT).show()
                                    applied = true
                                }
                            }
                            "pause" -> {
                                val target = Habits.findByName(h.name)
                                if (target == null) {
                                    Toast.makeText(ctx, "没找到「${h.name}」这个习惯", Toast.LENGTH_SHORT).show()
                                } else {
                                    runCatching { Habits.setEnabled(ctx, target.id, on = false) }
                                        .onSuccess {
                                            Toast.makeText(ctx, "已暂停「${target.name}」，日历不再响",
                                                Toast.LENGTH_LONG).show()
                                            applied = true
                                        }
                                        .onFailure {
                                            Toast.makeText(ctx, "暂停失败：${it.message}", Toast.LENGTH_SHORT).show()
                                        }
                                }
                            }
                            else -> when (val r = Habits.create(
                                ctx, h.name, h.cue, h.days, h.start, ackCost = ackCost)) {
                                is HabitCheck.Ok -> {
                                    Toast.makeText(ctx,
                                        (if (habitUpdate) "已改好，日历里那条重写了" else "已写进日历「快休习惯」") +
                                            "，连响 ${r.habit.days} 天 ✅", Toast.LENGTH_LONG).show()
                                    applied = true
                                }
                                is HabitCheck.QuotaFull -> {
                                    ackCost = true
                                    Toast.makeText(ctx, "第 3 个习惯要先确认代价，再点一次才真的加上",
                                        Toast.LENGTH_LONG).show()
                                }
                                is HabitCheck.AnchorConflict -> Toast.makeText(ctx,
                                    "锚点和「${r.otherName}」只差 ${r.gapMin} 分钟，先错开时间",
                                    Toast.LENGTH_LONG).show()
                                is HabitCheck.Invalid -> Toast.makeText(ctx, r.reason,
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    "image" -> {
                        applied = true
                        onImage(action.optString("prompt"))
                    }
                }
            }, enabled = !applied && !habitBlocked) {
                val cancel = kind == "reminder" && action.optString("op") == "cancel"
                Text(when {
                    kind == "image" -> if (applied) "已交给画师 ✓" else "🎨 生成这张助眠图"
                    cancel -> if (applied) "已删掉 ✓" else "删掉这个提醒"
                    kind == "reminder" -> if (applied) "已加进日历 ✓" else "加进日历"
                    kind == "habit" -> when {
                        applied -> when (habit?.op) {
                            "done" -> "已打卡 ✓"
                            "pause" -> "已暂停 ✓"
                            else -> if (habitUpdate) "已改好 ✓" else "已写进日历 ✓"
                        }
                        habit?.op == "done" -> "记为今天做到了"
                        habit?.op == "pause" -> "暂停这个习惯"
                        habitBlocked -> "先解决冲突再来"
                        ackCost -> "我了解代价，照样加"
                        else -> if (habitUpdate) "就按这个改" else "开这个习惯"
                    }
                    else -> if (applied) "已应用 ✓" else "应用更改"
                })
            }
        }
    }
}

private data class HabitArgs(
    val op: String, val name: String, val cue: String, val days: Int, val start: String,
)

/** 列表行：一条消息一行，它带的动作卡另起一行排在它下面。 */
private data class ChatRow(val idx: Int, val card: Boolean)

private fun rowsOf(msgs: List<ChatMsg>): List<ChatRow> = buildList {
    msgs.forEachIndexed { i, m ->
        add(ChatRow(i, false))
        if (m.actionJson != null) add(ChatRow(i, true))
    }
}

/** 教练给的 habit 参数：缺 startDate 就当今天，缺 days 按 21 天档。 */
private fun parseHabit(a: JSONObject) = HabitArgs(
    op = a.optString("op", "add").ifBlank { "add" },
    name = a.optString("name", ""),
    cue = a.optString("cueTime", ""),
    days = if (a.has("days")) a.optInt("days", 21) else 21,
    start = a.optString("startDate").ifBlank { LocalDate.now().toString() },
)
