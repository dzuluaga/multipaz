package org.multipaz.testapp.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CLIPBOARD_CLEAR_MILLIS = 45_000L

/** Renders a QR for [text] at [sizePx] pixels. */
private fun qrBitmap(text: String, sizePx: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}

/**
 * One-time recovery-key display. [recoveryKey] is the Ed25519 recovery private key string.
 * On [onSaved] the caller MUST drop its reference to [recoveryKey] — it is never shown again.
 */
@Composable
fun RecoveryKeyContent(
    recoveryKey: String,
    onSaved: () -> Unit,
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val qr = remember(recoveryKey) { qrBitmap(recoveryKey).asImageBitmap() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Belt-and-suspenders: if the user leaves this screen (e.g. taps Continue) while our key is
    // still on the clipboard, clear it so it doesn't linger after the one-time screen is gone.
    DisposableEffect(Unit) {
        onDispose { clearClipboardIfHolds(context, recoveryKey) }
    }

    // No verticalScroll here: this is always rendered inside the Pay screen's scrolling Column,
    // and nesting two vertical scrolls measures the inner one with infinite height (crash).
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Back up your recovery key", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your wallet key lives in this phone's secure hardware — it can't be exported, " +
                "even by you.\n\nThis is your RECOVERY key. It's shown ONCE. If you lose this phone, " +
                "it's the only way back into your account. Save it now — scan the QR into a password " +
                "manager.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Keep this screen open until you've saved the key. If the app closes, it can't be shown again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Image(
            bitmap = qr,
            contentDescription = "Recovery key QR code",
            modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            recoveryKey,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                copyRecoveryKeyToClipboard(context, recoveryKey)
                copied = true
                scope.launch {
                    delay(CLIPBOARD_CLEAR_MILLIS)
                    clearClipboardIfHolds(context, recoveryKey)
                    copied = false
                }
            },
        ) {
            Text(if (copied) "Copied — clears in 45s. Paste into your password manager." else "Copy recovery key")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = acknowledged,
                    onValueChange = { acknowledged = it },
                    role = Role.Checkbox,
                )
                .padding(vertical = 4.dp),
        ) {
            Checkbox(checked = acknowledged, onCheckedChange = null)
            Text("I've saved my recovery key. I understand it won't be shown again.")
        }
        Button(enabled = acknowledged, onClick = onSaved) {
            Text("Continue")
        }
    }
}

private fun clipboard(context: Context): ClipboardManager =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

/**
 * Copies [key] to the clipboard, flagged sensitive so Android 13+ hides it from the clipboard
 * preview and excludes it from clipboard history. Auto-cleared after [CLIPBOARD_CLEAR_MILLIS] and
 * on leaving the screen (see [clearClipboardIfHolds]).
 */
private fun copyRecoveryKeyToClipboard(context: Context, key: String) {
    val clip = ClipData.newPlainText("Hedera recovery key", key)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard(context).setPrimaryClip(clip)
}

/** Clears the clipboard only if it still holds [key], so a later unrelated copy is left intact. */
private fun clearClipboardIfHolds(context: Context, key: String) {
    val cm = clipboard(context)
    val current = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
    if (current == key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
