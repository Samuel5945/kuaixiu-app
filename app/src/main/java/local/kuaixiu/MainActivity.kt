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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import local.kuaixiu.data.KxStore
import local.kuaixiu.ui.ChatScreen
import local.kuaixiu.ui.HomeScreen
import local.kuaixiu.ui.KxTheme
import local.kuaixiu.ui.PcScreen
import local.kuaixiu.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KxStore.load(this)
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
}
