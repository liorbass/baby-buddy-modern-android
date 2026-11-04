package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.PrimaryFixed
import com.example.ui.theme.SurfaceColor
import com.example.ui.theme.SurfaceContainer
import com.example.ui.theme.SurfaceContainerHigh

// Building blocks shared by the entry dialogs and forms: frame and title, steppers, toggles, chips, notes and button rows, and their limits.

internal const val ML_STEP = 10

internal const val ML_MIN = 10

internal const val ML_MAX = 400

internal val ML_PRESETS = listOf(60, 90, 120, 150, 180)

internal const val TUMMY_MIN = 1

internal const val TUMMY_MAX = 120

internal const val PUMP_ML_MAX = 500

internal const val PUMP_MIN_MINUTES = 1

internal const val PUMP_MAX_MINUTES = 90

/** Display label to stored value. */
internal val BARRIER_OPTIONS = listOf(
    "Barrier" to "Aquaphor / Barrier",
    "Zinc" to "Zinc Oxide Cream",
    "Powder" to "Baby Powder"
)

internal val CONSISTENCY_OPTIONS = listOf(
    "Loose" to "Loose / Liquid",
    "Seedy" to "Seedy / Mustard",
    "Paste" to "Paste / Normal",
    "Hard" to "Hard / Dry"
)

internal val COLOR_OPTIONS = listOf("Mustard", "Tan/Brown", "Greenish", "Dark")

@Composable
internal fun DialogFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = SurfaceColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun DialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = OnSurface
    )
}

@Composable
internal fun ChoiceTile(emoji: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(PrimaryFixed)
            .clickable(onClick = onClick)
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor
        )
    }
}

@Composable
fun SegmentedToggle(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(SurfaceContainer)
            .padding(4.dp)
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (active) PrimaryColor else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (active) OnPrimary else OnSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun MilkToggle(formula: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedToggle(
        options = listOf(stringResource(R.string.dialog_breast_milk), stringResource(R.string.dialog_formula)),
        selectedIndex = if (formula) 1 else 0,
        onSelect = { onChange(it == 1) }
    )
}

@Composable
internal fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(SurfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) PrimaryColor else OnSurfaceVariant.copy(alpha = 0.35f)
        )
    }
}

@Composable
internal fun AmountStepper(ml: Int, onChange: (Int) -> Unit, max: Int = ML_MAX) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepButton("−", enabled = ml > ML_MIN) { onChange((ml - ML_STEP).coerceIn(ML_MIN, max)) }
            Text(
                stringResource(R.string.amount_ml, ml),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            StepButton("+", enabled = ml < max) { onChange((ml + ML_STEP).coerceIn(ML_MIN, max)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ML_PRESETS.forEach { preset ->
                val active = ml == preset
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (active) PrimaryColor else SurfaceContainer)
                        .clickable { onChange(preset) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$preset",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (active) OnPrimary else OnSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun MinuteStepper(
    label: String,
    minutes: Int,
    onChange: (Int) -> Unit,
    min: Int = 0,
    max: Int = Int.MAX_VALUE
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        StepButton("−", enabled = minutes > min) { onChange((minutes - 1).coerceIn(min, max)) }
        Text(
            stringResource(R.string.amount_minutes, minutes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp)
        )
        StepButton("+", enabled = minutes < max) { onChange((minutes + 1).coerceIn(min, max)) }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = OnSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

/** Pill chips; [options] are (label, stored value) pairs. Selection logic lives in the caller. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipGroup(options: List<Pair<String, String>>, isSelected: (String) -> Boolean, onToggle: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (label, value) ->
            val active = isSelected(value)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (active) PrimaryColor else SurfaceContainer)
                    .clickable { onToggle(value) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (active) OnPrimary else OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = PrimaryColor, checkedThumbColor = OnPrimary)
        )
    }
}

@Composable
internal fun NotesField(value: String, onChange: (String) -> Unit, placeholderText: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.notes)) },
        placeholder = if (placeholderText != null) {
            { Text(placeholderText) }
        } else {
            null
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
        shape = RoundedCornerShape(32.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
internal fun CancelSaveRow(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = OnSurfaceVariant) }
        Spacer(Modifier.width(8.dp))
        PrimaryButton(text = stringResource(R.string.save), onClick = onSave)
    }
}

@Composable
internal fun DeleteSaveRow(onDelete: () -> Unit, onSave: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
            Text(
                stringResource(if (confirmDelete) R.string.tap_again_to_delete else R.string.delete),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
        PrimaryButton(text = stringResource(R.string.save), onClick = onSave)
    }
}
