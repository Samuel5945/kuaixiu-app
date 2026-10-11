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

    companion object {
        /** 解析回复尾部的动作块 ```kuaixiu {...}```，返回 JSONObject 或 null。 */
        fun parseAction(text: String): JSONObject? {
            val m = Regex("```kuaixiu\\s*\\n?([\\s\\S]*?)```").find(text) ?: return null
            return runCatching { JSONObject(m.groupValues[1].trim()) }.getOrNull()
        }

        /** 去掉动作块，得到展示用正文。 */
        fun stripAction(text: String): String =
            text.replace(Regex("```kuaixiu\\s*\\n?[\\s\\S]*?```"), "").trim()
    }
}
