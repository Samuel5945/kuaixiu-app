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
import local.kuaixiu.R
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
        text(c, KxText.s(R.string.poster_a_streak_label), PAD, 234f, 38f, 0xFFC3D0EA.toInt(), bold, letterSpacing = .42f)
        text(c, d.streak.toString(), PAD, 700f, 520f, Color.WHITE, bold)
        // 单位字的位置按巨字的实际宽度量：换语言后"天 / days"长短差很多，写死会压到画布外
        val unitX = PAD + textWidth(d.streak.toString(), 520f, bold) + 32f
        val dayUnit = KxText.n(R.plurals.poster_unit_days, d.streak)
        text(c, dayUnit, unitX, 648f, fittedSize(dayUnit, 96f, W - PAD - unitX), 0xFFDBE5F8.toInt(), bold)
        val nightLine = KxText.s(R.string.poster_a_night_line, d.dayIndex, phaseForArt(d.stage), d.targetBedtime)
        text(c, nightLine, PAD, 800f, fittedSize(nightLine, 52f, W - 2 * PAD, normal), MUTED_LIGHT, normal)
        // 底部三格
        val y = 2210f
        c.drawRect(PAD, y, W - PAD, y + 2f, paint().apply { color = 0x42C8D6F0 })
        val cols = listOf(
            KxText.s(R.string.poster_lab_score) to "${d.scoreTotal}/100",
            KxText.s(R.string.poster_lab_phone_down) to d.phoneDown.ifBlank { "—" },
            KxText.s(R.string.poster_lab_wake_anchor) to d.wakeup.ifBlank { d.wakeAnchor },
        )
        cols.forEachIndexed { i, (label, value) ->
            val colW = (W - 2 * PAD) / 3f
            val x = PAD + i * colW
            text(c, label, x, 2268f, fittedSize(label, 32f, colW - 40f, normal, .14f),
                0xFF93A5C8.toInt(), normal, letterSpacing = .14f)
            text(c, value, x, 2360f, fittedSize(value, 84f, colW - 48f), 0xFFF2F6FF.toInt(), bold)
        }
        footerRow(c, KxText.s(R.string.poster_brand), d.dateDot, 2620f, 36f, 0xFF8494B4.toInt())
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
        textCentered(c, KxText.s(R.string.poster_b_still_going), W / 2f, 252f, 40f, 0xFFC8BCD8.toInt(), normal, letterSpacing = .34f)
        val cx = W / 2f; val cy = 702f; val r = 356f; val stroke = 48f
        c.drawCircle(cx, cy, r, paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x24FFFFFF })
        val sweep = 360f * (milestoneProgress(d.streak))
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false,
            paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = AMBER })
        c.drawCircle(cx, cy, r - stroke / 2f - 4f, paint().apply { color = 0xFF150F22.toInt() })
        textCentered(c, d.streak.toString(), cx, cy + 92f, 272f, Color.WHITE, bold)
        textCentered(c, KxText.n(R.plurals.poster_b_days_on_time, d.streak), cx, cy + 168f, 46f, 0xFFC3B6D8.toInt(), normal)
        val badge = nextMilestone(d.streak)
        val bw = 560f; val bh = 104f; val by = 1180f
        c.drawRoundRect(RectF(cx - bw / 2, by, cx + bw / 2, by + bh), bh / 2, bh / 2,
            paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x73FFD79A })
        // 胶囊是定宽的：按真正那句话量宽，换语言后顶出边框就缩号
        val unlock = KxText.s(R.string.poster_b_unlock, badge - d.streak, badge)
        textCentered(c, unlock, cx, by + 68f, fittedSize(unlock, 46f, bw - 48f, normal), AMBER_LIGHT, normal)
        val rows = listOf(
            KxText.s(R.string.poster_lab_score) to "${d.scoreTotal} / 100",
            KxText.s(R.string.poster_lab_phone_down) to d.phoneDown.ifBlank { "—" },
            // 起床锚点他没填、早上也没手选时间：用 UsageProbe 抓的"首次亮屏"当起床时刻，跟放下手机成对
            KxText.s(R.string.poster_lab_morning) to d.wakeup.ifBlank { "—" },
        )
        var y = 1900f
        rows.forEach { (label, value) ->
            // 一行里标签和数字抢同一段宽度：按实际数值串量，别挤到右边压着数字
            val maxLabelW = (W - 2 * PAD) - textWidth(value, 64f, bold) - 40f
            text(c, label, PAD, y + 50f, fittedSize(label, 48f, maxLabelW, normal), 0xFFB3A6C6.toInt(), normal)
            textRight(c, value, W - PAD, y + 50f, 64f, 0xFFF5F0FB.toInt(), bold)
            y += 92f
            c.drawRect(PAD, y, W - PAD, y + 2f, paint().apply { color = 0x2EC8BCD8 })
            y += 68f
        }
        footerRow(c, KxText.s(R.string.poster_brand), d.dateDot, 2620f, 36f, 0xFF887BA0.toInt())
    }

    // ---------- C 米色杂志（主动分享） ----------
    private fun drawC(c: Canvas, d: PosterData, bg: Bitmap?) {
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
                intArrayOf(0xFFF3EDE3.toInt(), 0xFFE9E0D2.toInt(), CREAM_DEEP), floatArrayOf(0f, .54f, 1f), Shader.TileMode.CLAMP)
        })
        // 三行大标题的行盒：275 / 472 / 652 / 832，行距 180；日期那行要留在标题升部之上
        text(c, d.dateCn.uppercase(), PAD, 275f, 38f, MUTED_DARK, normal, letterSpacing = .3f)
        // 没抓到"放下手机"就只能说入睡，标签跟着换——标签写放下手机却显示别的时间就是假数据
        val openLine = KxText.s(R.string.poster_c_last_night)
        val tailLine = if (d.phoneDown.isBlank()) KxText.s(R.string.poster_c_asleep) else KxText.s(R.string.poster_c_phone_down)
        // 三行大字共用一个字号：换语言后哪一行都别冲出画布，也不出现一行大一行小
        val bigSize = minOf(fittedSize(openLine, 142f, W - 2 * PAD), fittedSize(tailLine, 142f, W - 2 * PAD))
        text(c, openLine, PAD, 472f, bigSize, INK, bold)
        val hero = d.phoneDown.ifBlank { d.bedtime.ifBlank { "--:--" } }
        val heroW = digitWidth(hero, 142f)
        // 高亮先画、文字压在上面：只糊住字的下半截（像荧光笔），别沉到基线下面去
        c.drawRect(PAD, 652f - 43f, PAD + heroW, 652f + 9f, paint().apply { color = 0xFFFFD08A.toInt() })
        text(c, hero, PAD, 652f, 142f, INK, bold)
        text(c, tailLine, PAD, 832f, bigSize, INK, bold)
        // 圆角图卡按 1024×1152 摆（都是 32 的倍数）：跟要来的图 1:1，一点不裁，主体不会掉
        val cardW = 1024f; val cardH = 1152f
        val cardTop = 862f
        val card = RectF((W - cardW) / 2f, cardTop, (W + cardW) / 2f, cardTop + cardH)
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
        val onCard = KxText.s(R.string.poster_c_night_stage, d.dayIndex, phaseForArt(d.stage))
        text(c, onCard, card.left + 52f, card.bottom - 52f,
            fittedSize(onCard, 36f, card.width() - 104f, normal, .1f), 0xFFEFEAE0.toInt(), normal, letterSpacing = .1f)
        c.restore()
        // 数据条：三格竖分隔
        val gy = 2200f
        val cells = listOf(
            KxText.s(R.string.poster_lab_streak) to KxText.n(R.plurals.poster_days_count, d.streak, d.streak),
            KxText.s(R.string.poster_lab_phone_down) to d.phoneDown.ifBlank { "—" },
            KxText.s(R.string.poster_lab_morning) to d.wakeup.ifBlank { "—" },
        )
        val cw = (W - 2 * PAD) / 3f
        cells.forEachIndexed { i, (label, value) ->
            val x = PAD + i * cw
            if (i > 0) c.drawRect(x - 20f, gy - 10f, x - 18f, gy + 150f, paint().apply { color = 0x291D1A16 })
            text(c, label, x, gy + 30f, fittedSize(label, 32f, cw - 40f, normal, .1f),
                MUTED_DARK, normal, letterSpacing = .1f)
            text(c, value, x, gy + 136f, fittedSize(value, 92f, cw - 66f), INK, bold)
        }
        // 页脚：品牌短名 + 那句话，共享一行宽度
        val brandShort = KxText.s(R.string.app_name)
        val tagline = KxText.s(R.string.poster_c_tagline)
        text(c, brandShort, PAD, 2620f, 44f, INK, bold)
        textRight(c, tagline, W - PAD, 2620f,
            fittedSize(tagline, 38f, (W - 2 * PAD) - textWidth(brandShort, 44f, bold) - 40f, normal),
            0xFF6B6154.toInt(), normal)
    }

    // ---------- D 周报横条（每周一次；条长 = 当晚评分） ----------
    private fun drawD(c: Canvas, d: PosterData) {
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
                intArrayOf(0xFFF3EDE3.toInt(), 0xFFE9E0D2.toInt(), CREAM_DEEP), floatArrayOf(0f, .54f, 1f), Shader.TileMode.CLAMP)
        })
        text(c, d.weekLabel, PAD, 300f, 38f, MUTED_DARK, normal, letterSpacing = .3f)
        // 标题是"前缀 + 6/7 + 后缀"三段拼出来的：高亮块要正好压在比率底下，
        // 所以宽度一律按真正要画的那几段字量（英文前缀是空串，位置自然贴到左边）
        val lead = KxText.s(R.string.poster_d_this_week)
        val trail = KxText.s(R.string.poster_d_on_time)
        val titleSize = fitRowSize(listOf(lead, d.weekRatio, trail), 118f, W - 2 * PAD)
        text(c, lead, PAD, 470f, titleSize, INK, bold)
        val p1 = textWidth(lead, titleSize, bold)
        val ratioW = textWidth(d.weekRatio, titleSize, bold)
        c.drawRect(PAD + p1, 470f - 35f, PAD + p1 + ratioW, 470f + 7f, paint().apply { color = 0xFFFFD08A.toInt() })
        text(c, d.weekRatio, PAD + p1, 470f, titleSize, INK, bold)
        text(c, trail, PAD + p1 + ratioW, 470f, titleSize, INK, bold)
        val barHint = KxText.s(R.string.poster_d_hint)
        text(c, barHint, PAD, 540f, fittedSize(barHint, 32f, W - 2 * PAD, normal), MUTED_DARK, normal)
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
            KxText.s(R.string.poster_d_hit_rate) to "${d.ratePercent()}%",
            // 一晚「放下手机」都没探到时也要有个占位，别留一格空白（A/B/C 同样兜底）
            KxText.s(R.string.poster_d_avg_phone) to d.avgPhoneDown.ifBlank { "—" },
            // 这格是连续天数，不是本周准时晚数：断档的周里两者不同，画错了就是假数据
            KxText.s(R.string.poster_lab_streak) to KxText.n(R.plurals.poster_days_count, d.streak, d.streak),
            KxText.s(R.string.poster_d_avg_score) to d.avgScore.toString(),
        ).forEachIndexed { i, (label, value) ->
            val x = PAD + i * cw
            if (i > 0) c.drawRect(x - 18f, gy - 40f, x - 16f, gy + 120f, paint().apply { color = 0x291D1A16 })
            text(c, label, x, gy, fittedSize(label, 31f, cw - 30f, normal, .1f),
                MUTED_DARK, normal, letterSpacing = .1f)
            text(c, value, x, gy + 132f, fittedSize(value, 88f, cw - 46f), INK, bold)
        }
        // 结语
        wrapText(c, d.quote, PAD + 34f, 2380f, W - PAD - PAD - 34f, 40f, 0xFF4C463C.toInt())
        c.drawRect(PAD, 2320f, PAD + 6f, 2470f, paint().apply { color = AMBER })
        val brandShort = KxText.s(R.string.app_name)
        val planLine = KxText.s(R.string.poster_d_plan_line, d.dayIndex, d.planDays, d.targetBedtime)
        text(c, brandShort, PAD, 2660f, 44f, INK, bold)
        textRight(c, planLine, W - PAD, 2660f,
            fittedSize(planLine, 38f, (W - 2 * PAD) - textWidth(brandShort, 44f, bold) - 40f, normal),
            0xFF6B6154.toInt(), normal)
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

    /** 海报里 NIGHT / TARGET 这类全大写行，阶段名也要跟着大写（中文没有大小写，原样返回）。 */
    private fun phaseForArt(stage: String): String =
        if (KxText.english) stage.uppercase() else stage

    private fun textCentered(c: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int, tf: Typeface,
                             letterSpacing: Float = 0f) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = tf; textAlign = Paint.Align.CENTER; this.letterSpacing = letterSpacing
        }
        c.drawText(s, x, baseline, p)
    }

    /**
     * 量一段字要占多宽（含字距）。凡是"用上一个元素的宽度定下一个元素的坐标"，
     * 一律用它量真正要画出来的字符串——换语言后字串长短不同，写死字面量就会溢出。
     */
    private fun textWidth(s: String, size: Float, tf: Typeface = bold, spacing: Float = 0f): Float =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; typeface = tf; letterSpacing = spacing
        }.measureText(s)

    private fun digitWidth(s: String, size: Float, tf: Typeface = bold): Float = textWidth(s, size, tf)

    /** 超过格宽就缩字号，别让相邻两格贴到一起（100/100、长英文标签这种尤其明显）。 */
    private fun fittedSize(s: String, size: Float, maxW: Float, tf: Typeface = bold, spacing: Float = 0f): Float {
        val w = textWidth(s, size, tf, spacing)
        return if (w <= maxW) size else (size * maxW / w).coerceAtLeast(size * 0.6f)
    }

    /** 几段字拼成一行（前缀 + 数字 + 后缀）：整行放不下就一起缩号，别让最后一截冲出画布。 */
    private fun fitRowSize(parts: List<String>, size: Float, maxW: Float, tf: Typeface = bold): Float {
        val w = parts.fold(0f) { acc, s -> acc + textWidth(s, size, tf) }
        return if (w <= maxW) size else (size * maxW / w).coerceAtLeast(size * 0.6f)
    }

    /** 页脚一行：左边品牌行、右边日期，两边共享同一段宽度，谁长都不许压到对方。 */
    private fun footerRow(c: Canvas, left: String, right: String, baseline: Float, size: Float, color: Int) {
        textRight(c, right, W - PAD, baseline, size, color, normal)
        val maxLeftW = (W - 2 * PAD) - textWidth(right, size, normal) - 40f
        text(c, left, PAD, baseline, fittedSize(left, size, maxLeftW, normal), color, normal)
    }

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
