package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import org.junit.Test

class EwsClientSearchTest {
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
    fun searchFindItemUsesProvenUnrestrictedPagingShape() {
        val xml = searchFindItemRequestXml(
            folderServerId = "folder-123",
            maxEntries = 250,
            offset = 500,
        )

        assertThat(xml).contains("""<m:FindItem Traversal="Shallow">""")
        assertThat(xml).doesNotContain("<m:Restriction>")
        assertThat(xml).doesNotContain("<t:Contains")
        assertThat(xml).contains("""FieldURI="item:Subject"""")
        assertThat(xml).contains("""FieldURI="message:From"""")
        assertThat(xml).contains("""FieldURI="item:DisplayTo"""")
        assertThat(xml).contains("""FieldURI="item:DisplayCc"""")
        assertThat(xml).contains("""FieldURI="item:DisplayBcc"""")
        assertThat(xml).contains("""FieldURI="message:IsRead"""")
        assertThat(xml).contains("""FieldURI="item:Flag"""")
        assertThat(xml).contains("""MaxEntriesReturned="250"""")
        assertThat(xml).contains("""Offset="500"""")
    }

    @Test
    fun metadataSearchMatchesSubjectSenderAndRecipientsCaseInsensitively() {
        val item = EwsSearchItem(
            serverId = "id",
            subject = "Preventivo Progetto Alfa",
            from = "Mario Rossi mario@example.invalid",
            displayTo = "Alice Bianchi",
            displayCc = "Contabilita",
            displayBcc = null,
            isRead = false,
            isFlagged = false,
        )

        assertThat(searchMetadataMatches(item, "progetto")).isEqualTo(true)
        assertThat(searchMetadataMatches(item, "MARIO".lowercase())).isEqualTo(true)
        assertThat(searchMetadataMatches(item, "bianchi")).isEqualTo(true)
        assertThat(searchMetadataMatches(item, "contabilita")).isEqualTo(true)
        assertThat(searchMetadataMatches(item, "inesistente")).isEqualTo(false)
    }

    private fun searchFindItemRequestXml(folderServerId: String, maxEntries: Int, offset: Int): String {
        val method = EwsClient::class.java.getDeclaredMethod(
            "searchFindItemRequestXml",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(client, folderServerId, maxEntries, offset) as String
    }

    private fun searchMetadataMatches(item: EwsSearchItem, normalizedQuery: String): Boolean {
        val method = EwsClient::class.java.getDeclaredMethod(
            "searchMetadataMatches",
            EwsSearchItem::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(client, item, normalizedQuery) as Boolean
    }
}
