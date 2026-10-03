package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MimeMessage
import java.time.Instant
import java.util.Date
import org.junit.Test

class EwsClientSentDateTest {
    private val client = EwsClient(
        ServerSettings(
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
    fun sentMetadataContainsClientSubmitTimeAndInternetMessageId() {
        val sentTime = "2026-09-22T10:41:07Z"
        val message = MimeMessage.create().apply {
            setMessageId("<sent-date@example.invalid>")
            setSentDate(Date.from(Instant.parse(sentTime)), false)
        }

        val xml = sentMetadataPropertiesXml(message, sentTime)

        assertThat(xml).contains(
            "<t:ExtendedFieldURI PropertyTag=\"0x0039\" PropertyType=\"SystemTime\"/>",
        )
        assertThat(xml).contains("<t:Value>$sentTime</t:Value>")
        assertThat(xml).contains(
            "<t:ExtendedFieldURI PropertyTag=\"0x1035\" PropertyType=\"String\"/>",
        )
        assertThat(xml).contains("&lt;sent-date@example.invalid&gt;")
        assertThat(xml).doesNotContain("<t:DateTimeSent>")
        assertThat(xml).doesNotContain("<t:InternetMessageHeaders>")
    }

    @Test
    fun sentMetadataOmitsInternetMessageIdWhenUnavailable() {
        val sentTime = "2026-09-22T10:41:07Z"
        val message = MimeMessage.create()

        val xml = sentMetadataPropertiesXml(message, sentTime)

        assertThat(xml).contains("""PropertyTag="0x0039"""")
        assertThat(xml).doesNotContain("""PropertyTag="0x1035"""")
    }

    private fun sentMetadataPropertiesXml(message: Message, sentTime: String): String {
        val method = EwsClient::class.java.getDeclaredMethod(
            "sentMetadataPropertiesXml",
            Message::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, message, sentTime) as String
    }
}
