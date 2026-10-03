package it.directmail.backend.exchange

import com.fsck.k9.mail.ServerSettings
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal data class EasFolderNode(
    val serverId: String,
    val parentId: String?,
    val displayName: String,
    val type: Int,
)

internal data class EasFolderSyncResult(
    val syncKey: String,
    val inboxId: String,
    val folders: List<EasFolderNode> = emptyList(),
)

internal data class EasPingResult(
    val status: Int,
    val changedFolderIds: List<String>,
    val suggestedHeartbeatSeconds: Int?,
)

internal data class EasSyncItem(
    val serverId: String,
    val subject: String?,
    val dateReceived: String?,
    val from: String?,
    val isRead: Boolean?,
)

internal data class EasSyncResult(
    val syncKey: String,
    val moreAvailable: Boolean,
    val items: List<EasSyncItem> = emptyList(),
)

internal object EasSyncStateLock {
    val monitor = Any()

    @Volatile
    private var pushClient: EasClient? = null

    fun registerPushClient(client: EasClient) {
        pushClient = client
    }

    fun unregisterPushClient(client: EasClient) {
        if (pushClient === client) {
            pushClient = null
        }
    }

    fun interruptPushForCommand() {
        pushClient?.cancelPendingRequest()
    }
}

internal class EasHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal class EasRequestCancelledException(
    cause: Throwable,
) : IOException("EAS request cancelled", cause)

