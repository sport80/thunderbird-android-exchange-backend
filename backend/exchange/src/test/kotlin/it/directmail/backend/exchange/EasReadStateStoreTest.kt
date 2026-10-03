package it.directmail.backend.exchange

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class EasReadStateStoreTest {
    @Test
    fun `internet message id mapping ignores angle brackets and detects ambiguity`() {
        val storage = InMemoryBackendStorage()

        EasReadStateStore.recordInternetMessageId(storage, "<message@example.test>", "server-id-1")

        assertThat(
            EasReadStateStore.lookupByInternetMessageId(storage, "message@example.test"),
        ).isEqualTo("server-id-1")

        EasReadStateStore.recordInternetMessageId(storage, "message@example.test", "server-id-2")

        assertThat(
            EasReadStateStore.lookupByInternetMessageId(storage, "<message@example.test>"),
        ).isNull()
    }

    @Test
    fun `fingerprint normalizes fractional seconds subject whitespace and sender address`() {
        val first = EasReadStateStore.fingerprint(
            subject = "  Example   Subject ",
            dateReceived = "2026-09-22T10:41:07.987Z",
            from = "Example User <USER@example.test>",
        )
        val second = EasReadStateStore.fingerprint(
            subject = "example subject",
            dateReceived = "2026-09-22T10:41:07Z",
            from = "user@example.test",
        )

        assertThat(first).isEqualTo(second)
    }
}
