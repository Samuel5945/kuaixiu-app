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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.kuaixiu.data.ImageGen
import local.kuaixiu.data.KxConfig
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.LanSync
import local.kuaixiu.data.ModelClassifier
import local.kuaixiu.data.Prefs
import local.kuaixiu.data.ProviderProfiles
import local.kuaixiu.data.UpdateChecker
import local.kuaixiu.llm.CoachPrompt
import org.json.JSONObject

private data class ProviderPreset(
    val name: String, val baseUrl: String, val modelSeed: String, val imageSeed: String = "",
)

// 种子值只含官方公开端点；个人 base_url/Key 存在各自档案里（不进代码）
private val PROVIDER_PRESETS = listOf(
    ProviderPreset("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash"),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    // Step Plan 订阅套餐专属通道；按量付费的 key 要改成 https://api.stepfun.com/v1
    ProviderPreset("Step 阶跃", "https://api.stepfun.com/step_plan/v1", "step-3.5-flash"),
    // 商汤走 token.sensenova.cn（api.sensenova.cn 上没有任何 /v1 路由，实测 404）
    ProviderPreset("商汤日日新", "https://token.sensenova.cn/v1",
        "sensenova-6.8-flash-lite", "sensenova-u1.5-lite"),
    ProviderPreset("基元律动", "https://tokenrhythm.studio/v1", "deepseek-v4-flash"),
)

