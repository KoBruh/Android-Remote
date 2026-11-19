package com.androidremote.app.ui.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.androidremote.app.BuildConfig
import com.androidremote.app.R
import com.androidremote.app.RemoteController
import com.androidremote.app.tv.DiscoveredTv
import com.androidremote.app.tv.InvalidPairingCodeException
import com.androidremote.app.tv.PairingSecret
import com.androidremote.app.tv.PairingSession
import com.androidremote.app.tv.describe
import com.androidremote.app.ui.DebugReport
import com.androidremote.app.ui.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Collections

/** Which TV a pairing dialog is for. With a null [host] the dialog first asks for the IP address. */
data class PairingTarget(val host: String? = null, val discovered: DiscoveredTv? = null)

private enum class Step { Address, Connecting, Code, Verifying }

/**
 * Pairs with a TV: (optionally) asks for its IP address, makes the TV show its 6-character
 * code, and finishes pairing once the user has typed the code.
 */
@Composable
fun PairingDialog(target: PairingTarget, controller: RemoteController, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (target.host == null) Step.Address else Step.Connecting) }
    var host by remember { mutableStateOf(target.host.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var session by remember { mutableStateOf<PairingSession?>(null) }
    // Debug builds: what exactly went wrong, stage by stage, plus a probe of every layer.
    var report by remember { mutableStateOf<String?>(null) }

    fun connect() {
        step = Step.Connecting
        error = null
        report = null
        scope.launch {
            val stages = Collections.synchronizedList(mutableListOf<String>())
            try {
                session = controller.startPairing(host.trim()) { stages += it }
                step = Step.Code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = R.string.pair_error_connect
                step = Step.Address
                if (BuildConfig.DEBUG) {
                    val attempt = "== Pairing attempt ==\n${e.describe()}\n-- stages\n${stages.toList().joinToString("\n")}\n"
                    report = attempt + "(probing...)"
                    report = attempt + controller.diagnose(host.trim())
                }
            }
        }
    }

    fun verify() {
        val current = session ?: return
        step = Step.Verifying
        error = null
        scope.launch {
            try {
                controller.finishPairing(current, code, host.trim(), target.discovered?.takeIf { it.host == host.trim() })
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (_: InvalidPairingCodeException) {
                error = R.string.pair_error_code
                code = ""
                step = Step.Code
            } catch (e: Exception) {
                // The TV closed the pairing (wrong code sent, timeout or Cancel pressed): start over.
                current.close()
                session = null
                if (BuildConfig.DEBUG) report = "== Pairing code step ==\n${e.describe()}"
                error = R.string.pair_error_rejected
                code = ""
                step = Step.Address
            }
        }
    }

    LaunchedEffect(Unit) { if (target.host != null) connect() }
    DisposableEffect(Unit) { onDispose { session?.close() } }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Tokens.Surface,
        title = { Text(stringResource(R.string.pair_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (step) {
                    Step.Address -> {
                        Text(stringResource(R.string.pair_address_hint))
                        Spacer(Modifier.height(Tokens.ItemSpacing))
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text(stringResource(R.string.pair_address_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (host.isNotBlank()) connect() }),
                        )
                    }
                    Step.Connecting, Step.Verifying -> CircularProgressIndicator()
                    Step.Code -> {
                        Text(stringResource(R.string.pair_code_hint))
                        Spacer(Modifier.height(Tokens.ItemSpacing))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { input ->
                                code = input.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(PairingSecret.CODE_LENGTH)
                            },
                            label = { Text(stringResource(R.string.pair_code_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { if (code.length == PairingSecret.CODE_LENGTH) verify() }),
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(Tokens.ItemSpacing))
                    Text(stringResource(it), color = Tokens.Error)
                }
                report?.let {
                    Spacer(Modifier.height(Tokens.ItemSpacing))
                    DebugReport(it)
                }
            }
        },
        confirmButton = {
            when (step) {
                Step.Address -> TextButton(onClick = ::connect, enabled = host.isNotBlank()) {
                    Text(stringResource(R.string.pair_connect))
                }
                Step.Code -> TextButton(onClick = ::verify, enabled = code.length == PairingSecret.CODE_LENGTH) {
                    Text(stringResource(R.string.pair_confirm))
                }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
