package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.AppLanguage
import com.example.BuildConfig
import com.example.R
import com.example.data.ml.RhythmTarget
import com.example.data.model.AppText
import com.example.data.model.BabySex
import com.example.data.model.DayHours
import com.example.data.model.ReminderSettings
import com.example.data.model.ServerConnectionConfig
import com.example.data.model.StartSide
import com.example.data.model.ThemeMode
import com.example.data.model.TrackedItem
import com.example.data.model.formatMinutes
import com.example.ui.CertificatePrompt
import com.example.ui.components.DurationField
import com.example.ui.components.SegmentedToggle
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OutlineColor
import com.example.ui.theme.PrimaryColor
import com.example.ui.theme.SecondaryColor
import com.example.ui.theme.SurfaceContainerLow
import com.example.update.AppUpdater
import com.example.update.UpdateState
import kotlinx.coroutines.delay

/**
 * A settings category. In the list it is a row (icon, title, summary, chevron); once opened it fills
 * the page with its options, and Back returns to the list. [hidden] while another category is open.
 */
@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    icon: ImageVector,
    expanded: Boolean,
    hidden: Boolean,
    onOpen: () -> Unit,
    content: @Composable () -> Unit
) {
    if (hidden) return
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        return
    }
    Surface(shape = RoundedCornerShape(20.dp), color = SurfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.settings_open_section, title), tint = OutlineColor)
        }
    }
}

@Composable
private fun SwitchSetting(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
        )
    }
}

/** Settings → Baby → Day and night ([DayHours]). */
@Composable
private fun DayNightSetting(onDayHoursChange: (Int, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsGroupTitle(stringResource(R.string.settings_day_night), stringResource(R.string.settings_day_night_desc))
        val day = DayHours.dayStartHour
        val night = DayHours.nightStartHour

        // Day and night can't start at the same hour: step past the other one
        fun step(hour: Int, delta: Int, other: Int) = (hour + delta).mod(24).let { if (it == other) (it + delta).mod(24) else it }
        HourStepper(stringResource(R.string.settings_day_starts), day) { delta -> onDayHoursChange(step(day, delta, night), night) }
        HourStepper(stringResource(R.string.settings_night_starts), night) { delta -> onDayHoursChange(day, step(night, delta, day)) }
    }
}

/** Settings → Tracking → the hour Trends' days start at ([DayHours.trendsDayStartHour]). */
@Composable
private fun TrendsDayStartSetting(onTrendsDayStartChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val trendsStart = DayHours.trendsDayStartHour
        HourStepper(stringResource(R.string.settings_trends_day_start), trendsStart) { delta -> onTrendsDayStartChange((trendsStart + delta).mod(24)) }
        Text(
            stringResource(R.string.settings_trends_day_start_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A heading inside a category, the same in every one, with an optional line under it. */
@Composable
private fun SettingsGroupTitle(title: String, description: String? = null) {
    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = PrimaryColor)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A reminder: its switch and, while on, how long after to remind. */
@Composable
private fun ReminderSetting(
    title: String,
    minutes: Int,
    defaultMinutes: Int,
    onText: String, // what "on" means, with the time
    offText: String,
    modelTimed: Boolean, // a model saved in Trends → Models times it
    dialogNote: String,
    onChange: (Int) -> Unit
) {
    SwitchSetting(
        title = title,
        description = when {
            minutes <= 0 -> offText
            modelTimed -> stringResource(R.string.settings_model_timed)
            else -> onText
        },
        checked = minutes > 0,
        onChange = { on -> onChange(if (on) defaultMinutes else 0) }
    )
    if (minutes > 0) {
        DurationField(label = stringResource(R.string.settings_remind_after), minutes = minutes, onChange = onChange, dialogNote = dialogNote)
    }
}

/** A label and an hour with − / + buttons (one hour per tap). */
@Composable
private fun HourStepper(label: String, hour: Int, onStep: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.hour_earlier, label))
        }
        Text(
            DayHours.label(hour),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 64.dp),
            textAlign = TextAlign.Center
        )
        IconButton(onClick = { onStep(1) }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.hour_later, label))
        }
    }
}

