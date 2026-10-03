package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MimeBodyPart
import com.fsck.k9.mail.internet.MimeHeader
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.MimeMultipart
import com.fsck.k9.mail.internet.TextBody
import com.fsck.k9.mailstore.BinaryMemoryBody
import java.time.Instant
import java.util.Base64
import java.util.Date
import org.junit.Test

class EwsClientSendStructureTest {
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
    fun mimeUploadPreservesQuotedReplyAndPhotoAttachment() {
        val photo = MimeBodyPart(
            BinaryMemoryBody(byteArrayOf(0x01, 0x02, 0x03, 0x04), "base64"),
            "image/jpeg",
        ).apply {
            setHeader(
                MimeHeader.HEADER_CONTENT_DISPOSITION,
                "attachment; filename=\"photo.jpg\"",
            )
        }
        val multipart = MimeMultipart.newInstance().apply {
            addBodyPart(
                MimeBodyPart.create(
                    TextBody("Nuova risposta\r\n\r\n> testo originale citato"),
                    "text/plain",
                ),
            )
            addBodyPart(photo)
        }
        val message = MimeMessage.create().apply {
            setMessageId("<reply@example.invalid>")
            setHeader("X-DirectMail-Compose-Action", "reply")
            setHeader("X-DirectMail-Composed-Offset", "0")
            setHeader("X-DirectMail-Composed-Length", "14")
            MimeMessageHelper.setBody(this, multipart)
        }

        val raw = serializeMime(message).toString(Charsets.UTF_8)

        assertThat(raw).contains("testo originale citato")
        assertThat(raw).contains("photo.jpg")
        assertThat(raw).contains("image/jpeg")
        assertThat(raw).doesNotContain("X-DirectMail-Compose-Action")
        assertThat(raw).doesNotContain("X-DirectMail-Composed-Offset")
    }

    @Test
    fun mimeUploadPreservesForwardAsRfc822Attachment() {
        val embedded = MimeMessage.create().apply {
            setMessageId("<embedded@example.invalid>")
            subject = "Messaggio originale"
            MimeMessageHelper.setBody(this, TextBody("contenuto originale"))
        }
        val rfc822 = MimeBodyPart(embedded, "message/rfc822").apply {
            setHeader(
                MimeHeader.HEADER_CONTENT_DISPOSITION,
                "attachment; filename=\"Messaggio originale.eml\"",
            )
        }
        val multipart = MimeMultipart.newInstance().apply {
            addBodyPart(MimeBodyPart.create(TextBody("Inoltro"), "text/plain"))
            addBodyPart(rfc822)
        }
        val message = MimeMessage.create().apply {
            setMessageId("<forward@example.invalid>")
            MimeMessageHelper.setBody(this, multipart)
        }

        val raw = serializeMime(message).toString(Charsets.UTF_8)

        assertThat(raw).contains("message/rfc822")
        assertThat(raw).contains("Messaggio originale.eml")
        assertThat(raw).contains("contenuto originale")
    }

