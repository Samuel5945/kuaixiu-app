package local.kuaixiu.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import local.kuaixiu.R
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

// 常用时区（覆盖主要地区）；默认「跟随系统」，空串即跟随。冷门地区以后再补
private val ZONE_CHOICES = listOf(
    "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Taipei", "Asia/Tokyo", "Asia/Seoul",
    "Asia/Singapore", "Asia/Bangkok", "Asia/Kolkata", "Asia/Dubai",
    "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Moscow",
    "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
    "Australia/Sydney", "Pacific/Auckland", "UTC",
)

/** 风格键 → 资源 id：短标签与长说明各一套（中文在 values，英文在 values-en）。 */
@StringRes
private fun styleLabelRes(key: String): Int = when (key) {
    "strict" -> R.string.style_strict
    "data" -> R.string.style_data
    "humor" -> R.string.style_humor
    "auto" -> R.string.style_auto
    else -> R.string.style_gentle
}

@StringRes
private fun styleDescRes(key: String): Int = when (key) {
    "strict" -> R.string.style_strict_desc
    "data" -> R.string.style_data_desc
    "humor" -> R.string.style_humor_desc
    "auto" -> R.string.style_auto_desc
    else -> R.string.style_gentle_desc
}

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
    var zoneDlg by remember { mutableStateOf(false) }

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
        if (cfg.llmKey.isBlank()) {
            Toast.makeText(ctx, ctx.getString(R.string.set_need_key), Toast.LENGTH_SHORT).show(); return
        }
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
                if (textIds.isEmpty() && imageIds.isEmpty()) error(ctx.getString(R.string.set_empty_models))
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
                    Toast.makeText(ctx, ctx.getString(R.string.set_plan_done, n), Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    planBusy = false; cfg = KxStore.config
                    Toast.makeText(ctx, ctx.getString(R.string.set_plan_failed, e.message ?: e.toString()),
                        Toast.LENGTH_LONG).show()
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
                    Toast.makeText(ctx, ctx.getString(R.string.upd_latest), Toast.LENGTH_SHORT).show()
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
                    Text(stringResource(R.string.set_rhythm), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField(cfg.currentBedtime, stringResource(R.string.set_current_bedtime), Modifier.weight(1f)) {
                            edit { c -> c.copy(currentBedtime = it) }
                        }
                        TimeField(cfg.targetBedtime, stringResource(R.string.set_target_bedtime), Modifier.weight(1f)) {
                            edit { c -> c.copy(targetBedtime = it) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField(cfg.wake, stringResource(R.string.set_wake), Modifier.weight(1f)) {
                            edit { c -> c.copy(wake = it) }
                        }
                        DateField(cfg.startDate, stringResource(R.string.set_start_date), Modifier.weight(1f)) {
                            edit { c -> c.copy(startDate = it) }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.set_plan_days), style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(21, 28, 30).forEach { n ->
                                FilterChip(
                                    selected = cfg.planDays == n,
                                    onClick = { edit { c -> c.copy(planDays = n) } },
                                    label = { Text(stringResource(R.string.set_days_n, n)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.set_winddown), style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30, 45, 60).forEach { n ->
                                FilterChip(
                                    selected = cfg.winddownMin == n,
                                    onClick = { edit { c -> c.copy(winddownMin = n) } },
                                    label = { Text(stringResource(R.string.set_minutes_n, n)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    // 只有这一块参数影响计划，所以重算单独放这里，别的地方改完不碰日历
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { regeneratePlan() }, enabled = !planBusy) {
                            Text(stringResource(
                                if (planBusy) R.string.set_regenerating else R.string.set_regenerate))
                        }
                        Text(stringResource(R.string.set_apply_hint),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.set_coach_style), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoachPrompt.STYLE_KEYS.forEach { k ->
                            val selected = cfg.coachStyle == k
                            FilterChip(
                                selected = selected,
                                onClick = { edit { c -> c.copy(coachStyle = k) } },
                                label = { Text(stringResource(styleLabelRes(k))) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected),
                                          modifier = Modifier.size(18.dp)) }
                                } else null,
                            )
                        }
                    }
                    Text(
                        stringResource(styleDescRes(cfg.coachStyle)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.set_llm), style = MaterialTheme.typography.titleMedium)
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
                                    { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected),
                                          modifier = Modifier.size(18.dp)) }
                                } else null,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = cfg.llmBaseUrl, onValueChange = { editProvider { c -> c.copy(llmBaseUrl = it) } },
                        label = { Text(stringResource(R.string.set_base_url)) },
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
                        placeholder = { Text(stringResource(R.string.set_paste_key)) },
                        visualTransformation = if (showKey) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            val icon = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                            Icon(icon, contentDescription = stringResource(
                                if (showKey) R.string.set_hide_key else R.string.set_show_key),
                                 modifier = Modifier
                                     .padding(4.dp)
                                     .clickable { showKey = !showKey })
                        },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.llmModel, onValueChange = { editProvider { c -> c.copy(llmModel = it) } },
                        label = { Text(stringResource(R.string.set_model)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    // 生图归属哪家由「这个模型是从哪家的列表里选的」决定，切文本供应商不动它
                    OutlinedTextField(
                        value = cfg.imageModel,
                        onValueChange = { edit { c -> c.copy(imageModel = it, imageProvider = cfg.providerName) } },
                        label = { Text(stringResource(R.string.set_image_model)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fetchModels() }, enabled = !modelsBusy) {
                            Text(stringResource(if (modelsBusy) R.string.set_fetching else R.string.set_fetch_models))
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
                            Toast.makeText(ctx, ctx.getString(R.string.set_key_saved), Toast.LENGTH_SHORT).show()
                        }, enabled = keyInput.isNotBlank()) { Text(stringResource(R.string.set_save_key)) }
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
                    Text(stringResource(R.string.set_pc), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = cfg.pcUrl, onValueChange = { edit { c -> c.copy(pcUrl = it) } },
                        label = { Text(stringResource(R.string.set_pc_url)) },
                        placeholder = { Text(stringResource(R.string.set_pc_url_hint)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    val found = LanSync.discoveredUrl
                    when {
                        found != null && found != cfg.pcUrl -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.set_pc_found, found),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { edit { c -> c.copy(pcUrl = found) } }) {
                                Text(stringResource(R.string.set_pc_use))
                            }
                        }
                        found != null -> Text(stringResource(R.string.set_pc_found, found) + " 🟢",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text(stringResource(R.string.set_pc_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cfg.pcUploadCheckin,
                               onCheckedChange = { edit { c -> c.copy(pcUploadCheckin = it) } })
                        Text("  " + stringResource(R.string.set_pc_upload))
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.set_time), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tz_label))
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        TextButton(onClick = { zoneDlg = true }) {
                            Text(
                                (if (cfg.timezone.isBlank())
                                    stringResource(R.string.tz_follow_system_short,
                                        java.time.ZoneId.systemDefault().id)
                                else cfg.timezone) + " ▾",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cfg.notifyWinddown,
                               onCheckedChange = { edit { c -> c.copy(notifyWinddown = it) } })
                        Text("  " + stringResource(R.string.notify_winddown))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cfg.notifyMorning,
                               onCheckedChange = { edit { c -> c.copy(notifyMorning = it) } })
                        Text("  " + stringResource(R.string.notify_morning))
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
                        Text(stringResource(R.string.set_about), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.about_version, UpdateChecker.currentVersion(ctx)),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { checkUpdate() }, enabled = !updateBusy) {
                        Text(stringResource(if (updateBusy) R.string.upd_checking else R.string.upd_check))
                    }
                }
            }
        }
    }

    // 模型列表对话框（文本可选 / 生图仅展示）
    modelsDlg?.let { (text, image) ->
        AlertDialog(
            onDismissRequest = { modelsDlg = null },
            confirmButton = { TextButton(onClick = { modelsDlg = null }) { Text(stringResource(R.string.common_close)) } },
            title = { Text(stringResource(R.string.set_models_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.set_models_text), style = MaterialTheme.typography.labelSmall,
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
                        Text(stringResource(R.string.set_models_image,
                            imgSrc.ifBlank { stringResource(R.string.set_current_provider) }),
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

    if (zoneDlg) {
        AlertDialog(
            onDismissRequest = { zoneDlg = false },
            confirmButton = { TextButton(onClick = { zoneDlg = false }) { Text(stringResource(R.string.common_close)) } },
            title = { Text(stringResource(R.string.tz_title)) },
            text = {
                val sysZone = java.time.ZoneId.systemDefault().id
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    item {
                        TextButton(onClick = {
                            edit { c -> c.copy(timezone = "") }
                            zoneDlg = false
                        }) { Text(stringResource(R.string.tz_follow_system, sysZone) +
                            if (cfg.timezone.isBlank()) " ✓" else "") }
                    }
                    items(ZONE_CHOICES.size) { i ->
                        val z = ZONE_CHOICES[i]
                        TextButton(onClick = {
                            edit { c -> c.copy(timezone = z) }
                            zoneDlg = false
                        }) { Text(z + if (cfg.timezone == z) " ✓" else "") }
                    }
                }
            },
        )
    }

}
