package au.edu.unimelb.campuscompanion.ui.model

internal const val MAX_PENDING_DOCUMENTS = 9
internal const val MAX_DOCUMENT_SIZE_BYTES = 10L * 1024L * 1024L

data class PendingDocument(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long
)

internal data class PendingDocumentMergeResult(
    val documents: List<PendingDocument>,
    val rejectedTooLarge: Int,
    val rejectedByLimit: Int
)

internal fun mergePendingDocuments(
    existing: List<PendingDocument>,
    selected: List<PendingDocument>
): PendingDocumentMergeResult {
    val existingUris = existing.mapTo(mutableSetOf(), PendingDocument::uri)
    val uniqueSelected = selected.filter { existingUris.add(it.uri) }
    val (acceptedBySize, rejectedBySize) = uniqueSelected.partition { document ->
        document.sizeBytes in 0L..MAX_DOCUMENT_SIZE_BYTES
    }
    val availableSlots = (MAX_PENDING_DOCUMENTS - existing.size).coerceAtLeast(0)
    val accepted = acceptedBySize.take(availableSlots)

    return PendingDocumentMergeResult(
        documents = existing + accepted,
        rejectedTooLarge = rejectedBySize.size,
        rejectedByLimit = acceptedBySize.size - accepted.size
    )
}
