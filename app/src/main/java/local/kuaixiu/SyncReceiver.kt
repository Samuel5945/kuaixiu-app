package local.kuaixiu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import local.kuaixiu.calendar.CalendarWriter
import local.kuaixiu.data.Prefs

/** 接收 PC 端（KuaixiuTray.exe）经 adb 推送的计划：重载 + 重写日历。 */
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "local.kuaixiu.SYNC_PLAN") return
        val pending = goAsync()
        Thread {
            try {
                val plan = Prefs.importPlanFromFile(context)
                if (plan != null) {
                    CalendarWriter.syncPlan(context, plan)
                    android.util.Log.i("Kuaixiu", "SYNC_PLAN ok: " + plan.days.size + " days")
                }
            } catch (e: Exception) {
                android.util.Log.e("Kuaixiu", "SYNC_PLAN failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
