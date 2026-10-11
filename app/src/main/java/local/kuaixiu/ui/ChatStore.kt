package local.kuaixiu.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import local.kuaixiu.data.ChatLog

/**
 * 聊天消息的应用级单例：切页签/重组都不丢（进程被杀才靠 ChatLog 恢复）。
 * 每条逻辑消息同时写入 ChatLog 落盘；占位"…"是瞬态 UI，不入盘。
 */
data class ChatMsg(val role: String, val text: String, val actionJson: String? = null,
                   val imagePath: String? = null)

object ChatStore {

    val msgs = mutableStateListOf<ChatMsg>()

    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val restored = ChatLog.load(ctx).map {
            ChatMsg(it.role, it.text, imagePath = it.imagePath.ifBlank { null })
        }
        msgs.addAll(restored)
        if (msgs.isEmpty()) {
            msgs.add(ChatMsg("assistant",
                "我是你的早睡教练。可以问我任何作息问题，也可以直接说「目标改到23:00」「昨晚1点睡的帮我打卡」。"))
        }
    }

    /** 追加一条消息并落盘（占位"…"不要用这个）。 */
    fun add(ctx: Context, role: String, text: String, imagePath: String? = null) {
        msgs.add(ChatMsg(role, text, imagePath = imagePath?.ifBlank { null }))
        ChatLog.append(ctx, role, text, imagePath ?: "")
    }

    /** 瞬态消息（如"生图中…"），不入盘。 */
    fun addTransient(msg: ChatMsg) { msgs.add(msg) }

    /** 原地替换（流式更新/最终化），完成时落盘。 */
    fun replace(ctx: Context, index: Int, msg: ChatMsg, persisted: Boolean) {
        if (index !in msgs.indices) return
        msgs[index] = msg
        if (persisted) ChatLog.append(ctx, msg.role, msg.text, msg.imagePath ?: "")
    }
}
