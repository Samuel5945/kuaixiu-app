package local.kuaixiu.data

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 海报的四种输出方式。 */
enum class PosterMode(val label: String) {
    LOCAL_ONLY("本地出图"),          // 不调生图模型：用内置底图排版 —— 秒出、不耗额度
    LOCAL_POSTER("本地叠字海报"),   // 底图交给模型，字全部本地画 —— 默认，数字不会错
    BARE_BG("只存无字底图"),        // 只要那张画，拿去别处用 / 当壁纸
    AI_WITH_TEXT("让 AI 直接出图"),  // 连字一起交给模型画（装饰性文案可以，数字可能被画错）
}

/**
 * 出图编排：数据一律来自 [PosterBuild]，只有"画面"交给生图模型。
 *
 * **按版式要尺寸、零裁剪**：A/B 是满幅海报（1248×2752），C 是 1024×1152 的图卡；
 * 要来的图就跟最终显示尺寸一致（都是 32 的倍数，商汤允许），不用裁、主体不会掉。
 *
 * **提示词说的是版式真正的需要**，不是想当然：
 * - A 的字在顶部（连续天数巨字）和底部（三格数据）→ 上下要深色，亮部与主体留中间；
 * - B 整体压暗（圆环 + 三行数据）→ 主体在中上部即可；
 * - C 的字全在卡外 → 画面不用给文字留白，主体居中偏上、构图完整就行。
 */
object PosterFlow {

    /** 内置底图：从历史生图里留下的三张（竖幅给 A/B，方形给 C）。 */
    private const val ASSET_TALL = "poster_bg/tall_sea.jpg"
    private val ASSET_SQUARE = listOf("poster_bg/square_cabin.jpg", "poster_bg/square_monkey.jpg")

    /** 各版式的出图尺寸：跟最终版面 1:1，别让模型画完再被裁。 */
    fun bgSize(tpl: PosterTemplate): String = when (tpl) {
        PosterTemplate.C -> "1024x1152"
        else -> "1248x2752"
    }

    fun bgPrompt(tpl: PosterTemplate, dayIndex: Int): String {
        val scene = ImageGen.sleepPrompt(dayIndex)
        val noText = "画面里绝对不要出现任何文字、字母、数字、签名或水印。"
        return when (tpl) {
            PosterTemplate.C -> "近方形（1024×1152）助眠插画，深蓝到紫的夜空渐变：$scene，" +
                "极简治愈插画。构图：主体（月亮/小屋/光点）居中偏上、完整不出画，地平线压在下三分之一，" +
                "整体安静、干净、有呼吸感。$noText"
            PosterTemplate.B -> "竖幅（1248×2752）助眠插画，深蓝到紫的夜空渐变：$scene，极简治愈插画。" +
                "构图：月亮在画面上方三分之一处、水平略偏右；整体偏暗，亮部集中在中上部；" +
                "下部三分之一留深色安静区域。$noText"
            else -> "竖幅（1248×2752）助眠插画，深蓝到紫的夜空渐变：$scene，极简治愈插画。" +
                "构图（重要）：月亮或发光主体位于画面高度 35%–50% 处、水平居中略偏右，不要贴角、不要出画；" +
                "上部约四分之一与下部约四分之一都保持深色干净（要压白色文字和数据），亮部集中在中间。$noText"
        }
    }

    /** AI 直接出图那条路：把真数字交给模型，让它连字一起画。 */
    fun aiTextPrompt(d: PosterData, dayIndex: Int): String =
        bgPrompt(PosterTemplate.A, dayIndex) +
            "在画面中部用中等粗细的无衬线中文写这几行字，位置居中偏上，颜色用暖白：" +
            "第一行「连续准时」小字；第二行「${d.streak} 天」要大而醒目；" +
            "第三行「${d.phoneDown.ifBlank { d.targetBedtime }} 放下手机」；" +
            "底部一行小字「快休 · 第 ${d.dayIndex}/${d.planDays} 天」。" +
            "只写这些字，不要多写，不要写错数字，不要英文。"

    /** 内置底图：不联网也能出海报（也是生图失败时的兜底）。 */
    fun builtinBg(ctx: Context, tpl: PosterTemplate, dayIndex: Int) = runCatching {
        val path = if (tpl == PosterTemplate.C) ASSET_SQUARE[Math.floorMod(dayIndex, ASSET_SQUARE.size)]
        else ASSET_TALL
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /**
     * 生成并保存。返回落盘的 PNG（也会进系统相册）。
     * LOCAL_ONLY 与"生图失败"都退回内置底图，不把失败甩给用户。
     */
    suspend fun make(ctx: Context, tpl: PosterTemplate, mode: PosterMode): File = withContext(Dispatchers.IO) {
        val data = (if (tpl == PosterTemplate.D) PosterBuild.week(ctx) else PosterBuild.tonight(ctx))
            ?: error("还没有计划，先在设置里生成计划")
        // 周报是纯排版，任何模式都不需要底图
        val needBg = mode == PosterMode.BARE_BG ||
            (mode == PosterMode.LOCAL_POSTER && tpl != PosterTemplate.D)
        val aiBg = if (needBg) runCatching {
            ImageGen.generate(ctx, KxStore.config, bgPrompt(tpl, data.dayIndex), bgSize(tpl))
        }.getOrNull() else null
        when (mode) {
            PosterMode.BARE_BG -> {
                val f = aiBg ?: error("生图失败（额度或网络），可先用「本地出图」")
                ImageGen.saveToGallery(ctx, f)
                f
            }
            PosterMode.AI_WITH_TEXT -> {
                val f = ImageGen.generate(ctx, KxStore.config, aiTextPrompt(data, data.dayIndex),
                    ImageGen.POSTER_SIZE)
                ImageGen.saveToGallery(ctx, f)
                f
            }
            PosterMode.LOCAL_ONLY, PosterMode.LOCAL_POSTER -> {
                val bmp = aiBg?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    ?: builtinBg(ctx, tpl, data.dayIndex)
                val out = PosterComposer.render(tpl, data, bmp)
                val f = PosterComposer.save(ctx, out, "poster_${tpl.name.lowercase()}")
                ImageGen.saveToGallery(ctx, f)
                f
            }
        }
    }
}
