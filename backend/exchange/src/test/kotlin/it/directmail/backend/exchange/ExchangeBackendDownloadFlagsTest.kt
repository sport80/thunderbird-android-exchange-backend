package it.directmail.backend.exchange

import app.k9mail.backend.testing.InMemoryBackendStorage
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MimeMessage
import net.thunderbird.core.common.mail.Flag
import org.junit.Test

class ExchangeBackendDownloadFlagsTest {
    private val backend = ExchangeBackend(
        backendStorage = InMemoryBackendStorage(),
        serverSettings = ServerSettings(
            type = "exchange",
            host = "example.invalid",
            port = 443,
            connectionSecurity = ConnectionSecurity.SSL_TLS_REQUIRED,
            authenticationType = AuthType.PLAIN,
            username = "user",
            password = "password",
            clientCertificateAlias = null,
        ),
    )

    @Test
    fun preservesSeenAndFlaggedWhenReplacingDownloadedMessage() {
        val message = MimeMessage()
        applyPreservedDownloadFlags(message, setOf(Flag.SEEN, Flag.FLAGGED))

        assertThat(message.flags).contains(Flag.SEEN)
        assertThat(message.flags).contains(Flag.FLAGGED)
    }

    @Test
    fun internalDownloadFlagIsNotCarriedOver() {
        val message = MimeMessage()
        applyPreservedDownloadFlags(message, setOf(Flag.X_DOWNLOADED_PARTIAL))

        assertThat(message.flags).doesNotContain(Flag.X_DOWNLOADED_PARTIAL)
    }

    private fun applyPreservedDownloadFlags(message: Message, flags: Set<Flag>) {
        val method = ExchangeBackend::class.java.getDeclaredMethod(
            "applyPreservedDownloadFlags",
            Message::class.java,
            Set::class.java,
        )
        method.isAccessible = true
        method.invoke(backend, message, flags)
    }
}
