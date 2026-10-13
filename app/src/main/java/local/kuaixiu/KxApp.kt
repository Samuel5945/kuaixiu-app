package local.kuaixiu

import android.app.Application
import local.kuaixiu.data.KxText

/** 只做一件事：给拿不到 Compose/Context 的逻辑层（离线教练、评分、日历写入）注入文案入口。 */
class KxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KxText.init(this)
    }
}
