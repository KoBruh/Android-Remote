package com.androidremote.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.androidremote.app.R
import com.androidremote.app.ui.theme.Tokens

/** Debug builds: a report or log, selectable, with a button that copies it to the clipboard. */
@Composable
fun DebugReport(report: String) {
    val context = LocalContext.current
    Column {
        TextButton(onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Android Remote report", report))
        }) { Text(stringResource(R.string.debug_copy_report)) }
        SelectionContainer {
            Text(report, color = Tokens.TextSecondary, fontFamily = FontFamily.Monospace, fontSize = Tokens.DebugReportTextSize)
        }
    }
}
