package it.directmail.backend.exchange

import com.fsck.k9.backend.api.Backend
import com.fsck.k9.backend.api.BackendFolder.MoreMessages
import com.fsck.k9.backend.api.BackendPusher
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.backend.api.SyncConfig
import com.fsck.k9.backend.api.SyncListener
import com.fsck.k9.backend.api.updateFolders
import com.fsck.k9.mail.BodyFactory
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageDownloadState
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.MimeMessageHelper
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.Flag
import net.thunderbird.feature.mail.folder.api.FOLDER_DEFAULT_PATH_DELIMITER
import net.thunderbird.feature.mail.folder.api.FolderPathDelimiter

class ExchangeBackend(
    private val backendStorage: BackendStorage,
    internal val serverSettings: ServerSettings,
    private val diagnosticSink: (String?) -> Unit = {},
) : Backend {
    private val ewsClient by lazy { EwsClient(serverSettings) }
    private val easClient by lazy { EasClient(serverSettings) }
    private val remoteSearchFlags = ConcurrentHashMap<Pair<String, String>, Set<Flag>>()
    private var readFlagWriteSupported: Boolean? = null

    // Enable only operations that are actually wired. This keeps Thunderbird from
    // offering server-side actions before their EWS implementation exists.
    override val supportsFlags = false
    override val supportsExpunge = false
    override val supportsMove = true
    override val supportsCopy = true
    override val supportsUpload = true
    override val supportsTrashFolder = true
    override val supportsSearchByDate = false
    override val supportsFolderSubscriptions = false
    override val isPushCapable = true

    override fun refreshFolderList(): FolderPathDelimiter {
        diagnosticSink(null)

        try {
            val remoteFolders = ewsClient.getFolders()
            val remoteIds = remoteFolders.mapTo(mutableSetOf()) { it.serverId }
            val localIds = backendStorage.getFolderServerIds().toSet()

            backendStorage.updateFolders {
                createFolders(
                    remoteFolders
                        .filterNot { it.serverId in localIds }
                        .map {
                            FolderInfo(
                                serverId = it.serverId,
                                name = it.displayName,
                                type = it.type,
                            )
                        },
                )

                deleteFolders((localIds - remoteIds).toList())
            }

            return FOLDER_DEFAULT_PATH_DELIMITER
        } catch (e: Exception) {
            diagnosticSink(
                buildDiagnostic(
                    folderServerId = "folder-list",
                    stage = "FindFolder",
                    details = e.message ?: "Errore EWS durante la lettura delle cartelle",
                    throwable = e,
                ),
            )
            throw e
        }
    }

    override fun sync(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        listener.syncStarted(folderServerId)
        diagnosticSink(null)

        try {
            val backendFolder = backendStorage.getFolder(folderServerId)
            val syncState = backendFolder.getFolderExtraString(EWS_SYNC_STATE_KEY)

            if (!syncState.isNullOrBlank()) {
                try {
                    syncIncremental(
                        folderServerId = folderServerId,
                        initialSyncState = syncState,
                        listener = listener,
                    )
                    return
                } catch (e: Exception) {
                    if (isInvalidSyncState(e)) {
                        backendFolder.setFolderExtraString(EWS_SYNC_STATE_KEY, null)
                    } else {
                        throw e
                    }
                }
            }

            syncFull(
                folderServerId = folderServerId,
                syncConfig = syncConfig,
                listener = listener,
            )
        } catch (e: Exception) {
            diagnosticSink(
                buildDiagnostic(
                    folderServerId = folderServerId,
                    stage = "sync",
                    details = e.message ?: "Errore EWS",
                    throwable = e,
                ),
            )
            listener.syncFailed(folderServerId, e.message ?: "Errore EWS", e)
        }
    }

    private fun syncFull(folderServerId: String, syncConfig: SyncConfig, listener: SyncListener) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        val localIds = backendFolder.getMessageServerIds()
        val visibleLimit = backendFolder.visibleLimit
            .takeIf { it > 0 }
            ?: syncConfig.defaultVisibleLimit.coerceAtLeast(1)

        val remoteItems = mutableListOf<EwsItem>()
        var offset = 0
        var includesLast = false
        var pageGuard = 0

        while (remoteItems.size < visibleLimit && !includesLast && pageGuard++ < MAX_ITEM_PAGES) {
            val remaining = visibleLimit - remoteItems.size
            val page = ewsClient.findItems(
                folderServerId = folderServerId,
                maxEntries = minOf(ITEM_PAGE_SIZE, remaining),
                offset = offset,
            )

            remoteItems += page.items
            includesLast = page.includesLastItemInRange

            if (includesLast || page.items.isEmpty()) break

            val nextOffset = page.nextOffset ?: (offset + page.items.size)
            if (nextOffset <= offset) break
            offset = nextOffset
        }

        listener.syncAuthenticationSuccess()
        listener.syncHeadersStarted(folderServerId)

        if (remoteItems.isEmpty()) {
            diagnosticSink(
                buildDiagnostic(
                    folderServerId = folderServerId,
                    stage = "FindItem",
                    details = "La risposta EWS e' valida ma contiene 0 messaggi.",
                ),
            )
        }

        var newMessages = 0
        val messageFailures = mutableListOf<String>()

        remoteItems.filter { it.serverId in localIds }.forEach { item ->
            val seenChanged = applyRemoteSeenState(backendFolder, item)
            val flaggedChanged = applyRemoteFlaggedState(backendFolder, item)
            val actionChanged = applyRemoteActionStates(backendFolder, item)
            if (seenChanged || flaggedChanged || actionChanged) {
                listener.syncFlagChanged(folderServerId, item.serverId)
            }
        }

        val newItems = remoteItems.filterNot { it.serverId in localIds }
        newItems.chunked(MIME_DOWNLOAD_BATCH_SIZE).forEachIndexed { batchIndex, batch ->
            try {
                val messages = ewsClient.getSyncMessages(batch.map { it.serverId })
                batch.forEachIndexed { itemIndex, item ->
                    val progress = batchIndex * MIME_DOWNLOAD_BATCH_SIZE + itemIndex
                    listener.syncHeadersProgress(folderServerId, progress, newItems.size)

                    val download = messages[item.serverId]
                        ?: error("EWS GetItem batch: messaggio ${item.serverId} assente")
                    val message = download.message
                    message.setFlag(Flag.SEEN, item.isRead)
                    message.setFlag(Flag.FLAGGED, item.isFlagged)
                    message.setFlag(Flag.ANSWERED, item.isAnswered)
                    message.setFlag(Flag.FORWARDED, item.isForwarded)
                    backendFolder.saveMessage(
                        message,
                        if (download.isPartial) MessageDownloadState.PARTIAL else MessageDownloadState.FULL,
                    )
                    listener.syncNewMessage(
                        folderServerId,
                        item.serverId,
                        isOldMessage = false,
                    )
                    newMessages++
                }
            } catch (e: Exception) {
                messageFailures += "${e::class.simpleName}: ${e.message ?: "errore senza messaggio"}"
            }
        }

        if (newMessages == 0 && messageFailures.isNotEmpty()) {
            error(
                "EWS GetItem/MIME: ${messageFailures.size} messaggi falliti; " +
                    "primo errore: ${messageFailures.first()}",
            )
        }

        backendFolder.setMoreMessages(
            if (includesLast) MoreMessages.FALSE else MoreMessages.TRUE,
        )

        // Establish a point-in-time SyncFolderItems state after the proven FindItem sync.
        // This drains only metadata and doesn't alter the current visible-message policy.
        runCatching {
            establishIncrementalSyncState(folderServerId)
        }

        listener.syncHeadersFinished(
            folderServerId,
            remoteItems.size,
            newMessages,
        )
        backendFolder.setLastChecked(System.currentTimeMillis())
        backendFolder.setStatus(null)
        listener.syncFinished(folderServerId)
    }

    private fun syncIncremental(
        folderServerId: String,
        initialSyncState: String,
        listener: SyncListener,
    ) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        val knownIds = backendFolder.getMessageServerIds().toMutableSet()

        listener.syncAuthenticationSuccess()
        listener.syncHeadersStarted(folderServerId)

        var syncState = initialSyncState
        var includesLast = false
        var pageCount = 0
        var newMessages = 0
        var processedChanges = 0

        var settlePasses = 0

        while (pageCount++ < MAX_SYNC_FOLDER_ITEMS_PAGES) {
            val page = ewsClient.syncFolderItems(
                folderServerId = folderServerId,
                syncState = syncState,
            )

            val pendingItems = linkedMapOf<String, PendingSyncItem>()
            val readChanges = linkedMapOf<String, Boolean>()
            val deletedIds = mutableListOf<String>()

            page.changes.forEach { change ->
                when (change) {
                    is EwsSyncCreate -> {
                        pendingItems[change.item.serverId] = PendingSyncItem(
                            item = change.item,
                            isCreate = true,
                        )
                    }

                    is EwsSyncUpdate -> {
                        pendingItems[change.item.serverId] = PendingSyncItem(
                            item = change.item,
                            isCreate = false,
                        )
                    }

                    is EwsSyncDelete -> deletedIds += change.serverId
                    is EwsSyncReadFlag -> readChanges[change.serverId] = change.isRead
                }
            }

            if (deletedIds.isNotEmpty()) {
                backendFolder.destroyMessages(deletedIds)
                knownIds.removeAll(deletedIds.toSet())
            }

            readChanges.forEach { (serverId, isRead) ->
                val pending = pendingItems[serverId]
                if (pending != null) {
                    pendingItems[serverId] = pending.copy(
                        item = pending.item.copy(isRead = isRead),
                    )
                } else if (serverId in knownIds) {
                    val localSeen = Flag.SEEN in backendFolder.getMessageFlags(serverId)
                    if (localSeen != isRead) {
                        backendFolder.setMessageFlag(serverId, Flag.SEEN, isRead)
                        listener.syncFlagChanged(folderServerId, serverId)
                    }
                }
            }

            val toFetch = pendingItems.values.filter { pending ->
                !pending.isCreate || pending.item.serverId !in knownIds
            }

            toFetch.chunked(MIME_DOWNLOAD_BATCH_SIZE).forEach { batch ->
                val messages = ewsClient.getSyncMessages(batch.map { it.item.serverId })

                batch.forEach { pending ->
                    val serverId = pending.item.serverId
                    val download = messages[serverId]
                        ?: error("EWS SyncFolderItems/GetItem: messaggio $serverId assente")
                    val message = download.message
                    val wasKnown = serverId in knownIds
                    val oldFlags = if (wasKnown) backendFolder.getMessageFlags(serverId) else emptySet()

                    message.setFlag(Flag.SEEN, pending.item.isRead)
                    message.setFlag(Flag.FLAGGED, pending.item.isFlagged)
                    message.setFlag(Flag.ANSWERED, pending.item.isAnswered)
                    message.setFlag(Flag.FORWARDED, pending.item.isForwarded)
                    backendFolder.saveMessage(
                        message,
                        if (download.isPartial) MessageDownloadState.PARTIAL else MessageDownloadState.FULL,
                    )

                    if (!wasKnown) {
                        listener.syncNewMessage(
                            folderServerId,
                            serverId,
                            isOldMessage = false,
                        )
                        knownIds += serverId
                        newMessages++
                    } else if (remoteFlagsDiffer(oldFlags, pending.item)) {
                        listener.syncFlagChanged(folderServerId, serverId)
                    }
                }
            }

            // Create events already present locally still need their authoritative flag state.
            pendingItems.values
                .filter { it.item.serverId in knownIds }
                .forEach { pending ->
                    if (applyRemoteItemFlags(backendFolder, pending.item)) {
                        listener.syncFlagChanged(folderServerId, pending.item.serverId)
                    }
                }

            processedChanges += page.changes.size

            // Advance the durable state only after this page was fully applied.
            syncState = page.syncState
            backendFolder.setFolderExtraString(EWS_SYNC_STATE_KEY, syncState)

            if (!page.includesLastItemInRange) {
                includesLast = false
                settlePasses = 0
                continue
            }

            if (
                folderServerId == EasReadStateStore.INBOX_SERVER_ID ||
                page.changes.isEmpty()
            ) {
                includesLast = true
                break
            }

            // Gromox can return a ReadFlagChange with IncludesLastItemInRange=true,
            // while another flag change becomes visible only when querying again with
            // the newly returned SyncState. Drain a few immediate follow-up deltas so
            // multiple read/unread changes in the same subfolder don't require multiple
            // 120-second push heartbeats.
            settlePasses++
            if (settlePasses >= MAX_SUBFOLDER_SETTLE_PASSES) {
                includesLast = true
                break
            }

            includesLast = false
        }

        if (!includesLast) {
            error("EWS SyncFolderItems: troppe pagine di cambiamenti senza raggiungere lo stato corrente")
        }

        listener.syncHeadersFinished(
            folderServerId,
            backendFolder.getMessageServerIds().size,
            newMessages,
        )
        backendFolder.setLastChecked(System.currentTimeMillis())
        backendFolder.setStatus(null)
        listener.syncFinished(folderServerId)
    }

    private fun establishIncrementalSyncState(folderServerId: String) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        var syncState: String? = null
        var includesLast = false
        var pageCount = 0

        while (!includesLast && pageCount++ < MAX_INITIAL_SYNC_STATE_PAGES) {
            val page = ewsClient.syncFolderItems(
                folderServerId = folderServerId,
                syncState = syncState,
            )
            syncState = page.syncState
            includesLast = page.includesLastItemInRange
        }

        if (includesLast && !syncState.isNullOrBlank()) {
            backendFolder.setFolderExtraString(EWS_SYNC_STATE_KEY, syncState)
        }
    }

    private fun applyRemoteSeenState(
        backendFolder: com.fsck.k9.backend.api.BackendFolder,
        item: EwsItem,
    ): Boolean {
        val localSeen = Flag.SEEN in backendFolder.getMessageFlags(item.serverId)
        val shouldApplyRemoteSeen = when {
            localSeen == item.isRead -> false
            item.isRead -> true
            readFlagWriteSupported == false -> false
            localSeen -> false
            else -> true
        }

        if (shouldApplyRemoteSeen) {
            backendFolder.setMessageFlag(item.serverId, Flag.SEEN, item.isRead)
        }
        return shouldApplyRemoteSeen
    }

    private fun applyRemoteFlaggedState(
        backendFolder: com.fsck.k9.backend.api.BackendFolder,
        item: EwsItem,
    ): Boolean {
        val localFlagged = Flag.FLAGGED in backendFolder.getMessageFlags(item.serverId)
        val changed = localFlagged != item.isFlagged
        if (changed) {
            backendFolder.setMessageFlag(item.serverId, Flag.FLAGGED, item.isFlagged)
        }
        return changed
    }

    private fun applyRemoteActionStates(
        backendFolder: com.fsck.k9.backend.api.BackendFolder,
        item: EwsItem,
    ): Boolean {
        val localFlags = backendFolder.getMessageFlags(item.serverId)
        var changed = false
        if ((Flag.ANSWERED in localFlags) != item.isAnswered) {
            backendFolder.setMessageFlag(item.serverId, Flag.ANSWERED, item.isAnswered)
            changed = true
        }
        if ((Flag.FORWARDED in localFlags) != item.isForwarded) {
            backendFolder.setMessageFlag(item.serverId, Flag.FORWARDED, item.isForwarded)
            changed = true
        }
        return changed
    }

    private fun applyRemoteItemFlags(
        backendFolder: com.fsck.k9.backend.api.BackendFolder,
        item: EwsItem,
    ): Boolean {
        val localFlags = backendFolder.getMessageFlags(item.serverId)
        if (!remoteFlagsDiffer(localFlags, item)) return false

        backendFolder.setMessageFlag(item.serverId, Flag.SEEN, item.isRead)
        backendFolder.setMessageFlag(item.serverId, Flag.FLAGGED, item.isFlagged)
        backendFolder.setMessageFlag(item.serverId, Flag.ANSWERED, item.isAnswered)
        backendFolder.setMessageFlag(item.serverId, Flag.FORWARDED, item.isForwarded)
        return true
    }

    private fun remoteFlagsDiffer(localFlags: Set<Flag>, item: EwsItem): Boolean {
        return (Flag.SEEN in localFlags) != item.isRead ||
            (Flag.FLAGGED in localFlags) != item.isFlagged ||
            (Flag.ANSWERED in localFlags) != item.isAnswered ||
            (Flag.FORWARDED in localFlags) != item.isForwarded
    }

    private fun isInvalidSyncState(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                "ErrorInvalidSyncStateData" in message ||
                "InvalidSyncState" in message
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private data class EasFolderReadSnapshot(
        val syncKey: String,
        val items: List<EasSyncItem>,
    )

    private data class PendingSyncItem(
        val item: EwsItem,
        val isCreate: Boolean,
    )

    private fun buildDiagnostic(
        folderServerId: String,
        stage: String,
        details: String,
        throwable: Throwable? = null,
    ): String {
        return buildString {
            appendLine("DirectMail EWS sync diagnostic")
            appendLine("At: ${Instant.now()}")
            appendLine("Host: ${serverSettings.host}")
            appendLine("Folder: $folderServerId")
            appendLine("Stage: $stage")
            appendLine("Details: $details")
            if (throwable != null) {
                appendLine()
                append(throwable.stackTraceToString())
            }
        }
    }

    override fun downloadMessage(syncConfig: SyncConfig, folderServerId: String, messageServerId: String) {
        downloadPartialMessage(folderServerId, messageServerId)
    }

    override fun downloadMessageStructure(folderServerId: String, messageServerId: String) {
        downloadPartialMessage(folderServerId, messageServerId)
    }

    override fun downloadCompleteMessage(folderServerId: String, messageServerId: String) {
        downloadFullMessage(folderServerId, messageServerId)
    }

    private fun downloadPartialMessage(folderServerId: String, messageServerId: String) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        val searchFlags = remoteSearchFlags.remove(folderServerId to messageServerId)
        val flagsToPreserve = if (messageServerId in backendFolder.getMessageServerIds()) {
            backendFolder.getMessageFlags(messageServerId)
        } else {
            searchFlags
        }
        val download = ewsClient.getPartialMessage(messageServerId)
        flagsToPreserve?.let { applyPreservedDownloadFlags(download.message, it) }
        backendFolder.saveMessage(
            download.message,
            if (download.isPartial) MessageDownloadState.PARTIAL else MessageDownloadState.FULL,
        )
    }

    private fun downloadFullMessage(folderServerId: String, messageServerId: String) {
        val backendFolder = backendStorage.getFolder(folderServerId)
        val searchFlags = remoteSearchFlags.remove(folderServerId to messageServerId)
        val flagsToPreserve = if (messageServerId in backendFolder.getMessageServerIds()) {
            backendFolder.getMessageFlags(messageServerId)
        } else {
            searchFlags
        }
        val message = ewsClient.getCompleteMessage(messageServerId)
        flagsToPreserve?.let { applyPreservedDownloadFlags(message, it) }
        backendFolder.saveMessage(message, MessageDownloadState.FULL)
    }

    private fun applyPreservedDownloadFlags(message: Message, localFlags: Set<Flag>) {
        PRESERVED_DOWNLOAD_FLAGS.forEach { flag ->
            message.setFlag(flag, flag in localFlags)
        }
    }

    override fun setFlag(folderServerId: String, messageServerIds: List<String>, flag: Flag, newState: Boolean) {
        when (flag) {
            Flag.SEEN -> {
                readFlagWriteSupported = if (folderServerId == EasReadStateStore.INBOX_SERVER_ID) {
                    runEasOperation("Sync Change Read") {
                        setInboxReadStateViaEas(messageServerIds, newState)
                    }
                } else {
                    runEasOperation("Sync Change Read folder") {
                        setFolderReadStateViaEas(
                            folderServerId = folderServerId,
                            messageServerIds = messageServerIds,
                            isRead = newState,
                        )
                    }
                }
            }

            Flag.FLAGGED -> {
                runEwsOperation("UpdateItem Flag") {
                    ewsClient.setFlaggedState(messageServerIds, newState)
                }
            }

            Flag.ANSWERED -> {
                runEwsOperation("UpdateItem Answered") {
                    ewsClient.setAnsweredState(messageServerIds, newState)
                }
            }

            Flag.FORWARDED -> {
                runEwsOperation("UpdateItem Forwarded") {
                    ewsClient.setForwardedState(messageServerIds, newState)
                }
            }

            else -> throw UnsupportedOperationException("Exchange flag update not wired for $flag")
        }
    }

    override fun markAllAsRead(folderServerId: String) {
        readFlagWriteSupported = if (folderServerId == EasReadStateStore.INBOX_SERVER_ID) {
            runEasOperation("Sync Change MarkAllRead") {
                setInboxReadStateViaEas(
                    backendStorage.getFolder(folderServerId).getMessageServerIds().toList(),
                    true,
                )
            }
        } else {
            runEwsOperation("UpdateItem MarkAllRead") {
                ewsClient.markAllAsRead(folderServerId)
            }
        }
    }

    override fun expunge(folderServerId: String) = Unit

    override fun deleteMessages(folderServerId: String, messageServerIds: List<String>) {
        runEwsOperation("DeleteItem") {
            ewsClient.deleteItems(messageServerIds)
        }
    }

    override fun deleteAllMessages(folderServerId: String) {
        runEwsOperation("DeleteItem all") {
            ewsClient.deleteAllItems(folderServerId)
        }
    }

    override fun moveMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>? {
        return runEwsOperation("MoveItem") {
            moveItemsOrRetireMissing(
                targetFolderServerId = targetFolderServerId,
                messageServerIds = messageServerIds,
                operation = "MoveItem",
            )
        }
    }

    override fun moveMessagesAndMarkAsRead(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>? {
        if (sourceFolderServerId == EasReadStateStore.INBOX_SERVER_ID) {
            // Thunderbird uses MOVE_AND_MARK_AS_READ for normal delete-to-Trash when
            // "mark as read on delete" is enabled. Read-state propagation must never
            // prevent the actual server move. EAS read mapping is currently best-effort.
            runCatching {
                readFlagWriteSupported = setInboxReadStateViaEas(messageServerIds, true)
            }

            return runEwsOperation("MoveItem/read") {
                moveItemsOrRetireMissing(
                    targetFolderServerId = targetFolderServerId,
                    messageServerIds = messageServerIds,
                    operation = "MoveItem/read",
                )
            }
        }

        return runEwsOperation("MoveItem/read") {
            val moved = moveItemsOrRetireMissing(
                targetFolderServerId = targetFolderServerId,
                messageServerIds = messageServerIds,
                operation = "MoveItem/read",
            )
            if (moved.isNotEmpty()) {
                readFlagWriteSupported = ewsClient.setReadState(moved.values.toList(), true)
            }
            moved
        }
    }

    override fun copyMessages(
        sourceFolderServerId: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String>? {
        return runEwsOperation("CopyItem") {
            ewsClient.copyItems(targetFolderServerId, messageServerIds)
        }
    }

    override fun search(
        folderServerId: String,
        query: String?,
        requiredFlags: Set<Flag>?,
        forbiddenFlags: Set<Flag>?,
        performFullTextSearch: Boolean,
    ): List<String> {
        val required = requiredFlags.orEmpty()
        val forbidden = forbiddenFlags.orEmpty()
        val supportedSearchFlags = setOf(Flag.SEEN, Flag.FLAGGED)
        val unsupported = (required + forbidden) - supportedSearchFlags
        if (unsupported.isNotEmpty()) {
            throw UnsupportedOperationException("Exchange search flags not supported: $unsupported")
        }

        if ((Flag.SEEN in required && Flag.SEEN in forbidden) ||
            (Flag.FLAGGED in required && Flag.FLAGGED in forbidden)
        ) {
            return emptyList()
        }

        val seenState = when {
            Flag.SEEN in required -> true
            Flag.SEEN in forbidden -> false
            else -> null
        }
        val flaggedState = when {
            Flag.FLAGGED in required -> true
            Flag.FLAGGED in forbidden -> false
            else -> null
        }

        remoteSearchFlags.keys.removeIf { it.first == folderServerId }
        val result = runEwsOperation("FindItem search") {
            ewsClient.searchWithCompatibilityFallback(
                folderServerId = folderServerId,
                query = query,
                seenState = seenState,
                flaggedState = flaggedState,
                performFullTextSearch = performFullTextSearch,
            )
        }
        result.flagsByServerId.forEach { (serverId, flags) ->
            remoteSearchFlags[folderServerId to serverId] = flags
        }
        return result.messageServerIds
    }

    override fun fetchPart(folderServerId: String, messageServerId: String, part: Part, bodyFactory: BodyFactory) {
        val attachmentId = part.serverExtra
            ?.takeIf { it.isNotBlank() }
            ?: part.getHeader("X-DirectMail-Ews-Attachment-Id")
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }

        if (attachmentId == null) {
            // Legacy/unsupported partial structure: keep the proven full-message escape hatch.
            downloadFullMessage(folderServerId, messageServerId)
            return
        }

        val contentType = part.getHeader("Content-Type")
            .firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
        val content = runEwsOperation("GetAttachment") {
            ewsClient.getAttachmentContent(attachmentId)
        }
        val body = bodyFactory.createBody(
            "8bit",
            contentType,
            ByteArrayInputStream(content),
        )
        MimeMessageHelper.setBody(part, body)
    }

    override fun findByMessageId(folderServerId: String, messageId: String): String? {
        return runEwsOperation("FindItem InternetMessageId") {
            ewsClient.findByMessageId(folderServerId, messageId)
        }
    }

    override fun uploadMessage(folderServerId: String, message: Message): String? {
        if (folderServerId == SENT_FOLDER_ID) {
            val internetMessageId = message.messageId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw MessagingException(
                    "Sent handoff: Message-ID assente",
                    false,
                )

            val existing = runEwsOperation("FindItem Sent handoff") {
                ewsClient.findByMessageId(SENT_FOLDER_ID, internetMessageId)
            }
            if (existing != null) {
                return existing
            }

            // SendItem is authoritative for Sent persistence. Never create a second
            // server message from PendingAppend; retry only the lookup.
            throw MessagingException(
                "Sent handoff: copia server non ancora visibile per Message-ID; ritentare PendingAppend",
                false,
            )
        }

        return runEwsOperation("CreateItem SaveOnly") {
            ewsClient.saveMessage(folderServerId, message)
        }
    }

    override fun sendMessage(message: Message) {
        try {
            diagnosticSink(null)
            val result = ewsClient.sendMessage(message)
            if (!result.sentCopyConfirmed) {
                diagnosticSink(
                    buildDiagnostic(
                        folderServerId = "sentitems",
                        stage = "SendItem Sent copy",
                        details = result.details,
                    ),
                )
            }
        } catch (e: Exception) {
            diagnosticSink(
                buildDiagnostic(
                    folderServerId = "outbox",
                    stage = "SendItem",
                    details = e.message ?: "Errore EWS durante invio",
                    throwable = e,
                ),
            )
            throw e
        }
    }

    private fun setInboxReadStateViaEas(
        messageServerIds: List<String>,
        isRead: Boolean,
    ): Boolean {
        val identities = linkedMapOf<String, EwsReadMappingIdentity>()
        messageServerIds.distinct().forEach { ewsId ->
            try {
                val identity = ewsClient.getReadMappingIdentity(ewsId)
                val internetMessageId = EasReadStateStore.normalizeInternetMessageId(identity.internetMessageId)
                if (internetMessageId == null) {
                    throw MessagingException(
                        "EAS Message-ID mapping: InternetMessageId EWS assente",
                        true,
                    )
                }
                identities[ewsId] = identity
            } catch (e: Exception) {
                if (!isItemNotFound(e)) throw e
                // The source object no longer exists, so a queued flag mutation is obsolete.
            }
        }
        if (identities.isEmpty()) return true

        // Ping is a long-running Sync-state consumer. Cancel it before taking the shared
        // monitor, then resolve EWS ItemId -> EAS ServerId using the real Message-ID.
        EasSyncStateLock.interruptPushForCommand()

        synchronized(EasSyncStateLock.monitor) {
            val policyKey = ensureEasPolicyKey()
            val inboxId = ensureEasInboxId(policyKey)
            val mappings = linkedMapOf<String, String>()

            identities.forEach { (ewsId, identity) ->
                val internetMessageId = identity.internetMessageId ?: return@forEach
                EasReadStateStore.lookupByInternetMessageId(backendStorage, internetMessageId)
                    ?.let { mappings[ewsId] = it }
            }

            val unresolved = identities.keys - mappings.keys
            if (unresolved.isNotEmpty()) {
                val easItems = EasReadStateStore.collectInboxItems(
                    storage = backendStorage,
                    client = easClient,
                    policyKey = policyKey,
                    inboxId = inboxId,
                    maxPages = EAS_MAPPING_MAX_PAGES,
                    windowSize = EAS_MAPPING_WINDOW_SIZE,
                )

                unresolved.forEach { ewsId ->
                    val identity = identities.getValue(ewsId)
                    val resolvedServerId = resolveEasServerIdByMessageId(
                        policyKey = policyKey,
                        inboxId = inboxId,
                        identity = identity,
                        easItems = easItems,
                    )

                    if (resolvedServerId != null) {
                        val internetMessageId = identity.internetMessageId.orEmpty()
                        EasReadStateStore.recordInternetMessageId(
                            storage = backendStorage,
                            internetMessageId = internetMessageId,
                            serverId = resolvedServerId,
                        )
                        mappings[ewsId] = resolvedServerId
                    }
                }
            }

            val missing = identities.keys - mappings.keys
            if (missing.isNotEmpty()) {
                throw MessagingException(
                    "EAS Message-ID mapping assente per ${missing.size} messaggi dopo verifica MIME",
                    true,
                )
            }

            repeat(2) { attempt ->
                var syncKey = ensureEasMailSyncKey(policyKey, inboxId)
                try {
                    mappings.values.distinct().forEach { easServerId ->
                        val result = easClient.changeReadState(
                            policyKey = policyKey,
                            inboxId = inboxId,
                            syncKey = syncKey,
                            serverId = easServerId,
                            isRead = isRead,
                        )
                        syncKey = result.syncKey
                        backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, syncKey)
                        EasReadStateStore.record(backendStorage, result.items)
                    }
                    return true
                } catch (e: Exception) {
                    if (attempt != 0 || !isInvalidEasSyncKey(e)) throw e
                    backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, "")
                    syncKey = ensureEasMailSyncKey(policyKey, inboxId)
                }
            }
        }

        return true
    }

    private fun resolveEasServerIdByMessageId(
        policyKey: String,
        inboxId: String,
        identity: EwsReadMappingIdentity,
        easItems: List<EasSyncItem>,
    ): String? {
        val targetMessageId = EasReadStateStore.normalizeInternetMessageId(identity.internetMessageId)
            ?: return null

        val targetExactKey = EasReadStateStore.candidateKey(
            subject = identity.subject,
            dateReceived = identity.dateReceived,
        )
        val targetDateKey = EasReadStateStore.candidateDateKey(identity.dateReceived)
        val targetSubjectKey = EasReadStateStore.candidateSubjectKey(identity.subject)

        val candidateGroups = buildList {
            if (targetExactKey != null) {
                add(
                    easItems.filter { item ->
                        EasReadStateStore.candidateKey(item.subject, item.dateReceived) == targetExactKey
                    },
                )
            }
            if (targetDateKey != null) {
                add(
                    easItems.filter { item ->
                        EasReadStateStore.candidateDateKey(item.dateReceived) == targetDateKey
                    },
                )
            }
            if (targetSubjectKey != null) {
                add(
                    easItems.filter { item ->
                        EasReadStateStore.candidateSubjectKey(item.subject) == targetSubjectKey
                    },
                )
            }
        }

        val attempted = mutableSetOf<String>()
        candidateGroups.forEach { group ->
            val candidates = group
                .filter { attempted.add(it.serverId) }
                .distinctBy { it.serverId }

            if (candidates.size > EAS_IDENTITY_MAX_CANDIDATES) {
                return@forEach
            }

            val matches = candidates.filter { candidate ->
                val easMessageId = easClient.fetchInternetMessageId(
                    policyKey = policyKey,
                    inboxId = inboxId,
                    serverId = candidate.serverId,
                )
                EasReadStateStore.normalizeInternetMessageId(easMessageId) == targetMessageId
            }

            when (matches.size) {
                1 -> return matches.single().serverId
                0 -> Unit
                else -> throw MessagingException(
                    "EAS Message-ID mapping ambiguo: ${matches.size} ServerId per lo stesso Message-ID",
                    true,
                )
            }
        }

        return null
    }

    private fun resolveEasServerIdByMessageIdForFolder(
        policyKey: String,
        folderId: String,
        identity: EwsReadMappingIdentity,
        easItems: List<EasSyncItem>,
    ): String? {
        resolveEasServerIdByMessageId(
            policyKey = policyKey,
            inboxId = folderId,
            identity = identity,
            easItems = easItems,
        )?.let { return it }

        val targetMessageId = EasReadStateStore.normalizeInternetMessageId(identity.internetMessageId)
            ?: return null

        val candidates = easItems
            .distinctBy { it.serverId }
            .take(EAS_IDENTITY_FULL_SCAN_LIMIT)

        for (candidate in candidates) {
            val easMessageId = try {
                easClient.fetchInternetMessageId(
                    policyKey = policyKey,
                    inboxId = folderId,
                    serverId = candidate.serverId,
                )
            } catch (_: Exception) {
                continue
            }

            if (EasReadStateStore.normalizeInternetMessageId(easMessageId) == targetMessageId) {
                return candidate.serverId
            }
        }

        return null
    }

    private fun setFolderReadStateViaEas(
        folderServerId: String,
        messageServerIds: List<String>,
        isRead: Boolean,
    ): Boolean {
        val identities = linkedMapOf<String, EwsReadMappingIdentity>()
        messageServerIds.distinct().forEach { ewsId ->
            try {
                val identity = ewsClient.getReadMappingIdentity(ewsId)
                if (EasReadStateStore.normalizeInternetMessageId(identity.internetMessageId) == null) {
                    throw MessagingException(
                        "EAS folder Message-ID mapping: InternetMessageId EWS assente",
                        true,
                    )
                }
                identities[ewsId] = identity
            } catch (e: Exception) {
                if (!isItemNotFound(e)) throw e
            }
        }
        if (identities.isEmpty()) return true

        EasSyncStateLock.interruptPushForCommand()

        synchronized(EasSyncStateLock.monitor) {
            val policyKey = ensureEasPolicyKey()
            val easFolderId = resolveEasFolderId(
                policyKey = policyKey,
                ewsFolderServerId = folderServerId,
            )

            repeat(2) { attempt ->
                try {
                    val snapshot = loadEasFolderSnapshot(
                        policyKey = policyKey,
                        easFolderId = easFolderId,
                    )
                    val mappings = linkedMapOf<String, String>()

                    identities.forEach { (ewsId, identity) ->
                        resolveEasServerIdByMessageIdForFolder(
                            policyKey = policyKey,
                            folderId = easFolderId,
                            identity = identity,
                            easItems = snapshot.items,
                        )?.let { mappings[ewsId] = it }
                    }

                    val missing = identities.keys - mappings.keys
                    if (missing.isNotEmpty()) {
                        throw MessagingException(
                            "EAS folder Message-ID mapping assente per ${missing.size} messaggi dopo verifica MIME " +
                                "(snapshot=${snapshot.items.size}, scan<=$EAS_IDENTITY_FULL_SCAN_LIMIT)",
                            true,
                        )
                    }

                    var syncKey = snapshot.syncKey
                    mappings.values.distinct().forEach { easServerId ->
                        val result = easClient.changeReadState(
                            policyKey = policyKey,
                            inboxId = easFolderId,
                            syncKey = syncKey,
                            serverId = easServerId,
                            isRead = isRead,
                        )
                        syncKey = result.syncKey
                    }
                    return true
                } catch (e: Exception) {
                    if (attempt != 0 || !isInvalidEasSyncKey(e)) throw e
                }
            }
        }

        return true
    }

    private fun resolveEasFolderId(
        policyKey: String,
        ewsFolderServerId: String,
    ): String {
        val ewsFolder = ewsClient.getFolders()
            .firstOrNull { it.serverId == ewsFolderServerId }
            ?: error("EAS folder mapping: cartella EWS non trovata")

        val hierarchy = easClient.folderSync(
            policyKey = policyKey,
            syncKey = "0",
        )
        val byId = hierarchy.folders.associateBy { it.serverId }

        fun pathFor(folder: EasFolderNode, seen: Set<String> = emptySet()): String {
            if (folder.serverId in seen) return folder.displayName
            val parentId = folder.parentId
                ?.takeUnless { it == "0" }
                ?: return folder.displayName
            val parent = byId[parentId] ?: return folder.displayName
            val parentPath = pathFor(parent, seen + folder.serverId)
            return if (parentPath.isBlank()) {
                folder.displayName
            } else {
                "$parentPath/${folder.displayName}"
            }
        }

        fun normalizePath(path: String): String =
            path.split('/')
                .map { it.trim().replace(Regex("\\s+"), " ").lowercase() }
                .filter { it.isNotEmpty() }
                .joinToString("/")

        val targetPath = normalizePath(ewsFolder.displayName)
        val matches = hierarchy.folders.filter { folder ->
            normalizePath(pathFor(folder)) == targetPath
        }

        return when (matches.size) {
            1 -> matches.single().serverId
            0 -> throw MessagingException(
                "EAS folder mapping assente per path EWS '${ewsFolder.displayName}'",
                true,
            )
            else -> throw MessagingException(
                "EAS folder mapping ambiguo per path EWS '${ewsFolder.displayName}': ${matches.size} cartelle",
                true,
            )
        }
    }

    private fun loadEasFolderSnapshot(
        policyKey: String,
        easFolderId: String,
    ): EasFolderReadSnapshot {
        val initial = easClient.sync(
            policyKey = policyKey,
            inboxId = easFolderId,
            syncKey = "0",
            getChanges = false,
        )
        var syncKey = initial.syncKey
        val items = mutableListOf<EasSyncItem>()
        var pageCount = 0
        var moreAvailable: Boolean

        do {
            if (pageCount++ >= EAS_MAPPING_MAX_PAGES) {
                throw MessagingException(
                    "EAS folder mapping: superato limite pagine durante snapshot",
                    true,
                )
            }

            val result = easClient.sync(
                policyKey = policyKey,
                inboxId = easFolderId,
                syncKey = syncKey,
                getChanges = true,
                windowSize = EAS_MAPPING_WINDOW_SIZE,
            )
            items += result.items
            syncKey = result.syncKey
            moreAvailable = result.moreAvailable
        } while (moreAvailable)

        return EasFolderReadSnapshot(
            syncKey = syncKey,
            items = items,
        )
    }

    private fun ensureEasPolicyKey(): String {
        backendStorage.getExtraString(EasReadStateStore.POLICY_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val policyKey = easClient.provision()
        backendStorage.setExtraString(EasReadStateStore.POLICY_KEY, policyKey)
        return policyKey
    }

    private fun ensureEasInboxId(policyKey: String): String {
        backendStorage.getExtraString(EasReadStateStore.INBOX_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val result = easClient.folderSync(
            policyKey = policyKey,
            syncKey = "0",
        )
        backendStorage.setExtraString(EasReadStateStore.FOLDER_SYNC_KEY, result.syncKey)
        backendStorage.setExtraString(EasReadStateStore.INBOX_ID, result.inboxId)
        return result.inboxId
    }

    private fun ensureEasMailSyncKey(policyKey: String, inboxId: String): String {
        backendStorage.getExtraString(EasReadStateStore.MAIL_SYNC_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val initial = easClient.sync(
            policyKey = policyKey,
            inboxId = inboxId,
            syncKey = "0",
            getChanges = false,
        )
        backendStorage.setExtraString(EasReadStateStore.MAIL_SYNC_KEY, initial.syncKey)
        return initial.syncKey
    }

    private fun isInvalidEasSyncKey(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if ("Sync collection status=3" in current.message.orEmpty()) return true
            current = current.cause
        }
        return false
    }

    private fun moveItemsOrRetireMissing(
        targetFolderServerId: String,
        messageServerIds: List<String>,
        operation: String,
    ): Map<String, String> {
        return try {
            ewsClient.moveItems(targetFolderServerId, messageServerIds)
        } catch (e: Exception) {
            if (isItemNotFound(e)) {
                throw MessagingException(
                    "DirectMail obsolete pending $operation: source item no longer exists",
                    true,
                )
            }
            throw e
        }
    }

    private fun isItemNotFound(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if ("ErrorItemNotFound" in current.message.orEmpty()) return true
            current = current.cause
        }
        return false
    }

    private fun <T> runEasOperation(operation: String, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            val retiredPending =
                e is MessagingException &&
                    e.isPermanentFailure &&
                    e.message.orEmpty().startsWith("DirectMail obsolete pending")
            if (!retiredPending) {
                diagnosticSink(
                    buildDiagnostic(
                        folderServerId = "server-write",
                        stage = "EAS $operation",
                        details = e.message ?: "Errore EAS durante operazione server",
                        throwable = e,
                    ),
                )
            }

            if (e is MessagingException) throw e
            throw MessagingException("EAS $operation: ${e.message ?: "errore"}", e)
        }
    }

    private fun <T> runEwsOperation(operation: String, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            val retiredPending =
                e is MessagingException &&
                    e.isPermanentFailure &&
                    e.message.orEmpty().startsWith("DirectMail obsolete pending")
            if (!retiredPending) {
                diagnosticSink(
                    buildDiagnostic(
                        folderServerId = "server-write",
                        stage = operation,
                        details = e.message ?: "Errore EWS durante operazione server",
                        throwable = e,
                    ),
                )
            }

            if (e is MessagingException) {
                throw e
            }

            throw MessagingException("EWS $operation: ${e.message ?: "errore"}", e)
        }
    }

    private companion object {
        const val ITEM_PAGE_SIZE = 100
        const val MAX_ITEM_PAGES = 100
        const val MIME_DOWNLOAD_BATCH_SIZE = 20
        val PRESERVED_DOWNLOAD_FLAGS = setOf(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED)
        const val MAX_SYNC_FOLDER_ITEMS_PAGES = 100
        const val MAX_SUBFOLDER_SETTLE_PASSES = 8
        const val MAX_INITIAL_SYNC_STATE_PAGES = 100
        const val EAS_MAPPING_WINDOW_SIZE = 100
        const val EAS_MAPPING_MAX_PAGES = 100
        const val EAS_IDENTITY_MAX_CANDIDATES = 32
        const val EAS_IDENTITY_FULL_SCAN_LIMIT = 512
        const val EWS_SYNC_STATE_KEY = "directmail.ews.sync-state"
        const val SENT_FOLDER_ID = "sentitems"
    }

    override fun createPusher(callback: BackendPusherCallback): BackendPusher {
        return ExchangeBackendPusher(
            backendStorage = backendStorage,
            serverSettings = serverSettings,
            callback = callback,
            diagnosticSink = diagnosticSink,
        )
    }
}
