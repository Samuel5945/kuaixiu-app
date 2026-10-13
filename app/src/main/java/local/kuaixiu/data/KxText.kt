package local.kuaixiu.data

import android.content.Context

/**
 * 非 Compose 代码取多语言文案的唯一入口（离线教练、评分、计划事件标题、日历写入…）。
 * 界面里优先用 stringResource()，手上有 Context 的用 ctx.getString()；这里只兜住两者都拿不到的地方。
 */
object KxText {

    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun s(id: Int, vararg args: Any): String {
        val c = app ?: throw IllegalStateException("KxText 未初始化（KxApp.onCreate 没跑到？）")
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }

    /**
     * 当前界面语言是否走英文。**取文案不要用它的**（资源自己按语言选），
     * 只有日期/数字格式这类要挑 Locale 的地方才需要（如海报的「10 月 13 日 · 星期一」）。
     */
    val english: Boolean
        get() = app?.resources?.configuration?.locales
            ?.takeIf { it.size() > 0 }?.get(0)?.language == "en"
}
