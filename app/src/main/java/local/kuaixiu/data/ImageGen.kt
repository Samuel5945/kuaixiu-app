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

    fun generate(ctx: Context, cfg: KxConfig, prompt: String, size: String = DEFAULT_SIZE): File {
        require(cfg.imageModel.isNotBlank()) { "未配置生图模型：设置 → AI 教练 → 拉取模型列表 → 生图列点选一个" }
        val url = cfg.llmBaseUrl.trimEnd('/') + "/images/generations"
        val body = JSONObject().apply {
            put("model", cfg.imageModel)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("response_format", "b64_json")
            if ("sensenova" in cfg.llmBaseUrl.lowercase()) put("watermark", false)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "KuaixiuApp/0.3")
            setRequestProperty("Authorization", "Bearer ${cfg.llmKey}")
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
}
