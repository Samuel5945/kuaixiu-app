package local.kuaixiu.data

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 海报的三种输出方式（他要的三样都要）。 */
enum class PosterMode(val label: String) {
    LOCAL_POSTER("本地叠字海报"),   // 底图交给模型，字全部本地画 —— 默认，数字不会错
    BARE_BG("只存无字底图"),        // 只要那张画，拿去别处用 / 当壁纸
    AI_WITH_TEXT("让 AI 直接出图"),  // 连字一起交给模型画（装饰性文案可以，数字可能被画错）
}

/**
 * 出图编排：数据一律来自 [PosterBuild]，只有"画面"交给生图模型。
 * 底图 prompt 写死构图约束——月亮右上、地平线下 1/4、中上留白，
 * 不约束的话模型会把中间塞满，叠字就没地方放（这是四版稿子试出来的）。
 */
object PosterFlow {

    fun bgPrompt(dayIndex: Int): String =
        "竖幅 9:16 助眠插画：${ImageGen.sleepPrompt(dayIndex)}。" +
            "构图要求：月亮位于画面右上，地平线在下方四分之一处，中上部大面积留白（留白处要叠文字），" +
            "柔和的深蓝到紫的渐变，安静、干净、有呼吸感。" +
            "画面里绝对不要出现任何文字、字母、数字、签名或水印。"

    /** AI 直接出图那条路：把真数字交给模型，让它连字一起画。 */
    fun aiTextPrompt(d: PosterData, dayIndex: Int): String =
        bgPrompt(dayIndex) +
            "在留白处用中等粗细的无衬线中文写这几行字，位置居中偏上，颜色用暖白：" +
            "第一行「连续准时」小字；第二行「${d.streak} 天」要大而醒目；" +
            "第三行「${d.phoneDown.ifBlank { d.targetBedtime }} 放下手机」；" +
            "底部一行小字「快休 · 第 ${d.dayIndex}/${d.planDays} 天」。" +
            "只写这些字，不要多写，不要写错数字，不要英文。"

    /**
     * 生成并保存。返回落盘的 PNG（也会进系统相册）。
     * 需要联网调生图时请在 IO 线程调用；本地叠字那条路只有 D 版完全不联网。
     */
    suspend fun make(ctx: Context, tpl: PosterTemplate, mode: PosterMode): File = withContext(Dispatchers.IO) {
        val data = (if (tpl == PosterTemplate.D) PosterBuild.week(ctx) else PosterBuild.tonight(ctx))
            ?: error("还没有计划，先在设置里生成计划")
        val bgFile = if (tpl == PosterTemplate.D && mode == PosterMode.LOCAL_POSTER) null
            else ImageGen.generate(ctx, KxStore.config, bgPrompt(data.dayIndex))
        when (mode) {
            PosterMode.BARE_BG -> {
                ImageGen.saveToGallery(ctx, bgFile!!)
                bgFile
            }
            PosterMode.AI_WITH_TEXT -> {
                val f = ImageGen.generate(ctx, KxStore.config, aiTextPrompt(data, data.dayIndex))
                ImageGen.saveToGallery(ctx, f)
                f
            }
            PosterMode.LOCAL_POSTER -> {
                // 周报 D 是纯排版，本来就没有底图；底图解码失败也照样出图（composer 自己有兜底底色）
                val bmp = bgFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
                val out = PosterComposer.render(tpl, data, bmp)
                val f = PosterComposer.save(ctx, out, "poster_${tpl.name.lowercase()}")
                ImageGen.saveToGallery(ctx, f)
                f
            }
        }
    }
}
