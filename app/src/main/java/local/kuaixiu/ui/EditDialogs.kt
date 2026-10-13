package local.kuaixiu.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import local.kuaixiu.data.HabitCheck
import local.kuaixiu.data.Habits
import local.kuaixiu.llm.ActionRunner
import local.kuaixiu.data.Time
import java.time.LocalDate

/**
 * 手动入口：不经过教练也能加提醒 / 建习惯。
 * 写操作走 [ActionRunner]（与 AI 动作卡同一套语义），复用 PickerFields 的 TimeField/DateField。
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(Time.today().toString()) }
    var time by remember { mutableStateOf("08:00") }
    var text by remember { mutableStateOf("") }
    var lead by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加一条提醒") },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    ActionRunner.applyReminder(ctx, date, time, text.trim(), lead)
                        .onSuccess {
                            Toast.makeText(ctx, "已加进日历「快休提醒」✅", Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                        .onFailure {
                            Toast.makeText(ctx, "提醒写入失败：${it.message}", Toast.LENGTH_LONG).show()
                        }
                },
            ) { Text("加进日历") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("提醒什么事") }, placeholder = { Text("喝水 / 吃药 / 接人") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(date, "日期", Modifier.weight(1f)) { date = it }
                    TimeField(time, "时间", Modifier.weight(1f)) { time = it }
                }
                Text("提前", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10, 30).forEach { n ->
                        FilterChip(selected = lead == n, onClick = { lead = n },
                            label = { Text(if (n == 0) "到点" else "${n}分") })
                    }
                }
            }
        },
    )
}

@Composable
fun HabitDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var cue by remember { mutableStateOf("20:30") }
    var days by remember { mutableStateOf(21) }
    var start by remember { mutableStateOf(Time.today().toString()) }
    var ackCost by remember { mutableStateOf(false) }

    // 只查冲突、不写库：输入变化时实时提示，点确认才落盘
    val check = remember(name, cue, days, start, ackCost) {
        if (name.isBlank()) null
        else Habits.check(Habits.items, name.trim(), cue, days, start, ackCost = ackCost)
    }
    val blocked = check is HabitCheck.Invalid || check is HabitCheck.AnchorConflict

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开一个新习惯") },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !blocked,
                onClick = {
                    when (val r = ActionRunner.applyHabitAdd(ctx, name.trim(), cue, days, start, ackCost)) {
                        is HabitCheck.Ok -> {
                            Toast.makeText(ctx, "已写进日历「快休习惯」，连响 ${r.habit.days} 天 ✅",
                                Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                        is HabitCheck.QuotaFull -> {
                            if (!ackCost) {
                                ackCost = true
                                Toast.makeText(ctx, "同时开第 3 个，成功率明显下降——再点一次才真的加上",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(ctx, "还是被拦下了：先暂停一个再开", Toast.LENGTH_LONG).show()
                            }
                        }
                        is HabitCheck.AnchorConflict -> Toast.makeText(ctx,
                            "锚点和「${r.otherName}」只差 ${r.gapMin} 分钟，先错开时间", Toast.LENGTH_LONG).show()
                        is HabitCheck.Invalid -> Toast.makeText(ctx, r.reason, Toast.LENGTH_LONG).show()
                    }
                },
            ) {
                Text(
                    when {
                        check is HabitCheck.QuotaFull && !ackCost -> "我了解代价，照样加"
                        else -> "开这个习惯"
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("习惯名（情境+动作）") }, placeholder = { Text("饭后散步10分钟") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(cue, "锚点时间", Modifier.weight(1f)) { cue = it }
                    DateField(start, "开始日期", Modifier.weight(1f)) { start = it }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("连响天数", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        listOf(21, 66).forEach { n ->
                            FilterChip(selected = days == n, onClick = { days = n },
                                label = { Text("${n}天") })
                        }
                        OutlinedTextField(
                            value = days.toString(), onValueChange = { s ->
                                s.toIntOrNull()?.let { if (it in 1..365) days = it }
                            },
                            label = { Text("自定") }, modifier = Modifier.weight(1f), singleLine = true,
                        )
                    }
                }
                val hint = when (check) {
                    is HabitCheck.AnchorConflict -> "锚点和「${check.otherName}」只差 ${check.gapMin} 分钟——" +
                        "挨太近的两个执行意图会互相抵消，先错开时间。" to MaterialTheme.colorScheme.error
                    is HabitCheck.QuotaFull -> "「${check.activeNames.joinToString("、")}」还在跑。" +
                        "同时开第 3 个，研究里成功率明显下降，建议先跑稳这两个。" to MaterialTheme.colorScheme.tertiary
                    is HabitCheck.Invalid -> check.reason to MaterialTheme.colorScheme.error
                    else -> null
                }
                hint?.let { (msg, color) ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        },
    )
}