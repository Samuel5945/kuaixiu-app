package local.kuaixiu.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.kuaixiu.calendar.CalendarWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 局域网同步：
 * - 监听 PC（KuaixiuTray）每 10 秒的 UDP 广播 KUAIXIU_OFFER 拿到电脑 IP——这是唯一不需要用户
 *   先填地址的发现途径。
 * - 每 30 秒向该 IP 主动 PULL 计划 + 回传打卡，并在会话末尾问出 PC 信息页端口。
 * - 设置里没填 pcUrl（或还留着早期写死的开发机地址）时，把发现到的地址写进 pcUrl。
 */
object LanSync {

    private const val OFFER_PORT = 8766
    private const val PC_PORT = 8766
    private const val DEFAULT_INFO_PORT = 8642
    private const val TAG = "KuaixiuLan"
    private const val INTERVAL_MS = 30_000L
    private const val SCAN_INTERVAL_MS = 60_000L   // 还没定下电脑时，每分钟重扫一次网段

    /** 自动发现的 PC 信息页地址（设置页/电脑页观察；端口是信息页端口，不是同步端口）。 */
    var discoveredUrl by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var running = false
    @Volatile
    private var discoveredHost: String? = null
    @Volatile
    private var infoPort = DEFAULT_INFO_PORT
    private var lastSig = ""

    @Synchronized
    fun startIfNotRunning(ctx: Context) {
        if (running) return
        running = true
        listenOffers(ctx)
        pullLoop(ctx)
    }

    /** 听 PC 广播：收到即采纳，不等 PULL 成功。 */
    private fun listenOffers(ctx: Context) {
        Thread {
            try {
                DatagramSocket(OFFER_PORT).use { udp ->
                    val buf = ByteArray(256)
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        udp.receive(pkt)
                        if (!String(buf, 0, pkt.length).startsWith("KUAIXIU_OFFER|")) continue
                        val ip = pkt.address?.hostAddress ?: continue
                        discoveredHost = ip
                        adopt(ctx, ip)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "offer listener stopped: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun pullLoop(ctx: Context) {
        Thread {
            while (true) {
                // 手填 > 广播发现 > 按本机网段扫描；一个都没有就不拨任何地址（不猜默认 IP）
                val configured = Uri.parse(KxStore.config.pcUrl).host
                val host = configured ?: discoveredHost ?: scanForPc()
                if (host != null) {
                    runCatching { pull(ctx, host) }
                        .onFailure { Log.w(TAG, "pull $host failed: ${it.message}") }
                }
                try {
                    Thread.sleep(if (configured != null) INTERVAL_MS else SCAN_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    running = false
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * 按手机自己的 Wi-Fi 网段找开 8766 的那台机器。AP 常拦 PC→手机的入向包，广播和单播都
     * 送不到（本机实测），只有手机主动连才可靠。端口开着不等于认——要 PULL 握手真的返回
     * 计划，adopt() 才会把它写进设置。
     */
    private fun scanForPc(): String? {
        val local = localIpv4() ?: return null
        val base = local.substringBeforeLast('.', "")
        if (base.isEmpty()) return null
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(32)
        try {
            (1..254).forEach { i ->
                val h = "$base.$i"
                if (h != local) pool.execute {
                    if (found.get() != null) return@execute
                    try {
                        Socket().use { it.connect(java.net.InetSocketAddress(h, PC_PORT), 250) }
                        found.compareAndSet(null, h)
                    } catch (ignored: Exception) { }
                }
            }
            val deadline = System.currentTimeMillis() + 5000
            while (found.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        } catch (e: InterruptedException) {
            return null
        } finally {
            pool.shutdownNow()
        }
        return found.get()
    }

    /** 优先 Wi-Fi：USB 共享会给手机一个 192.168.137.x 的临时网段，拔线即断，不能当发现网段。 */
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .map { n ->
                n.name to n.inetAddresses.asSequence()
                    .filterIsInstance<java.net.Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }?.hostAddress
            }
            .filter { it.second != null }
            .sortedBy { if (it.first.startsWith("wl")) 0 else 1 }
            .firstOrNull()?.second
    }.getOrNull()

    /** 发现即采纳：刷新 discoveredUrl；用户没手填地址时把 pcUrl 填成它。 */
    private fun adopt(ctx: Context, host: String) {
        val url = "http://$host:$infoPort"
        discoveredUrl = url
        val c = KxStore.config
        if (c.pcUrl.isBlank() || c.pcUrl == Prefs.LEGACY_PC_URL) {
            KxStore.saveConfig(ctx, c.copy(pcUrl = url))
        }
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

            // 原始字节级读行（不能用 BufferedReader，会吞掉后续二进制）
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

            // 会话末尾问一次信息页端口：老版本 PC 不应答（EOF 或超时），继续用协议默认值
            runCatching {
                sock.soTimeout = 2500
                sendLine("INFO")
                val reply = readLine() ?: return@runCatching
                if (reply.startsWith("PORT ")) infoPort = reply.substring(5).trim().toInt()
            }
            adopt(ctx, host)
        }
    }
}
