package local.kuaixiu.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 按供应商存档案：每个预设各自记 base_url / Key（Keystore 加密）/ 模型。
 * 点供应商 chips = 切换档案，互不串用；Key 永远跟随各自的供应商。
 */
object ProviderProfiles {

    @Serializable
    data class Profile(
        val baseUrl: String = "", val keyEnc: String = "", val model: String = "",
        val imageModel: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(ctx: Context) =
        ctx.getSharedPreferences("kuaixiu", Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableMap<String, Profile> = runCatching {
        file(ctx).getString("providerProfiles", null)
            ?.let { json.decodeFromString<Map<String, Profile>>(it).toMutableMap() }
    }.getOrNull() ?: mutableMapOf()

    private fun save(ctx: Context, profiles: Map<String, Profile>) {
        file(ctx).edit().putString("providerProfiles", json.encodeToString(profiles)).apply()
    }

    fun get(ctx: Context, name: String): Profile? = all(ctx)[name]

    fun put(ctx: Context, name: String, baseUrl: String, key: String, model: String,
            imageModel: String? = null) {
        if (name.isBlank()) return
        val profiles = all(ctx)
        val old = profiles[name]
        // Key 留空 = 保持该供应商已存的 Key；生图模型 null = 保持
        val keyEnc = if (key.isBlank()) old?.keyEnc ?: "" else SecretStore.encrypt(key)
        val img = imageModel ?: old?.imageModel ?: ""
        profiles[name] = Profile(baseUrl, keyEnc, model, img)
        save(ctx, profiles)
    }

    fun decryptKey(p: Profile?): String =
        p?.let { SecretStore.decrypt(it.keyEnc) } ?: ""
}
