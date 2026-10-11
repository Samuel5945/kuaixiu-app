package local.kuaixiu.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.kuaixiu.data.KxConfig
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.ModelClassifier
import local.kuaixiu.data.ProviderProfiles
import local.kuaixiu.llm.CoachPrompt
import org.json.JSONObject

private data class ProviderPreset(val name: String, val baseUrl: String, val modelSeed: String)

// 种子值只含官方公开端点；个人 base_url/Key 存在各自档案里（不进代码）
private val PROVIDER_PRESETS = listOf(
    ProviderPreset("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    ProviderPreset("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    ProviderPreset("Step 阶跃", "https://api.stepfun.com/v1", "step-3.5-flash"),
    ProviderPreset("商汤日日新", "https://api.sensenova.cn/v1", ""),
    ProviderPreset("基元律动", "https://tokenrhythm.studio/v1", "deepseek-v4-flash"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cfg by remember { mutableStateOf(KxStore.config) }
    var saved by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }  // 空 = 保持已存 Key
    var modelsDlg by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) } // (text, image)
    var modelsErr by remember { mutableStateOf<String?>(null) }
    var modelsBusy by remember { mutableStateOf(false) }
    var importErr by remember { mutableStateOf<String?>(null) }

    fun edit(block: (KxConfig) -> KxConfig) {
        cfg = block(cfg); saved = false
    }

    fun fetchModels() {
        if (cfg.llmKey.isBlank()) { Toast.makeText(ctx, "先填 API Key", Toast.LENGTH_SHORT).show(); return }
        modelsBusy = true; modelsErr = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                val url = cfg.llmBaseUrl.trimEnd('/') + "/models"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.setRequestProperty("Authorization", "Bearer ${cfg.llmKey}")
                val body = conn.inputStream.use { it.readBytes().decodeToString() }
                val ids = JSONObject(body).optJSONArray("data")
                    ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") } }
                    ?: emptyList()
                if (ids.isEmpty()) error("接口返回了空模型列表")
                ModelClassifier.classify(ids)
            }.onSuccess { (text, image) ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    modelsDlg = text to image; modelsBusy = false
                }
            }.onFailure { e ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    modelsErr = e.message ?: e.toString(); modelsBusy = false
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
                    Text("作息参数", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField(cfg.currentBedtime, "当前入睡", Modifier.weight(1f)) {
                            edit { c -> c.copy(currentBedtime = it) }
                        }
                        TimeField(cfg.targetBedtime, "目标入睡", Modifier.weight(1f)) {
                            edit { c -> c.copy(targetBedtime = it) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField(cfg.wake, "起床锚点", Modifier.weight(1f)) {
                            edit { c -> c.copy(wake = it) }
                        }
                        DateField(cfg.startDate, "开始日期", Modifier.weight(1f)) {
                            edit { c -> c.copy(startDate = it) }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("计划天数", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(21, 28, 30).forEach { n ->
                                FilterChip(
                                    selected = cfg.planDays == n,
                                    onClick = { edit { c -> c.copy(planDays = n) } },
                                    label = { Text("$n 天") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("风屏时长（睡前放下手机）", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30, 45, 60).forEach { n ->
                                FilterChip(
                                    selected = cfg.winddownMin == n,
                                    onClick = { edit { c -> c.copy(winddownMin = n) } },
                                    label = { Text("$n 分钟") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("教练风格", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoachPrompt.STYLES.forEach { (k, label) ->
                            FilterChip(
                                selected = cfg.coachStyle == k,
                                onClick = { edit { c -> c.copy(coachStyle = k) } },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        when (cfg.coachStyle) {
                            "strict" -> "严格督军：短句、直接、破功一针见血但给下一步。"
                            "data" -> "数据理性：偏差分钟、连续天数、达成率，结论先行。"
                            "humor" -> "幽默损友：调侃带梗，笑完真的去睡。"
                            "auto" -> "自适应：教练观察你的执行率与语气，逐渐匹配严格度。"
                            else -> "温和陪伴：温暖、肯定微小进步、绝不指责。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI 教练（OpenAI 兼容）", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PROVIDER_PRESETS.forEach { preset ->
                            val p = ProviderProfiles.get(ctx, preset.name)
                            val keyHint = if (p != null && p.keyEnc.isNotBlank()) " 🔑" else ""
                            FilterChip(
                                selected = cfg.providerName == preset.name,
                                onClick = {
                                    // 切档案：载入该供应商自己的 base_url/Key/模型（没有则用官方种子）
                                    keyInput = ""
                                    cfg = cfg.copy(
                                        providerName = preset.name,
                                        llmBaseUrl = p?.baseUrl ?: preset.baseUrl,
                                        llmModel = p?.model?.ifBlank { preset.modelSeed } ?: preset.modelSeed,
                                        llmKey = ProviderProfiles.decryptKey(p),
                                    )
                                    saved = false
                                },
                                label = { Text(preset.name + keyHint) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = cfg.llmBaseUrl, onValueChange = { edit { c -> c.copy(llmBaseUrl = it) } },
                        label = { Text("base_url") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = keyInput, onValueChange = { keyInput = it },
                        label = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("API Key")
                                if (cfg.keyMasked().isNotBlank()) {
                                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                    Text(cfg.keyMasked())
                                }
                            }
                        },
                        placeholder = { Text("粘贴 API Key") },
                        visualTransformation = if (showKey) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            val icon = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                            Icon(icon, contentDescription = if (showKey) "隐藏" else "显示",
                                 modifier = Modifier
                                     .padding(4.dp)
                                     .clickable { showKey = !showKey })
                        },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.llmModel, onValueChange = { edit { c -> c.copy(llmModel = it) } },
                        label = { Text("模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.imageModel, onValueChange = { edit { c -> c.copy(imageModel = it) } },
                        label = { Text("生图模型（可选，供教练画助眠图）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fetchModels() }, enabled = !modelsBusy) {
                            Text(if (modelsBusy) "拉取中…" else "拉取模型列表")
                        }
                    }
                    modelsErr?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("电脑联动（可选）", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = cfg.pcUrl, onValueChange = { edit { c -> c.copy(pcUrl = it) } },
                        label = { Text("PC 信息页地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cfg.pcUploadCheckin,
                               onCheckedChange = { edit { c -> c.copy(pcUploadCheckin = it) } })
                        Text("  打卡自动上传电脑（同一 Wi-Fi 时）")
                    }
                }
            }
        }
        item {
            Button(onClick = {
                val finalCfg = cfg.copy(llmKey = keyInput.trim().ifBlank { cfg.llmKey })
                cfg = finalCfg
                keyInput = ""
                if (finalCfg.providerName.isNotBlank()) {
                    ProviderProfiles.put(ctx, finalCfg.providerName,
                        finalCfg.llmBaseUrl, finalCfg.llmKey, finalCfg.llmModel, finalCfg.imageModel)
                }
                KxStore.saveConfig(ctx, finalCfg)
                runCatching {
                    KxStore.regenerate(ctx)
                    val n = KxStore.syncCalendar(ctx)
                    saved = true
                    Toast.makeText(ctx, "已保存：计划重生成 + $n 事件 + 通知已排", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(ctx, "已保存，但日历写入失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }, enabled = !saved) { Text(if (saved) "已保存 ✓" else "保存并重新生成计划") }
        }
    }

    // 模型列表对话框（文本可选 / 生图仅展示）
    modelsDlg?.let { (text, image) ->
        AlertDialog(
            onDismissRequest = { modelsDlg = null },
            confirmButton = { TextButton(onClick = { modelsDlg = null }) { Text("关闭") } },
            title = { Text("模型列表") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("文本模型（点选）", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        items(text.size) { i ->
                            TextButton(onClick = {
                                val nc = cfg.copy(llmModel = text[i])
                                cfg = nc
                                KxStore.saveConfig(ctx, nc)  // 即时落盘
                                modelsDlg = null
                            }) { Text(text[i] + if (cfg.llmModel == text[i]) " ✓" else "") }
                        }
                    }
                    if (image.isNotEmpty()) {
                        Text("生图模型（点选给教练画图用）", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        image.forEach { m ->
                            TextButton(onClick = {
                                val nc = cfg.copy(imageModel = m)
                                cfg = nc
                                KxStore.saveConfig(ctx, nc)  // 即时落盘
                                modelsDlg = null
                            }) { Text(m + if (nc.imageModel == m) " ✓" else "") }
                        }
                    }
                }
            },
        )
    }

}
