package local.kuaixiu.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration

/** OpenAI 兼容 /chat/completions 客户端（非流式，MVP）。 */
class LlmClient(private val baseUrl: String, private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(120))
        .build()

    /** messages: role->content 有序列表，同步阻塞，请在 IO 线程调用。 */
    fun chat(model: String, messages: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.7)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
            return JSONObject(text).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    /** 流式对话：SSE 逐段回调（回调切主线程），返回完整文本。 */
    suspend fun chatStreaming(
        model: String,
        messages: List<Pair<String, String>>,
        onDelta: suspend (String) -> Unit,
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model).put("messages", arr)
            .put("temperature", 0.7).put("stream", true)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            val src = resp.body!!.source()
            val sb = StringBuilder()
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                runCatching {
                    val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta")?.optString("content", "")
                    if (!delta.isNullOrEmpty()) {
                        sb.append(delta)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onDelta(sb.toString())
                        }
                    }
                }
            }
            sb.toString()
        }
    }

    companion object {
        private val BLOCK = Regex("```kuaixiu\\s*\\n?([\\s\\S]*?)```")

        /** 取最后一个动作块原文（模型有时会发多块，最后一块才是结论）。 */
        fun extractAction(text: String): String? =
            BLOCK.findAll(text).lastOrNull()?.groupValues?.get(1)?.trim()

        /** 解析动作块；解析不出来就返回 null，调用侧必须保留原文，不能让它凭空消失。 */
        fun parseAction(text: String): JSONObject? =
            extractAction(text)?.let { raw -> parseJson(raw) }

        private fun parseJson(raw: String): JSONObject? =
            sequenceOf(raw, repairJson(raw))
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull()

        /** 模型常把引号/冒号/逗号写成全角，或留个尾逗号——修一遍再试一次。 */
        private fun repairJson(raw: String): String = raw
            .replace('“', '"').replace('”', '"')
            .replace('：', ':').replace('，', ',')
            .replace(Regex(",\\s*}"), "}")
            .replace(Regex(",\\s*]"), "]")
            .trim()

        /**
         * 去掉动作块得到展示用正文。**只删解析得掉的块**：解析失败的块留着显示，
         * 否则卡片没出来、原文又被吞掉，用户和模型都只能看见一句「这就安排」后面空空如也。
         */
        fun stripAction(text: String): String {
            val kept = StringBuilder()
            var last = 0
            for (m in BLOCK.findAll(text)) {
                if (parseJson(m.groupValues[1].trim()) != null) {
                    kept.append(text, last, m.range.first)
                    last = m.range.last + 1
                }
            }
            kept.append(text, last, text.length)
            return kept.toString().trim()
        }
    }
}
