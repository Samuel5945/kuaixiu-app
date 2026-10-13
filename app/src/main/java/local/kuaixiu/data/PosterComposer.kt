package local.kuaixiu.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/**
 * 把海报画出来。坐标就是设计稿的像素（1248×2752），1:1 落地，别在这里做缩放。
 * 文字全部用 [Paint]/[StaticLayout] 本地绘制——中文字形和数字都得可控，
 * 而且数字必须来自 [PosterData]（模型不知道他的数据）。
 */
object PosterComposer {

    const val W = 1248
    const val H = 2752
    private const val PAD = 104f

    // 设计稿取色（A/B 深色、C/D 米色）——这几个是版式的一部分，不是主题色，故意写死
    private const val INK = 0xFF1D1A16.toInt()
    private const val CREAM = 0xFFF2ECE2.toInt()
    private const val CREAM_DEEP = 0xFFDBD0BD.toInt()
    private const val AMBER = 0xFFE8A33D.toInt()
    private const val AMBER_LIGHT = 0xFFFFD79A.toInt()
    private const val NIGHT = 0xFF0A0F1D.toInt()
    private const val MUTED_DARK = 0xFF8B7F6B.toInt()
    private const val MUTED_LIGHT = 0xFFB9C7E2.toInt()

    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val normal = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

    /** 生成海报。bg 为 null 时用纯色/渐变兜底（周报 D 根本不需要底图）。 */
    fun render(tpl: PosterTemplate, d: PosterData, bg: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (tpl) {
            PosterTemplate.A -> drawA(c, d, bg)
            PosterTemplate.B -> drawB(c, d, bg)
            PosterTemplate.C -> drawC(c, d, bg)
            PosterTemplate.D -> drawD(c, d)
        }
        return bmp
    }

