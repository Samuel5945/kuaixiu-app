package local.kuaixiu.llm

import android.content.Context
import local.kuaixiu.data.LlmLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration

/** OpenAI 兼容 /chat/completions 客户端，支持 tools/function calling 与流式返回。 */
class LlmClient(private val baseUrl: String, private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(180))
        .build()

    /** 流式回复：文本增量走 [onDelta]，函数调用在流结束后解析。 */
    suspend fun chatStreaming(
        model: String,
        messages: List<Pair<String, String>>,
        tools: JSONArray? = null,
        logCtx: Context? = null,
        onDelta: suspend (String) -> Unit,
    ): LlmReply = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.7)
            .put("stream", true)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            // 默认 tool_choice 即为 auto：允许模型在工具与文本之间选择，不强制必须调用工具
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            val src = resp.body!!.source()
            val text = StringBuilder()
            val toolCalls = mutableMapOf<Int, AccumulatedToolCall>()
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                runCatching {
                    val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("delta") ?: return@runCatching
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        text.append(content)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onDelta(text.toString())
                        }
                    }
                    val tcDeltas = delta.optJSONArray("tool_calls")
                    if (tcDeltas != null) {
                        for (i in 0 until tcDeltas.length()) {
                            val tc = tcDeltas.getJSONObject(i)
                            val idx = tc.optInt("index", 0)
                            val acc = toolCalls.getOrPut(idx) { AccumulatedToolCall() }
                            acc.id += tc.optString("id", "")
                            acc.type += tc.optString("type", "")
                            tc.optJSONObject("function")?.let { fn ->
                                acc.name += fn.optString("name", "")
                                acc.arguments.append(fn.optString("arguments", ""))
                            }
                        }
                    }
                }
            }
            val sortedTools = toolCalls.toSortedMap().values.toList()
            val actionFromTool = sortedTools.firstOrNull()?.let { parseToolCall(it) }
            val finalText = text.toString().trim()
            // 如果 provider/模型不支持 tool_choice，可能 still 用 Markdown 块输出动作，兜底解析
            val actionJson = actionFromTool ?: parseAction(finalText)
            val toolNames = sortedTools.joinToString(",") { it.name }
            val rawForLog = buildString {
                appendLine("[streaming] tool_calls=${if (toolNames.isBlank()) "(none)" else toolNames}")
                if (sortedTools.size > 1) appendLine("(note: ${sortedTools.size} tool_calls, only first executed)")
                appendLine(finalText)
            }
            logCtx?.let { LlmLogger.log(it, model, rawForLog, actionJson?.toString()) }
            LlmReply(finalText, actionJson?.toString())
        }
    }

    /** 同步对话（简单场景用）。 */
    fun chat(model: String, messages: List<Pair<String, String>>, tools: JSONArray? = null, logCtx: Context? = null): LlmReply {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.7)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
            val json = JSONObject(text)
            val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val toolCalls = msg.optJSONArray("tool_calls")
            val finalText = msg.optString("content", "").trim()
            val actionFromTool = if (toolCalls != null && toolCalls.length() > 0) {
                parseToolCall(AccumulatedToolCall().apply {
                    val tc = toolCalls.getJSONObject(0)
                    id = tc.optString("id", "")
                    type = tc.optString("type", "")
                    tc.optJSONObject("function")?.let { fn ->
                        name = fn.optString("name", "")
                        arguments.append(fn.optString("arguments", ""))
                    }
                })
            } else null
            val actionJson = actionFromTool ?: parseAction(finalText)
            logCtx?.let { LlmLogger.log(it, model, text, actionJson?.toString()) }
            return LlmReply(finalText, actionJson?.toString())
        }
    }

    companion object {
        /** 主解析：只认 ```kuaixiu 块，避免普通 JSON 示例被误当成动作。 */
        private val BLOCK = Regex("```\\s*kuaixiu\\s*\\n?([\\s\\S]*?)```")
        /** 兜底：模型没写 kuaixiu 语言标识时也能试着解析。 */
        private val LOOSE_BLOCK = Regex("```\\s*(?:json)?\\s*\\n?([\\s\\S]*?)```")
        private val KUAIXIU_BLOCK = Regex("```\\s*kuaixiu\\s*\\n?([\\s\\S]*?)```")

        /** 取最后一个可解析的动作块原文。 */
        fun extractAction(text: String): String? =
            BLOCK.findAll(text).lastOrNull()?.let { m ->
                if (parseJson(m.groupValues[1].trim()) != null) m.groupValues[1].trim() else null
            }

        /** 取最后一个 kuaixiu 块的原始内容（无论 JSON 是否解析成功），用于排查。 */
        fun extractKuaixiuRaw(text: String): String? =
            KUAIXIU_BLOCK.findAll(text).lastOrNull()?.groupValues?.get(1)?.trim()

        /** 解析动作块：优先 tool_calls，其次 ```kuaixiu，最后宽松块兜底。 */
        fun parseAction(text: String): JSONObject? =
            BLOCK.findAll(text).mapNotNull { parseJson(it.groupValues[1].trim()) }.lastOrNull()
                ?: LOOSE_BLOCK.findAll(text).mapNotNull { parseJson(it.groupValues[1].trim()) }.lastOrNull()

        private fun parseJson(raw: String): JSONObject? =
            runCatching { JSONObject(raw) }.getOrNull()
                ?: runCatching { JSONObject(repairJson(raw)) }.getOrNull()

        private fun repairJson(raw: String): String = raw
            .replace('“', '"').replace('”', '"')
            .replace('：', ':').replace('，', ',')
            .replace(Regex(",(\\s*[}\\]])"), "$1")
            .trim()

        /** 去掉已解析的动作块，得到展示用正文；解析失败的块保留在正文里，方便排查。 */
        fun stripAction(text: String): String {
            val kept = StringBuilder()
            var last = 0
            // 只剥 kuaixiu 块；兜底块里可能是示例 JSON，不剥
            for (m in BLOCK.findAll(text)) {
                if (parseJson(m.groupValues[1].trim()) != null) {
                    kept.append(text, last, m.range.first)
                    last = m.range.last + 1
                }
            }
            kept.append(text, last, text.length)
            return kept.toString().trim().replace(Regex("\n{3,}"), "\n\n")
        }

        /** 把工具调用解析成 App 认识的动作 JSON；所有字段都用 opt，避免模型给错类型直接崩。 */
        private fun parseToolCall(acc: AccumulatedToolCall): JSONObject? {
            val name = acc.name.trim()
            val args = runCatching { JSONObject(acc.arguments.toString()) }.getOrNull() ?: return null
            val action = args.optString("action", "")
            return when (name) {
                "adjust_plan" -> JSONObject().apply {
                    put("action", "adjust")
                    optStr(args, "currentBedtime")?.let { put("currentBedtime", it) }
                    optStr(args, "targetBedtime")?.let { put("targetBedtime", it) }
                    optStr(args, "wake")?.let { put("wake", it) }
                    optInt(args, "days")?.let { put("days", it) }
                    optStr(args, "startDate")?.let { put("startDate", it) }
                }
                "mark_checkin" -> JSONObject().apply {
                    put("action", "checkin")
                    optStr(args, "status")?.let { put("status", it) }
                    optStr(args, "bedtime")?.let { put("bedtime", it) }
                    optStr(args, "note")?.let { put("note", it) }
                    optStr(args, "winddown")?.let { put("winddown", it) }
                }
                "add_reminder" -> JSONObject().apply {
                    put("action", "reminder")
                    optStr(args, "date")?.let { put("date", it) }
                    optStr(args, "time")?.let { put("time", it) }
                    optStr(args, "text")?.let { put("text", it) }
                    optInt(args, "leadMin")?.let { put("leadMin", it) }
                }
                "cancel_reminder" -> JSONObject().apply {
                    put("action", "reminder")
                    put("op", "cancel")
                    optStr(args, "text")?.let { put("text", it) }
                }
                "manage_habit" -> JSONObject().apply {
                    put("action", "habit")
                    put("op", args.optString("op", "add"))
                    optStr(args, "name")?.let { put("name", it) }
                    optStr(args, "cueTime")?.let { put("cueTime", it) }
                    optInt(args, "days")?.let { put("days", it) }
                    optStr(args, "startDate")?.let { put("startDate", it) }
                }
                "make_poster" -> JSONObject().apply {
                    put("action", "poster")
                    optStr(args, "template")?.let { put("template", it.uppercase()) }
                }
                "generate_image" -> JSONObject().apply {
                    put("action", "image")
                    optStr(args, "prompt")?.let { put("prompt", it) }
                    optStr(args, "ratio")?.let { put("ratio", it) }
                }
                // 兼容模型把 action 字段直接塞进 arguments 的情况
                else -> if (action.isNotBlank() && action in setOf("adjust", "checkin", "reminder", "habit", "image", "poster")) args else null
            }
        }

        private fun optStr(args: JSONObject, key: String): String? =
            if (args.has(key) && !args.isNull(key)) args.optString(key, "") else null

        private fun optInt(args: JSONObject, key: String): Int? =
            if (args.has(key) && !args.isNull(key)) {
                when (val v = args.get(key)) {
                    is Int -> v
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }
            } else null

        /** OpenAI tools 定义：描述务必与 system prompt 里的动作协议一致。 */
        fun toolsJson(): JSONArray = JSONArray().apply {
            put(tool(
                "adjust_plan", "调整早睡计划参数（current/target bedtime、wake、days、startDate）",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("currentBedtime", timeProp("用户当前实际入睡基线 HH:MM"))
                        put("targetBedtime", timeProp("新目标入睡 HH:MM"))
                        put("wake", timeProp("起床锚点 HH:MM"))
                        put("days", intProp("计划天数"))
                        put("startDate", dateProp("开始日期 YYYY-MM-DD，省略=明天"))
                    })
                }
            ))
            put(tool(
                "mark_checkin", "记录昨晚/风屏打卡",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("status", enumProp(listOf("on_time", "late", "skip"), "昨晚状态"))
                        put("bedtime", timeProp("昨晚实际入睡 HH:MM"))
                        put("note", strProp("备注"))
                        put("winddown", timeProp("今晚风屏开始 HH:MM"))
                    })
                }
            ))
            put(tool(
                "add_reminder", "添加一次性日历提醒",
                JSONObject().apply {
                    put("type", "object")
                    put("required", JSONArray().apply { put("text"); put("time") })
                    put("properties", JSONObject().apply {
                        put("date", dateProp("日期 YYYY-MM-DD，省略=今天"))
                        put("time", timeProp("时间 HH:MM"))
                        put("text", strProp("提醒内容"))
                        put("leadMin", intProp("提前几分钟响"))
                    })
                }
            ))
            put(tool(
                "cancel_reminder", "按标题删除已有提醒",
                JSONObject().apply {
                    put("type", "object")
                    put("required", JSONArray().apply { put("text") })
                    put("properties", JSONObject().apply {
                        put("text", strProp("要删除的提醒标题，必须逐字匹配"))
                    })
                }
            ))
            put(tool(
                "manage_habit", "创建/打卡/暂停一个重复习惯",
                JSONObject().apply {
                    put("type", "object")
                    put("required", JSONArray().apply { put("op"); put("name") })
                    put("properties", JSONObject().apply {
                        put("op", enumProp(listOf("add", "done", "pause"), "操作类型"))
                        put("name", strProp("习惯名称"))
                        put("cueTime", timeProp("每日固定锚点 HH:MM，add 时必填"))
                        put("days", intProp("连续提醒天数，add 时默认 21"))
                        put("startDate", dateProp("开始日期 YYYY-MM-DD，add 时省略=今天"))
                    })
                }
            ))
            put(tool(
                "make_poster", "做分享海报：连续天数/评分/放下手机时刻由 App 本地排版，不写画；" +
                    "没配生图模型也能用（走 App 内置底图）",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("template", enumProp(listOf("A", "B", "C", "D"),
                            "A=打卡纪念（连续天数巨字）B=奖章圆环 C=杂志「放下了手机」D=周报横条；默认 A"))
                    })
                }
            ))
            put(tool(
                "generate_image", "生成助眠插画/壁纸",
                JSONObject().apply {
                    put("type", "object")
                    put("required", JSONArray().apply { put("prompt") })
                    put("properties", JSONObject().apply {
                        put("prompt", strProp("中文画面描述，安静柔和的助眠风格，无文字"))
                        put("ratio", enumProp(listOf("portrait", "square"), "portrait=竖幅壁纸，square=方图"))
                    })
                }
            ))
        }

        private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
            JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", parameters)
                })
            }

        private fun strProp(desc: String): JSONObject =
            JSONObject().apply { put("type", "string"); put("description", desc) }
        private fun intProp(desc: String): JSONObject =
            JSONObject().apply { put("type", "integer"); put("description", desc) }
        private fun timeProp(desc: String): JSONObject =
            JSONObject().apply { put("type", "string"); put("description", desc) }
        private fun dateProp(desc: String): JSONObject =
            JSONObject().apply { put("type", "string"); put("description", desc) }
        private fun enumProp(values: List<String>, desc: String): JSONObject =
            JSONObject().apply {
                put("type", "string")
                put("description", desc)
                put("enum", JSONArray(values))
            }
    }
}

/** LLM 一次回复的解析结果：展示文本 + 可选动作 JSON。 */
data class LlmReply(val text: String, val actionJson: String? = null)

private data class AccumulatedToolCall(
    var id: String = "",
    var type: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
)
