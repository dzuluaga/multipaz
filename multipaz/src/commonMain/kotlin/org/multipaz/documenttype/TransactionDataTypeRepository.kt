package org.multipaz.documenttype

/**
 * A registry of known transaction data types.
 *
 * This is the transaction data equivalent of [DocumentTypeRepository]. It allows the consent
 * UI to look up field metadata (display names, icons) for any registered transaction data type.
 */
class TransactionDataTypeRepository {
    private val _types: MutableList<TransactionDataType> = mutableListOf()

    /** The list of registered transaction data types. */
    val types: List<TransactionDataType>
        get() = _types

    /**
     * Registers a transaction data type in the repository.
     *
     * @param type the [TransactionDataType] to add.
     */
    fun addType(type: TransactionDataType) {
        _types.add(type)
    }

    /**
     * Looks up a transaction data type by its identifier.
     *
     * @param typeIdentifier the type identifier string to search for.
     * @return the matching [TransactionDataType], or null if not found.
     */
    fun getType(typeIdentifier: String): TransactionDataType? =
        _types.find { it.type == typeIdentifier }
}
