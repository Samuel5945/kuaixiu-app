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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import local.kuaixiu.R
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
        title = { Text(stringResource(R.string.rem_title)) },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    ActionRunner.applyReminder(ctx, date, time, text.trim(), lead)
                        .onSuccess {
                            Toast.makeText(ctx, ctx.getString(R.string.rem_added), Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                        .onFailure {
                            Toast.makeText(ctx, ctx.getString(R.string.rem_failed, it.message ?: ""),
                                Toast.LENGTH_LONG).show()
                        }
                },
            ) { Text(stringResource(R.string.rem_add_btn)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(stringResource(R.string.rem_what_label)) },
                    placeholder = { Text(stringResource(R.string.rem_what_hint)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(date, stringResource(R.string.common_date), Modifier.weight(1f)) { date = it }
                    TimeField(time, stringResource(R.string.common_time), Modifier.weight(1f)) { time = it }
                }
                Text(stringResource(R.string.rem_lead_label), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10, 30).forEach { n ->
                        FilterChip(selected = lead == n, onClick = { lead = n },
                            label = { Text(stringResource(
                                if (n == 0) R.string.rem_lead_at_time else R.string.rem_lead_n, n)) })
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
        title = { Text(stringResource(R.string.habit_title)) },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !blocked,
                onClick = {
                    when (val r = ActionRunner.applyHabitAdd(ctx, name.trim(), cue, days, start, ackCost)) {
                        is HabitCheck.Ok -> {
                            Toast.makeText(ctx, ctx.getString(R.string.habit_added, r.habit.days),
                                Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                        is HabitCheck.QuotaFull -> {
                            if (!ackCost) {
                                ackCost = true
                                Toast.makeText(ctx, ctx.getString(R.string.habit_quota_again),
                                    Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(ctx, ctx.getString(R.string.habit_quota_blocked),
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                        is HabitCheck.AnchorConflict -> Toast.makeText(ctx,
                            ctx.getString(R.string.habit_conflict_toast, r.otherName, r.gapMin),
                            Toast.LENGTH_LONG).show()
                        is HabitCheck.Invalid -> Toast.makeText(ctx, r.reason, Toast.LENGTH_LONG).show()
                    }
                },
            ) {
                Text(stringResource(
                    if (check is HabitCheck.QuotaFull && !ackCost) R.string.habit_ack_btn
                    else R.string.habit_add_btn
                ))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.habit_name_label)) },
                    placeholder = { Text(stringResource(R.string.habit_name_hint)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(cue, stringResource(R.string.habit_cue_label), Modifier.weight(1f)) { cue = it }
                    DateField(start, stringResource(R.string.set_start_date), Modifier.weight(1f)) { start = it }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.habit_days_label), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        listOf(21, 66).forEach { n ->
                            FilterChip(selected = days == n, onClick = { days = n },
                                label = { Text(stringResource(R.string.habit_days_n, n)) })
                        }
                        OutlinedTextField(
                            value = days.toString(), onValueChange = { s ->
                                s.toIntOrNull()?.let { if (it in 1..365) days = it }
                            },
                            label = { Text(stringResource(R.string.habit_days_custom)) },
                            modifier = Modifier.weight(1f), singleLine = true,
                        )
                    }
                }
                val hint = when (check) {
                    is HabitCheck.AnchorConflict -> stringResource(
                        R.string.habit_conflict_hint, check.otherName, check.gapMin
                    ) to MaterialTheme.colorScheme.error
                    is HabitCheck.QuotaFull -> stringResource(
                        R.string.habit_quota_hint,
                        check.activeNames.joinToString(stringResource(R.string.common_or))
                    ) to MaterialTheme.colorScheme.tertiary
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