package local.kuaixiu.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 聊天记录持久化：跨会话延续教练的记忆。
 * 存 filesDir/chat_history.json，保留最近 MAX 条；发往 LLM 的上下文由调用侧截取。
 */
object ChatLog {

    const val MAX = 200

    @Serializable
    data class Entry(val role: String, val text: String, val imagePath: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(ctx: Context) = File(ctx.filesDir, "chat_history.json")

    fun load(ctx: Context): List<Entry> = runCatching {
        val f = file(ctx)
        if (!f.exists()) emptyList() else json.decodeFromString<List<Entry>>(f.readText())
    }.getOrDefault(emptyList())

    fun append(ctx: Context, role: String, text: String, imagePath: String = ""): List<Entry> {
        val list = (load(ctx) + Entry(role, text, imagePath)).takeLast(MAX)
        file(ctx).writeText(json.encodeToString(list))
        return list
    }

    /** 整表覆写（UI 侧消息列表为唯一真源时使用）。 */
    fun persist(ctx: Context, entries: List<Entry>) {
        file(ctx).writeText(json.encodeToString(entries.takeLast(MAX)))
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
