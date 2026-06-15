package org.multipaz.testapp.ui

import androidx.compose.runtime.Composable
import org.multipaz.document.DocumentStore
import org.multipaz.prompt.PromptModel

/**
 * x402 Hedera payment demo: pay from the persistent hardware (TEE) Hedera account credential
 * (provisioned via "Create Test Documents in Platform Secure Area"), settled via blocky402.
 * Android-only (the Hedera SDK is JVM/Android); other targets render an unsupported notice.
 */
@Composable
expect fun HederaPayScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
    documentStore: DocumentStore,
)
