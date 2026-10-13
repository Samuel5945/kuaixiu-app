package local.kuaixiu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * 生图（OpenAI images 兼容协议，移植 tuwen-editor imageGen.ts 的标准路径）：
 * POST {base}/images/generations  body {model, prompt, n, size, response_format:b64_json}
 * 商汤扩展：watermark:false；尺寸须 32 倍数（1024x1024 安全）。
 * UA 必须纯 ASCII——中文 UA 会被商汤网关 500。
 */
object ImageGen {

    const val DEFAULT_SIZE = "1024x1024"
    const val PORTRAIT_SIZE = "832x1216"  // 竖屏壁纸，32 倍数（商汤要求）
    const val POSTER_SIZE = "1248x2752"   // 满幅分享海报：跟版面 1:1（都是 32 倍数）

    /** 助眠场景模板：按天轮换，避免每晚同一张。 */
    val SLEEP_SCENES = listOf(
        "深邃夜空中的银河与流星，山峦剪影，极简插画风格，柔和蓝紫渐变，安静助眠氛围，无文字",
        "月光洒在平静海面，微微波光，远处一艘小船，柔和冷色调，极简治愈插画，无文字",
        "雨夜窗边，暖黄台灯，雨滴在玻璃上缓缓滑落，温暖安静的室内视角，治愈插画，无文字",
        "晨雾中的森林小屋，窗内炉火微光，暖色调，宁静治愈插画，无文字",
        "竹林间的月光小径，几点萤火虫微光，青绿色调，极简治愈插画，无文字",
        "雪原小木屋的夜晚，星空与淡淡极光，暖窗灯光，宁静治愈插画，无文字",
    )

    fun sleepPrompt(dayIndex: Int): String =
        SLEEP_SCENES[Math.floorMod(dayIndex, SLEEP_SCENES.size)]

    /** 生图可与文本分属不同供应商：按 imageProvider 取那家的档案（端点 + Key），为空则跟文本同一家。 */
    fun imageEndpoint(ctx: Context, cfg: KxConfig): Pair<String, String> {
        if (cfg.imageProvider.isBlank()) return cfg.llmBaseUrl to cfg.llmKey
        val p = ProviderProfiles.get(ctx, cfg.imageProvider)
        val base = p?.baseUrl?.ifBlank { cfg.llmBaseUrl } ?: cfg.llmBaseUrl
        return base to ProviderProfiles.decryptKey(p).ifBlank { cfg.llmKey }
    }

    fun generate(ctx: Context, cfg: KxConfig, prompt: String, size: String = DEFAULT_SIZE): File {
        val imageModel = cfg.effectiveImageModel()
        require(imageModel.isNotBlank()) { "未配置生图模型：设置 → AI 教练 → 拉取模型列表 → 生图列点选一个" }
        val (base, key) = imageEndpoint(ctx, cfg)
        val url = base.trimEnd('/') + "/images/generations"
        val body = JSONObject().apply {
            put("model", imageModel)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("response_format", "b64_json")
            if ("sensenova" in base.lowercase()) put("watermark", false)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "KuaixiuApp/0.3")
            setRequestProperty("Authorization", "Bearer $key")
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val text = try {
            conn.inputStream.use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            val errText = runCatching { conn.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull() ?: ""
            throw java.io.IOException("生图失败 HTTP ${conn.responseCode}：${errText.take(200)}")
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(text)
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: throw java.io.IOException("响应缺少 data：${text.take(200)}")
        val b64 = item.optString("b64_json", "")
        val bytes = when {
            b64.isNotBlank() -> Base64.getDecoder().decode(b64)
            else -> {
                val imgUrl = item.optString("url", "")
                require(imgUrl.isNotBlank()) { "响应既无 b64_json 也无 url" }
                (URL(imgUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 60_000
                }.inputStream.use { it.readBytes() }
            }
        }
        val dir = File(ctx.filesDir, "gen_images").apply { mkdirs() }
        val f = File(dir, "img_${System.currentTimeMillis()}.png")
        f.writeBytes(bytes)
        return f
    }

    /** 保存生成的图片到系统相册（API 29+ 走 MediaStore IS_PENDING，无需存储权限）。 */
    fun saveToGallery(ctx: Context, file: File): android.net.Uri? = runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                "kuaixiu_${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= 29)
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver
            .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().copyTo(out)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        }
        uri
    }.getOrNull()
}
