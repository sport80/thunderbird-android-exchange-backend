package com.fsck.k9.preferences

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class ServerTypeConverterTest {
    @Test
    fun `Exchange server type is exported using a stable file value`() {
        assertThat(ServerTypeConverter.fromServerSettingsType("exchange")).isEqualTo("EXCHANGE")
    }

    @Test
    fun `Exchange export value imports back to the DirectMail server type`() {
        assertThat(ServerTypeConverter.toServerSettingsType("EXCHANGE")).isEqualTo("exchange")
    }
}
