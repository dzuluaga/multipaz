package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.prompt.PromptModel

@Composable
actual fun HederaPayScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
) {
    Text(
        "The x402 Hedera payment demo is Android-only (the Hedera SDK is JVM/Android).",
        modifier = Modifier.padding(16.dp),
    )
}
