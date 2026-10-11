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
    val llmModel: String = "glm-4-flash",
    val imageModel: String = "",    // 生图模型（空=未启用生图）
    val pcUrl: String = "http://192.168.2.5:8642",
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
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

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
            llmModel = s.getString("llmModel", null) ?: "glm-4-flash",
            imageModel = s.getString("imageModel", null) ?: "",
            pcUrl = s.getString("pcUrl", null) ?: "http://192.168.2.5:8642",
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
            putString("pcUrl", c.pcUrl)
            putBoolean("pcUploadCheckin", c.pcUploadCheckin)
            putString("coachStyle", c.coachStyle)
            putString("providerName", c.providerName)
            putBoolean("notifyWinddown", c.notifyWinddown)
            putBoolean("notifyMorning", c.notifyMorning)
            apply()
        }
    }

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
