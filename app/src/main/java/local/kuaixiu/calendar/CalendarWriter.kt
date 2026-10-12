package local.kuaixiu.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import local.kuaixiu.engine.Plan
import local.kuaixiu.engine.PlanEngine
import java.time.ZoneId

/**
 * 把计划写入系统日历（荣耀日历原生显示、原生提醒）。
 * 自建本地账户日历"快休早睡"（account_type = LOCAL），同步策略：删旧插新（幂等）。
 * 无后台服务——提醒完全由系统日历承担，不受任何厂商杀后台影响。
 */
object CalendarWriter {

    const val ACCOUNT = "kuaixiu.local"
    const val CALENDAR_NAME = "快休早睡"
    /** 一次性提醒单独一个账户+日历：syncPlan 是删旧插新，混在一起会被连带删掉。 */
    const val REMIND_ACCOUNT = "kuaixiu.remind"
    const val REMIND_CALENDAR_NAME = "快休提醒"
    private val TZ = ZoneId.of("Asia/Shanghai")

    private fun syncAdapterUri(base: android.net.Uri, account: String = ACCOUNT) = base.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** 建/复用本地日历，返回 calendar_id。每个账户下只有一个日历，所以按账户查即可。 */
    fun ensureCalendar(context: Context, account: String = ACCOUNT,
                       displayName: String = CALENDAR_NAME): Long {
        val cr = context.contentResolver
        cr.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
            arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val cv = ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.CALENDAR_COLOR, 0xFFB457.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, 700)  // CALENDAR_ACCESS_OWNER
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.OWNER_ACCOUNT, account)
            put(Calendars.CALENDAR_TIME_ZONE, TZ.id)
        }
        val uri = cr.insert(syncAdapterUri(Calendars.CONTENT_URI, account), cv)
            ?: throw IllegalStateException("日历创建失败（缺少 WRITE_CALENDAR 权限？）")
        return uri.lastPathSegment!!.toLong()
    }

    /** 教练帮你加的一次性提醒（喝水/吃药/接人之类），落进「快休提醒」日历并带系统闹钟。 */
    fun addReminder(context: Context, dateIso: String, hhmm: String, text: String,
                    leadMin: Int = 0): Long {
        val cr = context.contentResolver
        val calId = ensureCalendar(context, REMIND_ACCOUNT, REMIND_CALENDAR_NAME)
        val start = java.time.LocalDateTime.parse("${dateIso}T$hhmm:00")
        require(!start.isBefore(java.time.LocalDateTime.now())) { "提醒时间已经在过去了" }
        val cv = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, text)
            put(Events.DTSTART, start.atZone(TZ).toInstant().toEpochMilli())
            put(Events.DTEND, start.plusMinutes(10).atZone(TZ).toInstant().toEpochMilli())
            put(Events.EVENT_TIMEZONE, TZ.id)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)   // 不占忙闲
        }
        val uri = cr.insert(syncAdapterUri(Events.CONTENT_URI, REMIND_ACCOUNT), cv)
            ?: throw IllegalStateException("提醒写入失败")
        val id = uri.lastPathSegment!!.toLong()
        cr.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id)
            put(Reminders.MINUTES, leadMin.coerceAtLeast(0))
            put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })
        return id
    }

    /** 删旧插新，返回写入事件数。 */
    fun syncPlan(context: Context, plan: Plan): Int {
        val cr = context.contentResolver
        val calId = ensureCalendar(context)
        cr.delete(
            syncAdapterUri(Events.CONTENT_URI),
            "${Events.CALENDAR_ID}=?",
            arrayOf(calId.toString()),
        )
        var n = 0
        for (e in PlanEngine.eventsFor(plan)) {
            val startMs = e.start.atZone(TZ).toInstant().toEpochMilli()
            val endMs = e.start.plusMinutes(e.durationMin.toLong()).atZone(TZ).toInstant().toEpochMilli()
            val cv = ContentValues().apply {
                put(Events.CALENDAR_ID, calId)
                put(Events.TITLE, e.title)
                put(Events.DESCRIPTION, e.description)
                put(Events.DTSTART, startMs)
                put(Events.DTEND, endMs)
                put(Events.EVENT_TIMEZONE, TZ.id)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                put(Events.GUESTS_CAN_MODIFY, 0)
            }
            val eventUri = cr.insert(syncAdapterUri(Events.CONTENT_URI), cv)
                ?: continue
            val eventId = eventUri.lastPathSegment!!.toLong()
            if (e.alarmMin > 0) {
                val rv = ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, e.alarmMin)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                }
                cr.insert(Reminders.CONTENT_URI, rv)
            }
            n++
        }
        return n
    }

    /** 待触发的提醒（id / 标题 / 起始毫秒）——厂商日历不给这类事件删除入口，所以自己列出来管。 */
    fun upcomingReminders(context: Context, limit: Int = 12): List<Triple<Long, String, Long>> {
        val calId = ensureCalendar(context, REMIND_ACCOUNT, REMIND_CALENDAR_NAME)
        val out = ArrayList<Triple<Long, String, Long>>()
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.TITLE, Events.DTSTART),
            "${Events.CALENDAR_ID}=? AND ${Events.DTSTART}>=? AND ${Events.DELETED}=0",
            arrayOf(calId.toString(), System.currentTimeMillis().toString()),
            "${Events.DTSTART} ASC LIMIT $limit",
        )?.use { c ->
            while (c.moveToNext()) {
                val t = c.getString(1) ?: "(无标题)"
                out += Triple(c.getLong(0), t, if (c.isNull(2)) 0L else c.getLong(2))
            }
        }
        return out
    }

    /** 删一条提醒；按标题删时返回删除条数。 */
    fun deleteReminder(context: Context, id: Long): Boolean =
        context.contentResolver.delete(
            syncAdapterUri(Events.CONTENT_URI, REMIND_ACCOUNT),
            "${Events._ID}=?", arrayOf(id.toString()),
        ) > 0

    fun deleteRemindersByText(context: Context, text: String): Int =
        context.contentResolver.delete(
            syncAdapterUri(Events.CONTENT_URI, REMIND_ACCOUNT),
            "${Events.TITLE}=?", arrayOf(text),
        )

    /** 清空该日历（卸载计划用）。 */
    fun clearPlan(context: Context): Int {
        val cr = context.contentResolver
        val calId = ensureCalendar(context)
        return cr.delete(
            syncAdapterUri(Events.CONTENT_URI),
            "${Events.CALENDAR_ID}=?",
            arrayOf(calId.toString()),
        )
    }
}
