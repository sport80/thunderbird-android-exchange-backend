package it.directmail.backend.exchange

import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.mail.ServerSettings
import java.io.IOException

internal class ExchangeBackendPusher(
    private val backendStorage: BackendStorage,
    private val serverSettings: ServerSettings,
    private val callback: BackendPusherCallback,
    private val diagnosticSink: (String?) -> Unit = {},
) : BackendPusher {
    private val easClient = EasClient(serverSettings)
    private val lock = Object()
    private val callbackLock = Object()

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var subfolderWorker: Thread? = null

    @Volatile
    private var pushFolderServerIds: Set<String> = emptySet()

    override fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            EasSyncStateLock.registerPushClient(easClient)
            worker = Thread(::runLoop, "DirectMail-EAS-Ping-${serverSettings.host}").also {
                it.isDaemon = true
                it.start()
            }
            subfolderWorker = Thread(
                ::runSubfolderReconciliationLoop,
                "DirectMail-EWS-Reconcile-${serverSettings.host}",
            ).also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    override fun updateFolders(folderServerIds: Collection<String>) {
        pushFolderServerIds = folderServerIds.toSet()
        synchronized(lock) {
            lock.notifyAll()
        }

        if (pushFolderServerIds.isEmpty()) {
            // No folder needs push/reconciliation anymore: abort the long poll.
            easClient.cancelPendingRequest()
        }
    }

    override fun stop() {
        running = false
        easClient.cancelPendingRequest()
        EasSyncStateLock.unregisterPushClient(easClient)
        synchronized(lock) {
            lock.notifyAll()
        }
        worker?.interrupt()
        subfolderWorker?.interrupt()
        worker = null
        subfolderWorker = null
    }

    override fun reconnect() {
        if (!running) return

        // PushController invokes this when connectivity changes. Abort the old long poll;
        // the worker keeps persisted EAS state and opens a new Ping immediately.
        easClient.cancelPendingRequest()
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    private fun runLoop() {
        var backoffMs = MIN_BACKOFF_MS
        var heartbeatSeconds = DEFAULT_HEARTBEAT_SECONDS
        var baselineSyncDone = false

        while (running) {
            if (pushFolderServerIds.isEmpty()) {
                waitForConfiguration()
                continue
            }

            try {
                val policyKey = ensurePolicyKey()
                val inboxId = ensureInboxId(policyKey)
                ensureReadMappingBootstrap(policyKey, inboxId)
                ensureMailSyncKey(policyKey, inboxId)

                if (!baselineSyncDone) {
                    // Prime Thunderbird's per-folder lastChecked state before the first
                    // long-poll. Initial account population is still silenced by the stock
                    // controller; later pushed messages can then generate notifications.
                    dispatchPushEvent(INBOX_SERVER_ID)
                    baselineSyncDone = true
                }

                diagnosticSink(null)
                val ping = try {
                    synchronized(EasSyncStateLock.monitor) {
                        easClient.ping(
                            policyKey = policyKey,
                            inboxId = inboxId,
                            heartbeatSeconds = heartbeatSeconds,
                        )
                    }
                } catch (e: EasRequestCancelledException) {
                    throw e
                } catch (_: IOException) {
                    // A long-poll can be aborted by the network before Gromox returns a
                    // Ping status. Treat transport failures as transient and keep the
                    // independent EWS subfolder reconciliation worker running.
                    waitBackoff(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }

                backoffMs = MIN_BACKOFF_MS

                when (ping.status) {
                    PING_STATUS_EXPIRED -> {
                        // Preserve the proven Inbox heartbeat reconciliation.
                        dispatchPushEvent(INBOX_SERVER_ID)
                    }

                    PING_STATUS_CHANGES -> {
                        if (ping.changedFolderIds.isEmpty()) {
                            // Some EAS servers return status=2 without echoing folder IDs.
                            // Treat it as an Inbox wake-up rather than dropping the event.
                            dispatchPushEvent(INBOX_SERVER_ID)
                        } else if (inboxId in ping.changedFolderIds) {
                            // Deliver the event before EAS acknowledgement. A stale EAS SyncKey
                            // must never prevent the authoritative EWS delta sync/notification.
                            dispatchPushEvent(INBOX_SERVER_ID)

                            runCatching {
                                acknowledgeInboxChanges(
                                    policyKey = policyKey,
                                    inboxId = inboxId,
                                )
                            }.onFailure { error ->
                                if (isInvalidMailSyncKey(error)) {
                                    backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, "")
                                }
                            }
                        }
                    }

                    PING_STATUS_HEARTBEAT_OUT_OF_RANGE -> {
                        heartbeatSeconds = ping.suggestedHeartbeatSeconds
                            ?.coerceIn(MIN_HEARTBEAT_SECONDS, MAX_HEARTBEAT_SECONDS)
                            ?: DEFAULT_HEARTBEAT_SECONDS
                    }

                    PING_STATUS_FOLDER_HIERARCHY_CHANGED -> {
                        clearFolderState()
                    }

                    PING_STATUS_SERVER_ERROR -> {
                        error("EAS Ping: errore server transitorio (status=8)")
                    }

                    else -> {
                        error("EAS Ping status=${ping.status}")
                    }
                }

            } catch (e: EasRequestCancelledException) {
                if (!running) break
                // Expected when connectivity changes or push is reconfigured.
                // Reopen the long poll immediately without surfacing a diagnostic.
                continue
            } catch (e: Exception) {
                if (!running) break

                if (e is EasHttpException && e.statusCode == HTTP_PROVISION_REQUIRED) {
                    clearProvisionState()
                }
                if (isInvalidMailSyncKey(e)) {
                    backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, "")
                }

                diagnosticSink(
                    buildString {
                        appendLine("DirectMail EAS push diagnostic")
                        appendLine("Host: ${serverSettings.host}")
                        appendLine("Stage: Ping")
                        appendLine("Details: ${e.message ?: e::class.simpleName}")
                        appendLine()
                        append(e.stackTraceToString())
                    },
                )

                if (isUserVisibleFailure(e)) {
                    callback.onPushError(e)
                }

                waitBackoff(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun runSubfolderReconciliationLoop() {
        var nextReconciliationAt = 0L

        while (running) {
            val subfolders = pushFolderServerIds
                .filterNot { it == INBOX_SERVER_ID }
                .toList()

            if (subfolders.isEmpty()) {
                nextReconciliationAt = 0L
                waitForSubfolderReconciliation(SUBFOLDER_RECONCILIATION_INTERVAL_MS)
                continue
            }

            val now = System.currentTimeMillis()
            if (nextReconciliationAt == 0L) {
                nextReconciliationAt = now + SUBFOLDER_RECONCILIATION_INTERVAL_MS
            }

            val delayMs = nextReconciliationAt - now
            if (delayMs > 0L) {
                waitForSubfolderReconciliation(delayMs)
                continue
            }

            subfolders.forEach { folderServerId ->
                if (!running) return
                if (folderServerId in pushFolderServerIds) {
                    dispatchPushEvent(folderServerId)
                }
            }

            nextReconciliationAt = System.currentTimeMillis() + SUBFOLDER_RECONCILIATION_INTERVAL_MS
        }
    }

    private fun dispatchPushEvent(folderServerId: String) {
        synchronized(callbackLock) {
            if (running) {
                callback.onPushEvent(folderServerId)
            }
        }
    }

    private fun ensurePolicyKey(): String {
        backendStorage.getExtraString(EasReadStateStore.POLICY_KEY)?.takeIf { it.isNotBlank() }?.let { return it }

        val policyKey = easClient.provision()
        backendStorage.setExtraString(EasReadStateStore.POLICY_KEY, policyKey)
        return policyKey
    }

    private fun ensureMailSyncKey(
        policyKey: String,
        inboxId: String,
    ): String = synchronized(EasSyncStateLock.monitor) {
        backendStorage.getExtraString(EasReadStateStore.MAIL_SYNC_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { return@synchronized it }

        // Mirror the old DirectMail MVP sequence that was validated against this server:
        // initial SyncKey=0 request without GetChanges, then persist the returned key.
        val initial = easClient.sync(
            policyKey = policyKey,
            inboxId = inboxId,
            syncKey = "0",
            getChanges = false,
        )
        backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, initial.syncKey)
        initial.syncKey
    }

    private fun ensureReadMappingBootstrap(
        policyKey: String,
        inboxId: String,
    ) {
        if (!EasReadStateStore.needsBootstrap(backendStorage)) return

        synchronized(EasSyncStateLock.monitor) {
            if (!EasReadStateStore.needsBootstrap(backendStorage)) return@synchronized

            EasReadStateStore.beginBootstrap(backendStorage)
            var syncKey = ensureMailSyncKey(policyKey, inboxId)
            var pageCount = 0
            var moreAvailable: Boolean

            do {
                if (pageCount++ >= MAX_EAS_MAPPING_PAGES) {
                    // Keep the mappings collected so far. They cover the newest portion of
                    // normal mailboxes and are safer than repeatedly restarting a huge full sync.
                    EasReadStateStore.finishBootstrap(backendStorage)
                    return@synchronized
                }

                val result = easClient.sync(
                    policyKey = policyKey,
                    inboxId = inboxId,
                    syncKey = syncKey,
                    getChanges = true,
                    windowSize = EAS_MAPPING_WINDOW_SIZE,
                )
                EasReadStateStore.record(backendStorage, result.items)
                syncKey = result.syncKey
                backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, syncKey)
                moreAvailable = result.moreAvailable
            } while (moreAvailable)

            EasReadStateStore.finishBootstrap(backendStorage)
        }
    }

    private fun acknowledgeInboxChanges(
        policyKey: String,
        inboxId: String,
    ) = synchronized(EasSyncStateLock.monitor) {
        var syncKey = ensureMailSyncKey(policyKey, inboxId)
        var pageCount = 0
        var moreAvailable: Boolean

        do {
            if (pageCount++ >= MAX_EAS_SYNC_PAGES) {
                error("EAS Sync: troppe pagine durante acknowledge")
            }

            val result = easClient.sync(
                policyKey = policyKey,
                inboxId = inboxId,
                syncKey = syncKey,
                getChanges = true,
                windowSize = EAS_SYNC_WINDOW_SIZE,
            )
            EasReadStateStore.record(backendStorage, result.items)
            syncKey = result.syncKey
            backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, syncKey)
            moreAvailable = result.moreAvailable
        } while (moreAvailable)
    }

    private fun ensureInboxId(policyKey: String): String {
        backendStorage.getExtraString(EasReadStateStore.INBOX_ID)?.takeIf { it.isNotBlank() }?.let { return it }

        // If Inbox ID is missing, start from SyncKey=0. A non-zero incremental FolderSync
        // isn't guaranteed to repeat an unchanged Inbox entry.
        val result = easClient.folderSync(
            policyKey = policyKey,
            syncKey = "0",
        )
        backendStorage.setExtraString(EasReadStateStore.FOLDER_SYNC_KEY, result.syncKey)
        backendStorage.setExtraString(EasReadStateStore.INBOX_ID, result.inboxId)
        return result.inboxId
    }

    private fun clearProvisionState() {
        backendStorage.setExtraString(EasReadStateStore.POLICY_KEY, "")
        clearFolderState()
    }

    private fun clearFolderState() {
        backendStorage.setExtraString(EasReadStateStore.FOLDER_SYNC_KEY, "")
        backendStorage.setExtraString(EasReadStateStore.INBOX_ID, "")
        backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, "")
    }

    private fun isInvalidMailSyncKey(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if ("Sync collection status=3" in current.message.orEmpty()) return true
            current = current.cause
        }
        return false
    }

    private fun isUserVisibleFailure(exception: Exception): Boolean {
        return when (exception) {
            is EasHttpException -> exception.statusCode == 401 || exception.statusCode == 403
            is IOException -> false
            else -> false
        }
    }

    private fun waitForConfiguration() {
        synchronized(lock) {
            if (running && pushFolderServerIds.isEmpty()) {
                try {
                    lock.wait(CONFIGURATION_RECHECK_MS)
                } catch (_: InterruptedException) {
                    // Re-evaluate running/folder state immediately.
                }
            }
        }
    }

    private fun waitBackoff(delayMs: Long) {
        synchronized(lock) {
            if (!running) return
            try {
                lock.wait(delayMs)
            } catch (_: InterruptedException) {
                // Connectivity reconnect/stop should wake the loop immediately.
            }
        }
    }

    private fun waitForSubfolderReconciliation(delayMs: Long) {
        synchronized(lock) {
            if (!running) return
            try {
                lock.wait(delayMs.coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                // Folder updates/reconnect/stop should re-evaluate the deadline immediately.
            }
        }
    }

    private companion object {
        const val INBOX_SERVER_ID = "inbox"

        const val DEFAULT_HEARTBEAT_SECONDS = 120
        const val MIN_HEARTBEAT_SECONDS = 60
        const val MAX_HEARTBEAT_SECONDS = 900

        const val PING_STATUS_EXPIRED = 1
        const val PING_STATUS_CHANGES = 2
        const val PING_STATUS_HEARTBEAT_OUT_OF_RANGE = 5
        const val PING_STATUS_FOLDER_HIERARCHY_CHANGED = 7
        const val PING_STATUS_SERVER_ERROR = 8

        const val HTTP_PROVISION_REQUIRED = 449
        const val EAS_SYNC_WINDOW_SIZE = 50
        const val MAX_EAS_SYNC_PAGES = 100
        const val EAS_MAPPING_WINDOW_SIZE = 100
        const val MAX_EAS_MAPPING_PAGES = 100

        const val MIN_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val CONFIGURATION_RECHECK_MS = 60_000L
        const val SUBFOLDER_RECONCILIATION_INTERVAL_MS = 120_000L
    }
}