/** Settings → Appearance → Language: the app's language, the phone's by default ([AppLanguage]). */
@Composable
private fun LanguageSetting() {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val phone = stringResource(R.string.settings_language_phone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .testTag("language_setting"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (current.isEmpty()) phone else AppLanguage.displayName(current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    (listOf("") + AppLanguage.TAGS).forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = tag == current, onClick = {
                                    picking = false
                                    if (tag != current) context.findActivity()?.let { AppLanguage.set(it, tag) }
                                })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = tag == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = PrimaryColor))
                            Text(if (tag.isEmpty()) phone else AppLanguage.displayName(tag), modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val BABY_BUDDY_REPO = "https://github.com/babybuddy/babybuddy"
private const val APP_REPO = "https://github.com/liorbass/baby-buddy-modern-android"

/** What Settings shows. */
data class SettingsState(
    val config: ServerConnectionConfig,
    val caregiverName: String = "",
    val babySex: BabySex? = null,
    val feedNotificationEnabled: Boolean = true,
    val nextUpNotification: Boolean = false,
    val startSide: StartSide = StartSide.OPPOSITE,
    val use24h: Boolean = true,
    val modelsTab: Boolean = false,
    val modelTargets: Set<RhythmTarget> = emptySet(), // rhythms whose reminders a saved model times (Trends → Models)
    val themeMode: ThemeMode = ThemeMode.BY_TIME,
    val reminders: ReminderSettings = ReminderSettings(),
    val hiddenItems: Set<TrackedItem> = emptySet(),
    val certificatePrompt: CertificatePrompt? = null,
    val update: UpdateState = UpdateState.Idle
)

/** What Settings can do. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onTestConnect: (url: String, token: String, allowSelfSigned: Boolean, cfClientId: String, cfClientSecret: String) -> Unit = { _, _, _, _, _ -> },
    val onShowToast: (String) -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onCaregiverNameChange: (String) -> Unit = {},
    val onBabySexChange: (BabySex) -> Unit = {},
    val onFeedNotificationChange: (Boolean) -> Unit = {},
    val onStartSideChange: (StartSide) -> Unit = {},
    val onNextUpNotificationChange: (Boolean) -> Unit = {},
    val onUse24hChange: (Boolean) -> Unit = {},
    val onDayHoursChange: (dayStartHour: Int, nightStartHour: Int) -> Unit = { _, _ -> },
    val onTrendsDayStartChange: (Int) -> Unit = {},
    val onModelsTabChange: (Boolean) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onRemindersChange: (ReminderSettings) -> Unit = {},
    val onItemTrackedChange: (TrackedItem, Boolean) -> Unit = { _, _ -> },
    val onEnterDemoMode: () -> Unit = {},
    val onTrustCertificate: () -> Unit = {},
    val onDismissCertificate: () -> Unit = {},
    val onCheckForUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onAllowInstalls: () -> Unit = {}
)

@Composable
fun ConnectBackendScreen(state: SettingsState, actions: SettingsActions, modifier: Modifier = Modifier) {
    // The fields of state and actions read as plain names below
    with(state) {
        with(actions) {
            certificatePrompt?.let { CertificateDialog(it, onTrustCertificate, onDismissCertificate) }

            val isLoggedIn = config.isConnected && !config.isDemoMode
            val uriHandler = LocalUriHandler.current
            val openLink: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) }.onFailure { onShowToast(AppText.get(R.string.settings_no_browser, url)) } }
            // Demo mode gets the whole of Settings too, so every feature can be tried; its Server connection logs in for real
            val showSettings = config.isConnected

            // The open category (its own page within Settings), or null for the list of categories
            var openSection by rememberSaveable { mutableStateOf<String?>(null) }
            BackHandler(enabled = showSettings && openSection != null) { openSection = null }
            val sectionTitles = mapOf(
                "baby" to stringResource(R.string.settings_baby),
                "tracking" to stringResource(R.string.settings_tracking),
                "notifications" to stringResource(R.string.settings_notifications),
                "appearance" to stringResource(R.string.settings_appearance),
                "you" to stringResource(R.string.settings_you),
                "connection" to stringResource(R.string.settings_connection),
                "about" to stringResource(R.string.settings_about)
            )

            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!showSettings) {
                    // First run / logged out: this is the login screen
                    Column(
                        modifier = Modifier.padding(top = 48.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.login_intro),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Up top as well as under the form, so it's seen before the server fields
                        TextButton(onClick = onEnterDemoMode, contentPadding = PaddingValues(0.dp), shape = RectangleShape, modifier = Modifier.testTag("demo_mode_link")) {
                            Text(stringResource(R.string.login_no_server), color = PrimaryColor, fontWeight = FontWeight.Medium)
                        }
                        var showWhatIs by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { showWhatIs = !showWhatIs }, contentPadding = PaddingValues(0.dp), shape = RectangleShape, modifier = Modifier.testTag("what_is_baby_buddy_link")) {
                            Text(stringResource(if (showWhatIs) R.string.login_what_is_open else R.string.login_what_is_closed), color = PrimaryColor, fontWeight = FontWeight.Medium)
                        }
                        AnimatedVisibility(visible = showWhatIs) {
                            Surface(color = SurfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        stringResource(R.string.login_what_is_text),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        stringResource(R.string.login_what_is_companion),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(onClick = { openLink(BABY_BUDDY_REPO) }, contentPadding = PaddingValues(0.dp), shape = RectangleShape) {
                                        Text(stringResource(R.string.login_set_up_server), color = PrimaryColor, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                    ServerConnectionForm(config, isLoggedIn, onTestConnect, onShowToast, onEnterDemoMode)
                    Spacer(modifier = Modifier.height(24.dp))
                    return@Column
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back closes an open category first, then leaves Settings
                    IconButton(onClick = { if (openSection != null) openSection = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = openSection?.let { sectionTitles[it] } ?: stringResource(R.string.nav_settings),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                @Composable
                fun Category(key: String, summary: String, icon: ImageVector, content: @Composable () -> Unit) = SettingsSection(
                    title = sectionTitles.getValue(key),
                    summary = summary,
                    icon = icon,
                    expanded = openSection == key,
                    hidden = openSection != null && openSection != key,
                    onOpen = { openSection = key },
                    content = content
                )

                Category(
                    "baby",
                    listOfNotNull(
                        babySex?.label,
                        stringResource(R.string.settings_day_hours_summary, DayHours.label(DayHours.dayStartHour), DayHours.label(DayHours.nightStartHour))
                    ).joinToString(" · "),
                    Icons.Default.ChildCare
                ) { BabySettings(state, actions) }

                Category(
                    "tracking",
                    if (hiddenItems.isEmpty()) {
                        stringResource(R.string.settings_tracking_everything)
                    } else {
                        stringResource(R.string.settings_hidden, TrackedItem.entries.filter { it in hiddenItems }.joinToString(", ") { it.label })
                    },
                    Icons.Default.Checklist
                ) { TrackingSettings(state, actions) }

                Category(
                    "notifications",
                    listOfNotNull(
                        stringResource(R.string.settings_summary_lock_screen).takeIf { feedNotificationEnabled },
                        stringResource(R.string.settings_summary_next_up).takeIf { nextUpNotification },
                        stringResource(R.string.settings_summary_feed).takeIf { reminders.feedAfterMinutes > 0 },
                        stringResource(R.string.settings_summary_awake).takeIf { reminders.awakeAfterMinutes > 0 },
                        stringResource(R.string.settings_summary_sleep).takeIf { reminders.sleepAfterMinutes > 0 },
                        stringResource(R.string.settings_summary_diaper).takeIf { reminders.diaperAfterMinutes > 0 }
                    ).joinToString(" · ").replaceFirstChar(Char::uppercase).ifBlank { stringResource(R.string.off) },
                    Icons.Default.Notifications
                ) { NotificationSettings(state, actions) }

                Category(
                    "appearance",
                    stringResource(if (use24h) R.string.settings_appearance_24h else R.string.settings_appearance_12h, themeMode.label),
                    Icons.Default.Palette
                ) { AppearanceSettings(state, actions) }

                Category("you", caregiverName.ifBlank { stringResource(R.string.settings_add_your_name) }, Icons.Default.Person) {
                    YouSettings(state, actions)
                }

                Category("connection", stringResource(R.string.settings_connected_to, config.serverUrl), Icons.Default.Cloud) {
                    ServerConnectionForm(config, isLoggedIn, onTestConnect, onShowToast, onEnterDemoMode)
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("disconnect_btn")
                    ) {
                        Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
                    }
                }

                Category("about", stringResource(R.string.about_summary, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE), Icons.Default.Info) {
                    AboutSettings(state, actions, openLink)
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** Trust on first use: the server's certificate, shown so the user can check it before trusting it. */
@Composable
private fun CertificateDialog(prompt: CertificatePrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (prompt.changed) R.string.cert_changed_title else R.string.cert_trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (prompt.changed) {
                        stringResource(R.string.cert_changed_text)
                    } else {
                        stringResource(R.string.cert_unknown_text)
                    }
                )
                Text(prompt.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust, modifier = Modifier.testTag("trust_cert_btn")) { Text(stringResource(R.string.cert_trust), color = PrimaryColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Server URL, token, certificates, Cloudflare and the Connect button (and, logged out, the demo button). */
@Composable
private fun ServerConnectionForm(
    config: ServerConnectionConfig,
    isLoggedIn: Boolean,
    onTestConnect: (url: String, token: String, allowSelfSigned: Boolean, cfClientId: String, cfClientSecret: String) -> Unit,
    onShowToast: (String) -> Unit,
    onEnterDemoMode: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(config.serverUrl) }
    var apiToken by remember { mutableStateOf(config.apiToken) }
    var tokenVisible by remember { mutableStateOf(false) }
    var allowSelfSigned by remember { mutableStateOf(config.allowSelfSignedCert) }
    var cfClientId by remember { mutableStateOf(config.cfClientId) }
    var cfClientSecret by remember { mutableStateOf(config.cfClientSecret) }
    var cfSecretVisible by remember { mutableStateOf(false) }
    var showCloudflare by remember { mutableStateOf(config.cfClientId.isNotBlank()) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Server URL
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(stringResource(R.string.login_server_url)) },
            placeholder = { Text("https://babybuddy.example.com") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("server_url_input")
        )

        // API token
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = apiToken,
                onValueChange = { apiToken = it },
                label = { Text(stringResource(R.string.login_api_token)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(if (tokenVisible) R.string.login_hide_token else R.string.login_show_token),
                            tint = OutlineColor
                        )
                    }
                },
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("api_token_input")
            )
            Text(
                stringResource(R.string.login_token_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Self-signed certificates
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.login_self_signed), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.login_self_signed_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = allowSelfSigned,
                onCheckedChange = { allowSelfSigned = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
            )
        }

        // Cloudflare Access service token (for servers behind Cloudflare Zero Trust)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.login_cloudflare), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.login_cloudflare_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = showCloudflare,
                onCheckedChange = {
                    showCloudflare = it
                    if (!it) {
                        cfClientId = ""
                        cfClientSecret = ""
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
            )
        }
        if (showCloudflare) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = cfClientId,
                    onValueChange = { cfClientId = it },
                    label = { Text("CF-Access-Client-Id") },
                    placeholder = { Text("xxxxxxxx.access") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("cf_client_id_input")
                )
                OutlinedTextField(
                    value = cfClientSecret,
                    onValueChange = { cfClientSecret = it },
                    label = { Text("CF-Access-Client-Secret") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { cfSecretVisible = !cfSecretVisible }) {
                            Icon(
                                imageVector = if (cfSecretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(if (cfSecretVisible) R.string.login_hide_secret else R.string.login_show_secret),
                                tint = OutlineColor
                            )
                        }
                    },
                    visualTransformation = if (cfSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("cf_client_secret_input")
                )
                Text(
                    stringResource(R.string.login_cloudflare_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // Actions
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (serverUrl.isBlank() || apiToken.isBlank()) {
                        onShowToast(AppText.get(R.string.login_enter_url_token))
                    } else if (showCloudflare && (cfClientId.isBlank() || cfClientSecret.isBlank())) {
                        onShowToast(AppText.get(R.string.login_enter_cloudflare))
                    } else {
                        onTestConnect(serverUrl.trim(), apiToken.trim(), allowSelfSigned, cfClientId.trim(), cfClientSecret.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("test_connect_btn")
            ) {
                Text(
                    text = stringResource(if (isLoggedIn) R.string.login_reconnect else R.string.login_connect),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onEnterDemoMode,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("demo_mode_btn")
            ) {
                Text(
                    text = stringResource(R.string.login_try_demo),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryColor
                )
            }
        }
    }
}

/** Settings → You: this phone's caregiver name. */
@Composable
private fun YouSettings(state: SettingsState, actions: SettingsActions) {
    var name by remember { mutableStateOf(state.caregiverName) }
    LaunchedEffect(name) {
        delay(600) // save shortly after typing stops
        if (name.trim() != state.caregiverName) actions.onCaregiverNameChange(name)
    }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.settings_your_name)) },
        supportingText = { Text(stringResource(R.string.settings_your_name_desc)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Settings → Baby: sex (for the growth charts) and the baby's day and night hours. */
@Composable
private fun BabySettings(state: SettingsState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupTitle(stringResource(R.string.settings_growth_charts), stringResource(R.string.settings_growth_charts_desc))
        SegmentedToggle(
            options = BabySex.entries.map { it.label },
            selectedIndex = state.babySex?.let { BabySex.entries.indexOf(it) } ?: -1,
            onSelect = { actions.onBabySexChange(BabySex.entries[it]) }
        )
    }
    DayNightSetting(onDayHoursChange = actions.onDayHoursChange)
}

/** Settings → Tracking: what's tracked, how breastfeeds start, and how Trends shows it. */
@Composable
private fun TrackingSettings(state: SettingsState, actions: SettingsActions) {
    with(state) {
        with(actions) {
            SettingsGroupTitle(stringResource(R.string.settings_tracked), stringResource(R.string.settings_tracked_desc))
            TrackedItem.entries.forEach { item ->
                SwitchSetting(
                    title = item.label,
                    description = item.description,
                    checked = item !in hiddenItems,
                    onChange = { on -> onItemTrackedChange(item, on) }
                )
            }
            if (TrackedItem.BREASTFEEDING !in hiddenItems) {
                Column {
                    SettingsGroupTitle(stringResource(R.string.settings_start_side), stringResource(R.string.settings_start_side_desc))
                    StartSide.entries.forEach { side ->
                        RadioOption(side.label, side.description, selected = side == startSide) { onStartSideChange(side) }
                    }
                }
            }
            SettingsGroupTitle(stringResource(R.string.nav_trends))
            TrendsDayStartSetting(onTrendsDayStartChange)
            SwitchSetting(
                title = stringResource(R.string.settings_models_tab),
                description = stringResource(R.string.settings_models_tab_desc),
                checked = modelsTab,
                onChange = onModelsTabChange
            )
        }
    }
}

/** A radio button row with a title and a line under it. */
@Composable
private fun RadioOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = PrimaryColor))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Settings → Appearance: theme, clock and language. */
@Composable
private fun AppearanceSettings(state: SettingsState, actions: SettingsActions) {
    Column {
        SettingsGroupTitle(stringResource(R.string.settings_theme))
        ThemeMode.entries.forEach { mode ->
            RadioOption(mode.label, mode.description, selected = mode == state.themeMode) { actions.onThemeModeChange(mode) }
        }
    }
    SwitchSetting(
        title = stringResource(R.string.settings_24h),
        description = stringResource(if (state.use24h) R.string.settings_24h_on else R.string.settings_24h_off),
        checked = state.use24h,
        onChange = actions.onUse24hChange
    )
    LanguageSetting()
}

/** Settings → Notifications: lock-screen notifications, then reminders. */
@Composable
private fun NotificationSettings(state: SettingsState, actions: SettingsActions) {
    with(state) {
        with(actions) {
            SettingsGroupTitle(stringResource(R.string.settings_lock_screen))
            SwitchSetting(
                title = stringResource(R.string.settings_lock_screen_timers),
                description = stringResource(R.string.settings_lock_screen_timers_desc),
                checked = feedNotificationEnabled,
                onChange = onFeedNotificationChange
            )
            SwitchSetting(
                title = stringResource(R.string.settings_next_up),
                description = stringResource(R.string.settings_next_up_desc),
                checked = nextUpNotification,
                onChange = onNextUpNotificationChange
            )

            SettingsGroupTitle(stringResource(R.string.settings_reminders), stringResource(R.string.settings_reminders_note))
            if (TrackedItem.BREASTFEEDING !in hiddenItems || TrackedItem.BOTTLE !in hiddenItems) {
                ReminderSetting(
                    title = stringResource(R.string.settings_feed_reminder),
                    minutes = reminders.feedAfterMinutes,
                    defaultMinutes = 180,
                    onText = stringResource(R.string.settings_feed_reminder_on, formatMinutes(reminders.feedAfterMinutes)),
                    offText = stringResource(R.string.settings_feed_reminder_off),
                    modelTimed = RhythmTarget.FEED_GAP in modelTargets,
                    dialogNote = stringResource(R.string.settings_feed_reminder_note),
                    onChange = { onRemindersChange(reminders.copy(feedAfterMinutes = it)) }
                )
            }
            if (TrackedItem.SLEEP !in hiddenItems) {
                ReminderSetting(
                    title = stringResource(R.string.settings_awake_reminder),
                    minutes = reminders.awakeAfterMinutes,
                    defaultMinutes = 90,
                    onText = stringResource(R.string.settings_awake_reminder_on, formatMinutes(reminders.awakeAfterMinutes)),
                    offText = stringResource(R.string.settings_awake_reminder_off),
                    modelTimed = RhythmTarget.AWAKE_WINDOW in modelTargets,
                    dialogNote = stringResource(R.string.settings_awake_reminder_note),
                    onChange = { onRemindersChange(reminders.copy(awakeAfterMinutes = it)) }
                )
                ReminderSetting(
                    title = stringResource(R.string.settings_sleep_reminder),
                    minutes = reminders.sleepAfterMinutes,
                    defaultMinutes = 120,
                    onText = stringResource(R.string.settings_sleep_reminder_on, formatMinutes(reminders.sleepAfterMinutes)),
                    offText = stringResource(R.string.settings_sleep_reminder_off),
                    modelTimed = RhythmTarget.SLEEP_LENGTH in modelTargets,
                    dialogNote = stringResource(R.string.settings_sleep_reminder_note),
                    onChange = { onRemindersChange(reminders.copy(sleepAfterMinutes = it)) }
                )
            }
            if (TrackedItem.DIAPERS !in hiddenItems) {
                ReminderSetting(
                    title = stringResource(R.string.settings_diaper_reminder),
                    minutes = reminders.diaperAfterMinutes,
                    defaultMinutes = 180,
                    onText = stringResource(R.string.settings_diaper_reminder_on, formatMinutes(reminders.diaperAfterMinutes)),
                    offText = stringResource(R.string.settings_diaper_reminder_off),
                    modelTimed = RhythmTarget.DIAPER_GAP in modelTargets,
                    dialogNote = stringResource(R.string.settings_diaper_reminder_note),
                    onChange = { onRemindersChange(reminders.copy(diaperAfterMinutes = it)) }
                )
            }
            if (reminders.anyOn) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_heads_up), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (reminders.earlyMinutes > 0) {
                            stringResource(R.string.settings_heads_up_on, formatMinutes(reminders.earlyMinutes))
                        } else {
                            stringResource(R.string.settings_heads_up_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 5, 10, 15, 20, 30, 45, 60).forEach { minutes ->
                            FilterChip(
                                selected = reminders.earlyMinutes == minutes,
                                onClick = { onRemindersChange(reminders.copy(earlyMinutes = minutes)) },
                                label = { Text(if (minutes == 0) stringResource(R.string.off) else formatMinutes(minutes)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSettings(state: SettingsState, actions: SettingsActions, openLink: (String) -> Unit) {
    with(state) {
        with(actions) {
            // What to quote in a bug report: the release, the exact commit and when it was built
            listOf(
                stringResource(R.string.about_version) to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                stringResource(R.string.about_build) to BuildConfig.BUILD_TYPE,
                stringResource(R.string.about_git) to BuildConfig.GIT_DESCRIBE,
                stringResource(R.string.about_build_date) to BuildConfig.BUILD_DATE,
                stringResource(R.string.about_app_id) to BuildConfig.APPLICATION_ID
            ).forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            if (AppUpdater.isEnabled) UpdateSettings(update, onCheckForUpdate, onInstallUpdate, onAllowInstalls, openLink)
            Column {
                TextButton(onClick = { openLink(APP_REPO) }, contentPadding = PaddingValues(0.dp), shape = RectangleShape, modifier = Modifier.testTag("app_repo_link")) {
                    Text(stringResource(R.string.about_source), color = PrimaryColor, fontWeight = FontWeight.Medium)
                }
                TextButton(onClick = { openLink(BABY_BUDDY_REPO) }, contentPadding = PaddingValues(0.dp), shape = RectangleShape, modifier = Modifier.testTag("baby_buddy_repo_link")) {
                    Text(stringResource(R.string.about_server), color = PrimaryColor, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Settings → About → Updates: checks this app's GitHub releases, then downloads and installs a newer one. */
@Composable
private fun UpdateSettings(
    update: UpdateState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    openLink: (String) -> Unit
) {
    SettingsGroupTitle(stringResource(R.string.update_title), stringResource(R.string.update_desc))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("update_settings")) {
        val bodyStyle = MaterialTheme.typography.bodyMedium
        val release = when (update) {
            is UpdateState.Available -> update.release
            is UpdateState.Downloading -> update.release
            is UpdateState.NeedsPermission -> update.release
            is UpdateState.Installing -> update.release
            is UpdateState.Failed -> update.release
            else -> null
        }
        when (update) {
            UpdateState.Idle -> Unit

            UpdateState.Checking -> Text(stringResource(R.string.update_checking), style = bodyStyle)

            UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), style = bodyStyle, color = SecondaryColor)

            is UpdateState.Available -> {
                Text(stringResource(R.string.update_available, update.release.versionName), style = bodyStyle, fontWeight = FontWeight.Medium)
                if (update.release.notes.isNotBlank()) {
                    Text(update.release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }

            is UpdateState.Downloading -> {
                val progress = update.progress
                Text(
                    if (progress == null) stringResource(R.string.update_downloading) else stringResource(R.string.update_downloading_percent, (progress * 100).toInt()),
                    style = bodyStyle
                )
                if (progress == null) {
                    LinearProgressIndicator(color = PrimaryColor, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, color = PrimaryColor, modifier = Modifier.fillMaxWidth())
                }
            }

            is UpdateState.NeedsPermission -> Text(stringResource(R.string.update_needs_permission), style = bodyStyle)

            is UpdateState.Installing -> Text(stringResource(R.string.update_confirm), style = bodyStyle)

            is UpdateState.Failed -> Text(update.message, style = bodyStyle, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                update is UpdateState.NeedsPermission -> {
                    Button(onClick = onAllowInstalls, colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary), modifier = Modifier.testTag("update_allow_btn")) {
                        Text(stringResource(R.string.update_allow))
                    }
                    OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.update_install_short)) }
                }

                update is UpdateState.Available || (update is UpdateState.Failed && release != null) -> Button(
                    onClick = onInstall,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = OnPrimary),
                    modifier = Modifier.testTag("update_install_btn")
                ) {
                    Text(stringResource(if (update is UpdateState.Failed) R.string.update_try_again else R.string.update_install))
                }

                update is UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Installing -> Unit

                else -> OutlinedButton(onClick = onCheck, modifier = Modifier.testTag("update_check_btn")) {
                    Text(stringResource(if (update is UpdateState.Idle) R.string.update_check else R.string.update_check_again))
                }
            }
        }
        if (release != null) {
            TextButton(onClick = { openLink(release.pageUrl) }, contentPadding = PaddingValues(0.dp), shape = RectangleShape) {
                Text(stringResource(R.string.update_whats_new), color = PrimaryColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}
