package local.kuaixiu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import local.kuaixiu.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 选择器外壳。不用 M3 的 DatePickerDialog / AlertDialog：前者最小宽度 360dp，比这块屏（约 359dp）
 * 还宽，必然左右顶到屏幕边；后者要把内容裁高，一裁高就得滚，一滚就多出那条滚动条。
 *
 * 卡片宽度必须跟着内容走：按钮行一旦 fillMaxWidth，卡片就被撑满整屏，而列是默认左对齐的，
 * 256dp 的表盘会贴着左边、右边空出一大块。所以按钮用 align(End) 而不撑宽。
 * scale 只给日期用（月历 360dp 放不下），时间盘按原密度渲染，别把 48dp 的选点热区缩小。
 */
@Composable
private fun PickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    scale: Float = 0.9f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                Column {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density * scale, density.fontScale),
                    ) { content() }
                    Row(
                        Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                        TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_ok)) }
                    }
                }
            }
        }
    }
}

/** 选择卡片：label 小字 + 当前值大字 + 图标，整卡可点，值对读屏可见。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(value: String, label: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerCard(modifier, label, value.ifBlank { stringResource(R.string.picker_unset) },
        Icons.Filled.Schedule) { open = true }
    if (open) {
        TimePickerDialog(value, onDismiss = { open = false }, onConfirm = { onChange(it); open = false })
    }
}

/** 时间选择弹窗。表盘固定 256dp，只能放在宽度不受限的外壳里，见 [PickerDialog] 注释。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(value: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val init = runCatching { LocalTime.parse(value) }.getOrElse { LocalTime.of(23, 0) }
    val st = rememberTimePickerState(initialHour = init.hour, initialMinute = init.minute, is24Hour = true)
    PickerDialog(
        onDismiss = onDismiss,
        onConfirm = { onConfirm("%02d:%02d".format(st.hour, st.minute)) },
        scale = 1f,
    ) { TimePicker(state = st, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
}

/** 选择卡片：日期版，值为 ISO yyyy-MM-dd（空 = 今天）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, label: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val display = runCatching { LocalDate.parse(value) }
        .map { "%02d-%02d".format(it.monthValue, it.dayOfMonth) }
        .getOrDefault(value.ifBlank { stringResource(R.string.picker_today) })
    PickerCard(modifier, label, display, Icons.Filled.Event, bigValue = false) { open = true }
    if (open) {
        val init = runCatching { LocalDate.parse(value) }
            .getOrElse { LocalDate.now() }
            .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val st = rememberDatePickerState(initialSelectedDateMillis = init)
        PickerDialog(
            onDismiss = { open = false },
            onConfirm = {
                st.selectedDateMillis?.let {
                    onChange(Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString())
                }
                open = false
            },
        ) { DatePicker(state = st) }
    }
}

@Composable
private fun PickerCard(
    modifier: Modifier,
    label: String,
    value: String,
    icon: ImageVector,
    bigValue: Boolean = true,
    onClick: () -> Unit,
) {
    Card(modifier.clickable(onClickLabel = stringResource(R.string.common_pick, label)) { onClick() }) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = if (bigValue) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
