package local.kuaixiu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 选择卡片：label 小字 + 当前值大字 + 图标，整卡可点，值对读屏可见。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(value: String, label: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerCard(modifier, label, value.ifBlank { "未设置" }, Icons.Filled.Schedule) { open = true }
    if (open) {
        val init = runCatching { LocalTime.parse(value) }.getOrElse { LocalTime.of(23, 0) }
        val st = rememberTimePickerState(initialHour = init.hour, initialMinute = init.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(st.hour, st.minute)); open = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                TimePicker(state = st)
            } },
        )
    }
}

/** 选择卡片：日期版，值为 ISO yyyy-MM-dd（空 = 今天）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, label: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val display = runCatching { LocalDate.parse(value) }
        .map { "%02d-%02d".format(it.monthValue, it.dayOfMonth) }
        .getOrDefault(value.ifBlank { "今天" })
    PickerCard(modifier, label, display, Icons.Filled.Event, bigValue = false) { open = true }
    if (open) {
        val init = runCatching { LocalDate.parse(value) }
            .getOrElse { LocalDate.now() }
            .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val st = rememberDatePickerState(initialSelectedDateMillis = init)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate().toString())
                    }
                    open = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                DatePicker(state = st)
            } },
        )
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
    Card(modifier.clickable(onClickLabel = "选择$label") { onClick() }) {
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
