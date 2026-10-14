package local.kuaixiu.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.kuaixiu.R
import local.kuaixiu.data.ChatLog
import local.kuaixiu.data.HabitCheck
import local.kuaixiu.data.Habits
import local.kuaixiu.data.ImageGen
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.KxText
import local.kuaixiu.data.LlmLogger
import local.kuaixiu.data.Time
import local.kuaixiu.llm.ActionRunner
import local.kuaixiu.llm.CoachPrompt
import local.kuaixiu.llm.LlmClient
import local.kuaixiu.llm.LlmReply
import local.kuaixiu.llm.LocalCoach
import org.json.JSONObject
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    ChatStore.load(ctx)
    val msgs = ChatStore.msgs
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menuIdx by remember { mutableStateOf<Int?>(null) }   // 长按气泡弹出的复制/多选/删除菜单
    var posterResult by remember { mutableStateOf<java.io.File?>(null) }  // 做完的海报，弹保存/分享
    var selecting by remember { mutableStateOf(false) }      // 多选模式（气泡和动作卡都能选）
    val picked = remember { mutableStateListOf<Int>() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 动作卡是列表里的独立一行，不塞进气泡：长回复会把按钮顶出视口，看着就像「没有卡片」
    val rows = rowsOf(msgs)

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }

    // 做好了就弹出来：先看一眼，再决定分享还是收下
    posterResult?.let { f -> PosterResultDialog(file = f, onDismiss = { posterResult = null }) }

    Column(modifier = modifier.fillMaxSize().imePadding().padding(12.dp)) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.chat_selected_count, picked.size),
                     style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    picked.clear(); picked.addAll(msgs.indices)
                }) { Text(stringResource(R.string.chat_select_all)) }
                TextButton(onClick = {
                    ChatStore.removeAll(ctx, picked.toList())
                    picked.clear(); selecting = false
                    Toast.makeText(ctx, ctx.getString(R.string.home_reminder_deleted),
                        Toast.LENGTH_SHORT).show()
                }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.home_delete)) }
                TextButton(onClick = { picked.clear(); selecting = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows) { r ->
                val m = msgs[r.idx]
                val aj = if (r.card) m.actionJson else null
                val checked = selecting && r.idx in picked
                val toggle = { if (r.idx in picked) picked.remove(r.idx) else picked.add(r.idx) }
                Box(Modifier.fillMaxWidth()) {
                if (aj != null) {
                    ActionCard(aj, onPoster = { tpl, onResult ->
                        val t = when (tpl.uppercase()) {
                            "B" -> local.kuaixiu.data.PosterTemplate.B
                            "C" -> local.kuaixiu.data.PosterTemplate.C
                            "D" -> local.kuaixiu.data.PosterTemplate.D
                            else -> local.kuaixiu.data.PosterTemplate.A
                        }
                        // 没配生图模型就直接用内置底图——不该因为缺个模型就让这条路走不通
                        val mode = if (KxStore.config.effectiveImageModel().isNotBlank())
                            local.kuaixiu.data.PosterMode.LOCAL_POSTER
                        else local.kuaixiu.data.PosterMode.LOCAL_ONLY
                        Toast.makeText(ctx, ctx.getString(R.string.chat_poster_making),
                            Toast.LENGTH_SHORT).show()
                        local.kuaixiu.KxScope.app.launch {
                            runCatching { local.kuaixiu.data.PosterFlow.make(ctx, t, mode) }
                                .onSuccess { f -> kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    posterResult = f; onResult(true)
                                } }
                                .onFailure { e -> kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, ctx.getString(R.string.chat_poster_failed,
                                        e.message ?: ""), Toast.LENGTH_LONG).show()
                                    onResult(false)
                                } }
                        }
                    }, onImage = { prompt, ratio ->
                        val genIdx = msgs.size
                        ChatStore.addTransient(ChatMsg("assistant", ctx.getString(R.string.chat_image_generating)))
                        val size = if (ratio.equals("portrait", ignoreCase = true))
                            ImageGen.PORTRAIT_SIZE else ImageGen.DEFAULT_SIZE
                        // 应用级作用域：切页签/锁屏不中断，完成后原地变成图片并落盘
                        local.kuaixiu.KxScope.app.launch {
                            runCatching { ImageGen.generate(ctx, KxStore.config, prompt, size) }
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
                                            ChatMsg("assistant", ctx.getString(R.string.chat_image_failed,
                                                e.message ?: "")), persisted = true)
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
                                    contentDescription = stringResource(R.string.chat_image_desc),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                )
                            }
                        }
                    }
                    }
                }
                }
                if (selecting) {
                    if (checked) Box(Modifier.matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)))
                    Icon(
                        if (checked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = if (checked) stringResource(R.string.common_selected)
                                             else stringResource(R.string.chat_not_selected),
                        tint = if (checked) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterStart).size(20.dp),
                    )
                    Box(Modifier.matchParentSize().clickable { toggle() })
                }
                }
            }
        }
        // 发送：有 Key 走 LLM 流式；没 Key 走离线教练（同一个「text→动作卡」链路）
        fun send(text: String) {
            val q = text.trim()
            if (q.isEmpty() || busy) return
            input = ""
            ChatStore.add(ctx, "user", q)
            if (KxStore.config.llmKey.isBlank()) {
                val knownReminders = runCatching {
                    local.kuaixiu.calendar.CalendarWriter.peekReminders(ctx)
                }.getOrDefault(emptyList()).map { it.second }
                val knownHabits = Habits.items.filter { it.enabled }.map { it.name }
                // 达标线跟首页打卡用同一把尺：昨晚那一档的计划入睡，没有才退回初始基线
                val planned = KxStore.plan?.entryFor(Time.today().minusDays(1).toString())?.bedtime
                val reply = LocalCoach.reply(q, KxStore.config,
                    knownReminders = knownReminders, knownHabits = knownHabits,
                    plannedBedtime = planned)
                msgs.add(ChatMsg("assistant", reply.text, actionJson = reply.actionJson))
                ChatLog.append(ctx, "assistant", reply.text, actionJson = reply.actionJson ?: "")
                return
            }
            busy = true
            val placeholderIdx = msgs.size
            msgs.add(ChatMsg("assistant", "…"))  // 瞬态占位，不入盘
            // 应用级作用域：切页签/锁屏流式不中断
            local.kuaixiu.KxScope.app.launch {
                // 开场白只是给用户的引导，不该进上下文：按当前语言的开场白文案剔掉
                val greeting = listOf(ctx.getString(R.string.chat_welcome),
                    ctx.getString(R.string.chat_welcome_offline))
                val history = msgs.filter {
                    greeting.none { g -> it.text.startsWith(g) } && it.text != "…"
                }.takeLast(12).map { it.role to it.text }
                val onDelta: suspend (String) -> Unit = { full ->
                    if (placeholderIdx < msgs.size) {
                        // 流式过程中就把 Markdown 动作块剥掉，用户看不到 raw JSON
                        msgs[placeholderIdx] = ChatMsg("assistant", LlmClient.stripAction(full))
                    }
                }
                fun finalize(reply: LlmReply) {
                    val shown = LlmClient.stripAction(reply.text).ifBlank {
                        ctx.getString(R.string.chat_fallback_suggestion)
                    }
                    val actionJson = reply.actionJson
                    // 如果工具/Markdown 都没解析出动作，但原文里有 kuaixiu 块，把它暴露成排查卡片
                    val fallbackAction = if (actionJson == null) {
                        LlmClient.extractKuaixiuRaw(reply.text)
                    } else null
                    if (placeholderIdx < msgs.size) {
                        msgs[placeholderIdx] = ChatMsg("assistant", shown,
                            actionJson = actionJson ?: fallbackAction)
                    }
                    ChatLog.append(ctx, "assistant", shown,
                        actionJson = actionJson ?: fallbackAction ?: "")
                    busy = false
                }
                fun showError(e: Throwable) {
                    LlmLogger.log(ctx, KxStore.config.llmModel, raw = "", error = e.message ?: e.toString())
                    if (placeholderIdx < msgs.size) {
                        msgs[placeholderIdx] = ChatMsg("assistant",
                            ctx.getString(R.string.chat_llm_error, e.message ?: e.toString()))
                    }
                    busy = false
                }
                suspend fun tryOnce(hist: List<Pair<String, String>>): Result<LlmReply> = runCatching {
                    callCoachStreaming(ctx, hist, withTools = true, onDelta)
                }.recoverCatching { e ->
                    // 部分 provider/模型不支持 tools，失败一次后关掉 tools 重试
                    if (looksLikeToolsError(e)) {
                        callCoachStreaming(ctx, hist, withTools = false, onDelta)
                    } else throw e
                }

                val first = tryOnce(history)
                if (first.isFailure) {
                    showError(first.exceptionOrNull()!!)
                    return@launch
                }
                val reply1 = first.getOrThrow()
                val hasAction = reply1.actionJson != null ||
                    LlmClient.extractKuaixiuRaw(reply1.text) != null
                if (!hasAction && hasActionIntent(q)) {
                    // 模型嘴上答应却没执行：追加追呼系统消息再调一次
                    val retryHist = history + listOf(
                        "assistant" to reply1.text,
                        "system" to "用户刚刚的要求明显需要执行具体动作（改计划/记打卡/加提醒/建习惯/生图），" +
                            "但你没有调用工具函数，也没有输出动作块。请立即调用对应工具完成，不要只回复文本。"
                    )
                    val second = tryOnce(retryHist)
                    if (second.isSuccess) {
                        val reply2 = second.getOrThrow()
                        val hasAction2 = reply2.actionJson != null ||
                            LlmClient.extractKuaixiuRaw(reply2.text) != null
                        finalize(if (hasAction2) reply2 else reply1)
                    } else {
                        finalize(reply1)
                    }
                } else {
                    finalize(reply1)
                }
            }
        }
        // 没配 Key：离线教练 + 快捷面板（点一下填进输入框，或直接发）
        if (KxStore.config.llmKey.isBlank()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.chat_offline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        R.string.chat_chip_target_23 to false,
                        R.string.chat_chip_wake_7 to false,
                        R.string.chat_chip_winddown to true,
                        R.string.chat_chip_water_rem to false,
                        R.string.chat_chip_del_water to false,
                        R.string.chat_chip_new_habit to false,
                        R.string.chat_chip_done to true,
                        R.string.chat_chip_scientific to true,
                    ).forEach { (res, auto) ->
                        val tpl = stringResource(res)
                        AssistChip(
                            onClick = { if (auto) send(tpl) else input = tpl },
                            label = { Text(tpl, maxLines = 1) },
                        )
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
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                maxLines = 4,
            )
            Button(
                onClick = { send(input) },
                enabled = !busy && !selecting,   // 多选时禁发，否则索引会错位
                modifier = Modifier.height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            ) { Text(stringResource(R.string.chat_send)) }
        }
    }

    // 长按气泡 → 复制 / 删除
    menuIdx?.let { i ->
        val m = msgs.getOrNull(i)
        if (m == null) { menuIdx = null; return@let }
        AlertDialog(
            onDismissRequest = { menuIdx = null },
            title = { Text(if (m.role == "user") stringResource(R.string.chat_menu_mine)
                           else stringResource(R.string.chat_menu_coach)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(m.text, maxLines = 6,
                         overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                         style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                            menuIdx = null
                            Toast.makeText(ctx, ctx.getString(R.string.chat_copied),
                                Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.chat_copy)) }
                        TextButton(onClick = {
                            selecting = true
                            picked.clear(); picked.add(i)
                            menuIdx = null
                        }) { Text(stringResource(R.string.chat_multi_select)) }
                        TextButton(onClick = {
                            ChatStore.remove(ctx, i)
                            menuIdx = null
                            Toast.makeText(ctx, ctx.getString(R.string.chat_msg_deleted),
                                Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.home_delete)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuIdx = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ActionCard(actionJson: String, onImage: (prompt: String, ratio: String) -> Unit,
                       onPoster: (template: String, onResult: (ok: Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var applied by remember { mutableStateOf(false) }
    var posterDone by remember { mutableStateOf(false) }
    val parsedAction = runCatching { JSONObject(actionJson) }.getOrNull()
    val action = parsedAction
    val kind = action?.optString("action") ?: ""
    // 动作块 JSON 解析失败时也要看得见，否则模型给了块、App 却直接消失，无法排查
    if (action == null) {
        Card(Modifier.fillMaxWidth(),
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.chat_parse_fail_title), style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.chat_parse_fail_body), style = MaterialTheme.typography.bodySmall)
                Text(actionJson, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
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
    val actionable = kind in setOf("adjust", "checkin", "image", "reminder", "habit", "poster")
    // 删除按钮的显示条件看"东西还在不在"，不看 applied——applied 不落盘，重启后旧卡也该能删
    val remindText = action.optString("text")
    val reminderExists = remember(actionJson) {
        kind == "reminder" && action.optString("op") != "cancel" && remindText.isNotBlank() &&
            runCatching {
                local.kuaixiu.calendar.CalendarWriter.peekReminders(ctx).any { it.second == remindText }
            }.getOrDefault(false)
    }
    val habitExists = kind == "habit" && habit?.op == "add" && Habits.findByName(habit.name) != null
    Card(Modifier.fillMaxWidth(),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (kind) {
                "adjust" -> {
                    val labelMap = mapOf(
                        "currentBedtime" to ctx.getString(R.string.set_current_bedtime),
                        "targetBedtime" to ctx.getString(R.string.set_target_bedtime),
                        "wake" to ctx.getString(R.string.set_wake),
                        "days" to ctx.getString(R.string.chat_days))
                    val parts = buildList {
                        labelMap.forEach { (k, label) ->
                            val v = action.optString(k)
                            if (action.has(k) && v.isNotBlank()) add("$label → $v")
                        }
                        // 起始日无论动作里有没有都一定落到今天，不写出来用户只会觉得日历自己跳了
                        add(ctx.getString(R.string.set_start_date) + " → " +
                            action.optString("startDate").ifBlank { ctx.getString(R.string.picker_today) })
                    }
                    Text(stringResource(R.string.chat_adjust_title), style = MaterialTheme.typography.titleSmall)
                    Text(parts.joinToString("  "))
                    Text(stringResource(R.string.chat_adjust_restart),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "checkin" -> {
                    Text(stringResource(R.string.chat_checkin_title, action.optString("status")) +
                        (if (action.optString("bedtime").isNotBlank())
                            stringResource(R.string.home_parenthetical, action.optString("bedtime"))
                         else ""))
                }
                "image" -> {
                    Text(stringResource(R.string.chat_image_title), style = MaterialTheme.typography.titleSmall)
                    Text(action.optString("prompt"))
                    if (applied) {
                        Text(stringResource(R.string.chat_image_sent), style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                }
                "poster" -> {
                    val tpl = action.optString("template").ifBlank { "A" }
                    Text(stringResource(R.string.chat_poster_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(when (tpl) {
                        "B" -> R.string.chat_poster_tpl_b
                        "C" -> R.string.chat_poster_tpl_c
                        "D" -> R.string.chat_poster_tpl_d
                        else -> R.string.chat_poster_tpl_a
                    }))
                    Text(stringResource(R.string.chat_poster_note), style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "reminder" -> {
                    val when_ = listOf(action.optString("date"), action.optString("time"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                    val leadMin = action.optInt("leadMin")
                    val lead = if (leadMin > 0) stringResource(R.string.chat_reminder_lead, leadMin) else ""
                    if (action.optString("op") == "cancel") {
                        Text(stringResource(R.string.chat_reminder_cancel_title, action.optString("text", "")))
                    } else {
                        Text(stringResource(R.string.chat_reminder_add_title,
                                action.optString("text", stringResource(R.string.chat_reminder_default))) +
                            (if (when_.isNotBlank())
                                stringResource(R.string.home_parenthetical, when_ + lead) else ""))
                    }
                }
                "habit" -> {
                    val h = habit ?: return@Column
                    when (h.op) {
                        "done" -> Text(stringResource(R.string.chat_habit_done_title, h.name))
                        "pause" -> Text(stringResource(R.string.chat_habit_pause_title, h.name))
                        else -> {
                            val addTitleRes = if (habitUpdate) R.string.chat_habit_update_title
                                              else R.string.chat_habit_add_title
                            Text(stringResource(addTitleRes, h.name),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text((if (habitUpdate) stringResource(R.string.chat_habit_change_prefix) else "") +
                                stringResource(R.string.chat_habit_schedule_line, h.start, h.cue, h.days))
                            when (val r = habitCheck) {
                                is HabitCheck.AnchorConflict -> Text(
                                    stringResource(R.string.habit_conflict_hint, r.otherName, r.gapMin),
                                    color = MaterialTheme.colorScheme.error)
                                is HabitCheck.QuotaFull -> Text(
                                    if (ackCost) stringResource(R.string.chat_habit_ack_again)
                                    else stringResource(R.string.habit_quota_hint,
                                        r.activeNames.joinToString(stringResource(R.string.common_or))),
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
                    val kindShown = if (kind.isBlank())
                        stringResource(R.string.chat_unknown_action_blank) else kind
                    Text(stringResource(R.string.chat_unknown_action, kindShown),
                         style = MaterialTheme.typography.titleSmall)
                    Text(action.toString(), style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (actionable) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Button(onClick = {
                if (applied) return@Button
                validateAction(action)?.let {
                    Toast.makeText(ctx, ctx.getString(R.string.chat_invalid_params, it),
                        Toast.LENGTH_LONG).show()
                    return@Button
                }
                when (kind) {
                    "adjust" -> {
                        // 28 天 ≈ 140 次日历读写，和设置页那条按钮一样走 IO，别在点击线程上跑
                        local.kuaixiu.KxScope.app.launch {
                            val r = runCatching {
                                withContext(Dispatchers.IO) {
                                    local.kuaixiu.calendar.CalendarWriter.syncPlan(
                                        ctx, ActionRunner.applyAdjust(ctx, action))
                                }
                            }
                            withContext(Dispatchers.Main) {
                                r.onSuccess { n ->
                                    Toast.makeText(ctx, ctx.getString(R.string.set_plan_done, n),
                                        Toast.LENGTH_LONG).show()
                                    applied = true
                                }.onFailure { e ->
                                    Toast.makeText(ctx, ctx.getString(R.string.chat_calendar_write_failed,
                                        e.message ?: ""), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    "checkin" -> {
                        if (action.has("winddown") && action.optString("winddown").isNotBlank()) {
                            local.kuaixiu.data.Checkins.logWinddown(
                                ctx, local.kuaixiu.data.Checkins.tonightDate().toString(),
                                action.optString("winddown"))
                        }
                        if (action.has("status") && action.optString("status").isNotBlank()) {
                            // 合并而不是整条替换：这一晚可能已经记过风屏、探测到起床和放下手机
                            local.kuaixiu.data.Checkins.patch(
                                ctx, Time.today().minusDays(1).toString()
                            ) { old ->
                                old.copy(
                                    status = action.optString("status", "late"),
                                    bedtime = action.optString("bedtime", "").ifBlank { old.bedtime },
                                    note = action.optString("note", "").ifBlank { old.note },
                                )
                            }
                        }
                        Toast.makeText(ctx, ctx.getString(R.string.home_checkin_saved_toast),
                            Toast.LENGTH_SHORT).show()
                        applied = true
                    }
                    "reminder" -> {
                        if (action.optString("op") == "cancel") {
                            val t = action.optString("text")
                            runCatching {
                                ActionRunner.cancelReminder(ctx, t)
                            }.onSuccess { n ->
                                Toast.makeText(ctx, if (n > 0)
                                        ctx.getString(R.string.chat_reminders_deleted_n, n, t)
                                    else ctx.getString(R.string.chat_reminder_not_found, t),
                                    Toast.LENGTH_SHORT).show()
                                applied = n > 0
                            }.onFailure {
                                Toast.makeText(ctx, ctx.getString(R.string.chat_delete_failed,
                                    it.message ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val today = Time.today()
                            val d = action.optString("date").ifBlank { today.toString() }
                            val t = action.optString("time", "09:00")
                            ActionRunner.applyReminder(
                                ctx, d, t, action.optString("text",
                                    ctx.getString(R.string.chat_reminder_default)),
                                action.optInt("leadMin", 0)
                            ).onSuccess {
                                Toast.makeText(ctx, ctx.getString(R.string.rem_added),
                                    Toast.LENGTH_LONG).show()
                                applied = true
                            }.onFailure {
                                Toast.makeText(ctx, ctx.getString(R.string.rem_failed,
                                    it.message ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "habit" -> {
                        val h = habit ?: return@Button
                        when (h.op) {
                            "done" -> {
                                val target = Habits.findByName(h.name)
                                if (target == null) {
                                    Toast.makeText(ctx,
                                        ctx.getString(R.string.chat_habit_not_found, h.name),
                                        Toast.LENGTH_SHORT).show()
                                } else if (Habits.dayOf(target) == 0) {
                                    Toast.makeText(ctx, ctx.getString(R.string.chat_habit_not_started,
                                            target.name, target.startDate), Toast.LENGTH_LONG).show()
                                } else {
                                    Habits.markDone(ctx, target.id)
                                    Toast.makeText(ctx, ctx.getString(R.string.chat_habit_checked_n,
                                            target.name, Habits.dayOf(target)), Toast.LENGTH_SHORT).show()
                                    applied = true
                                }
                            }
                            "pause" -> {
                                val target = Habits.findByName(h.name)
                                if (target == null) {
                                    Toast.makeText(ctx,
                                        ctx.getString(R.string.chat_habit_not_found, h.name),
                                        Toast.LENGTH_SHORT).show()
                                } else {
                                    runCatching { Habits.setEnabled(ctx, target.id, on = false) }
                                        .onSuccess {
                                            Toast.makeText(ctx,
                                                ctx.getString(R.string.chat_habit_paused, target.name),
                                                Toast.LENGTH_LONG).show()
                                            applied = true
                                        }
                                        .onFailure {
                                            Toast.makeText(ctx, ctx.getString(R.string.chat_pause_failed,
                                                it.message ?: ""), Toast.LENGTH_SHORT).show()
                                        }
                                }
                            }
                            else -> when (val r = ActionRunner.applyHabitAdd(
                                ctx, h.name, h.cue, h.days, h.start, ackCost)) {
                                is HabitCheck.Ok -> {
                                    Toast.makeText(ctx,
                                        if (habitUpdate)
                                            ctx.getString(R.string.chat_habit_updated_toast, r.habit.days)
                                        else ctx.resources.getQuantityString(R.plurals.habit_added, r.habit.days, r.habit.days),
                                        Toast.LENGTH_LONG).show()
                                    applied = true
                                }
                                is HabitCheck.QuotaFull -> {
                                    ackCost = true
                                    Toast.makeText(ctx, ctx.getString(R.string.habit_quota_again),
                                        Toast.LENGTH_LONG).show()
                                }
                                is HabitCheck.AnchorConflict -> Toast.makeText(ctx,
                                    ctx.getString(R.string.habit_conflict_toast, r.otherName, r.gapMin),
                                    Toast.LENGTH_LONG).show()
                                is HabitCheck.Invalid -> Toast.makeText(ctx, r.reason,
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    "image" -> {
                        applied = true
                        onImage(action.optString("prompt"), action.optString("ratio"))
                    }
                    "poster" -> {
                        applied = true
                        onPoster(action.optString("template").ifBlank { "A" }) { ok ->
                            if (ok) posterDone = true else applied = false
                        }
                    }
                }
            }, enabled = !applied && !habitBlocked) {
                val cancel = kind == "reminder" && action.optString("op") == "cancel"
                Text(stringResource(when {
                    kind == "image" -> if (applied) R.string.chat_btn_image_done else R.string.chat_btn_image
                    kind == "poster" -> when {
                        posterDone -> R.string.chat_btn_poster_done
                        applied -> R.string.chat_btn_poster_busy
                        else -> R.string.chat_btn_poster
                    }
                    kind == "checkin" ->
                        if (applied) R.string.chat_btn_checkin_done else R.string.chat_btn_checkin
                    cancel -> if (applied) R.string.chat_btn_cancel_done else R.string.chat_btn_del_reminder
                    kind == "reminder" -> if (applied) R.string.chat_btn_reminder_done else R.string.rem_add_btn
                    kind == "habit" -> when {
                        applied -> when (habit?.op) {
                            "done" -> R.string.chat_btn_habit_checkin_done
                            "pause" -> R.string.chat_btn_habit_pause_done
                            else -> if (habitUpdate) R.string.chat_btn_habit_update_done
                                    else R.string.chat_btn_habit_written_done
                        }
                        habit?.op == "done" -> R.string.home_habit_check_btn
                        habit?.op == "pause" -> R.string.chat_btn_pause_habit
                        habitBlocked -> R.string.chat_btn_fix_conflict
                        ackCost -> R.string.habit_ack_btn
                        else -> if (habitUpdate) R.string.chat_btn_apply_update else R.string.habit_add_btn
                    }
                    else -> if (applied) R.string.chat_btn_applied else R.string.chat_btn_apply
                }))
            }
                // 那张卡对应的提醒/习惯还在，就顺手给"删掉"
                if (reminderExists || habitExists) {
                    val h = habit
                    when {
                        reminderExists -> OutlinedButton(onClick = {
                            runCatching {
                                ActionRunner.cancelReminder(ctx, remindText)
                            }.onSuccess { n ->
                                Toast.makeText(ctx, if (n > 0)
                                        ctx.getString(R.string.chat_reminder_deleted_one)
                                    else ctx.getString(R.string.chat_reminder_not_found, remindText),
                                    Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(ctx, ctx.getString(R.string.chat_delete_failed,
                                    it.message ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.chat_btn_del_reminder)) }
                        habitExists && h != null -> OutlinedButton(onClick = {
                            val target = Habits.findByName(h.name)
                            if (target == null) {
                                Toast.makeText(ctx, ctx.getString(R.string.chat_habit_not_found, h.name),
                                    Toast.LENGTH_SHORT).show()
                            } else if (runCatching { Habits.remove(ctx, target.id) }.getOrDefault(false)) {
                                Toast.makeText(ctx, ctx.getString(R.string.home_habit_removed_toast, target.name),
                                    Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(ctx, ctx.getString(R.string.home_reminder_delete_failed),
                                    Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.chat_btn_del_habit)) }
                    }
                }
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
    start = a.optString("startDate").ifBlank { Time.today().toString() },
)

/** 带 tools / 不带 tools 的统一流式调用。 */
private suspend fun callCoachStreaming(
    ctx: Context,
    history: List<Pair<String, String>>,
    withTools: Boolean,
    onDelta: suspend (String) -> Unit,
): LlmReply {
    val sys = CoachPrompt.system(ctx)
    val tools = if (withTools) LlmClient.toolsJson() else null
    return LlmClient(KxStore.config.llmBaseUrl, KxStore.config.llmKey)
        .chatStreaming(KxStore.config.llmModel, listOf("system" to sys) + history, tools, logCtx = ctx, onDelta)
}

/** 判断用户语句是否明确要求执行某个动作（用于模型漏执行时追呼）。 */
private fun hasActionIntent(text: String): Boolean = listOf(
    Regex("""(目标|改成|改到|调到|调整|推迟|提前|起床|入睡时间|计划天数|重新定)"""),
    Regex("""(昨晚|睡了|入睡|几点睡|睡的|打卡|风屏|放下手机|不玩手机)"""),
    Regex("""(提醒|叫我|记一下|别忘了|别忘|记着)"""),
    Regex("""(习惯|养成|培养)"""),
    Regex("""(画一?张|画个|画一下|助眠图|晚安图|助眠画面|插画|配图)"""),
).any { it.containsMatchIn(text) }

/** 校验动作 JSON 的基本格式，返回错误文案；通过返回 null。 */
private fun validateAction(action: JSONObject): String? {
    val kind = action.optString("action")
    val timeRe = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")
    val dateRe = Regex("""^\d{4}-\d{2}-\d{2}$""")
    fun okTime(k: String) = !action.has(k) || action.optString(k).isBlank() || timeRe.matches(action.optString(k))
    fun okDate(k: String) = !action.has(k) || action.optString(k).isBlank() || dateRe.matches(action.optString(k))
    return when (kind) {
        "adjust" -> {
            listOf("currentBedtime", "targetBedtime", "wake").firstOrNull { !okTime(it) }
                ?.let { KxText.s(R.string.chat_err_time_fmt, it) }
                ?: if (!okDate("startDate"))
                    KxText.s(R.string.chat_err_date_fmt, "startDate") else null
        }
        "reminder" -> {
            if (action.optString("op") == "cancel") {
                if (action.optString("text").isBlank())
                    KxText.s(R.string.chat_err_cancel_needs_title) else null
            } else {
                if (!okTime("time")) KxText.s(R.string.chat_err_time_fmt, "time")
                else if (!okDate("date")) KxText.s(R.string.chat_err_date_fmt, "date")
                else if (action.optString("text").isBlank())
                    KxText.s(R.string.chat_err_reminder_empty)
                else null
            }
        }
        "habit" -> {
            when (action.optString("op", "add")) {
                "done", "pause" -> if (action.optString("name").isBlank())
                    KxText.s(R.string.habit_err_name_empty) else null
                else -> {
                    if (action.optString("name").isBlank()) KxText.s(R.string.habit_err_name_empty)
                    else if (!okTime("cueTime")) KxText.s(R.string.chat_err_time_fmt, "cueTime")
                    else if (!okDate("startDate")) KxText.s(R.string.chat_err_date_fmt, "startDate")
                    else null
                }
            }
        }
        "checkin" -> {
            listOf("bedtime", "winddown").firstOrNull { !okTime(it) }
                ?.let { KxText.s(R.string.chat_err_time_fmt, it) }
        }
        "image" -> if (action.optString("prompt").isBlank())
            KxText.s(R.string.chat_err_prompt_empty) else null
        // 模板随便填都能用：认不出来就当 A，不做成报错卡
        "poster" -> null
        else -> null
    }
}

/** 判断错误是否像是因为 provider 不支持 tools/function calling。 */
private fun looksLikeToolsError(e: Throwable): Boolean {
    val msg = e.message ?: return false
    val code = Regex("""HTTP\s+(\d+)""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
    return code in setOf(400, 422) ||
        msg.contains("tool", ignoreCase = true) ||
        msg.contains("function", ignoreCase = true) ||
        msg.contains("工具") ||
        msg.contains("函数") ||
        msg.contains("不支持")
}