internal class EasClient(
    private val settings: ServerSettings,
) {
    private val endpoint: String by lazy {
        val easPath = settings.extra["easPath"]?.takeIf { it.startsWith("/") } ?: DEFAULT_EAS_PATH
        val portPart = if (settings.port == 443) "" else ":${settings.port}"
        "https://${settings.host}$portPart$easPath"
    }

    val deviceId: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${settings.host}|${settings.username}|DirectMail".toByteArray(StandardCharsets.UTF_8))
        digest.joinToString(separator = "") { "%02X".format(it) }.take(32)
    }

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var cancellationGeneration: Long = 0

    fun cancelPendingRequest() {
        cancellationGeneration++
        activeConnection?.disconnect()
    }

    fun provision(): String {
        val initial = post(
            command = "Provision",
            policyKey = "0",
            body = EasWbxml.provisionInitialRequest(),
            readTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        )
        val temporaryKey = parseProvisionPolicyKey(initial, "Provision iniziale")

        val acknowledged = post(
            command = "Provision",
            policyKey = temporaryKey,
            body = EasWbxml.provisionAcknowledgeRequest(temporaryKey),
            readTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        )
        return parseProvisionPolicyKey(acknowledged, "Provision acknowledge")
    }

    fun folderSync(
        policyKey: String,
        syncKey: String,
        existingInboxId: String? = null,
    ): EasFolderSyncResult {
        val response = post(
            command = "FolderSync",
            policyKey = policyKey,
            body = EasWbxml.folderSyncRequest(syncKey),
            readTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        )
        val root = EasWbxml.decode(response)
        require(root.page == EasWbxml.PAGE_FOLDER_HIERARCHY && root.tag == EasWbxml.FH_FOLDER_SYNC) {
            "FolderSync: root WBXML inattesa"
        }

        val status = root.childText(EasWbxml.PAGE_FOLDER_HIERARCHY, EasWbxml.FH_STATUS)?.toIntOrNull() ?: 0
        require(status == EAS_STATUS_SUCCESS) { "FolderSync status=$status" }

        val nextSyncKey = root.childText(EasWbxml.PAGE_FOLDER_HIERARCHY, EasWbxml.FH_SYNC_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: error("FolderSync: SyncKey assente")

        val changes = root.firstChild(EasWbxml.PAGE_FOLDER_HIERARCHY, EasWbxml.FH_CHANGES)
        var inboxId = existingInboxId
        val folders = mutableListOf<EasFolderNode>()

        if (changes != null) {
            val candidates = changes.children.filter {
                it.page == EasWbxml.PAGE_FOLDER_HIERARCHY &&
                    (it.tag == EasWbxml.FH_ADD || it.tag == FH_UPDATE)
            }
            for (folder in candidates) {
                val serverId = folder.childText(
                    EasWbxml.PAGE_FOLDER_HIERARCHY,
                    EasWbxml.FH_SERVER_ID,
                )?.takeIf { it.isNotBlank() } ?: continue
                val parentId = folder.childText(
                    EasWbxml.PAGE_FOLDER_HIERARCHY,
                    EasWbxml.FH_PARENT_ID,
                )?.takeIf { it.isNotBlank() }
                val displayName = folder.childText(
                    EasWbxml.PAGE_FOLDER_HIERARCHY,
                    EasWbxml.FH_DISPLAY_NAME,
                ).orEmpty()
                val type = folder.childText(
                    EasWbxml.PAGE_FOLDER_HIERARCHY,
                    EasWbxml.FH_TYPE,
                )?.toIntOrNull() ?: continue

                folders += EasFolderNode(
                    serverId = serverId,
                    parentId = parentId,
                    displayName = displayName,
                    type = type,
                )

                if (type == EAS_INBOX_FOLDER_TYPE) {
                    inboxId = serverId
                }
            }
        }

        return EasFolderSyncResult(
            syncKey = nextSyncKey,
            inboxId = inboxId ?: error("FolderSync: Inbox non trovata"),
            folders = folders,
        )
    }

    fun sync(
        policyKey: String,
        inboxId: String,
        syncKey: String,
        getChanges: Boolean,
        windowSize: Int = 50,
    ): EasSyncResult {
        val response = post(
            command = "Sync",
            policyKey = policyKey,
            body = EasWbxml.syncRequest(
                folderId = inboxId,
                syncKey = syncKey,
                getChanges = getChanges,
                windowSize = windowSize,
            ),
            readTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        )

        return parseSyncResponse(response)
    }

    fun changeReadState(
        policyKey: String,
        inboxId: String,
        syncKey: String,
        serverId: String,
        isRead: Boolean,
    ): EasSyncResult {
        val response = post(
            command = "Sync",
            policyKey = policyKey,
            body = EasWbxml.syncReadChangeRequest(
                folderId = inboxId,
                syncKey = syncKey,
                serverId = serverId,
                isRead = isRead,
            ),
            readTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        )
        return parseSyncResponse(response)
    }

    private fun parseSyncResponse(response: ByteArray): EasSyncResult {
        val root = EasWbxml.decode(response)
        require(root.page == EasWbxml.PAGE_AIRSYNC && root.tag == EasWbxml.SYNC_ROOT) {
            "Sync: root WBXML inattesa"
        }

        val rootStatus = root.childText(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_STATUS)?.toIntOrNull()
        if (rootStatus != null && rootStatus != EAS_STATUS_SUCCESS) {
            error("Sync status=$rootStatus")
        }

        val collections = root.firstChild(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_COLLECTIONS)
            ?: error("Sync: Collections assente")
        val collection = collections.firstChild(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_COLLECTION)
            ?: error("Sync: Collection assente")

        val collectionStatus = collection.childText(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_STATUS)?.toIntOrNull()
        if (collectionStatus != null && collectionStatus != EAS_STATUS_SUCCESS) {
            error("Sync collection status=$collectionStatus")
        }

        val nextSyncKey = collection.childText(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_SYNC_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: error("Sync: SyncKey assente")
        val moreAvailable =
            collection.firstChild(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_MORE_AVAILABLE) != null

        val responses = collection.firstChild(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_RESPONSES)
        responses
            ?.children
            ?.filter {
                it.page == EasWbxml.PAGE_AIRSYNC &&
                    it.tag == EasWbxml.SYNC_CHANGE
            }
            ?.forEach { change ->
                val status = change.childText(
                    EasWbxml.PAGE_AIRSYNC,
                    EasWbxml.SYNC_STATUS,
                )?.toIntOrNull() ?: error("Sync Change response: Status assente")
                if (status != EAS_STATUS_SUCCESS) {
                    val serverId = change.childText(
                        EasWbxml.PAGE_AIRSYNC,
                        EasWbxml.SYNC_SERVER_ID,
                    ).orEmpty()
                    error("Sync Change status=$status serverId=$serverId")
                }
            }

        val commands = collection.firstChild(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_COMMANDS)
        val items = commands
            ?.children
            ?.mapNotNull { command ->
                if (
                    command.page != EasWbxml.PAGE_AIRSYNC ||
                    (command.tag != EasWbxml.SYNC_ADD && command.tag != EasWbxml.SYNC_CHANGE)
                ) {
                    return@mapNotNull null
                }

                val serverId = command.childText(EasWbxml.PAGE_AIRSYNC, EasWbxml.SYNC_SERVER_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val appData = command.firstChild(
                    EasWbxml.PAGE_AIRSYNC,
                    EasWbxml.SYNC_APPLICATION_DATA,
                )

                EasSyncItem(
                    serverId = serverId,
                    subject = appData?.childText(EasWbxml.PAGE_EMAIL, EasWbxml.EMAIL_SUBJECT),
                    dateReceived = appData?.childText(EasWbxml.PAGE_EMAIL, EasWbxml.EMAIL_DATE_RECEIVED),
                    from = appData?.childText(EasWbxml.PAGE_EMAIL, EasWbxml.EMAIL_FROM),
                    isRead = appData
                        ?.childText(EasWbxml.PAGE_EMAIL, EasWbxml.EMAIL_READ)
                        ?.let { it == "1" || it.equals("true", ignoreCase = true) },
                )
            }
            .orEmpty()

        return EasSyncResult(
            syncKey = nextSyncKey,
            moreAvailable = moreAvailable,
            items = items,
        )
    }

    fun fetchInternetMessageId(
        policyKey: String,
        inboxId: String,
        serverId: String,
    ): String? {
        val response = post(
            command = "ItemOperations",
            policyKey = policyKey,
            body = EasWbxml.itemOperationsMimeHeaderRequest(
                folderId = inboxId,
                serverId = serverId,
            ),
            readTimeoutMs = DEFAULT_COMMAND_TIMEOUT_MS,
        )

        val root = EasWbxml.decode(response)
        require(root.page == EasWbxml.PAGE_ITEM_OPERATIONS && root.tag == EasWbxml.IO_ROOT) {
            "ItemOperations: root WBXML inattesa"
        }

        val rootStatus = root.childText(
            EasWbxml.PAGE_ITEM_OPERATIONS,
            EasWbxml.IO_STATUS,
        )?.toIntOrNull()
        if (rootStatus != null && rootStatus != EAS_STATUS_SUCCESS) {
            error("ItemOperations status=$rootStatus")
        }

        val fetch = root
            .firstChild(EasWbxml.PAGE_ITEM_OPERATIONS, EasWbxml.IO_RESPONSE)
            ?.firstChild(EasWbxml.PAGE_ITEM_OPERATIONS, EasWbxml.IO_FETCH)
            ?: error("ItemOperations: Fetch response assente")

        val fetchStatus = fetch.childText(
            EasWbxml.PAGE_ITEM_OPERATIONS,
            EasWbxml.IO_STATUS,
        )?.toIntOrNull() ?: error("ItemOperations Fetch: Status assente")
        if (fetchStatus != EAS_STATUS_SUCCESS) {
            error("ItemOperations Fetch status=$fetchStatus serverId=$serverId")
        }

        val properties = fetch.firstChild(
            EasWbxml.PAGE_ITEM_OPERATIONS,
            EasWbxml.IO_PROPERTIES,
        ) ?: return null
        val body = properties.firstChild(
            EasWbxml.PAGE_AIRSYNC_BASE,
            EasWbxml.ASB_BODY,
        ) ?: return null
        val bodyType = body.childText(
            EasWbxml.PAGE_AIRSYNC_BASE,
            EasWbxml.ASB_TYPE,
        )
        if (bodyType != "4") return null

        val mimeBytes = body.childBytes(
            EasWbxml.PAGE_AIRSYNC_BASE,
            EasWbxml.ASB_DATA,
        ) ?: return null

        return extractInternetMessageIdFromMime(mimeBytes)
    }

    private fun extractInternetMessageIdFromMime(mimeBytes: ByteArray): String? {
        if (mimeBytes.isEmpty()) return null

        // MIME headers are ASCII-compatible. ISO-8859-1 preserves every input byte so
        // malformed/non-UTF8 body bytes can't corrupt the header scan.
        val raw = String(mimeBytes, StandardCharsets.ISO_8859_1)
        val crlfEnd = raw.indexOf("\r\n\r\n")
        val lfEnd = raw.indexOf("\n\n")
        val headerEnd = listOf(crlfEnd, lfEnd)
            .filter { it >= 0 }
            .minOrNull()
            ?: raw.length
        val headers = raw.substring(0, headerEnd)
            .replace(Regex("\\r?\\n[ \\t]+"), " ")

        return Regex(
            pattern = "^Message-ID\\s*:\\s*(.+)$",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        ).find(headers)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun ping(
        policyKey: String,
        inboxId: String,
        heartbeatSeconds: Int,
    ): EasPingResult {
        val heartbeat = heartbeatSeconds.coerceIn(MIN_HEARTBEAT_SECONDS, MAX_HEARTBEAT_SECONDS)
        val response = post(
            command = "Ping",
            policyKey = policyKey,
            body = EasWbxml.pingRequest(inboxId, heartbeat),
            readTimeoutMs = (heartbeat + PING_TIMEOUT_MARGIN_SECONDS) * 1000,
        )
        val root = EasWbxml.decode(response)
        require(root.page == EasWbxml.PAGE_PING && root.tag == EasWbxml.PING_ROOT) {
            "Ping: root WBXML inattesa"
        }

        val status = root.childText(EasWbxml.PAGE_PING, EasWbxml.PING_STATUS)?.toIntOrNull()
            ?: error("Ping: Status assente")
        val changed = root.firstChild(EasWbxml.PAGE_PING, EasWbxml.PING_FOLDERS)
            ?.children
            ?.filter { it.page == EasWbxml.PAGE_PING && it.tag == EasWbxml.PING_FOLDER }
            ?.mapNotNull { it.text.takeIf(String::isNotBlank) }
            .orEmpty()
        val suggestedHeartbeat = root.childText(EasWbxml.PAGE_PING, EasWbxml.PING_HEARTBEAT)
            ?.toIntOrNull()

        return EasPingResult(
            status = status,
            changedFolderIds = changed,
            suggestedHeartbeatSeconds = suggestedHeartbeat,
        )
    }
    private fun parseProvisionPolicyKey(bytes: ByteArray, stage: String): String {
        val root = EasWbxml.decode(bytes)
        require(root.page == EasWbxml.PAGE_PROVISION && root.tag == EasWbxml.PROVISION_ROOT) {
            "$stage: root WBXML inattesa"
        }

        val rootStatus = root.childText(EasWbxml.PAGE_PROVISION, EasWbxml.PROVISION_STATUS)?.toIntOrNull()
        if (rootStatus != null && rootStatus != EAS_STATUS_SUCCESS) {
            error("$stage: status=$rootStatus")
        }

        val policies = root.firstChild(EasWbxml.PAGE_PROVISION, EasWbxml.PROVISION_POLICIES)
            ?: error("$stage: Policies assente")
        val policy = policies.firstChild(EasWbxml.PAGE_PROVISION, EasWbxml.PROVISION_POLICY)
            ?: error("$stage: Policy assente")
        val policyStatus = policy.childText(EasWbxml.PAGE_PROVISION, EasWbxml.PROVISION_STATUS)?.toIntOrNull()
        if (policyStatus != null && policyStatus != EAS_STATUS_SUCCESS) {
            error("$stage: policy status=$policyStatus")
        }

        return policy.childText(EasWbxml.PAGE_PROVISION, EasWbxml.PROVISION_POLICY_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: error("$stage: PolicyKey assente")
    }

    private fun post(
        command: String,
        policyKey: String,
        body: ByteArray,
        readTimeoutMs: Int,
    ): ByteArray {
        val password = settings.password ?: error("Password Exchange mancante")
        val query = buildString {
            append("?Cmd=").append(urlEncode(command))
            append("&User=").append(urlEncode(settings.username))
            append("&DeviceId=").append(urlEncode(deviceId))
            append("&DeviceType=SmartPhone")
        }

        val connection = URL(endpoint + query).openConnection() as HttpURLConnection
        val requestGeneration = cancellationGeneration
        activeConnection = connection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("MS-ASProtocolVersion", "14.1")
            connection.setRequestProperty("Content-Type", "application/vnd.ms-sync.wbxml")
            connection.setRequestProperty("Accept", "application/vnd.ms-sync.wbxml")
            connection.setRequestProperty("User-Agent", "DirectMail/Thunderbird-EAS")
            connection.setRequestProperty("X-MS-PolicyKey", policyKey.ifEmpty { "0" })
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(
                    "${settings.username}:$password".toByteArray(StandardCharsets.UTF_8),
                ),
            )

            connection.setFixedLengthStreamingMode(body.size)
            try {
                connection.outputStream.use { it.write(body) }
            } catch (e: java.net.SocketException) {
                if (requestGeneration != cancellationGeneration) {
                    throw EasRequestCancelledException(e)
                }
                throw e
            }

            val status = try {
                connection.responseCode
            } catch (e: java.net.SocketException) {
                if (requestGeneration != cancellationGeneration) {
                    throw EasRequestCancelledException(e)
                }
                throw e
            }
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val response = stream?.use { it.readBytes() } ?: ByteArray(0)

            if (status !in 200..299) {
                val suffix = if (response.isEmpty()) {
                    ""
                } else {
                    " · body=" + response.take(48).joinToString(" ") { "%02x".format(it) }
                }
                throw EasHttpException(status, "$command HTTP $status$suffix")
            }
            if (response.isEmpty()) error("$command HTTP $status con body vuoto")

            return response
        } catch (e: java.net.SocketException) {
            if (requestGeneration != cancellationGeneration) {
                throw EasRequestCancelledException(e)
            }
            throw e
        } finally {
            if (activeConnection === connection) {
                activeConnection = null
            }
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val DEFAULT_EAS_PATH = "/Microsoft-Server-ActiveSync"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val DEFAULT_COMMAND_TIMEOUT_MS = 45_000
        const val PING_TIMEOUT_MARGIN_SECONDS = 45
        const val MIN_HEARTBEAT_SECONDS = 60
        const val MAX_HEARTBEAT_SECONDS = 900
        const val EAS_STATUS_SUCCESS = 1
        const val EAS_INBOX_FOLDER_TYPE = 2
        const val FH_UPDATE = 0x11
    }
}