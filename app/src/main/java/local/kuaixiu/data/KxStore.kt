package local.kuaixiu.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.kuaixiu.calendar.CalendarWriter
import local.kuaixiu.data.Time
import local.kuaixiu.engine.Plan
import local.kuaixiu.engine.PlanEngine
import java.time.LocalDate

/** 全局可观察状态（Compose state 单例，MVP 够用）。 */
object KxStore {

    var config by mutableStateOf(KxConfig())
        private set
    var plan by mutableStateOf<Plan?>(null)
        private set
    var lastSyncCount by mutableStateOf<Int?>(null)
        private set

    fun load(ctx: Context) {
        config = Prefs.loadConfig(ctx)
        plan = Prefs.loadPlan(ctx)
        Time.defaultZone = config.zone()
        Habits.load(ctx)
    }

    fun saveConfig(ctx: Context, newCfg: KxConfig) {
        config = newCfg
        Time.defaultZone = newCfg.zone()
        Prefs.saveConfig(ctx, newCfg)
    }

    /** 按当前配置重算计划并持久化。startDate 空则从今天开始（晚上装 App 今晚就生效）。 */
    fun regenerate(ctx: Context): Plan {
        val cfg = config.copy(startDate = config.startDate.ifBlank { Time.today().toString() })
        val p = PlanEngine.generate(cfg.toPlanConfig())
        config = cfg
        Prefs.saveConfig(ctx, cfg)
        plan = p
        Prefs.savePlan(ctx, p)
        return p
    }

    /** 把当前计划写入系统日历（无计划则先生成）。返回写入事件数。 */
    fun syncCalendar(ctx: Context): Int {
        val p = plan ?: regenerate(ctx)
        val n = CalendarWriter.syncPlan(ctx, p)
        lastSyncCount = n
        return n
    }
}