/**
 * 设置页。除「作息参数」外全部改动即时落盘、立即生效；
 * 只有作息参数需要重算计划并写日历（单独按钮，走 IO 线程）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(KxStore.config) }
    var showKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }  // 空 = 保持已存 Key
    var planBusy by remember { mutableStateOf(false) }
    var modelsDlg by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) } // (text, image)
    var imgSrc by remember { mutableStateOf("") }  // 本次生图列表来自哪家
    var modelsErr by remember { mutableStateOf<String?>(null) }
    var modelsBusy by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateDlg by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    fun persist(nc: KxConfig) {
        cfg = nc
        KxStore.saveConfig(ctx, nc)
    }

    fun edit(block: (KxConfig) -> KxConfig) = persist(block(cfg))

    /** AI 教练字段：配置与该供应商档案一起更新（Key 传空 = 保持档案里已存的 Key）。 */
    fun editProvider(block: (KxConfig) -> KxConfig) {
        val nc = block(cfg)
        persist(nc)
        if (nc.providerName.isNotBlank()) {
            ProviderProfiles.put(ctx, nc.providerName, nc.llmBaseUrl, "", nc.llmModel, nc.imageModel)
        }
    }

    fun idsAt(base: String, key: String): List<String> = runCatching {
        val conn = java.net.URL(base.trimEnd('/') + "/models").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $key")
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        JSONObject(body).optJSONArray("data")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") } }
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun fetchModels() {
        if (cfg.llmKey.isBlank()) { Toast.makeText(ctx, "先填 API Key", Toast.LENGTH_SHORT).show(); return }
        modelsBusy = true; modelsErr = null
        // 记下生图列表来自哪家，点选时把这份归属一起存下来（文本供应商随后切走也不影响）
        val imgHome = if (cfg.imageProvider.isBlank()) cfg.providerName else {
            val base = ProviderProfiles.get(ctx, cfg.imageProvider)?.baseUrl?.trimEnd('/')
            if (base.isNullOrBlank() || base == cfg.llmBaseUrl.trimEnd('/')) cfg.providerName else cfg.imageProvider
        }
        imgSrc = imgHome
        scope.launch(Dispatchers.IO) {
            runCatching {
                // 文本列表来自文本那家，生图列表来自生图那家（两家可以不同）
                val (imgBase, imgKey) = ImageGen.imageEndpoint(ctx, cfg)
                val textIds = idsAt(cfg.llmBaseUrl, cfg.llmKey)
                val imageIds = if (imgBase.trimEnd('/') == cfg.llmBaseUrl.trimEnd('/')) textIds
                               else idsAt(imgBase, imgKey)
                if (textIds.isEmpty() && imageIds.isEmpty()) error("接口返回了空模型列表")
                ModelClassifier.classify(textIds).first to
                    imageIds.filter { ModelClassifier.isImageModel(it) }.sorted()
            }.onSuccess { (text, image) ->
                withContext(Dispatchers.Main) { modelsDlg = text to image; modelsBusy = false }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { modelsErr = e.message ?: e.toString(); modelsBusy = false }
            }
        }
    }

    fun regeneratePlan() {
        planBusy = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                KxStore.regenerate(ctx)
                KxStore.syncCalendar(ctx)
            }.onSuccess { n ->
                withContext(Dispatchers.Main) {
                    planBusy = false; cfg = KxStore.config
                    Toast.makeText(ctx, "计划已重算，写入 $n 个日历事件", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    planBusy = false; cfg = KxStore.config
                    Toast.makeText(ctx, "重算失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 手动检查更新：必有反馈（弹窗 / 已是最新 / 失败提示），忽略记录不拦截手动检查。 */
    fun checkUpdate() {
        updateBusy = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) {
                UpdateChecker.check(ctx.applicationContext, manual = true)
            }) {
                is UpdateChecker.Result.Available -> { updateBusy = false; updateDlg = r.info }
                is UpdateChecker.Result.UpToDate -> {
                    updateBusy = false
                    Toast.makeText(ctx, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
                is UpdateChecker.Result.Failed -> {
                    updateBusy = false
                    Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
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
                    // 只有这一块参数影响计划，所以重算单独放这里，别的地方改完不碰日历
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { regeneratePlan() }, enabled = !planBusy) {
                            Text(if (planBusy) "重算中…" else "重算计划并写入日历")
                        }
                        Text("其他设置改完即生效",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            val selected = cfg.coachStyle == k
                            FilterChip(
                                selected = selected,
                                onClick = { edit { c -> c.copy(coachStyle = k) } },
                                label = { Text(label) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = "已选",
                                          modifier = Modifier.size(18.dp)) }
                                } else null,
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
                            val selected = cfg.providerName == preset.name
                            val keyHint = if (p != null && p.keyEnc.isNotBlank()) " 🔑" else ""
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    // 离开当前供应商前先把 Key 归位到它自己的档案：框里没点
                                    // 「保存 Key」的输入、以及只存在 llmKeyEnc 里的老 Key，
                                    // 否则切过来时带的是新档案的空 Key，会把上一家冲掉
                                    val pending = keyInput.trim().ifBlank { cfg.llmKey }
                                    if (cfg.providerName.isNotBlank() && pending.isNotBlank()) {
                                        ProviderProfiles.put(ctx, cfg.providerName, cfg.llmBaseUrl,
                                            pending, cfg.llmModel, cfg.imageModel)
                                    }
                                    keyInput = ""
                                    // 切档案：载入该供应商自己的 base_url/Key/模型（没有则用官方种子）
                                    val np = ProviderProfiles.get(ctx, preset.name)
                                    val seed = np?.imageModel?.ifBlank { preset.imageSeed } ?: preset.imageSeed
                                    persist(cfg.copy(
                                        providerName = preset.name,
                                        llmBaseUrl = np?.baseUrl?.ifBlank { preset.baseUrl } ?: preset.baseUrl,
                                        llmModel = np?.model?.ifBlank { preset.modelSeed } ?: preset.modelSeed,
                                        // 生图只在「还没配过」时用种子填一下；已配好的不跟文本供应商换
                                        imageModel = if (cfg.imageModel.isBlank()) seed else cfg.imageModel,
                                        llmKey = ProviderProfiles.decryptKey(np),
                                    ))
                                },
                                label = { Text(preset.name + keyHint) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = "已选",
                                          modifier = Modifier.size(18.dp)) }
                                } else null,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = cfg.llmBaseUrl, onValueChange = { editProvider { c -> c.copy(llmBaseUrl = it) } },
                        label = { Text("base_url（OpenAI 兼容端点，通常带 /v1）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
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
                        value = cfg.llmModel, onValueChange = { editProvider { c -> c.copy(llmModel = it) } },
                        label = { Text("模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    // 生图归属哪家由「这个模型是从哪家的列表里选的」决定，切文本供应商不动它
                    OutlinedTextField(
                        value = cfg.imageModel,
                        onValueChange = { edit { c -> c.copy(imageModel = it, imageProvider = cfg.providerName) } },
                        label = { Text("生图模型（从列表选会自动记住来自哪家；手填按当前文本这家算）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fetchModels() }, enabled = !modelsBusy) {
                            Text(if (modelsBusy) "拉取中…" else "拉取模型列表")
                        }
                        OutlinedButton(onClick = {
                            val k = keyInput.trim()
                            if (k.isEmpty()) return@OutlinedButton
                            keyInput = ""
                            persist(cfg.copy(llmKey = k))
                            if (cfg.providerName.isNotBlank()) {
                                ProviderProfiles.put(ctx, cfg.providerName, cfg.llmBaseUrl, k,
                                    cfg.llmModel, cfg.imageModel)
                            }
                            Toast.makeText(ctx, "Key 已加密保存", Toast.LENGTH_SHORT).show()
                        }, enabled = keyInput.isNotBlank()) { Text("保存 Key") }
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
                        label = { Text("PC 信息页地址") },
                        placeholder = { Text("留空 = 按局域网自动发现") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    val found = LanSync.discoveredUrl
                    when {
                        found != null && found != cfg.pcUrl -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("已发现 $found", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { edit { c -> c.copy(pcUrl = found) } }) { Text("用它") }
                        }
                        found != null -> Text("已发现 $found 🟢",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("未发现电脑：电脑上运行 KuaixiuTray，与手机连同一 Wi-Fi 即自动填入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cfg.pcUploadCheckin,
                               onCheckedChange = { edit { c -> c.copy(pcUploadCheckin = it) } })
                        Text("  打卡自动上传电脑（同一 Wi-Fi 时）")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("时间与通知", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = cfg.timezone,
                        onValueChange = { edit { c -> c.copy(timezone = it) } },
                        label = { Text("时区 ID") },
                        placeholder = { Text("Asia/Shanghai") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Text("如 Asia/Shanghai、America/New_York。改后计划/提醒/教练时间均按此时区计算。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cfg.notifyWinddown,
                               onCheckedChange = { edit { c -> c.copy(notifyWinddown = it) } })
                        Text("  风屏提醒")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cfg.notifyMorning,
                               onCheckedChange = { edit { c -> c.copy(notifyMorning = it) } })
                        Text("  晨间提醒")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("关于", style = MaterialTheme.typography.titleMedium)
                        Text("快休 v${UpdateChecker.currentVersion(ctx)}",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { checkUpdate() }, enabled = !updateBusy) {
                        Text(if (updateBusy) "检查中…" else "检查更新")
                    }
                }
            }
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
                                editProvider { c -> c.copy(llmModel = text[i]) }
                                modelsDlg = null
                            }) { Text(text[i] + if (cfg.llmModel == text[i]) " ✓" else "") }
                        }
                    }
                    if (image.isNotEmpty()) {
                        Text("生图模型（来自 ${imgSrc.ifBlank { "当前这家" }}，点选即归它管）",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        image.forEach { m ->
                            TextButton(onClick = {
                                edit { c -> c.copy(imageModel = m, imageProvider = imgSrc) }
                                modelsDlg = null
                            }) { Text(m + if (cfg.imageModel == m) " ✓" else "") }
                        }
                    }
                }
            },
        )
    }

    updateDlg?.let { info ->
        UpdateDialog(
            info = info,
            current = UpdateChecker.currentVersion(ctx),
            onDismiss = { updateDlg = null },
            onSkip = {
                Prefs.skipUpdate(ctx.applicationContext, info.version)
                updateDlg = null
            },
        )
    }

}
