package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import org.junit.Test

class EwsClientFlaggedTest {
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
    fun `flagged update writes EWS flag and MAPI flag status`() {
        val xml = flagUpdateFieldsXml(isFlagged = true)

        assertThat(xml).contains("""<t:FieldURI FieldURI="item:Flag"/>""")
        assertThat(xml).contains("<t:FlagStatus>Flagged</t:FlagStatus>")
        assertThat(xml).contains(
            """<t:ExtendedFieldURI PropertyTag="0x1090" PropertyType="Integer"/>""",
        )
        assertThat(xml).contains("<t:Value>2</t:Value>")
        assertThat(xml).contains(
            """<t:ExtendedFieldURI PropertyTag="0x1095" PropertyType="Integer"/>""",
        )
        assertThat(xml).contains("<t:Value>6</t:Value>")
        assertThat(xml).contains(
            """DistinguishedPropertySetId="Common" PropertyId="34096" PropertyType="String"""",
        )
        assertThat(xml).contains("<t:Value>Follow up</t:Value>")
        assertThat(xml).contains(
            """DistinguishedPropertySetId="Task" PropertyId="33025" PropertyType="Integer"""",
        )
        assertThat(xml).contains(
            """DistinguishedPropertySetId="Task" PropertyId="33052" PropertyType="Boolean"""",
        )
        assertThat(xml).doesNotContain("<t:FlagStatus>NotFlagged</t:FlagStatus>")
    }

    @Test
    fun `unflagged update clears EWS and MAPI flag status`() {
        val xml = flagUpdateFieldsXml(isFlagged = false)

        assertThat(xml).contains("""<t:FieldURI FieldURI="item:Flag"/>""")
        assertThat(xml).contains("<t:FlagStatus>NotFlagged</t:FlagStatus>")
        assertThat(xml).contains(
            """<t:ExtendedFieldURI PropertyTag="0x1090" PropertyType="Integer"/>""",
        )
        assertThat(xml).contains("<t:Value>0</t:Value>")
        assertThat(xml).contains(
            """<t:ExtendedFieldURI PropertyTag="0x1095" PropertyType="Integer"/>""",
        )
        assertThat(xml).contains(
            """DistinguishedPropertySetId="Common" PropertyId="34096" PropertyType="String"""",
        )
        assertThat(xml).doesNotContain("<t:Value>Follow up</t:Value>")
        assertThat(xml).doesNotContain("<t:FlagStatus>Flagged</t:FlagStatus>")
    }

    private fun flagUpdateFieldsXml(isFlagged: Boolean): String {
        val method = EwsClient::class.java.getDeclaredMethod(
            "flagUpdateFieldsXml",
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(client, isFlagged) as String
    }
}
