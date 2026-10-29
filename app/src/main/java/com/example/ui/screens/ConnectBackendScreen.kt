package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import com.example.AppLanguage
import com.example.BuildConfig
import com.example.R
import com.example.data.ml.RhythmTarget
import com.example.data.model.AppText
import com.example.data.model.BabySex
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

@Composable
fun ConnectBackendScreen(
    config: ServerConnectionConfig,
    onBack: () -> Unit,
    onTestConnect: (url: String, token: String, allowSelfSigned: Boolean, cfClientId: String, cfClientSecret: String) -> Unit,
    onShowToast: (String) -> Unit,
    onDisconnect: () -> Unit = {},
    caregiverName: String = "",
    onCaregiverNameChange: (String) -> Unit = {},
    babySex: BabySex? = null,
    onBabySexChange: (BabySex) -> Unit = {},
    feedNotificationEnabled: Boolean = true,
    onFeedNotificationChange: (Boolean) -> Unit = {},
    nextUpNotification: Boolean = false,
    startSide: StartSide = StartSide.OPPOSITE,
    onStartSideChange: (StartSide) -> Unit = {},
    onNextUpNotificationChange: (Boolean) -> Unit = {},
    use24h: Boolean = true,
    onUse24hChange: (Boolean) -> Unit = {},
    modelsTab: Boolean = false,
    modelTargets: Set<RhythmTarget> = emptySet(), // rhythms whose reminders a saved model times (Trends → Models)
    onModelsTabChange: (Boolean) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.BY_TIME,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    reminders: ReminderSettings = ReminderSettings(),
    onRemindersChange: (ReminderSettings) -> Unit = {},
    hiddenItems: Set<TrackedItem> = emptySet(),
    onItemTrackedChange: (TrackedItem, Boolean) -> Unit = { _, _ -> },
    onEnterDemoMode: () -> Unit = {},
    certificatePrompt: CertificatePrompt? = null,
    onTrustCertificate: () -> Unit = {},
    onDismissCertificate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    certificatePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onDismissCertificate,
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
                TextButton(onClick = onTrustCertificate, modifier = Modifier.testTag("trust_cert_btn")) { Text(stringResource(R.string.cert_trust), color = PrimaryColor) }
            },
            dismissButton = {
                TextButton(onClick = onDismissCertificate) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    val isLoggedIn = config.isConnected && !config.isDemoMode
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) }.onFailure { onShowToast(AppText.get(R.string.settings_no_browser, url)) } }
    // Demo mode gets the whole of Settings too, so every feature can be tried; its Server connection logs in for real
    val showSettings = config.isConnected
    var serverUrl by remember { mutableStateOf(config.serverUrl) }
    var apiToken by remember { mutableStateOf(config.apiToken) }
    var tokenVisible by remember { mutableStateOf(false) }
    var allowSelfSigned by remember { mutableStateOf(config.allowSelfSignedCert) }
    var cfClientId by remember { mutableStateOf(config.cfClientId) }
    var cfClientSecret by remember { mutableStateOf(config.cfClientSecret) }
    var cfSecretVisible by remember { mutableStateOf(false) }
    var showCloudflare by remember { mutableStateOf(config.cfClientId.isNotBlank()) }

    // The open category (its own page within Settings), or null for the list of categories
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = showSettings && openSection != null) { openSection = null }
    val sectionTitles = mapOf(
        "connection" to stringResource(R.string.settings_connection),
        "you" to stringResource(R.string.settings_you),
        "tracked" to stringResource(R.string.settings_tracked),
        "appearance" to stringResource(R.string.settings_appearance),
        "notifications" to stringResource(R.string.settings_notifications),
        "about" to stringResource(R.string.settings_about)
    )

    // Server URL, token, certificates, Cloudflare and the Connect button
    val connectionForm: @Composable () -> Unit = {
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
            connectionForm()
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

        SettingsSection(
            title = stringResource(R.string.settings_connection),
            summary = stringResource(R.string.settings_connected_to, config.serverUrl),
            icon = Icons.Default.Cloud,
            expanded = openSection == "connection",
            hidden = openSection != null && openSection != "connection",
            onOpen = { openSection = "connection" }
        ) {
            connectionForm()
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("disconnect_btn")
            ) {
                Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_you),
            summary = caregiverName.ifBlank { stringResource(R.string.settings_add_your_name) },
            icon = Icons.Default.Person,
            expanded = openSection == "you",
            hidden = openSection != null && openSection != "you",
            onOpen = { openSection = "you" }
        ) {
            var name by remember { mutableStateOf(caregiverName) }
            LaunchedEffect(name) {
                delay(600) // save shortly after typing stops
                if (name.trim() != caregiverName) onCaregiverNameChange(name)
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
            Text(stringResource(R.string.settings_growth_charts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text(
                stringResource(R.string.settings_growth_charts_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SegmentedToggle(
                options = BabySex.entries.map { it.label },
                selectedIndex = babySex?.let { BabySex.entries.indexOf(it) } ?: -1,
                onSelect = { onBabySexChange(BabySex.entries[it]) }
            )
            if (TrackedItem.BREASTFEEDING !in hiddenItems) {
                Text(stringResource(R.string.settings_start_side), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text(
                    stringResource(R.string.settings_start_side_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StartSide.entries.forEach { side ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = side == startSide, onClick = { onStartSideChange(side) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = side == startSide,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = PrimaryColor)
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(side.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                side.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_tracked),
            summary = if (hiddenItems.isEmpty()) {
                stringResource(R.string.settings_tracking_everything)
            } else {
                stringResource(R.string.settings_hidden, TrackedItem.entries.filter { it in hiddenItems }.joinToString(", ") { it.label })
            },
            icon = Icons.Default.Checklist,
            expanded = openSection == "tracked",
            hidden = openSection != null && openSection != "tracked",
            onOpen = { openSection = "tracked" }
        ) {
            Text(
                stringResource(R.string.settings_tracked_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TrackedItem.entries.forEach { item ->
                SwitchSetting(
                    title = item.label,
                    description = item.description,
                    checked = item !in hiddenItems,
                    onChange = { on -> onItemTrackedChange(item, on) }
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_appearance),
            summary = stringResource(if (use24h) R.string.settings_appearance_24h else R.string.settings_appearance_12h, themeMode.label),
            icon = Icons.Default.Palette,
            expanded = openSection == "appearance",
            hidden = openSection != null && openSection != "appearance",
            onOpen = { openSection = "appearance" }
        ) {
            Column {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge, color = OutlineColor)
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == themeMode, onClick = { onThemeModeChange(mode) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == themeMode,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = PrimaryColor)
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            LanguageSetting()
            SwitchSetting(
                title = stringResource(R.string.settings_24h),
                description = stringResource(if (use24h) R.string.settings_24h_on else R.string.settings_24h_off),
                checked = use24h,
                onChange = onUse24hChange
            )
            SwitchSetting(
                title = stringResource(R.string.settings_models_tab),
                description = stringResource(R.string.settings_models_tab_desc),
                checked = modelsTab,
                onChange = onModelsTabChange
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_notifications),
            summary = listOfNotNull(
                stringResource(R.string.settings_summary_lock_screen).takeIf { feedNotificationEnabled },
                stringResource(R.string.settings_summary_next_up).takeIf { nextUpNotification },
                stringResource(R.string.settings_summary_feed).takeIf { reminders.feedAfterMinutes > 0 },
                stringResource(R.string.settings_summary_awake).takeIf { reminders.awakeAfterMinutes > 0 },
                stringResource(R.string.settings_summary_sleep).takeIf { reminders.sleepAfterMinutes > 0 },
                stringResource(R.string.settings_summary_diaper).takeIf { reminders.diaperAfterMinutes > 0 }
            ).joinToString(" · ").replaceFirstChar(Char::uppercase).ifBlank { stringResource(R.string.off) },
            icon = Icons.Default.Notifications,
            expanded = openSection == "notifications",
            hidden = openSection != null && openSection != "notifications",
            onOpen = { openSection = "notifications" }
        ) {
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
            if (TrackedItem.BREASTFEEDING !in hiddenItems || TrackedItem.BOTTLE !in hiddenItems) {
                SwitchSetting(
                    title = stringResource(R.string.settings_feed_reminder),
                    description = if (reminders.feedAfterMinutes > 0) {
                        if (RhythmTarget.FEED_GAP in modelTargets) {
                            stringResource(R.string.settings_model_timed)
                        } else {
                            stringResource(R.string.settings_feed_reminder_on, formatMinutes(reminders.feedAfterMinutes))
                        }
                    } else {
                        stringResource(R.string.settings_feed_reminder_off)
                    },
                    checked = reminders.feedAfterMinutes > 0,
                    onChange = { on -> onRemindersChange(reminders.copy(feedAfterMinutes = if (on) 180 else 0)) }
                )
            }
            if (reminders.feedAfterMinutes > 0 && (TrackedItem.BREASTFEEDING !in hiddenItems || TrackedItem.BOTTLE !in hiddenItems)) {
                DurationField(
                    label = stringResource(R.string.settings_remind_after),
                    minutes = reminders.feedAfterMinutes,
                    onChange = { onRemindersChange(reminders.copy(feedAfterMinutes = it)) },
                    dialogNote = stringResource(R.string.settings_feed_reminder_note)
                )
            }
            if (TrackedItem.SLEEP !in hiddenItems) {
                SwitchSetting(
                    title = stringResource(R.string.settings_awake_reminder),
                    description = if (reminders.awakeAfterMinutes > 0) {
                        if (RhythmTarget.AWAKE_WINDOW in modelTargets) stringResource(R.string.settings_model_timed) else stringResource(R.string.settings_awake_reminder_on, formatMinutes(reminders.awakeAfterMinutes))
                    } else {
                        stringResource(R.string.settings_awake_reminder_off)
                    },
                    checked = reminders.awakeAfterMinutes > 0,
                    onChange = { on -> onRemindersChange(reminders.copy(awakeAfterMinutes = if (on) 90 else 0)) }
                )
            }
            if (reminders.awakeAfterMinutes > 0 && TrackedItem.SLEEP !in hiddenItems) {
                DurationField(
                    label = stringResource(R.string.settings_remind_after),
                    minutes = reminders.awakeAfterMinutes,
                    onChange = { onRemindersChange(reminders.copy(awakeAfterMinutes = it)) },
                    dialogNote = stringResource(R.string.settings_awake_reminder_note)
                )
            }
            if (TrackedItem.SLEEP !in hiddenItems) {
                SwitchSetting(
                    title = stringResource(R.string.settings_sleep_reminder),
                    description = if (reminders.sleepAfterMinutes > 0) {
                        if (RhythmTarget.SLEEP_LENGTH in modelTargets) stringResource(R.string.settings_model_timed) else stringResource(R.string.settings_sleep_reminder_on, formatMinutes(reminders.sleepAfterMinutes))
                    } else {
                        stringResource(R.string.settings_sleep_reminder_off)
                    },
                    checked = reminders.sleepAfterMinutes > 0,
                    onChange = { on -> onRemindersChange(reminders.copy(sleepAfterMinutes = if (on) 120 else 0)) }
                )
            }
            if (reminders.sleepAfterMinutes > 0 && TrackedItem.SLEEP !in hiddenItems) {
                DurationField(
                    label = stringResource(R.string.settings_remind_after),
                    minutes = reminders.sleepAfterMinutes,
                    onChange = { onRemindersChange(reminders.copy(sleepAfterMinutes = it)) },
                    dialogNote = stringResource(R.string.settings_sleep_reminder_note)
                )
            }
            if (TrackedItem.DIAPERS !in hiddenItems) {
                SwitchSetting(
                    title = stringResource(R.string.settings_diaper_reminder),
                    description = if (reminders.diaperAfterMinutes > 0) {
                        if (RhythmTarget.DIAPER_GAP in modelTargets) {
                            stringResource(R.string.settings_model_timed)
                        } else {
                            stringResource(R.string.settings_diaper_reminder_on, formatMinutes(reminders.diaperAfterMinutes))
                        }
                    } else {
                        stringResource(R.string.settings_diaper_reminder_off)
                    },
                    checked = reminders.diaperAfterMinutes > 0,
                    onChange = { on -> onRemindersChange(reminders.copy(diaperAfterMinutes = if (on) 180 else 0)) }
                )
            }
            if (reminders.diaperAfterMinutes > 0 && TrackedItem.DIAPERS !in hiddenItems) {
                DurationField(
                    label = stringResource(R.string.settings_remind_after),
                    minutes = reminders.diaperAfterMinutes,
                    onChange = { onRemindersChange(reminders.copy(diaperAfterMinutes = it)) },
                    dialogNote = stringResource(R.string.settings_diaper_reminder_note)
                )
            }
            if (reminders.anyOn) {
                Text(
                    stringResource(R.string.settings_heads_up),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
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
            Text(
                stringResource(R.string.settings_reminders_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_about),
            summary = stringResource(R.string.about_summary, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE),
            icon = Icons.Default.Info,
            expanded = openSection == "about",
            hidden = openSection != null && openSection != "about",
            onOpen = { openSection = "about" }
        ) {
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
            Column {
                TextButton(onClick = { openLink(APP_REPO) }, contentPadding = PaddingValues(0.dp), shape = RectangleShape, modifier = Modifier.testTag("app_repo_link")) {
                    Text(stringResource(R.string.about_source), color = PrimaryColor, fontWeight = FontWeight.Medium)
                }
                TextButton(onClick = { openLink(BABY_BUDDY_REPO) }, contentPadding = PaddingValues(0.dp), shape = RectangleShape, modifier = Modifier.testTag("baby_buddy_repo_link")) {
                    Text(stringResource(R.string.about_server), color = PrimaryColor, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