    @Test
    fun createItemUsesMimeContentAndTimestampMetadata() {
        val sentAt = Instant.parse("2026-09-23T09:15:00Z")
        val message = MimeMessage.create().apply {
            setMessageId("<directmail-test@example.invalid>")
            setSentDate(Date.from(sentAt), false)
            MimeMessageHelper.setBody(this, TextBody("body"))
        }
        val raw = serializeMime(message)

        val xml = createMimeItemXml(
            folderServerId = "drafts",
            message = message,
            sentTime = sentAt.toString(),
            mimeMessage = raw,
        )

        assertThat(xml).contains("<t:MimeContent CharacterSet=\"UTF-8\">")
        assertThat(xml).contains("""PropertyTag="0x0039"""")
        assertThat(xml).contains(sentAt.toString())
        assertThat(xml).contains("""PropertyTag="0x1035"""")
        assertThat(xml).contains("&lt;directmail-test@example.invalid&gt;")
        assertThat(xml).doesNotContain("<t:Body ")
    }

    @Test
    fun createItemMimePayloadRoundTripsByteForByte() {
        val embedded = MimeMessage.create().apply {
            setMessageId("<embedded-roundtrip@example.invalid>")
            subject = "Allegato inoltrato"
            MimeMessageHelper.setBody(this, TextBody("contenuto è completo"))
        }
        val photo = MimeBodyPart(
            BinaryMemoryBody(byteArrayOf(0x10, 0x20, 0x30, 0x40), "base64"),
            "image/jpeg",
        ).apply {
            setHeader(
                MimeHeader.HEADER_CONTENT_DISPOSITION,
                "attachment; filename=\"foto prova.jpg\"",
            )
        }
        val rfc822 = MimeBodyPart(embedded, "message/rfc822").apply {
            setHeader(
                MimeHeader.HEADER_CONTENT_DISPOSITION,
                "attachment; filename=\"inoltrata.eml\"",
            )
        }
        val multipart = MimeMultipart.newInstance().apply {
            addBodyPart(MimeBodyPart.create(TextBody("Corpo è UTF-8\r\n> citazione"), "text/plain"))
            addBodyPart(photo)
            addBodyPart(rfc822)
        }
        val message = MimeMessage.create().apply {
            setMessageId("<roundtrip@example.invalid>")
            MimeMessageHelper.setBody(this, multipart)
        }
        val raw = serializeMime(message)

        val xml = createMimeItemXml(
            folderServerId = "drafts",
            message = message,
            sentTime = "2026-09-23T10:00:00Z",
            mimeMessage = raw,
        )
        val encoded = Regex(
            """<t:MimeContent CharacterSet="UTF-8">([A-Za-z0-9+/=]+)</t:MimeContent>""",
        ).find(xml)?.groupValues?.get(1)
            ?: error("MimeContent base64 assente")

        val decoded = Base64.getDecoder().decode(encoded)

        assertThat(decoded.contentEquals(raw)).isTrue()
        assertThat(xml).contains("""<m:CreateItem MessageDisposition="SaveOnly">""")
        assertThat(xml).contains("""<t:DistinguishedFolderId Id="drafts"/>""")
        assertThat(xml).doesNotContain("<t:Attachments>")
        assertThat(xml).doesNotContain("<m:CreateAttachment>")
    }

    @Test
    fun mimeSerializationStripsLocalHeadersOnlyFromUploadedPayload() {
        val message = MimeMessage.create().apply {
            setMessageId("<local-header@example.invalid>")
            setHeader("X-DirectMail-Compose-Action", "reply")
            setHeader("X-DirectMail-Composed-Offset", "12")
            setHeader("X-DirectMail-Composed-Length", "34")
            MimeMessageHelper.setBody(this, TextBody("reply\r\n> quoted text"))
        }

        val raw = serializeMime(message).toString(Charsets.UTF_8)

        assertThat(raw).doesNotContain("X-DirectMail-Compose-Action")
        assertThat(raw).doesNotContain("X-DirectMail-Composed-Offset")
        assertThat(raw).doesNotContain("X-DirectMail-Composed-Length")
        assertThat(message.getHeader("X-DirectMail-Compose-Action").toList())
            .isEqualTo(listOf("reply"))
        assertThat(message.getHeader("X-DirectMail-Composed-Offset").toList())
            .isEqualTo(listOf("12"))
        assertThat(message.getHeader("X-DirectMail-Composed-Length").toList())
            .isEqualTo(listOf("34"))
    }

    @Test
    fun sendItemUsesStandardSentFolderParameters() {
        val xml = sendItemRequestXml("""<t:ItemId Id="item" ChangeKey="ck"/>""")

        assertThat(xml).contains("""<m:SendItem SaveItemToFolder="true">""")
        assertThat(xml).contains("""<t:DistinguishedFolderId Id="sentitems"/>""")
        assertThat(xml).doesNotContain("""SaveItemToFolder="false"""")
        assertThat(xml).doesNotContain("<m:CreateItem")
        assertThat(xml).doesNotContain("<m:CreateAttachment")
    }

    private fun serializeMime(message: MimeMessage): ByteArray {
        val method = EwsClient::class.java.getDeclaredMethod(
            "serializeMessageForMimeUpload",
            com.fsck.k9.mail.Message::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, message) as ByteArray
    }

    private fun createMimeItemXml(
        folderServerId: String,
        message: MimeMessage,
        sentTime: String,
        mimeMessage: ByteArray,
    ): String {
        val method = EwsClient::class.java.getDeclaredMethod(
            "createMimeItemRequestXml",
            String::class.java,
            com.fsck.k9.mail.Message::class.java,
            String::class.java,
            ByteArray::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, folderServerId, message, sentTime, mimeMessage) as String
    }

    private fun sendItemRequestXml(itemIdXml: String): String {
        val method = EwsClient::class.java.getDeclaredMethod(
            "sendItemRequestXml",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, itemIdXml) as String
    }
}