    fun save(ctx: Context, bmp: Bitmap, name: String = "kuaixiu_poster"): File {
        val f = File(File(ctx.filesDir, "posters").apply { mkdirs() }, "${name}_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    // ---------- A 底图铺满 + 巨字（打卡后自动弹） ----------
    private fun drawA(c: Canvas, d: PosterData, bg: Bitmap?) {
        c.drawColor(NIGHT)
        if (bg != null) c.drawBitmap(cover(bg, W.toFloat(), H.toFloat()), 0f, 0f, null)
        // 上浅下深的压暗：月亮留在上方，数字和底部数据压在暗处
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(
                0x0D060912, 0x4D060912, 0xDB060912.toInt(), 0xF2060912.toInt()), floatArrayOf(0f, .42f, .74f, 1f), Shader.TileMode.CLAMP)
        })
        text(c, "连 续 准 时", PAD, 234f, 38f, 0xFFC3D0EA.toInt(), bold, letterSpacing = .42f)
        text(c, d.streak.toString(), PAD, 700f, 520f, Color.WHITE, bold)
        text(c, "天", PAD + digitWidth(d.streak.toString(), 520f) + 32f, 648f, 96f, 0xFFDBE5F8.toInt(), bold)
        text(c, "第 ${d.dayIndex} 夜 · ${d.stage} · 目标入睡 ${d.targetBedtime}", PAD, 800f, 52f, MUTED_LIGHT, normal)
        // 底部三格
        val y = 2210f
        c.drawRect(PAD, y, W - PAD, y + 2f, paint().apply { color = 0x42C8D6F0 })
        val cols = listOf("昨晚评分" to "${d.scoreTotal}/100", "放下手机" to d.phoneDown.ifBlank { "—" }, "起床锚点" to d.wakeup.ifBlank { d.wakeAnchor })
        cols.forEachIndexed { i, (label, value) ->
            val x = PAD + i * ((W - 2 * PAD) / 3f)
            text(c, label, x, 2268f, 32f, 0xFF93A5C8.toInt(), normal, letterSpacing = .14f)
            text(c, value, x, 2360f, 84f, 0xFFF2F6FF.toInt(), bold)
        }
        text(c, "快休 · 早睡执行系统", PAD, 2620f, 36f, 0xFF8494B4.toInt(), normal)
        textRight(c, d.dateDot, W - PAD, 2620f, 36f, 0xFF8494B4.toInt(), normal)
    }

    // ---------- B 圆环奖章（里程碑） ----------
    private fun drawB(c: Canvas, d: PosterData, bg: Bitmap?) {
        c.drawColor(NIGHT)
        if (bg != null) c.drawBitmap(cover(bg, W.toFloat(), H.toFloat()), 0f, 0f, null)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
                intArrayOf(0x9E0A0814.toInt(), 0xCC0A0814.toInt(), 0xF00A0814.toInt()),
                floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)
        })
        textCentered(c, "坚 持 中", W / 2f, 252f, 40f, 0xFFC8BCD8.toInt(), normal, letterSpacing = .34f)
        val cx = W / 2f; val cy = 702f; val r = 356f; val stroke = 48f
        c.drawCircle(cx, cy, r, paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x24FFFFFF })
        val sweep = 360f * (milestoneProgress(d.streak))
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false,
            paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = AMBER })
        c.drawCircle(cx, cy, r - stroke / 2f - 4f, paint().apply { color = 0xFF150F22.toInt() })
        textCentered(c, d.streak.toString(), cx, cy + 92f, 272f, Color.WHITE, bold)
        textCentered(c, "连续准时 · 天", cx, cy + 168f, 46f, 0xFFC3B6D8.toInt(), normal)
        val badge = nextMilestone(d.streak)
        val bw = 560f; val bh = 104f; val by = 1180f
        c.drawRoundRect(RectF(cx - bw / 2, by, cx + bw / 2, by + bh), bh / 2, bh / 2,
            paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x73FFD79A })
        textCentered(c, "再 ${badge - d.streak} 天解锁「$badge 天徽章」", cx, by + 68f, 46f, AMBER_LIGHT, normal)
        val rows = listOf(
            "昨晚评分" to "${d.scoreTotal} / 100",
            "放下手机" to d.phoneDown.ifBlank { "—" },
            "计划进度" to "第 ${d.dayIndex} / ${d.planDays} 天",
        )
        var y = 1900f
        rows.forEach { (label, value) ->
            text(c, label, PAD, y + 50f, 48f, 0xFFB3A6C6.toInt(), normal)
            textRight(c, value, W - PAD, y + 50f, 64f, 0xFFF5F0FB.toInt(), bold)
            y += 92f
            c.drawRect(PAD, y, W - PAD, y + 2f, paint().apply { color = 0x2EC8BCD8 })
            y += 68f
        }
        text(c, "快休 · 早睡执行系统", PAD, 2620f, 36f, 0xFF887BA0.toInt(), normal)
        textRight(c, d.dateDot, W - PAD, 2620f, 36f, 0xFF887BA0.toInt(), normal)
    }

    // ---------- C 米色杂志（主动分享） ----------
    private fun drawC(c: Canvas, d: PosterData, bg: Bitmap?) {
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
                intArrayOf(0xFFF3EDE3.toInt(), 0xFFE9E0D2.toInt(), CREAM_DEEP), floatArrayOf(0f, .54f, 1f), Shader.TileMode.CLAMP)
        })
        text(c, d.dateCn.uppercase(), PAD, 296f, 38f, MUTED_DARK, normal, letterSpacing = .3f)
        text(c, "昨晚我在", PAD, 420f, 142f, INK, bold)
        val hero = d.phoneDown.ifBlank { d.bedtime.ifBlank { "--:--" } }
        val heroW = digitWidth(hero, 142f)
        text(c, hero, PAD, 600f, 142f, INK, bold)
        c.drawRect(PAD, 622f, PAD + heroW, 660f, paint().apply { color = 0xFFFFD08A.toInt() })
        text(c, "放下了手机", PAD, 780f, 142f, INK, bold)
        // 圆角图卡：底图 cover 进卡里，底部压暗放角标
        val cardTop = 862f; val cardH = 1180f
        val card = RectF(PAD, cardTop, W - PAD, cardTop + cardH)
        val path = Path().apply { addRoundRect(card, 44f, 44f, Path.Direction.CW) }
        c.save(); c.clipPath(path)
        if (bg != null) {
            val b = cover(bg, card.width(), card.height())
            c.drawBitmap(b, card.left, card.top, null)
        } else c.drawRect(card, paint().apply { color = 0xFF26314D.toInt() })
        c.drawRect(card.left, card.top + cardH * .58f, card.right, card.bottom, paint().apply {
            shader = LinearGradient(0f, card.top + cardH * .58f, 0f, card.bottom,
                intArrayOf(Color.TRANSPARENT, 0x80000000.toInt()), null, Shader.TileMode.CLAMP)
        })
        text(c, "第 ${d.dayIndex} 夜 · ${d.stage}", PAD + 52f, card.bottom - 52f, 36f, 0xFFEFEAE0.toInt(), normal, letterSpacing = .1f)
        c.restore()
        // 数据条：三格竖分隔
        val gy = 2200f
        val cells = listOf("连续准时" to "${d.streak}天", "昨晚评分" to "${d.scoreTotal}/100", "目标入睡" to d.targetBedtime)
        val cw = (W - 2 * PAD) / 3f
        cells.forEachIndexed { i, (label, value) ->
            val x = PAD + i * cw
            if (i > 0) c.drawRect(x - 20f, gy - 10f, x - 18f, gy + 150f, paint().apply { color = 0x291D1A16 })
            text(c, label, x, gy + 30f, 32f, MUTED_DARK, normal, letterSpacing = .1f)
            text(c, value, x, gy + 136f, 92f, INK, bold)
        }
        text(c, "快休", PAD, 2620f, 44f, INK, bold)
        textRight(c, "早睡不是意志力，是流程", W - PAD, 2620f, 38f, 0xFF6B6154.toInt(), normal)
    }

    // ---------- D 周报横条（每周一次；条长 = 当晚评分） ----------
    private fun drawD(c: Canvas, d: PosterData) {
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
                intArrayOf(0xFFF3EDE3.toInt(), 0xFFE9E0D2.toInt(), CREAM_DEEP), floatArrayOf(0f, .54f, 1f), Shader.TileMode.CLAMP)
        })
        text(c, d.weekLabel, PAD, 300f, 38f, MUTED_DARK, normal, letterSpacing = .3f)
        text(c, "这一周 ", PAD, 470f, 118f, INK, bold)
        val p1 = digitWidth("这一周 ", 118f, bold)
        val ratioW = digitWidth(d.weekRatio, 118f)
        text(c, d.weekRatio, PAD + p1, 470f, 118f, INK, bold)
        c.drawRect(PAD + p1, 492f, PAD + p1 + ratioW, 526f, paint().apply { color = 0xFFFFD08A.toInt() })
        text(c, " 晚准时", PAD + p1 + ratioW, 470f, 118f, INK, bold)
        text(c, "左边是每晚「放下手机」的时刻（自动探测）· 条长 = 当晚评分", PAD, 540f, 32f, MUTED_DARK, normal)
        // 7 根横条：不满 7 天时底对齐（最后一根落在满格布局同一位置），上面留白比中间空一块好看
        val trackL = 404f; val trackR = 1004f; val barH = 124f
        val shown = d.weekDays.takeLast(7)
        val lastBottom = 576f + 6 * 176f + barH
        var y = lastBottom - (shown.size - 1) * 176f - barH
        shown.forEach { line ->
            textCentered(c, line.weekday, PAD + 32f, y + 82f, 42f, MUTED_DARK, normal)
            text(c, line.phoneDown.ifBlank { "—" }, 192f, y + 82f, 40f, INK, bold)
            val track = RectF(trackL, y, trackR, y + barH)
            c.drawRoundRect(track, barH / 2, barH / 2, paint().apply { color = 0x121D1A16 })
            val w = (trackR - trackL) * (line.score.coerceIn(0, 100) / 100f)
            if (w > 4f) c.drawRoundRect(RectF(trackL, y, trackL + w, y + barH), barH / 2, barH / 2,
                paint().apply { color = when (line.status) { "late" -> AMBER; "skip" -> 0xFFC9BDA8.toInt(); else -> INK } })
            textRight(c, line.score.toString(), W - PAD, y + 82f, 40f, 0xFF6B6154.toInt(), bold)
            y += 176f
        }
        // 四格数据
        val gy = 1955f
        val cw = (W - 2 * PAD) / 4f
        listOf(
            "达成率" to "${d.ratePercent()}%",
            "平均放下手机" to d.avgPhoneDown,
            "连续准时" to "${d.onTimeCount}天",
            "平均评分" to d.avgScore.toString(),
        ).forEachIndexed { i, (label, value) ->
            val x = PAD + i * cw
            if (i > 0) c.drawRect(x - 18f, gy - 40f, x - 16f, gy + 120f, paint().apply { color = 0x291D1A16 })
            text(c, label, x, gy, 31f, MUTED_DARK, normal, letterSpacing = .1f)
            text(c, value, x, gy + 132f, 88f, INK, bold)
        }
        // 结语
        wrapText(c, d.quote, PAD + 34f, 2380f, W - PAD - PAD - 34f, 40f, 0xFF4C463C.toInt())
        c.drawRect(PAD, 2320f, PAD + 6f, 2470f, paint().apply { color = AMBER })
        text(c, "快休", PAD, 2660f, 44f, INK, bold)
        textRight(c, "计划第 ${d.dayIndex} / ${d.planDays} 天 · 目标入睡 ${d.targetBedtime}", W - PAD, 2660f, 38f, 0xFF6B6154.toInt(), normal)
    }

    // ---------- helpers ----------
    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun text(c: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int,
                     tf: Typeface, letterSpacing: Float = 0f) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = tf; this.letterSpacing = letterSpacing
        }
        c.drawText(s, x, baseline, p)
    }

    private fun textRight(c: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int, tf: Typeface) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = tf; textAlign = Paint.Align.RIGHT
        }
        c.drawText(s, x, baseline, p)
    }

    private fun textCentered(c: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int, tf: Typeface,
                             letterSpacing: Float = 0f) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = tf; textAlign = Paint.Align.CENTER; this.letterSpacing = letterSpacing
        }
        c.drawText(s, x, baseline, p)
    }

    private fun digitWidth(s: String, size: Float, tf: Typeface = bold): Float =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf }.measureText(s)

    /** 自动换行的多行文本，返回占用高度。 */
    private fun wrapText(c: Canvas, s: String, x: Float, top: Float, width: Float, size: Float, color: Int): Float {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = normal }
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, tp, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(18f, 1f)
            .build()
        c.save(); c.translate(x, top); layout.draw(c); c.restore()
        return layout.height.toFloat()
    }

    /** 按宽度裁成 cover（居中偏上，保留月亮那一带）。 */
    private fun cover(src: Bitmap, w: Float, h: Float): Bitmap {
        val scale = maxOf(w / src.width, h / src.height)
        val sw = (w / scale).toInt().coerceAtMost(src.width)
        val sh = (h / scale).toInt().coerceAtMost(src.height)
        val x = ((src.width - sw) / 2)
        val y = ((src.height - sh) * 0.28f).toInt()
        return Bitmap.createBitmap(src, x, y, sw, sh).let {
            Bitmap.createScaledBitmap(it, w.toInt(), h.toInt(), true)
        }
    }

    /** 圆环进度：下一个里程碑内已走完的比例（3/7/14/28）。 */
    private fun milestoneProgress(streak: Int): Float {
        val next = nextMilestone(streak)
        if (next == 0) return 1f
        val prev = listOf(0, 3, 7, 14).lastOrNull { it < next } ?: 0
        return ((streak - prev).toFloat() / (next - prev)).coerceIn(0f, 1f)
    }

    private fun nextMilestone(streak: Int): Int = listOf(3, 7, 14, 28).firstOrNull { it > streak } ?: streak
}
