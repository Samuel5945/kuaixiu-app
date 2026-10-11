package local.kuaixiu.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.kuaixiu.data.ChatLog
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding().padding(12.dp)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(msgs) { m ->
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
                        modifier = Modifier.widthIn(max = 1080.dp).fillMaxWidth(0.92f),
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
                        m.actionJson?.let { aj ->
                            ActionCard(aj, onImage = { prompt ->
                                val genIdx = msgs.size
                                ChatStore.addTransient(ChatMsg("assistant", "🎨 生图中…（约半分钟）"))
                                // 应用级作用域：切页签/锁屏不中断，完成后原地变成图片并落盘
                                local.kuaixiu.KxScope.app.launch {
                                    runCatching {
                                        ImageGen.generate(ctx, KxStore.config, prompt)
                                    }.onSuccess { f ->
                                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                                            ChatStore.replace(ctx, genIdx,
                                                ChatMsg("assistant", "🎨 ${prompt}", imagePath = f.absolutePath),
                                                persisted = true)
                                        }
                                    }.onFailure { e ->
                                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                                            ChatStore.replace(ctx, genIdx,
                                                ChatMsg("assistant", "生图失败：${e.message}"), persisted = true)
                                        }
                                    }
                                }
                            })
                        }
                    }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("和教练聊聊…") },
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
                            ChatLog.append(ctx, "assistant", shown.ifBlank { "（建议如下）" })
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
            ) { Text("发送") }
        }
    }
}

@Composable
private fun ActionCard(actionJson: String, onImage: (String) -> Unit) {
    val ctx = LocalContext.current
    var applied by remember { mutableStateOf(false) }
    val action = runCatching { JSONObject(actionJson) }.getOrNull() ?: return
    val kind = action.optString("action")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))) {
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
                else -> return@Column
            }
            Button(onClick = {
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
                    "image" -> {
                        applied = true
                        onImage(action.optString("prompt"))
                    }
                }
            }, enabled = !applied) {
                Text(when (kind) {
                    "image" -> if (applied) "已交给画师 ✓" else "🎨 生成这张助眠图"
                    else -> if (applied) "已应用 ✓" else "应用更改"
                })
            }
        }
    }
}
