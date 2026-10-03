package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Test

class EwsClientPartialMessageTest {
    init {
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
    }

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
    fun partialGetItemRequestsBodyAndAttachmentMetadataWithoutMimeContent() {
        val xml = invokeStringMethod(
            name = "partialGetItemRequestXml",
            parameterTypes = arrayOf(List::class.java),
            arguments = arrayOf(listOf("item-1", "item-2")),
        )

        assertThat(xml).contains("""<t:BodyType>Best</t:BodyType>""")
        assertThat(xml).contains("""FieldURI="item:Body"""")
        assertThat(xml).contains("""FieldURI="item:Attachments"""")
        assertThat(xml).contains("""FieldURI="message:InternetMessageId"""")
        assertThat(xml).doesNotContain("IncludeMimeContent")
    }

    @Test
    fun partialUsesOriginalHtmlMimeWhenStructuredBodyIsOnlyText() {
        val rawMime = """
            From: sender@example.invalid
            To: user@example.invalid
            Subject: Remote image
            Message-ID: <remote-image@example.invalid>
            MIME-Version: 1.0
            Content-Type: text/html; charset=utf-8

            <html><body>prima<img src="https://cdn.example.invalid/order.png">dopo</body></html>
        """.trimIndent().replace("\n", "\r\n")
        val element = messageElement(
            bodyType = "Text",
            body = "prima dopo",
            mimeContent = rawMime.toByteArray(Charsets.UTF_8),
        )

        val message = buildPartialMimeMessage(element, "server-html")
        val htmlPart = MimeUtility.findFirstPartByMimeType(message, "text/html")

        assertThat(htmlPart).isNotNull()
        val html = MessageExtractor.getTextFromPart(htmlPart!!)
        assertThat(html).contains("https://cdn.example.invalid/order.png")
        assertThat(html).contains("prima")
        assertThat(html).contains("dopo")
    }

    @Test
    fun partialPreservesRelatedCidTreeAndLeavesInlineImageOnDemand() {
        val rawMime = """
            From: sender@example.invalid
            To: user@example.invalid
            Subject: CID image
            Message-ID: <cid-image@example.invalid>
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="rel"

            --rel
            Content-Type: text/html; charset=utf-8

            <html><body>prima<img src="cid:inline-1">dopo</body></html>
            --rel
            Content-Type: image/png
            Content-Disposition: inline; filename="inline.png"
            Content-ID: <inline-1>
            Content-Transfer-Encoding: base64

            AQIDBA==
            --rel--
        """.trimIndent().replace("\n", "\r\n")
        val element = messageElement(
            bodyType = "Text",
            body = "prima dopo",
            mimeContent = rawMime.toByteArray(Charsets.UTF_8),
            attachmentXml = """
                <t:FileAttachment>
                  <t:AttachmentId Id="attachment-inline-1"/>
                  <t:Name>inline.png</t:Name>
                  <t:ContentType>image/png</t:ContentType>
                  <t:ContentId>inline-1</t:ContentId>
                  <t:Size>4</t:Size>
                  <t:IsInline>true</t:IsInline>
                </t:FileAttachment>
            """.trimIndent(),
        )

        val message = buildPartialMimeMessage(element, "server-cid")
        assertThat(message.isMimeType("multipart/related")).isTrue()

        val multipart = message.body as Multipart
        val htmlPart = multipart.getBodyPart(0)
        val imagePart = multipart.getBodyPart(1)

        assertThat(MessageExtractor.getTextFromPart(htmlPart)).contains("cid:inline-1")
        assertThat(imagePart.contentId).isEqualTo("inline-1")
        assertThat(imagePart.serverExtra).isEqualTo("attachment-inline-1")
        assertThat(imagePart.body).isNull()
    }

    @Test
    fun structuredFallbackBuildsRelatedTreeForInlineCid() {
        val element = messageElement(
            bodyType = "HTML",
            body = "<html><body>A<img src=\"cid:inline-2\">B</body></html>",
            mimeContent = null,
            attachmentXml = """
                <t:FileAttachment>
                  <t:AttachmentId Id="attachment-inline-2"/>
                  <t:Name>inline.jpg</t:Name>
                  <t:ContentType>image/jpeg</t:ContentType>
                  <t:ContentId>inline-2</t:ContentId>
                  <t:Size>123</t:Size>
                  <t:IsInline>true</t:IsInline>
                </t:FileAttachment>
            """.trimIndent(),
        )

        val message = buildPartialMimeMessage(element, "server-related")

        assertThat(message.isMimeType("multipart/related")).isTrue()
        val multipart = message.body as Multipart
        assertThat(multipart.getBodyPart(0).isMimeType("text/html")).isTrue()
        assertThat(multipart.getBodyPart(1).serverExtra).isEqualTo("attachment-inline-2")
        assertThat(multipart.getBodyPart(1).body).isNull()
    }

    @Test
    fun completeMimeKeepsOriginalStructureButRepairsStructuredUtf8Body() {
        val rawMime = """
            From: sender@example.invalid
            To: user@example.invalid
            Subject: UTF-8
            Message-ID: <utf8@example.invalid>
            MIME-Version: 1.0
            Content-Type: text/html; charset=utf-8

            <html><body>cittÃ </body></html>
        """.trimIndent().replace("\n", "\r\n")
        val element = messageElement(
            bodyType = "HTML",
            body = "<html><body>città</body></html>",
            mimeContent = rawMime.toByteArray(Charsets.UTF_8),
        )

        val message = buildCompleteMimeMessage(element, "server-utf8")
        val htmlPart = MimeUtility.findFirstPartByMimeType(message, "text/html")
        val html = MessageExtractor.getTextFromPart(htmlPart!!)

        assertThat(html).contains("città")
        assertThat(html).doesNotContain("cittÃ")
    }

    @Test
    fun completeRequestAsksForMimeAndStructuredBodyTogether() {
        val xml = invokeStringMethod(
            name = "completeGetItemRequestXml",
            parameterTypes = arrayOf(String::class.java),
            arguments = arrayOf("item-full"),
        )

        assertThat(xml).contains("<t:IncludeMimeContent>true</t:IncludeMimeContent>")
        assertThat(xml).contains("<t:BodyType>Best</t:BodyType>")
        assertThat(xml).contains("""FieldURI="item:Body"""")
        assertThat(xml).contains("""FieldURI="item:Attachments"""")
    }

    @Test
    fun getAttachmentRequestTargetsOnlyRequestedAttachment() {
        val xml = invokeStringMethod(
            name = "getAttachmentRequestXml",
            parameterTypes = arrayOf(String::class.java),
            arguments = arrayOf("attachment-123"),
        )

        assertThat(xml).contains("<m:GetAttachment>")
        assertThat(xml).contains("""<t:AttachmentId Id="attachment-123"/>""")
        assertThat(xml).doesNotContain("<m:GetItem>")
    }

    @Test
    fun attachmentPlaceholderUsesEwsAttachmentIdAsServerExtra() {
        val infoClass = Class.forName("it.directmail.backend.exchange.EwsAttachmentInfo")
        val constructor = infoClass.declaredConstructors.single()
        constructor.isAccessible = true
        val info = constructor.newInstance(
            "attachment-xyz",
            "photo.jpg",
            "image/jpeg",
            null,
            1234,
            false,
        )

        val method = EwsClient::class.java.getDeclaredMethod("createAttachmentPlaceholder", infoClass)
        method.isAccessible = true
        val part = method.invoke(client, info) as com.fsck.k9.mail.Part

        assertThat(part.serverExtra).isEqualTo("attachment-xyz")
        assertThat(part.body).isNull()
    }
    private fun buildPartialMimeMessage(element: Element, serverId: String): com.fsck.k9.mail.internet.MimeMessage {
        val method = EwsClient::class.java.getDeclaredMethod(
            "buildPartialMimeMessage",
            Element::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, element, serverId) as com.fsck.k9.mail.internet.MimeMessage
    }

    private fun buildCompleteMimeMessage(element: Element, serverId: String): com.fsck.k9.mail.internet.MimeMessage {
        val method = EwsClient::class.java.getDeclaredMethod(
            "buildCompleteMimeMessage",
            Element::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, element, serverId) as com.fsck.k9.mail.internet.MimeMessage
    }

    private fun messageElement(
        bodyType: String,
        body: String,
        mimeContent: ByteArray?,
        attachmentXml: String = "",
    ): Element {
        val encodedMime = mimeContent?.let { Base64.getMimeEncoder().encodeToString(it) }
        val mimeXml = encodedMime?.let { "<t:MimeContent>$it</t:MimeContent>" }.orEmpty()
        val xml = """
            <t:Message xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
              <t:ItemId Id="item-test"/>
              <t:Subject>Test</t:Subject>
              <t:Body BodyType="$bodyType"><![CDATA[$body]]></t:Body>
              <t:DateTimeReceived>2026-09-23T10:00:00Z</t:DateTimeReceived>
              <t:DateTimeSent>2026-09-23T09:59:00Z</t:DateTimeSent>
              <t:InternetMessageId>&lt;test@example.invalid&gt;</t:InternetMessageId>
              <t:Attachments>
                $attachmentXml
              </t:Attachments>
              $mimeXml
            </t:Message>
        """.trimIndent()

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            .documentElement
    }

    private fun invokeStringMethod(
        name: String,
        parameterTypes: Array<Class<*>>,
        arguments: Array<Any>,
    ): String {
        val method = EwsClient::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(client, *arguments) as String
    }
}
