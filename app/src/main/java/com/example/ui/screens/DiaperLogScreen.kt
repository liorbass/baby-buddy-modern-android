package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BabyProfile
import com.example.data.model.DIAPER_AMOUNTS
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.model.TimeFormat
import com.example.data.model.TrackedItem
import com.example.data.model.diaperValueLabel
import com.example.ui.components.TimeField
import com.example.ui.theme.ItemColors
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OutlineColor
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.PrimaryContainer
import com.example.ui.theme.PrimaryFixed
import com.example.ui.theme.SecondaryColor
import com.example.ui.theme.SurfaceContainer
import com.example.ui.theme.SurfaceContainerLow
import com.example.ui.theme.SurfaceContainerLowest
import kotlinx.coroutines.delay

/** What the diaper form saves; [timeMillis] null = now. */
typealias DiaperSave = (
    type: DiaperType,
    amount: String,
    consistency: String,
    color: String,
    rashPresent: Boolean,
    skinBarriers: List<String>,
    timeMillis: Long?,
    notes: String,
    startNursing: Boolean
) -> Unit

@Composable
fun DiaperLogScreen(
    babyProfile: BabyProfile,
    recentDiapers: List<DiaperEvent>,
    onBack: () -> Unit,
    onSaveDiaper: DiaperSave,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(SurfaceContainerLow)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    text = stringResource(R.string.diaper_change),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item { DiaperForm(onSave = onSaveDiaper) }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The full diaper entry, shared by the Diaper quick action and the Log screen: type, time, amount, stool
 * consistency and color, rash, cream and notes, saved alone or followed by a feed.
 */
@Composable
fun DiaperForm(onSave: DiaperSave, modifier: Modifier = Modifier) {
    var selectedType by remember { mutableStateOf(DiaperType.WET) }
    var selectedAmount by remember { mutableStateOf("Medium") }
    var selectedConsistency by remember { mutableStateOf("") } // optional; "" = not recorded
    var selectedColor by remember { mutableStateOf("Mustard") }
    var rashPresent by remember { mutableStateOf(false) }
    var selectedSkinBarriers by remember { mutableStateOf(setOf<String>()) }
    var pickedTime by remember { mutableStateOf<Long?>(null) } // null = now
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }
    var notesText by remember { mutableStateOf("") }
    var showMore by remember { mutableStateOf(false) }

    val isDirty = selectedType == DiaperType.DIRTY || selectedType == DiaperType.BOTH

    val save: (Boolean) -> Unit = { startNursing ->
        onSave(
            selectedType,
            selectedAmount,
            selectedConsistency,
            selectedColor,
            rashPresent,
            selectedSkinBarriers.toList(),
            pickedTime,
            notesText,
            startNursing
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // Type selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DiaperTypeButton(
                label = stringResource(R.string.diaper_wet),
                icon = Icons.Default.WaterDrop,
                isSelected = selectedType == DiaperType.WET,
                tint = ItemColors.tint(TrackedItem.DIAPERS),
                bgSelected = ItemColors.tint(TrackedItem.DIAPERS),
                modifier = Modifier.weight(1f),
                onClick = { selectedType = DiaperType.WET }
            )
            DiaperTypeButton(
                label = stringResource(R.string.diaper_dirty),
                icon = Icons.Default.Eco,
                isSelected = selectedType == DiaperType.DIRTY,
                tint = ItemColors.tint(TrackedItem.DIAPERS),
                bgSelected = ItemColors.tint(TrackedItem.DIAPERS),
                modifier = Modifier.weight(1f),
                onClick = { selectedType = DiaperType.DIRTY }
            )
            DiaperTypeButton(
                label = stringResource(R.string.diaper_both),
                icon = Icons.Default.CleaningServices,
                isSelected = selectedType == DiaperType.BOTH,
                tint = ItemColors.tint(TrackedItem.DIAPERS),
                bgSelected = ItemColors.tint(TrackedItem.DIAPERS),
                modifier = Modifier.weight(1f),
                onClick = { selectedType = DiaperType.BOTH }
            )
            DiaperTypeButton(
                label = stringResource(R.string.diaper_dry),
                icon = Icons.Default.CheckCircle,
                isSelected = selectedType == DiaperType.DRY,
                tint = ItemColors.tint(TrackedItem.DIAPERS),
                bgSelected = ItemColors.tint(TrackedItem.DIAPERS),
                modifier = Modifier.weight(1f),
                onClick = { selectedType = DiaperType.DRY }
            )
        }

        // Time of the change: now unless changed
        TimeField(
            label = stringResource(R.string.time),
            millis = pickedTime ?: now,
            onChange = { pickedTime = it },
            valueText = if (pickedTime == null) stringResource(R.string.now_at, TimeFormat.clock(now)) else null
        )

        // Amount
        if (selectedType != DiaperType.DRY) {
            DiaperSection(title = stringResource(R.string.diaper_amount)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DIAPER_AMOUNTS.forEach { amt ->
                        DiaperOptionChip(
                            label = diaperValueLabel(amt),
                            isSelected = selectedAmount == amt,
                            onClick = { selectedAmount = amt },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Stool details (dirty / both only)
        AnimatedVisibility(visible = isDirty) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                DiaperSection(title = stringResource(R.string.diaper_consistency_optional)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Loose / Liquid" to "Loose",
                            "Seedy / Mustard" to "Seedy",
                            "Paste / Normal" to "Paste",
                            "Hard / Dry" to "Hard"
                        ).forEach { (value, label) ->
                            DiaperOptionChip(
                                label = diaperValueLabel(label),
                                isSelected = selectedConsistency == value,
                                onClick = { selectedConsistency = if (selectedConsistency == value) "" else value },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                DiaperSection(title = stringResource(R.string.diaper_color)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Mustard" to PrimaryContainer,
                            "Tan/Brown" to OutlineColor,
                            "Greenish" to SecondaryColor,
                            "Dark" to MaterialTheme.colorScheme.inverseSurface
                        ).forEach { (label, dot) ->
                            ColorSwatch(
                                label = diaperValueLabel(label),
                                colorDot = dot,
                                isSelected = selectedColor == label,
                                onClick = { selectedColor = label },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // More details (rash, creams, notes)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showMore = !showMore }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.more_details), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(
                    imageVector = if (showMore) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (showMore) R.string.hide_details else R.string.show_details),
                    tint = OutlineColor
                )
            }

            AnimatedVisibility(visible = showMore) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.diaper_rash), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Switch(
                            checked = rashPresent,
                            onCheckedChange = { rashPresent = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = OnPrimary, checkedTrackColor = PrimaryColor)
                        )
                    }

                    DiaperSection(title = stringResource(R.string.diaper_cream)) {
                        val barriers = listOf("Aquaphor / Barrier" to "Barrier", "Zinc Oxide Cream" to "Zinc", "Baby Powder" to "Powder")
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            barriers.forEach { (value, label) ->
                                val isSel = value in selectedSkinBarriers
                                DiaperOptionChip(
                                    label = diaperValueLabel(label),
                                    isSelected = isSel,
                                    onClick = {
                                        selectedSkinBarriers = if (isSel) selectedSkinBarriers - value else selectedSkinBarriers + value
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        placeholder = { Text(stringResource(R.string.notes), style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = SurfaceContainerLow,
                            focusedContainerColor = SurfaceContainerLowest,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = PrimaryColor
                        )
                    )
                }
            }
        }

        // Actions
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { save(false) },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("save_diaper_btn")
            ) {
                Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = { save(true) }) {
                Text(stringResource(R.string.diaper_save_start_feed), color = SecondaryColor, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DiaperSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = OutlineColor)
        content()
    }
}

@Composable
private fun DiaperOptionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) PrimaryColor else SurfaceContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) OnPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DiaperTypeButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    tint: Color,
    bgSelected: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) bgSelected else SurfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = if (isSelected) OnPrimary else tint, modifier = Modifier.size(24.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) OnPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    label: String,
    colorDot: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) PrimaryFixed else SurfaceContainer,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(colorDot))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) PrimaryColor else OutlineColor
            )
        }
    }
}
