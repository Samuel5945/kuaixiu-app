package local.kuaixiu.data

/**
 * 分享海报的**数据**：全部来自本地记录（Checkins / Score / Plan / UsageProbe），
 * 生图模型只出无字底图，一个数字都不许模型写——它不知道、也抄不准。
 */
data class PosterLine(
    val weekday: String,     // 一 / 二 …
    val date: String,        // 10-13
    val phoneDown: String,   // 放下手机 00:38（可能是空串：老记录没有这一列）
    val bedtime: String,     // 手填的入睡（备查，卡面不主打）
    val score: Int,          // 当天评分 0-100
    val status: String,      // on_time / late / skip / ""（无记录）
)

data class PosterData(
    // 单夜（A/B/C）
    val streak: Int,
    val scoreTotal: Int,
    val scoreWinddown: Int,
    val scoreSleep: Int,
    val scoreWake: Int,
    val phoneDown: String,
    val bedtime: String,
    val wakeup: String,
    val wakeAnchor: String,
    val targetBedtime: String,
    val dayIndex: Int,
    val planDays: Int,
    val stage: String,
    val dateCn: String,      // 10 月 13 日 · 周二
    val dateDot: String,     // 2026.10.13
    // 周报（D）
    val weekLabel: String,   // 10.07 – 10.13 · 第 1 周
    val weekDays: List<PosterLine>,
    val onTimeCount: Int,
    val weekTotal: Int,
    val avgPhoneDown: String,
    val avgScore: Int,
    val rateHit: Int,
    val rateElapsed: Int,
    val quote: String,
) {
    /** 周报标题用的 "6/7"。 */
    val weekRatio: String get() = "$onTimeCount/$weekTotal"

    fun scorePercent(): Int = scoreTotal.coerceIn(0, 100)
    fun ratePercent(): Int =
        if (rateElapsed <= 0) 0 else Math.round(rateHit * 100f / rateElapsed)
}

/** 四版版式。A 打卡后自动弹 / B 里程碑 / C 主动分享 / D 周报。 */
enum class PosterTemplate(val label: String) {
    A("打卡纪念"), B("奖章"), C("杂志"), D("周报")
}
