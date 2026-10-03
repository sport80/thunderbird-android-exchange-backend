package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import org.junit.Test

class EwsClientMessageActionTest {
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
    fun answeredUpdateUsesReplyProperties() {
        val xml = actionXml("ANSWERED", true)
        assertThat(xml).contains("""PropertyTag="0x1080"""")
        assertThat(xml).contains("<t:Value>261</t:Value>")
        assertThat(xml).contains("""PropertyTag="0x1081"""")
        assertThat(xml).contains("<t:Value>102</t:Value>")
        assertThat(xml).contains("""PropertyTag="0x1082"""")
    }

    @Test
    fun forwardedUpdateUsesForwardProperties() {
        val xml = actionXml("FORWARDED", true)
        assertThat(xml).contains("<t:Value>262</t:Value>")
        assertThat(xml).contains("<t:Value>104</t:Value>")
    }

    @Test
    fun clearingActionResetsIconAndLastVerb() {
        val xml = actionXml("ANSWERED", false)
        assertThat(xml).contains("<t:Value>0</t:Value>")
    }

    private fun actionXml(actionName: String, enabled: Boolean): String {
        val actionClass = Class.forName("it.directmail.backend.exchange.EwsClient\$MessageAction")
        val action = actionClass.enumConstants.first { (it as Enum<*>).name == actionName }
        val method = EwsClient::class.java.getDeclaredMethod(
            "messageActionUpdateFieldsXml",
            actionClass,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(client, action, enabled) as String
    }
}
