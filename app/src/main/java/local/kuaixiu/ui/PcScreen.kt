package local.kuaixiu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.kuaixiu.data.KxStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration

/** 电脑页：监测 PC 端快休服务（开放协议 /api/status，以后可监测其他电脑 Agent）。 */
@Composable
fun PcScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var url by remember { mutableStateOf(KxStore.config.pcUrl) }
    var raw by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    fun fetch() {
        val base = url.trim().trimEnd('/')
        if (base.isBlank()) return
        KxStore.saveConfig(ctx, KxStore.config.copy(pcUrl = base))
        busy = true; error = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("$base/api/status").build()
                OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(6)).build()
                    .newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        body
                    }
            }.onSuccess {
                kotlinx.coroutines.withContext(Dispatchers.Main) { raw = it; busy = false }
            }.onFailure {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    error = "${it.message}（手机和电脑要在同一 Wi-Fi）"; busy = false
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("电脑端快休", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it },
                            label = { Text("PC 信息页地址") },
                            modifier = Modifier.weight(1f), singleLine = true,
                        )
                        Button(onClick = { fetch() }, enabled = !busy) { Text(if (busy) "…" else "刷新") }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        raw?.let { json ->
            item { StatusCards(json) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        TextButtonToggle(expanded, { expanded = !expanded })
                        if (expanded) {
                            Text(json, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonToggle(expanded: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(if (expanded) "收起原始 JSON" else "查看原始 JSON（开放协议，可接其他 Agent）")
    }
}

@Composable
private fun StatusCards(json: String) {
    val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 计划状态
        o.optJSONObject("plan")?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("计划（PC 端）", style = MaterialTheme.typography.titleMedium)
                    val phase = p.optString("phase")
                    val desc = when (phase) {
                        "active" -> "进行中：第 ${p.optInt("day")}/${p.optInt("total_days")} 天 · 目标入睡 ${p.optString("bedtime")}"
                        "pending" -> "待开始：${p.optString("start_date")} 起步，当前档 ${p.optString("bedtime")}"
                        "finished" -> "已结束 · 巩固期目标 ${p.optString("target_bedtime")}"
                        else -> "未生成"
                    }
                    Text(desc)
                    if (p.has("winddown")) Text("风屏 ${p.optString("winddown")} · ${p.optString("theme")}")
                }
            }
        }
        // 服务
        o.optJSONObject("services")?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("服务", style = MaterialTheme.typography.titleMedium)
                    listOf("caldav_port" to "CalDAV(Radicale)", "info_port" to "信息页").forEach { (k, label) ->
                        s.optJSONObject(k)?.let {
                            Text("${label}:${it.optInt("port")}  ${if (it.optBoolean("up")) "🟢 在线" else "🔴 离线"}")
                        }
                    }
                }
            }
        }
        // PC 督眠任务
        o.optJSONObject("agent_tasks")?.let { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("PC 督眠任务", style = MaterialTheme.typography.titleMedium)
                    t.keys().asSequence().filterNot { it == "_error" }.forEach { k ->
                        t.optJSONObject(k)?.let {
                            Text("${k}  下次 ${it.optString("next_run", "—")}")
                        }
                    }
                }
            }
        }
        // 最近打卡
        o.optJSONArray("recent_checkins")?.let { arr ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("PC 端最近打卡", style = MaterialTheme.typography.titleMedium)
                    for (i in 0 until arr.length()) {
                        val r = arr.optJSONObject(i) ?: continue
                        Text("${r.optString("date")}  ${r.optString("status")}  ${r.optString("actual_bedtime")}")
                    }
                }
            }
        }
    }
}
