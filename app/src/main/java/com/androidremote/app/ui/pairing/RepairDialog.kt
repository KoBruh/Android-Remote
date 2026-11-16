package com.androidremote.app.ui.pairing

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.androidremote.app.R
import com.androidremote.app.RepairPrompt
import com.androidremote.app.RepairReason
import com.androidremote.app.ui.theme.Tokens

/** Asks whether to pair again with a TV that changed its identity or no longer accepts this phone. */
@Composable
fun RepairDialog(prompt: RepairPrompt, onRepair: () -> Unit, onDismiss: () -> Unit) {
    val text = when (prompt.reason) {
        RepairReason.CertChanged -> R.string.repair_cert_changed
        RepairReason.Rejected -> R.string.repair_rejected
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        title = { Text(stringResource(R.string.repair_title)) },
        text = { Text(stringResource(text, prompt.device.name)) },
        confirmButton = { TextButton(onClick = onRepair) { Text(stringResource(R.string.settings_repair)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
