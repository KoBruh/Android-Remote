package com.androidremote.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.androidremote.app.BuildConfig
import com.androidremote.app.R
import com.androidremote.app.data.PairedDevice
import com.androidremote.app.data.Settings
import com.androidremote.app.ui.icons.Icons
import com.androidremote.app.ui.theme.Tokens

/**
 * Settings: paired TVs (Wake-on-LAN MAC, re-pair, forget), haptics, screen and volume-key
 * options, and the debug reference overlay.
 */
@Composable
fun SettingsScreen(
    devices: List<PairedDevice>,
    settings: Settings,
    onBack: () -> Unit,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    onSetMac: (PairedDevice, String) -> Boolean,
    onRepair: (PairedDevice) -> Unit,
    onForget: (PairedDevice) -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Tokens.BottomIcon)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.titleLarge, color = Tokens.BottomIcon)
        }

        if (!settings.standbyTipDismissed) {
            Card {
                Text(stringResource(R.string.tip_standby_title), style = MaterialTheme.typography.titleMedium, color = Tokens.Accent)
                Text(stringResource(R.string.tip_standby_body), color = Tokens.BottomIcon)
                TextButton(
                    onClick = { onSettingsChange { it.copy(standbyTipDismissed = true) } },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.got_it)) }
            }
        }

        SectionTitle(stringResource(R.string.settings_tvs))
        if (devices.isEmpty()) {
            Text(stringResource(R.string.settings_no_tvs), color = Tokens.TextSecondary)
        }
        for (device in devices) {
            DeviceCard(device, onSetMac = { onSetMac(device, it) }, onRepair = { onRepair(device) }, onForget = { onForget(device) })
        }

        SectionTitle(stringResource(R.string.settings_feel))
        Card {
            SwitchRow(stringResource(R.string.settings_haptics), settings.hapticsEnabled) { on ->
                onSettingsChange { it.copy(hapticsEnabled = on) }
            }
            if (settings.hapticsEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = listOf(R.string.haptic_light, R.string.haptic_medium, R.string.haptic_strong)
                    labels.forEachIndexed { strength, label ->
                        FilterChip(
                            selected = settings.hapticStrength == strength,
                            onClick = { onSettingsChange { it.copy(hapticStrength = strength) } },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
            SwitchRow(stringResource(R.string.settings_keep_awake), settings.keepScreenOn) { on ->
                onSettingsChange { it.copy(keepScreenOn = on) }
            }
            SwitchRow(stringResource(R.string.settings_volume_keys), settings.volumeKeysControlTv) { on ->
                onSettingsChange { it.copy(volumeKeysControlTv = on) }
            }
        }

        if (BuildConfig.DEBUG) {
            SectionTitle(stringResource(R.string.settings_debug))
            Card {
                SwitchRow(stringResource(R.string.settings_overlay), settings.referenceOverlay) { on ->
                    onSettingsChange { it.copy(referenceOverlay = on) }
                }
            }
        }

        Text(
            stringResource(R.string.about, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeviceCard(device: PairedDevice, onSetMac: (String) -> Boolean, onRepair: () -> Unit, onForget: () -> Unit) {
    val focus = LocalFocusManager.current
    var mac by remember(device.fingerprint, device.mac) { mutableStateOf(device.mac) }
    var invalid by remember(device.fingerprint) { mutableStateOf(false) }

    fun save() {
        invalid = !onSetMac(mac)
        if (!invalid) focus.clearFocus()
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Tv, contentDescription = null, tint = Tokens.BottomIcon, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(device.name, style = MaterialTheme.typography.titleMedium, color = Tokens.BottomIcon)
                Text(device.host, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            }
        }
        OutlinedTextField(
            value = mac,
            onValueChange = {
                mac = it.uppercase()
                invalid = false
            },
            label = { Text(stringResource(R.string.settings_mac)) },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            supportingText = {
                Text(stringResource(if (invalid) R.string.settings_mac_invalid else R.string.settings_mac_hint))
            },
            isError = invalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (mac != device.mac) {
                TextButton(onClick = ::save) { Text(stringResource(R.string.save)) }
            }
            TextButton(onClick = onRepair) { Text(stringResource(R.string.settings_repair)) }
            TextButton(onClick = onForget) { Text(stringResource(R.string.settings_forget), color = Tokens.Error) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Tokens.TextSecondary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.SurfaceCorner))
            .background(Tokens.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Tokens.BottomIcon, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
