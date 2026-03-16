package org.multipaz.testapp.ui

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.openid.TransactionData
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata

actual suspend fun launchAndroidPresentmentActivity(
    source: PresentmentSource,
    paData: AndroidPresentmentActivityData,
    requester: Requester,
    trustMetadata: TrustMetadata?,
    credentialPresentmentData: CredentialPresentmentData,
    preselectedDocuments: List<Document>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    transactionData: Map<String, List<TransactionData>>?
): CredentialPresentmentSelection? {
    throw IllegalStateException("Not implemented on this OS")
}
