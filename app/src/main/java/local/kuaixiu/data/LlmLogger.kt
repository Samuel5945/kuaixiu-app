package local.kuaixiu.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LLM 响应本地日志（脱敏 Key，只记 model / 返回原文 / 解析结果）。
 * 文件位于 ctx.filesDir/llm_log.txt，超过 100KB 时保留末尾 80KB。
 */
object LlmLogger {

    private const val MAX_SIZE = 100_000L
    private const val KEEP_TAIL = 80_000

    fun log(ctx: Context, model: String, raw: String, actionJson: String? = null, error: String? = null) {
        runCatching {
            val f = File(ctx.filesDir, "llm_log.txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val entry = buildString {
                appendLine("--- $ts ---")
                appendLine("model: $model")
                if (error != null) appendLine("error: $error")
                if (actionJson != null) appendLine("action: $actionJson")
                appendLine("raw:")
                appendLine(raw.take(4000))
                appendLine()
            }
            f.appendText(entry)
            if (f.length() > MAX_SIZE) {
                val tail = f.readText().takeLast(KEEP_TAIL)
                f.writeText(tail)
            }
        }
    }
}
