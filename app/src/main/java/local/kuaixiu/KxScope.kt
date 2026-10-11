package local.kuaixiu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 应用级协程作用域：生图/聊天等长任务挂这里，切页签不会被打断。 */
object KxScope {
    val app: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
