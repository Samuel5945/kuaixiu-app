package local.kuaixiu.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import local.kuaixiu.R
import local.kuaixiu.data.UpdateChecker

/**
 * 「发现新版本」弹窗：更新说明 + 网盘/官网下载入口。
 * 「稍后」只是本次不弹；「忽略此版本」会记住，之后启动的静默检查不再打扰。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    current: String,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upd_title, info.version)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.upd_later)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.upd_ignore)) } },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.upd_current, current) +
                        (info.releaseDate?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotEmpty()) {
                    Column(
                        Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        info.notes.forEach { n ->
                            Text("· $n", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (info.downloads.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        info.downloads.forEach { (key, url) ->
                            OutlinedButton(onClick = { openUrl(ctx, url) }) {
                                Text(when (key) {
                                    "quark" -> stringResource(R.string.dl_quark)
                                    "baidu" -> stringResource(R.string.dl_baidu)
                                    else -> stringResource(R.string.dl_site)
                                })
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.upd_no_browser), Toast.LENGTH_SHORT).show() }
}
