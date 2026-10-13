package local.kuaixiu.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Duration

@Serializable
data class Checkin(
    val date: String,          // ISO：这一夜所属的计划日（傍晚起算）
    val status: String = "",   // on_time / late / skip（结果打卡，次日早上记）
    val bedtime: String = "",  // 实际入睡 HH:MM（**手填**：他自己觉得几点睡着的）
    val note: String = "",
    val winddownAt: String = "", // 今晚风屏开始 HH:MM（过程打卡，当天记）
    val wakeupAt: String = "",   // 今早首次拿起手机 HH:MM（起床代理，次日早上记）
    // 自动探测的"放下手机"时刻（UsageProbe.estimateOnset）——比手填的 bedtime 客观，
    // 周报按它算均值/画点阵；老记录没有这个字段，反序列化默认空串。
    val phoneDownAt: String = "",
)

object Checkins {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun file(ctx: Context) = File(ctx.filesDir, "checkins.json")

    fun all(ctx: Context): List<Checkin> = runCatching {
        val f = file(ctx)
        if (!f.exists()) emptyList() else json.decodeFromString<List<Checkin>>(f.readText())
    }.getOrDefault(emptyList())

    /** 记一条打卡（同日期覆盖），返回最新列表。 */
    fun add(ctx: Context, c: Checkin): List<Checkin> {
        val list = (all(ctx).filterNot { it.date == c.date } + c).sortedBy { it.date }
        file(ctx).writeText(json.encodeToString(list))
        return list
    }

    /** "今晚"归属的计划日：中午 12 点前算昨晚的延续，之后算今天晚上。 */
    fun tonightDate(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): java.time.LocalDate =
        if (now.hour >= 12) now.toLocalDate() else now.toLocalDate().minusDays(1)

    /** 记录今晚风屏开始时间（同日期已有记录则合并，保留其他字段）。 */
    fun logWinddown(ctx: Context, date: String, time: String): List<Checkin> {
        val list = all(ctx).toMutableList()
        val idx = list.indexOfFirst { it.date == date }
        if (idx >= 0) list[idx] = list[idx].copy(winddownAt = time)
        else list += Checkin(date = date, winddownAt = time)
        val sorted = list.sortedBy { it.date }
        file(ctx).writeText(json.encodeToString(sorted))
        return sorted
    }

    /** 上传一条打卡到 PC（POST /checkin，字段名与 PC 端对齐）。 */
    fun upload(ctx: Context, pcUrl: String, c: Checkin): String {
        val body = org.json.JSONObject()
            .put("date", c.date)
            .put("status", c.status)
            .put("actual_bedtime", c.bedtime)
            .put("note", c.note + "（来自App）")
        if (c.winddownAt.isNotBlank()) body.put("winddown_at", c.winddownAt)
        if (c.wakeupAt.isNotBlank()) body.put("wakeup_at", c.wakeupAt)
        val req = Request.Builder()
            .url(pcUrl.trimEnd('/') + "/checkin")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(8)).build()
            .newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(200)}")
                return text
            }
    }
}
