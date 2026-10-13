package local.kuaixiu.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import local.kuaixiu.R
import local.kuaixiu.data.ImageGen
import java.io.File

/**
 * 海报做好之后的落地动作：先看一眼，再决定存/分享。
 * 图在生成时就已进系统相册，这里给的是"现在就发出去"的出口。
 */
@Composable
fun PosterResultDialog(file: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(file.path) { BitmapFactory.decodeFile(file.absolutePath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                sharePoster(ctx, file)
                onDismiss()
            }) { Text(stringResource(R.string.poster_share)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.poster_done)) } },
        title = { Text(stringResource(R.string.poster_result_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.poster_preview_desc),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                    )
                }
                Text(stringResource(R.string.poster_saved_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        ImageGen.saveToGallery(ctx, file)
                        Toast.makeText(ctx, R.string.poster_saved_again, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.poster_save_btn)) }
                    Text(file.name, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

/** 用系统分享面板把海报发出去（走 content://，不能直接给 file://）。 */
fun sharePoster(ctx: Context, file: File) {
    runCatching {
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(ctx, "local.kuaixiu.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.poster_chooser)))
    }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.poster_share_failed, it.message ?: ""),
        Toast.LENGTH_LONG).show() }
}
