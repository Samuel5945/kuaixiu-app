package local.kuaixiu.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.kuaixiu.engine.Plan
import local.kuaixiu.engine.PlanConfig

/** 应用配置。llmKey 内存中为明文；落盘是 AndroidKeyStore AES-GCM 密文（SecretStore）。 */
data class KxConfig(
    val currentBedtime: String = "01:15",
    val targetBedtime: String = "23:00",
    val wake: String = "07:00",
    val startDate: String = "",     // 空 = 今天
    val planDays: Int = 28,
    val winddownMin: Int = 45,
    val llmBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val llmKey: String = "",
    val llmModel: String = "glm-5.3-flash",
    val imageModel: String = "",    // 生图模型（空=未启用生图）
    val imageProvider: String = "", // 生图走哪家（空 = 跟文本同一家；生图与文本可以分属不同供应商）
    val pcUrl: String = "",    // 空 = 交给局域网自动发现（LanSync）
    val pcUploadCheckin: Boolean = false,
    val coachStyle: String = "gentle",
    val providerName: String = "",   // 当前激活的供应商档案名
    val notifyWinddown: Boolean = true,
    val notifyMorning: Boolean = true,
) {
    fun toPlanConfig() = PlanConfig(
        currentBedtime, targetBedtime, wake,
        startDate.ifBlank { "" }, planDays, winddownMin,
    )

    /** UI 展示用的掩码：前 4 位…后 4 位（sk-pq…dE58）。 */
    fun keyMasked(): String = when {
        llmKey.isBlank() -> ""
        llmKey.length <= 8 -> llmKey.take(2) + "…"
        else -> llmKey.take(4) + "…" + llmKey.takeLast(4)
    }
}

object Prefs {
    private const val FILE = "kuaixiu"

    /** 早期版本把开发机局域网 IP 写死为默认值；读到它一律视为未设置，交给自动发现。 */
    const val LEGACY_PC_URL = "http://192.168.2.5:8642"

    /**
     * pcUrl 净化：写死的开发机地址、以及被误存成 TCP 同步端口 8766 的（那端口永远不可能是
     * 信息页），一律当作未填写丢掉，让 LanSync 重新发现。
     */
    private fun sanitizePcUrl(v: String?): String {
        val u = (v ?: "").trim().trimEnd('/')
        return if (u.isBlank() || u == LEGACY_PC_URL || u.contains(":8766")) "" else u
    }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** PC 端 plan.json 是 snake_case（单一事实源的权威格式）；导入按它解码，本地存储仍用驼峰。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val pcJson = Json {
        ignoreUnknownKeys = true
        namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
    }

    fun loadConfig(ctx: Context): KxConfig {
        val s = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 密钥：优先 Keystore 密文；迁移历史明文（加密后删除明文）
        var key = ""
        val enc = s.getString("llmKeyEnc", null)
        if (enc != null) {
            key = SecretStore.decrypt(enc)
        } else {
            val legacy = s.getString("llmKey", "") ?: ""
            if (legacy.isNotBlank()) {
                key = legacy
                s.edit().putString("llmKeyEnc", SecretStore.encrypt(legacy)).remove("llmKey").apply()
            }
        }
        return KxConfig(
            currentBedtime = s.getString("currentBedtime", null) ?: "01:15",
            targetBedtime = s.getString("targetBedtime", null) ?: "23:00",
            wake = s.getString("wake", null) ?: "07:00",
            startDate = local.kuaixiu.engine.PlanEngine
                .parseDateLenient(s.getString("startDate", "") ?: "").toString()
                .takeIf { (s.getString("startDate", "") ?: "").isNotBlank() } ?: "",
            planDays = s.getInt("planDays", 28),
            winddownMin = s.getInt("winddownMin", 45),
            llmBaseUrl = s.getString("llmBaseUrl", null) ?: "https://open.bigmodel.cn/api/paas/v4",
            llmKey = key,
            llmModel = s.getString("llmModel", null) ?: "glm-5.3-flash",
            imageModel = s.getString("imageModel", null) ?: "",
            imageProvider = s.getString("imageProvider", null) ?: "",
            pcUrl = sanitizePcUrl(s.getString("pcUrl", null)),
            pcUploadCheckin = s.getBoolean("pcUploadCheckin", false),
            coachStyle = s.getString("coachStyle", null) ?: "gentle",
            providerName = s.getString("providerName", null) ?: "",
            notifyWinddown = s.getBoolean("notifyWinddown", true),
            notifyMorning = s.getBoolean("notifyMorning", true),
        )
    }

    fun saveConfig(ctx: Context, c: KxConfig) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putString("currentBedtime", c.currentBedtime)
            putString("targetBedtime", c.targetBedtime)
            putString("wake", c.wake)
            putString("startDate", c.startDate)
            putInt("planDays", c.planDays)
            putInt("winddownMin", c.winddownMin)
            putString("llmBaseUrl", c.llmBaseUrl)
            putString("llmKeyEnc", SecretStore.encrypt(c.llmKey))
            remove("llmKey")
            putString("llmModel", c.llmModel)
            putString("imageModel", c.imageModel)
            putString("imageProvider", c.imageProvider)
            putString("pcUrl", c.pcUrl)
            putBoolean("pcUploadCheckin", c.pcUploadCheckin)
            putString("coachStyle", c.coachStyle)
            putString("providerName", c.providerName)
            putBoolean("notifyWinddown", c.notifyWinddown)
            putBoolean("notifyMorning", c.notifyMorning)
            apply()
        }
    }

    /** 从 files/plan.json 导入（exe 经 adb 推送后的入口）。 */
    fun importPlanFromFile(ctx: Context): Plan? = runCatching {
        val f = java.io.File(ctx.filesDir, "plan.json")
        if (!f.exists()) return null
        val p = pcJson.decodeFromString<Plan>(f.readText())
        savePlan(ctx, p)
        p
    }.onFailure { android.util.Log.w("KuaixiuPrefs", "plan.json 导入失败: ${it.message}") }
        .getOrNull()

    /** 从字符串导入计划（LAN 同步通道入口）。 */
    fun importPlanFromString(ctx: Context, raw: String): Plan? = runCatching {
        val p = pcJson.decodeFromString<Plan>(raw.trimStart('﻿'))
        savePlan(ctx, p)
        p
    }.onFailure { android.util.Log.w("KuaixiuPrefs", "计划导入失败: ${it.message}") }
        .getOrNull()

    fun loadPlan(ctx: Context): Plan? = runCatching {
        val s = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        s.getString("plan", null)?.let { json.decodeFromString<Plan>(it) }
    }.getOrNull()

    fun savePlan(ctx: Context, plan: Plan?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("plan", plan?.let { json.encodeToString(it) })
            .apply()
    }
}
