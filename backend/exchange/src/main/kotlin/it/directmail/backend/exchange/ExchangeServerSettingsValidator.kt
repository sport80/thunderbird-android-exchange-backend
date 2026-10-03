package it.directmail.backend.exchange

import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.AuthStateStorage
import com.fsck.k9.mail.server.ServerSettingsValidationResult
import com.fsck.k9.mail.server.ServerSettingsValidator
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

class ExchangeServerSettingsValidator : ServerSettingsValidator {
    override fun checkServerSettings(
        serverSettings: ServerSettings,
        authStateStorage: AuthStateStorage?,
    ): ServerSettingsValidationResult {
        if (serverSettings.connectionSecurity != ConnectionSecurity.SSL_TLS_REQUIRED) {
            return ServerSettingsValidationResult.ServerError("Exchange richiede HTTPS/TLS")
        }

        val password = serverSettings.password
            ?: return ServerSettingsValidationResult.AuthenticationError("Password mancante")

        val ewsPath = serverSettings.extra["ewsPath"]?.takeIf { it.startsWith("/") }
            ?: DEFAULT_EWS_PATH
        val portPart = if (serverSettings.port == 443) "" else ":${serverSettings.port}"
        val endpoint = URL("https://${serverSettings.host}$portPart$ewsPath")

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetFolder>
                  <m:FolderShape><t:BaseShape>IdOnly</t:BaseShape></m:FolderShape>
                  <m:FolderIds><t:DistinguishedFolderId Id="inbox"/></m:FolderIds>
                </m:GetFolder>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        return try {
            val connection = endpoint.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                connection.setRequestProperty("Accept", "text/xml")
                connection.setRequestProperty("User-Agent", "DirectMail/Thunderbird-Exchange")
                connection.setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                        "${serverSettings.username}:$password".toByteArray(StandardCharsets.UTF_8),
                    ),
                )

                connection.outputStream.use {
                    it.write(request.toByteArray(StandardCharsets.UTF_8))
                }

                val status = connection.responseCode
                val response = (
                    if (status >= 400) connection.errorStream else connection.inputStream
                    )?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()

                when {
                    status == HttpURLConnection.HTTP_UNAUTHORIZED ->
                        ServerSettingsValidationResult.AuthenticationError("EWS HTTP 401")
                    status !in 200..299 ->
                        ServerSettingsValidationResult.ServerError("EWS HTTP $status")
                    "NoError" in response ->
                        ServerSettingsValidationResult.Success
                    else ->
                        ServerSettingsValidationResult.ServerError("Risposta EWS non valida")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            ServerSettingsValidationResult.NetworkError(e)
        } catch (e: Exception) {
            ServerSettingsValidationResult.UnknownError(e)
        }
    }

    private companion object {
        const val DEFAULT_EWS_PATH = "/EWS/Exchange.asmx"
    }
}
