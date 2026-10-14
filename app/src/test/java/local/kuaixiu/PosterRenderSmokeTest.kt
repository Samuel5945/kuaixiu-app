package local.kuaixiu

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import local.kuaixiu.data.Checkin
import local.kuaixiu.data.Checkins
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.PosterBuild
import local.kuaixiu.data.PosterComposer
import local.kuaixiu.data.PosterTemplate
import local.kuaixiu.data.Prefs
import local.kuaixiu.engine.Plan
import local.kuaixiu.engine.PlanConfig
import local.kuaixiu.engine.PlanDay
import local.kuaixiu.engine.PlanSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * 版式冒烟：`@GraphicsMode(NATIVE)` 下 Robolectric 用真 Skia 画，`PosterComposer` 出来的
 * 就是海报的真像素，所以四版能不能出图、有没有画崩，在 JVM 里就能钉住，不用连手机。
 *
 * 要看图时加 `-Dkx.poster.out=目录` 跑一次，PNG 会落在那个目录（默认不落盘，免得污染 CI）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterRenderSmokeTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun plan(from: String) = Plan(
        created = from,
        config = PlanConfig("01:00", "00:30", "07:00", from, 28, 30),
        summary = PlanSummary(totalShiftMinutes = 30, daysToTarget = 4, checkpoints = listOf(7, 14)),
        days = (1..28).map { i ->
            PlanDay(i, LocalDate.parse(from).plusDays((i - 1).toLong()).toString(),
                "", 1, "适应期", "", "00:30", "00:00", "16:30", emptyList(), "")
        },
    )

    @Before
    fun setUp() {
        Prefs.savePlan(ctx, plan("2026-10-11"))
        KxStore.load(ctx)
        File(ctx.filesDir, "checkins.json").delete()
        Checkins.add(ctx, Checkin(date = "2026-10-11", status = "late", bedtime = "01:20",
            winddownAt = "00:50", wakeupAt = "07:00", phoneDownAt = "01:05"))
        Checkins.add(ctx, Checkin(date = "2026-10-12", status = "on_time", bedtime = "00:41",
            winddownAt = "00:10", wakeupAt = "07:00", phoneDownAt = "00:33"))
        Checkins.add(ctx, Checkin(date = "2026-10-13", status = "on_time", bedtime = "00:25",
            winddownAt = "00:00", wakeupAt = "07:00", phoneDownAt = "00:38"))
    }

    @After
    fun tearDown() {
        Prefs.savePlan(ctx, null)
        File(ctx.filesDir, "checkins.json").delete()
    }

    @Test
    fun `四版海报都画得出非空白成品`() {
        for (tpl in PosterTemplate.values()) {
            val d = if (tpl == PosterTemplate.D) PosterBuild.week(ctx)!! else PosterBuild.tonight(ctx)!!
            val bmp = PosterComposer.render(tpl, d, null)
            assertEquals(PosterComposer.W, bmp.width)
            assertEquals(PosterComposer.H, bmp.height)
            assertNotBlank(bmp, tpl.name)
            dumpIfRequested(bmp, "smoke_$tpl")
        }
    }

    /** 渐变底 + 中文字 + 横条，按 8px 网格采样不可能只有几十种颜色；画崩成空白时这里会先炸。 */
    private fun assertNotBlank(bmp: Bitmap, tpl: String) {
        val colors = HashSet<Int>()
        for (y in 0 until bmp.height step 8) for (x in 0 until bmp.width step 8) colors.add(bmp.getPixel(x, y))
        assertTrue("$tpl 只采样到 ${colors.size} 种颜色，画面大概是空的", colors.size > 40)
    }

    private fun dumpIfRequested(bmp: Bitmap, name: String) {
        val dir = System.getProperty("kx.poster.out")?.takeIf { it.isNotBlank() } ?: return
        File(dir).mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
