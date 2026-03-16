package org.multipaz.documenttype

/**
 * Describes a type of transaction data, including metadata for rendering fields in a consent UI.
 *
 * This is the transaction data equivalent of [DocumentType] — it allows the consent UI to
 * render transaction fields generically without hardcoding field names or display labels.
 *
 * @property type the transaction data type identifier (e.g., "org.multipaz.transaction_data.payment").
 * @property displayName a human-readable name for this transaction type.
 * @property fields the fields that this transaction data type contains.
 */
data class TransactionDataType(
    val type: String,
    val displayName: String,
    val fields: List<TransactionDataField>
) {
    /**
     * Looks up a field by its JSON path (e.g., "payee.name", "amount").
     *
     * @param path the dot-separated JSON path to the field.
     * @return the [TransactionDataField] or null if not found.
     */
    fun getField(path: String): TransactionDataField? =
        fields.find { it.path == path }

    /**
     * Builder for constructing [TransactionDataType] instances.
     *
     * @param type the transaction data type identifier.
     * @param displayName a human-readable name for this transaction type.
     */
    class Builder(
        private val type: String,
        private val displayName: String
    ) {
        private val fields = mutableListOf<TransactionDataField>()

        /**
         * Adds a field definition to this transaction data type.
         *
         * @param path the dot-separated JSON path to the field.
         * @param displayName a human-readable label for the field.
         * @param icon the icon to display next to the field.
         * @param userVisible whether the field should be shown in the consent UI.
         * @return this builder for chaining.
         */
        fun addField(
            path: String,
            displayName: String,
            icon: Icon,
            userVisible: Boolean = true
        ): Builder {
            fields.add(TransactionDataField(path, displayName, icon, userVisible))
            return this
        }

        /**
         * Builds the [TransactionDataType] from the accumulated fields.
         *
         * @return the constructed [TransactionDataType].
         */
        fun build(): TransactionDataType = TransactionDataType(type, displayName, fields)
    }
}

/**
 * A field in a transaction data type.
 *
 * @property path the JSON path to this field (e.g., "payee.name", "amount").
 * @property displayName a human-readable label for this field.
 * @property icon the icon to display next to this field.
 * @property userVisible whether this field should be shown to the user in the consent UI.
 */
data class TransactionDataField(
    val path: String,
    val displayName: String,
    val icon: Icon,
    val userVisible: Boolean = true
)
