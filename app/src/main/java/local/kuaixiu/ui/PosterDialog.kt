package local.kuaixiu.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.kuaixiu.KxScope
import local.kuaixiu.data.PosterFlow
import local.kuaixiu.data.PosterMode
import local.kuaixiu.data.PosterTemplate

/**
 * 做分享海报。三种输出方式都在这里选：
 * 本地叠字（默认，数字读本地库不会错）、只存无字底图、让 AI 连字一起画。
 */
@Composable
fun PosterDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }

    fun run(tpl: PosterTemplate, mode: PosterMode) {
        busy = true
        Toast.makeText(ctx, "生成中…（要调生图，约半分钟）", Toast.LENGTH_SHORT).show()
        // KxScope.app 跑在 Dispatchers.Default：Toast 和 Compose 状态必须切回主线程，
        // 否则 "Can't create handler inside thread that has not called Looper.prepare()" 直接把 App 崩掉
        KxScope.app.launch {
            val r = runCatching { PosterFlow.make(ctx, tpl, mode) }
            withContext(Dispatchers.Main) {
                busy = false
                r.onSuccess { f -> Toast.makeText(ctx, "已存进相册：${f.name}", Toast.LENGTH_LONG).show() }
                    .onFailure { e -> Toast.makeText(ctx, "生成失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
        onDismiss()
    }

    val items = listOf(
        Triple("打卡纪念（本地叠字）", "底图 + 巨大连续天数，打卡后自动弹的那版", PosterTemplate.A),
        Triple("杂志（本地叠字）", "「昨晚我在 00:38 放下了手机」，发朋友圈那版", PosterTemplate.C),
        Triple("周报（本地画）", "7 根横条，条长 = 每晚评分；不出生图、秒出", PosterTemplate.D),
        Triple("奖章（本地叠字）", "圆环进度 + 距下一枚徽章还差几天", PosterTemplate.B),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("做分享海报") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                items.forEach { (title, desc, tpl) ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !busy) { run(tpl, PosterMode.LOCAL_POSTER) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(desc, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "只要画面不要字",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "存一张不带任何文字、构图留好放字位置的底图",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !busy) { run(PosterTemplate.A, PosterMode.BARE_BG) }
                        .padding(vertical = 6.dp),
                )
                Text(
                    "让 AI 连字一起画",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "数字由 App 塞进提示词交给模型写——方便，但字可能写错，不保证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !busy) { run(PosterTemplate.A, PosterMode.AI_WITH_TEXT) }
                        .padding(vertical = 6.dp),
                )
            }
        },
    )
}
