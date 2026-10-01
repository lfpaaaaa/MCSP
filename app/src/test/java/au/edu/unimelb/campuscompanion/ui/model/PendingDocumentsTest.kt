package au.edu.unimelb.campuscompanion.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingDocumentsTest {
    @Test
    fun `empty selection preserves existing documents`() {
        val existing = listOf(document(1))

        val result = mergePendingDocuments(existing, emptyList())

        assertEquals(existing, result.documents)
        assertEquals(0, result.rejectedTooLarge)
        assertEquals(0, result.rejectedByLimit)
    }

    @Test
    fun `new documents append without duplicating existing uri`() {
        val existing = listOf(document(1))

        val result = mergePendingDocuments(
            existing = existing,
            selected = listOf(document(1), document(2), document(3))
        )

        assertEquals(listOf("uri-1", "uri-2", "uri-3"), result.documents.map { it.uri })
    }

    @Test
    fun `selection is capped at nine documents`() {
        val result = mergePendingDocuments(
            existing = emptyList(),
            selected = (1..11).map(::document)
        )

        assertEquals(MAX_PENDING_DOCUMENTS, result.documents.size)
        assertEquals(2, result.rejectedByLimit)
    }

    @Test
    fun `documents larger than ten megabytes are rejected`() {
        val result = mergePendingDocuments(
            existing = emptyList(),
            selected = listOf(
                document(1, MAX_DOCUMENT_SIZE_BYTES),
                document(2, MAX_DOCUMENT_SIZE_BYTES + 1L)
            )
        )

        assertEquals(listOf("uri-1"), result.documents.map { it.uri })
        assertEquals(1, result.rejectedTooLarge)
    }

    @Test
    fun `documents with unknown size are rejected`() {
        val result = mergePendingDocuments(
            existing = emptyList(),
            selected = listOf(document(1, -1L))
        )

        assertEquals(emptyList<PendingDocument>(), result.documents)
        assertEquals(1, result.rejectedTooLarge)
    }

    private fun document(index: Int, sizeBytes: Long = 1_024L) = PendingDocument(
        uri = "uri-$index",
        displayName = "file-$index.pdf",
        sizeBytes = sizeBytes
    )
}
