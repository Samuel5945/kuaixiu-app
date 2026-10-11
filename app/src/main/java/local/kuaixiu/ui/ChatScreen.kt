package local.kuaixiu.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.kuaixiu.data.KxStore
import local.kuaixiu.calendar.CalendarWriter
import local.kuaixiu.llm.CoachPrompt
import local.kuaixiu.llm.LlmClient
import org.json.JSONObject
import java.time.LocalDate

private data class ChatMsg(val role: String, val text: String, val actionJson: String? = null)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val msgs = remember { mutableStateListOf(ChatMsg("assistant", "我是你的早睡教练。可以问我任何作息问题，也可以直接说「目标改到23:00」「昨晚1点睡的帮我打卡」。")) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(msgs) { m ->
                val isUser = m.role == "user"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.text)
                        m.actionJson?.let { aj -> ActionCard(aj) }
                    }
                }
            }
            if (busy) { item { Text("教练思考中…", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
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
                    msgs.add(ChatMsg("user", q))
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val history = msgs.filter { !it.text.startsWith("我是你的早睡教练") }
                                .takeLast(12)
                                .map { it.role to it.text }
                            val sys = CoachPrompt.system(ctx)
                            LlmClient(KxStore.config.llmBaseUrl, KxStore.config.llmKey)
                                .chat(KxStore.config.llmModel, listOf("system" to sys) + history)
                        }.onSuccess { reply ->
                            val action = LlmClient.parseAction(reply)
                            val shown = LlmClient.stripAction(reply)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                msgs.add(ChatMsg("assistant", shown.ifBlank { "（建议如下）" }, action?.toString()))
                                busy = false
                            }
                        }.onFailure { e ->
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                msgs.add(ChatMsg("assistant", "出错了：${e.message}\n检查设置页的 base_url / key / model。"))
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy,
            ) { Text("发送") }
        }
    }
}

@Composable
private fun ActionCard(actionJson: String) {
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
                            CalendarWriter.syncPlan(ctx, KxStore.plan!!)
                        }.onSuccess {
                            Toast.makeText(ctx, "计划已更新并写入日历（$it 个事件）", Toast.LENGTH_LONG).show()
                            applied = true
                        }.onFailure {
                            Toast.makeText(ctx, "日历写入失败: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    "checkin" -> {
                        var uploaded: local.kuaixiu.data.Checkin? = null
                        if (action.has("winddown") && action.optString("winddown").isNotBlank()) {
                            local.kuaixiu.data.Checkins.logWinddown(
                                ctx, local.kuaixiu.data.Checkins.tonightDate().toString(),
                                action.optString("winddown"))
                            uploaded = local.kuaixiu.data.Checkins.all(ctx)
                                .firstOrNull { it.date == local.kuaixiu.data.Checkins.tonightDate().toString() }
                        }
                        if (action.has("status") && action.optString("status").isNotBlank()) {
                            val c = local.kuaixiu.data.Checkin(
                                date = LocalDate.now().minusDays(1).toString(),
                                status = action.optString("status", "late"),
                                bedtime = action.optString("bedtime", ""),
                                note = action.optString("note", ""),
                            )
                            local.kuaixiu.data.Checkins.add(ctx, c)
                            uploaded = c
                        }
                        val toUpload = uploaded
                        if (KxStore.config.pcUploadCheckin && toUpload != null) {
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                runCatching { local.kuaixiu.data.Checkins.upload(ctx, KxStore.config.pcUrl, toUpload) }
                            }
                        }
                        Toast.makeText(ctx, "已打卡 ✅", Toast.LENGTH_SHORT).show()
                        applied = true
                    }
                }
            }, enabled = !applied) { Text(if (applied) "已应用 ✓" else "应用更改") }
        }
    }
}
