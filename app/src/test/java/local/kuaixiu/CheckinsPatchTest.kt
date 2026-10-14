package local.kuaixiu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.kuaixiu.data.Checkin
import local.kuaixiu.data.Checkins
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 一晚的记录是分几个时刻写进去的（昨晚风屏、今早入睡、自动探测的起床/放下手机），
 * 所以合并写是硬要求：整条替换会让评分的风屏分项和周报的点阵凭空变空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class CheckinsPatchTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val night = "2026-10-11"

    @Before
    fun clean() { File(ctx.filesDir, "checkins.json").delete() }

    private fun one(): Checkin = Checkins.all(ctx).first { it.date == night }

    @Test
    fun `先记风屏再记入睡_风屏不被清掉`() {
        Checkins.logWinddown(ctx, night, "22:15")
        Checkins.patch(ctx, night) { it.copy(status = "late", bedtime = "23:00") }
        val c = one()
        assertEquals("22:15", c.winddownAt)
        assertEquals("23:00", c.bedtime)
        assertEquals("late", c.status)
    }

    @Test
    fun `合并时能看到这一晚已有的记录`() {
        // 「这次没探到就沿用上次探到的」是调用侧的规则，patch 要保证的是：块里拿得到旧记录，
        // 且没被点名的字段一个字都不丢
        Checkins.patch(ctx, night) { it.copy(phoneDownAt = "22:20", wakeupAt = "07:05") }
        var seen: Checkin? = null
        Checkins.patch(ctx, night) { old -> seen = old; old.copy(status = "on_time", bedtime = "22:50") }
        assertEquals("22:20", seen?.phoneDownAt)
        assertEquals("22:20", one().phoneDownAt)
        assertEquals("07:05", one().wakeupAt)
    }

    @Test
    fun `没有那一晚就新建一条`() {
        Checkins.patch(ctx, night) { it.copy(bedtime = "23:00") }
        assertEquals(1, Checkins.all(ctx).size)
        assertEquals("23:00", one().bedtime)
    }
}
