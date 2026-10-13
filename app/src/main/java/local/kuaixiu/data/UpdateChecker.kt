package local.kuaixiu.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import local.kuaixiu.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 应用内更新检测（提示式，不做自动安装——APK 走网盘分发，装不装由用户决定）。
 * 只认官网清单 ligdesign.win/kuaixiu-update.json 这一条源：仓库是私密的，
 * GitHub Release 无鉴权查不到，不设那条源。清单 version 高于当前 versionName
 * 即「已就绪」（所以官网清单一定是发版最后一步）。
 */
object UpdateChecker {

    const val MANIFEST_URL = "https://ligdesign.win/kuaixiu-update.json"

    // 部分网关对默认 UA（okhttp/curl）会拦或给缓存页，装成浏览器最稳
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 8000L

    data class UpdateInfo(
        val version: String,
        val releaseDate: String?,
        val notes: List<String>,
        /** (渠道键, 链接)：quark / baidu / site。标签交给界面按当前语言解析，这里保持纯函数可单测。 */
        val downloads: List<Pair<String, String>>,
    )

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        data class UpToDate(val current: String) : Result()
        data class Failed(val message: String) : Result()
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 版本比较：a>b 返回 1，a<b 返回 -1，相等 0；兼容 v 前缀与非数字段（按 0 处理）。 */
    fun compareVersions(a: String, b: String): Int {
        val seg = { v: String ->
            v.trim().removePrefix("v").removePrefix("V")
                .split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        }
        val pa = seg(a)
        val pb = seg(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }

    /** 当前安装版本的 versionName（读不到按 0.0 算，宁可多提示一次也不漏）。 */
    fun currentVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    /**
     * 解析官网清单；不是 JSON / 没有合法版本号都返回 null。
     * Cloudflare 静态托管对未部署路径会 200 返回首页 HTML（软 404），
     * 所以先看正文像不像 JSON 再交给 JSONObject。
     */
    fun parseManifest(text: String): UpdateInfo? {
        val t = text.trimStart()
        if (!t.startsWith("{")) return null
        return try {
            val j = JSONObject(t)
            val version = j.optString("version").trim().removePrefix("v").removePrefix("V")
            if (!Regex("^\\d+\\.\\d+").containsMatchIn(version)) return null
            val notes = when (val n = j.opt("notes")) {
                is JSONArray -> (0 until n.length())
                    .mapNotNull { n.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
                is String -> n.lines().map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
            val d = j.optJSONObject("downloads")
            val downloads = listOf("quark", "baidu", "site").mapNotNull { key ->
                d?.optString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { key to it }
            }
            UpdateInfo(version, j.optString("releaseDate").trim().ifEmpty { null }, notes, downloads)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 检查更新。[manual]=false 为启动时的静默检查：已忽略的版本、已是最新都不打扰；
     * 手动检查忽略「忽略此版本」记录（弹窗里还能再忽略）。
     */
    fun check(ctx: Context, manual: Boolean): Result {
        val current = currentVersion(ctx)
        val text = try {
            fetch()
        } catch (_: Exception) {
            return Result.Failed(KxText.s(R.string.upd_check_failed))
        }
        // 清单还没发布（软 404/硬 404 已被 fetch 拦下）或内容无效：按没有新版本处理
        val info = parseManifest(text) ?: return Result.UpToDate(current)
        if (compareVersions(info.version, current) <= 0) return Result.UpToDate(current)
        if (!manual && info.version == Prefs.skippedUpdate(ctx)) return Result.UpToDate(current)
        return Result.Available(info)
    }

    /** cache-bust 避免 CDN 一直喂旧的清单。 */
    private fun fetch(): String {
        val req = Request.Builder()
            .url("$MANIFEST_URL?t=${System.currentTimeMillis()}")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string()?.takeIf { it.isNotBlank() } ?: error("空响应")
        }
    }
}
