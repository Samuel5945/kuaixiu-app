package local.kuaixiu.data

import android.content.Context
import android.net.Uri
import android.util.Log
import local.kuaixiu.calendar.CalendarWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket

/**
 * 局域网同步（主动拨出式，无广播/无开发者选项依赖）：
 * App 存活期间每 30 秒主动连 PC（KuaixiuTray TCP:8766）→ PULL 计划 + 回传打卡。
 * PC 地址取设置里的 pcUrl（默认 192.168.2.5）。
 */
object LanSync {

    private const val PC_PORT = 8766
    private const val TAG = "KuaixiuLan"
    private const val INTERVAL_MS = 30_000L

    @Volatile
    private var running = false
    private var lastSig = ""

    @Synchronized
    fun startIfNotRunning(ctx: Context) {
        if (running) return
        running = true
        Thread {
            while (true) {
                try {
                    val host = Uri.parse(KxStore.config.pcUrl).host ?: "192.168.2.5"
                    pull(ctx, host)
                } catch (e: Exception) {
                    Log.w(TAG, "pull failed: ${e.message}")
                }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (e: Exception) {
                    running = false
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun pull(ctx: Context, host: String) {
        val s0 = Socket()
        s0.connect(java.net.InetSocketAddress(host, PC_PORT), 6000)
        s0.soTimeout = 15000
        s0.use { sock ->
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            fun sendLine(s: String) {
                out.write((s + "\n").toByteArray())
                out.flush()
            }

            fun readLine(): String? {
                val sb = StringBuilder()
                while (true) {
                    val b = inp.read()
                    if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString()
                    if (b != '\r'.code) sb.append(b.toChar())
                }
            }

            sendLine("PULL")
            val header = readLine() ?: return
            if (!header.startsWith("PLAN ")) return
            val len = header.substring(5).trim().toInt()
            val data = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = inp.read(data, off, len - off)
                if (n <= 0) break
                off += n
            }
            val json = String(data, 0, off)
            val sig = json.length.toString() + ":" + json.hashCode()
            if (sig != lastSig) {
                val plan = Prefs.importPlanFromString(ctx, json)
                if (plan != null) {
                    val events = CalendarWriter.syncPlan(ctx, plan)
                    lastSig = sig
                    Log.i(TAG, "plan synced: ${plan.days.size} days / $events events")
                }
            }

            // 回传打卡（PC 端按日期去重）
            val arr = JSONArray()
            Checkins.all(ctx).forEach { c ->
                arr.put(
                    JSONObject()
                        .put("date", c.date)
                        .put("status", c.status)
                        .put("bedtime", c.bedtime)
                        .put("winddownAt", c.winddownAt)
                        .put("wakeupAt", c.wakeupAt)
                        .put("note", c.note)
                )
            }
            val payload = arr.toString().toByteArray()
            sendLine("CHECKINS " + payload.size)
            out.write(payload)
            out.flush()
            Log.i(TAG, "checkins pushed: ${arr.length()}")
        }
    }
}