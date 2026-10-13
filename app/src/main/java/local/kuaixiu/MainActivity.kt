package local.kuaixiu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import local.kuaixiu.data.KxStore
import local.kuaixiu.data.Prefs
import local.kuaixiu.data.UpdateChecker
import local.kuaixiu.ui.ChatScreen
import local.kuaixiu.ui.HomeScreen
import local.kuaixiu.ui.KxTheme
import local.kuaixiu.ui.PcScreen
import local.kuaixiu.ui.SettingsScreen
import local.kuaixiu.ui.UpdateDialog

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KxStore.load(this)
        local.kuaixiu.data.LanSync.startIfNotRunning(applicationContext)
        setContent {
            KxTheme { App() }
        }
    }
}

private data class Tab(val idx: Int, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(0, "首页", Icons.Filled.Bedtime),
    Tab(1, "教练", Icons.Filled.Forum),
    Tab(2, "电脑", Icons.Filled.Computer),
    Tab(3, "设置", Icons.Filled.Settings),
)

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    // 启动后 8 秒静默查一次官网清单：已最新、已忽略、失败都不打扰
    LaunchedEffect(Unit) {
        delay(8_000)
        val r = withContext(Dispatchers.IO) {
            UpdateChecker.check(ctx.applicationContext, manual = false)
        }
        if (r is UpdateChecker.Result.Available) update = r.info
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.idx,
                        onClick = { tab = t.idx },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(m)
            1 -> ChatScreen(m)
            2 -> PcScreen(m)
            else -> SettingsScreen(m)
        }
    }

    update?.let { info ->
        UpdateDialog(
            info = info,
            current = UpdateChecker.currentVersion(ctx),
            onDismiss = { update = null },
            onSkip = {
                Prefs.skipUpdate(ctx.applicationContext, info.version)
                update = null
            },
        )
    }
}
