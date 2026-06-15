package org.multipaz.testapp.ui

import androidx.compose.runtime.Composable
import org.multipaz.prompt.PromptModel

/**
 * x402 Hedera payment demo: pay from a hardware (TEE) credential, settled via blocky402.
 * Android-only (the Hedera SDK is JVM/Android); other targets render an unsupported notice.
 */
@Composable
expect fun HederaPayScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
)
