package local.kuaixiu.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.kuaixiu.KxScope
import local.kuaixiu.R
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
    // 没配生图模型就默认本地出图：那条路本来也调不通生图，别让人白等一次失败
    val hasImageModel = local.kuaixiu.data.KxStore.config.effectiveImageModel().isNotBlank()
    var localOnly by remember { mutableStateOf(!hasImageModel) }
    var done by remember { mutableStateOf<java.io.File?>(null) }

    fun run(tpl: PosterTemplate, mode: PosterMode) {
        busy = true
        // 本地出图不调模型，别说"要调生图"——提示得跟实际走的那条路一致
        val local = mode == PosterMode.LOCAL_ONLY || (mode == PosterMode.LOCAL_POSTER && tpl == PosterTemplate.D)
        Toast.makeText(ctx, if (local) ctx.getString(R.string.poster_toast_local)
            else ctx.getString(R.string.poster_toast_ai), Toast.LENGTH_SHORT).show()
        // KxScope.app 跑在 Dispatchers.Default：Toast 和 Compose 状态必须切回主线程，
        // 否则 "Can't create handler inside thread that has not called Looper.prepare()" 直接把 App 崩掉
        KxScope.app.launch {
            val r = runCatching { PosterFlow.make(ctx, tpl, mode) }
            withContext(Dispatchers.Main) {
                busy = false
                r.onSuccess { f -> done = f }
                    .onFailure { e -> Toast.makeText(ctx, ctx.getString(R.string.poster_failed, e.message ?: ""),
                        Toast.LENGTH_LONG).show() }
            }
        }
    }

    done?.let { f -> PosterResultDialog(file = f, onDismiss = { done = null; onDismiss() }) }

    val items = listOf(
        Triple(stringResource(R.string.poster_tpl_a), stringResource(R.string.poster_tpl_a_desc), PosterTemplate.A),
        Triple(stringResource(R.string.poster_tpl_c), stringResource(R.string.poster_tpl_c_desc), PosterTemplate.C),
        Triple(stringResource(R.string.poster_tpl_d), stringResource(R.string.poster_tpl_d_desc), PosterTemplate.D),
        Triple(stringResource(R.string.poster_tpl_b), stringResource(R.string.poster_tpl_b_desc), PosterTemplate.B),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        title = { Text(stringResource(if (busy) R.string.poster_busy else R.string.poster_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 调试/没额度时用：不出生图，只看字和数据摆得对不对（整行可点，别只让那个小开关能点）
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !busy) { localOnly = !localOnly }
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = localOnly, onCheckedChange = { localOnly = it }, enabled = !busy)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(stringResource(R.string.poster_local_only_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.poster_local_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items.forEach { (title, desc, tpl) ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !busy) {
                                run(tpl, if (localOnly) PosterMode.LOCAL_ONLY else PosterMode.LOCAL_POSTER)
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(desc, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(R.string.poster_bare_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    if (hasImageModel) stringResource(R.string.poster_bare_desc)
                    else stringResource(R.string.poster_need_image_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !busy && hasImageModel) { run(PosterTemplate.A, PosterMode.BARE_BG) }
                        .padding(vertical = 6.dp),
                )
                Text(
                    stringResource(R.string.poster_ai_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    if (hasImageModel) stringResource(R.string.poster_ai_desc)
                    else stringResource(R.string.poster_need_image_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !busy && hasImageModel) { run(PosterTemplate.A, PosterMode.AI_WITH_TEXT) }
                        .padding(vertical = 6.dp),
                )
            }
        },
    )
}
