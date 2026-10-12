package local.kuaixiu

import local.kuaixiu.data.KxConfig
import local.kuaixiu.llm.LocalCoach
import local.kuaixiu.llm.LocalReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** 离线教练的时间/日期/意图解析都是纯逻辑，直接 JVM 测。 */
class LocalCoachTest {

    private val today = LocalDate.of(2026, 10, 12)
    private val cfg = KxConfig()   // currentBedtime 01:15 / targetBedtime 23:00 / imageModel 空

    private fun action(r: LocalReply) = r.actionJson ?: ""

    // ---------- parseTime ----------

    @Test
    fun `显式时间与补零`() {
        assertEquals("23:00", LocalCoach.parseTime("目标改到23:00"))
        assertEquals("08:05", LocalCoach.parseTime("8:05 叫我"))
    }

    @Test
    fun `修饰词决定上下午`() {
        assertEquals("23:30", LocalCoach.parseTime("晚上11点半"))
        assertEquals("15:00", LocalCoach.parseTime("下午3点"))
        assertEquals("01:00", LocalCoach.parseTime("凌晨1点"))
        assertEquals("07:00", LocalCoach.parseTime("早上7点"))
    }

    @Test
    fun `昨晚的晚不是晚上`() {
        // 「昨晚1点睡的」的「晚」若被当修饰词会算成 13:00——回归锁
        assertEquals("01:00", LocalCoach.parseTime("昨晚1点睡的", defaultPm = false))
    }

    @Test
    fun `无修饰时按 defaultPm 兜底`() {
        assertEquals("23:00", LocalCoach.parseTime("11点", defaultPm = true))
        assertEquals("11:00", LocalCoach.parseTime("11点", defaultPm = false))
        assertEquals("07:00", LocalCoach.parseTime("7点", defaultPm = false))
    }

    @Test
    fun `汉字点数`() {
        assertEquals("10:30", LocalCoach.parseTime("十点半"))
        assertEquals("08:00", LocalCoach.parseTime("八点"))
    }

    @Test
    fun `无时间返回空`() {
        assertNull(LocalCoach.parseTime("明天记得吃药"))
    }

    // ---------- parseDate ----------

    @Test
    fun `相对日期`() {
        assertEquals("2026-10-13", LocalCoach.parseDate("明天", today))
        assertEquals("2026-10-14", LocalCoach.parseDate("后天", today))
        assertEquals("2026-10-15", LocalCoach.parseDate("大后天", today))
        assertEquals("2026-10-12", LocalCoach.parseDate("今晚", today))
    }

    @Test
    fun `周几落在未来七天内`() {
        val d = LocalDate.parse(LocalCoach.parseDate("周三", today)!!)
        assertEquals(DayOfWeek.WEDNESDAY, d.dayOfWeek)
        assertTrue(d >= today && d <= today.plusDays(6))
    }

    @Test
    fun `下周几在七天后`() {
        val d = LocalDate.parse(LocalCoach.parseDate("下周一", today)!!)
        assertEquals(DayOfWeek.MONDAY, d.dayOfWeek)
        assertTrue("$d", d > today.plusDays(6))
    }

    @Test
    fun `绝对日期`() {
        assertEquals("2026-10-20", LocalCoach.parseDate("10月20日", today))
        assertEquals("2026-11-01", LocalCoach.parseDate("2026-11-01", today))
    }

    // ---------- 意图 → 动作 ----------

    @Test
    fun `改计划产出 adjust`() {
        val a = action(LocalCoach.reply("目标改到23:00", cfg, today))
        assertTrue(a, a.contains("\"action\":\"adjust\""))
        assertTrue(a, a.contains("\"targetBedtime\":\"23:00\""))
    }

    @Test
    fun `起床改到七点安置 wake`() {
        val a = action(LocalCoach.reply("起床改到7点", cfg, today))
        assertTrue(a, a.contains("\"wake\":\"07:00\""))
    }

    @Test
    fun `记打卡按时间落 bedtime`() {
        val a = action(LocalCoach.reply("昨晚1点睡的，记一下", cfg, today))
        assertTrue(a, a.contains("\"action\":\"checkin\""))
        assertTrue(a, a.contains("\"bedtime\":\"01:00\""))
    }

    @Test
    fun `风屏用当前时刻`() {
        val a = action(LocalCoach.reply("开始风屏了", cfg, today, LocalTime.of(21, 5)))
        assertTrue(a, a.contains("\"winddown\":\"21:05\""))
    }

    @Test
    fun `加提醒带日期与正文`() {
        val a = action(LocalCoach.reply("明天8点提醒我喝水", cfg, today))
        assertTrue(a, a.contains("\"action\":\"reminder\""))
        assertTrue(a, a.contains("\"date\":\"2026-10-13\""))
        assertTrue(a, a.contains("\"time\":\"08:00\""))
        assertTrue(a, a.contains("\"text\":\"喝水\""))
    }

    @Test
    fun `建习惯解析名字锚点天数`() {
        val a = action(LocalCoach.reply("建个习惯：饭后散步，20:30，21天", cfg, today))
        assertTrue(a, a.contains("\"op\":\"add\""))
        assertTrue(a, a.contains("\"name\":\"饭后散步\""))
        assertTrue(a, a.contains("\"cueTime\":\"20:30\""))
        assertTrue(a, a.contains("\"days\":21"))
    }

    @Test
    fun `取消提醒对不上不猜`() {
        val r = LocalCoach.reply("把喝水提醒删了", cfg, today, knownReminders = emptyList())
        assertNull(r.actionJson)
        assertTrue(r.text, r.text.contains("没有待办提醒"))
    }

    @Test
    fun `取消提醒命中标题`() {
        val r = LocalCoach.reply("把喝水提醒删了", cfg, today, knownReminders = listOf("喝水"))
        assertTrue(action(r), action(r).contains("\"op\":\"cancel\""))
        assertTrue(action(r), action(r).contains("\"text\":\"喝水\""))
    }

    @Test
    fun `光杆做到了带单个习惯也打卡`() {
        // 快捷芯片只说「做到了」，没带习惯名，只有一个习惯时应直接认下来
        val a = action(LocalCoach.reply("做到了", cfg, today, knownHabits = listOf("饭后散步")))
        assertTrue(a, a.contains("\"op\":\"done\""))
        assertTrue(a, a.contains("\"name\":\"饭后散步\""))
    }

    @Test
    fun `多个习惯时做到了要追问`() {
        val r = LocalCoach.reply("做到了", cfg, today, knownHabits = listOf("散步", "冥想"))
        assertNull(r.actionJson)
        assertTrue(r.text, r.text.contains("给哪个习惯"))
    }

    // ---------- FAQ 与兜底 ----------

    @Test
    fun `常见问题给口径不给动作`() {
        val r = LocalCoach.reply("这个计划科学吗", cfg, today)
        assertNull(r.actionJson)
        assertTrue(r.text, r.text.contains("硬证据"))
    }

    @Test
    fun `生图未配模型时引导设置`() {
        val r = LocalCoach.reply("画一张助眠图", cfg, today)
        assertNull(r.actionJson)
        assertTrue(r.text, r.text.contains("生图模型"))
    }

    @Test
    fun `认不出来就老实兜底`() {
        val r = LocalCoach.reply("帮我写一首关于月亮的诗", cfg, today)
        assertNull(r.actionJson)
        assertTrue(r.text, r.text.contains("只认"))
    }
}