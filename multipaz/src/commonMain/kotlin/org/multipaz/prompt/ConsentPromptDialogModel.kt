package org.multipaz.prompt

import org.multipaz.document.Document
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.documenttype.TransactionDataTypeRepository
import org.multipaz.openid.TransactionData
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.trustmanagement.TrustPoint

class ConsentPromptDialogModel():
    PromptDialogModel<ConsentPromptDialogModel.ConsentPromptRequest, CredentialPresentmentSelection>() {
    override val dialogType: PromptDialogModel.DialogType<ConsentPromptDialogModel>
        get() = DialogType

    object DialogType : PromptDialogModel.DialogType<ConsentPromptDialogModel>

    class ConsentPromptRequest(
        val requester: Requester,
        val trustMetadata: TrustMetadata?,
        val credentialPresentmentData: CredentialPresentmentData,
        val preselectedDocuments: List<Document>,
        val onDocumentsInFocus: (documents: List<Document>) -> Unit,
        /** Transaction data to display in the consent prompt, keyed by credential query ID. */
        val transactionData: Map<String, List<TransactionData>>? = null,
        /** Repository for looking up transaction data field metadata (display names, icons). */
        val transactionDataTypeRepository: TransactionDataTypeRepository? = null
    )
}
