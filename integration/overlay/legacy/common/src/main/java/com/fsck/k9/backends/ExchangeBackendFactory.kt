package com.fsck.k9.backends

import android.content.Context
import com.fsck.k9.backend.api.Backend
import it.directmail.backend.exchange.ExchangeBackend
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.BackendStorageFactory
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountId

interface ExchangeBackendFactory : BackendFactory

class DefaultExchangeBackendFactory(
    private val context: Context,
    private val accountManager: LegacyAccountManager,
    private val backendStorageFactory: BackendStorageFactory,
) : ExchangeBackendFactory {
    override fun createBackend(accountId: AccountId): Backend {
        val account = accountManager.getAccount(accountId.toString())
            ?: error("Account not found: $accountId")

        return ExchangeBackend(
            backendStorage = backendStorageFactory.createBackendStorage(accountId),
            serverSettings = account.incomingServerSettings,
            diagnosticSink = { report ->
                runCatching {
                    val file = context.getFileStreamPath(EWS_SYNC_DIAGNOSTIC_FILE)
                    val now = System.currentTimeMillis()

                    if (report == null) {
                        // A new operation starts a new diagnostic session. Errors raised
                        // afterwards in the same operation will still be appended as a burst.
                        if (file.exists()) {
                            file.delete()
                        }
                    } else {
                        val keepPrevious =
                            file.exists() &&
                                now - file.lastModified() <= EWS_DIAGNOSTIC_BURST_MS
                        val rawPrevious = if (keepPrevious) file.readText() else ""
                        val previous = rawPrevious
                            .takeIf { it.contains("\nAt: ") }
                            .orEmpty()
                        val combined = buildString {
                            if (previous.isNotBlank()) {
                                append(previous.trimEnd())
                                append("\n\n========== NEXT EWS ERROR ==========\n\n")
                            }
                            append(report)
                        }
                        file.writeText(combined.takeLast(MAX_EWS_DIAGNOSTIC_CHARS))
                    }
                }
            },
        )
    }
}


private const val EWS_SYNC_DIAGNOSTIC_FILE = "directmail-last-ews-sync.txt"
private const val MAX_EWS_DIAGNOSTIC_CHARS = 64 * 1024
private const val EWS_DIAGNOSTIC_BURST_MS = 5 * 60 * 1000L
