package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.TextBody
import net.thunderbird.core.common.mail.Flag
import org.junit.Test

class ExchangeSearchCompatibilityTest {
    @Test
    fun searchResultFlagsPreserveRemoteReadAndActionState() {
        val flags = searchFlagsForItem(
            EwsItem(
                serverId = "id",
                isRead = true,
                isFlagged = false,
                isAnswered = true,
                isForwarded = false,
            ),
        )

        assertThat(flags).contains(Flag.SEEN)
        assertThat(flags).contains(Flag.ANSWERED)
        assertThat(flags).doesNotContain(Flag.FLAGGED)
        assertThat(flags).doesNotContain(Flag.FORWARDED)
    }

    @Test
    fun compatibilitySearchMatchesMetadataWithoutFullText() {
        val message = MimeMessage().apply {
            subject = "Preventivo Progetto Alfa"
        }

        assertThat(messageMatchesCompatibilitySearch(message, "progetto", false)).isTrue()
        assertThat(messageMatchesCompatibilitySearch(message, "corpo", false)).isFalse()
    }

    @Test
    fun compatibilitySearchMatchesBodyWhenFullTextEnabled() {
        val message = MimeMessage().apply {
            MimeMessageHelper.setBody(this, TextBody("Testo speciale nel corpo"))
            setHeader("Content-Type", "text/plain; charset=utf-8")
        }

        assertThat(messageMatchesCompatibilitySearch(message, "speciale", true)).isTrue()
        assertThat(messageMatchesCompatibilitySearch(message, "speciale", false)).isFalse()
    }
}
