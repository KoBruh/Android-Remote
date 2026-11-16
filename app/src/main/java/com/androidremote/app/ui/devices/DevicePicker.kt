package com.androidremote.app.ui.devices

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.androidremote.app.R
import com.androidremote.app.data.PairedDevice
import com.androidremote.app.tv.DiscoveredTv
import com.androidremote.app.ui.icons.Icons
import com.androidremote.app.ui.theme.Tokens

/**
 * The dropdown under the device pill: paired TVs, TVs found on the network, "Pair new" and
 * "Settings". Everything that is not on the remote itself is reached from here.
 */
@Composable
fun DevicePicker(
    expanded: Boolean,
    devices: List<PairedDevice>,
    selected: PairedDevice?,
    discovered: List<DiscoveredTv>,
    onDismiss: () -> Unit,
    onSelect: (PairedDevice) -> Unit,
    onPair: (DiscoveredTv) -> Unit,
    onPairManually: () -> Unit,
    onSettings: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Tokens.Surface) {
        if (devices.isNotEmpty()) {
            SectionLabel(stringResource(R.string.menu_paired))
            for (device in devices) {
                DropdownMenuItem(
                    text = { Text(device.name) },
                    leadingIcon = { Icon(Icons.Tv, contentDescription = null) },
                    trailingIcon = {
                        if (device.fingerprint == selected?.fingerprint) {
                            Icon(Icons.Check, contentDescription = stringResource(R.string.cd_selected), tint = Tokens.Accent)
                        }
                    },
                    onClick = { onSelect(device) },
                )
            }
        }
        if (discovered.isNotEmpty()) {
            SectionLabel(stringResource(R.string.menu_found))
            for (tv in discovered) {
                DropdownMenuItem(
                    text = { Text(tv.name) },
                    leadingIcon = { Icon(Icons.Tv, contentDescription = null) },
                    onClick = { onPair(tv) },
                )
            }
        }
        if (devices.isNotEmpty() || discovered.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_pair_new)) },
            leadingIcon = { Icon(Icons.Add, contentDescription = null) },
            onClick = onPairManually,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_settings)) },
            leadingIcon = { Icon(Icons.Settings, contentDescription = null) },
            onClick = onSettings,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.TextSecondary,
        modifier = Modifier.padding(horizontal = Tokens.ItemSpacing, vertical = Tokens.LineSpacing),
    )
}
