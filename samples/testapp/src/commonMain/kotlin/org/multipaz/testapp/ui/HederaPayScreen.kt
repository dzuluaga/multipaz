package org.multipaz.testapp.ui

import androidx.compose.runtime.Composable
import org.multipaz.document.DocumentStore
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.SecureArea

/**
 * x402 Hedera payment demo: a self-contained screen that creates a hardware (TEE) Hedera account
 * credential, provisions a recoverable on-chain account, and settles a transfer via blocky402.
 * [secureArea] is the platform SecureArea the credential's key is created in (KeyMint 2.0+ on
 * Android). Android-only (the Hedera SDK is JVM/Android); other targets render an unsupported notice.
 */
@Composable
expect fun HederaPayScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
    documentStore: DocumentStore,
    secureArea: SecureArea,
)
