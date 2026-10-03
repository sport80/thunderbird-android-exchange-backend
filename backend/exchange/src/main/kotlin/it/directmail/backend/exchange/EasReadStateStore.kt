package it.directmail.backend.exchange

import com.fsck.k9.backend.api.BackendStorage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

internal object EasReadStateStore {
    const val INBOX_SERVER_ID = "inbox"

    const val POLICY_KEY = "directmail.eas.policy-key"
    const val FOLDER_SYNC_KEY = "directmail.eas.folder-sync-key"
    const val INBOX_ID = "directmail.eas.inbox-id"
    const val MAIL_SYNC_KEY = "directmail.eas.mail-sync-key"

    private const val READ_MAP_KEY = "directmail.eas.read-map.v1"
    private const val READ_MAP_VERSION_KEY = "directmail.eas.read-map-version"
    const val READ_MAP_VERSION = "2"

    private const val AMBIGUOUS = "*"
    private const val MAX_MAPPING_ENTRIES = 2000

    fun needsBootstrap(storage: BackendStorage): Boolean =
        storage.getExtraString(READ_MAP_VERSION_KEY) != READ_MAP_VERSION

    fun beginBootstrap(storage: BackendStorage) {
        storage.setExtraString(READ_MAP_KEY, "")
        storage.setExtraString(MAIL_SYNC_KEY, "")
        storage.setExtraString(READ_MAP_VERSION_KEY, "")
    }

    fun finishBootstrap(storage: BackendStorage) {
        storage.setExtraString(READ_MAP_VERSION_KEY, READ_MAP_VERSION)
    }

    fun requestBootstrap(storage: BackendStorage) {
        storage.setExtraString(READ_MAP_VERSION_KEY, "")
    }

    fun bootstrap(
        storage: BackendStorage,
        client: EasClient,
        policyKey: String,
        inboxId: String,
        maxPages: Int = 100,
        windowSize: Int = 100,
    ) {
        beginBootstrap(storage)

        val initial = client.sync(
            policyKey = policyKey,
            inboxId = inboxId,
            syncKey = "0",
            getChanges = false,
        )
        var syncKey = initial.syncKey
        storage.setExtraString(MAIL_SYNC_KEY, syncKey)

        var pageCount = 0
        var moreAvailable: Boolean
        do {
            if (pageCount++ >= maxPages) {
                finishBootstrap(storage)
                return
            }

            val result = client.sync(
                policyKey = policyKey,
                inboxId = inboxId,
                syncKey = syncKey,
                getChanges = true,
                windowSize = windowSize,
            )
            record(storage, result.items)
            syncKey = result.syncKey
            storage.setExtraString(MAIL_SYNC_KEY, syncKey)
            moreAvailable = result.moreAvailable
        } while (moreAvailable)

        finishBootstrap(storage)
    }

    fun record(storage: BackendStorage, items: List<EasSyncItem>) {
        if (items.isEmpty()) return

        val map = readMap(storage)
        items.forEach { item ->
            val fingerprint = fingerprint(
                subject = item.subject,
                dateReceived = item.dateReceived,
                from = item.from,
            ) ?: return@forEach

            val previous = map[fingerprint]
            map[fingerprint] = when {
                previous == null -> item.serverId
                previous == item.serverId -> previous
                else -> AMBIGUOUS
            }
        }

        while (map.size > MAX_MAPPING_ENTRIES) {
            map.remove(map.keys.first())
        }
        writeMap(storage, map)
    }

    fun lookup(storage: BackendStorage, fingerprint: String): String? =
        readMap(storage)[fingerprint]?.takeUnless { it == AMBIGUOUS }

    fun lookupByInternetMessageId(storage: BackendStorage, internetMessageId: String): String? {
        val key = internetMessageIdKey(internetMessageId) ?: return null
        return readMap(storage)[key]?.takeUnless { it == AMBIGUOUS }
    }

    fun recordInternetMessageId(
        storage: BackendStorage,
        internetMessageId: String,
        serverId: String,
    ) {
        val key = internetMessageIdKey(internetMessageId) ?: return
        val map = readMap(storage)
        val previous = map[key]
        map[key] = when {
            previous == null -> serverId
            previous == serverId -> previous
            else -> AMBIGUOUS
        }
        while (map.size > MAX_MAPPING_ENTRIES) {
            map.remove(map.keys.first())
        }
        writeMap(storage, map)
    }

    fun candidateKey(subject: String?, dateReceived: String?): String? {
        val date = normalizeDate(dateReceived) ?: return null
        val normalizedSubject = normalizeSubject(subject)
        if (normalizedSubject.isEmpty()) return null
        return "$date\n$normalizedSubject"
    }

    fun candidateDateKey(dateReceived: String?): String? = normalizeDate(dateReceived)

    fun candidateSubjectKey(subject: String?): String? =
        normalizeSubject(subject).takeIf { it.isNotEmpty() }

    fun normalizeInternetMessageId(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return text
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun collectInboxItems(
        storage: BackendStorage,
        client: EasClient,
        policyKey: String,
        inboxId: String,
        maxPages: Int = 100,
        windowSize: Int = 100,
    ): List<EasSyncItem> {
        val initial = client.sync(
            policyKey = policyKey,
            inboxId = inboxId,
            syncKey = "0",
            getChanges = false,
        )
        var syncKey = initial.syncKey
        storage.setExtraString(MAIL_SYNC_KEY, syncKey)

        val items = mutableListOf<EasSyncItem>()
        var pageCount = 0
        var moreAvailable: Boolean
        do {
            if (pageCount++ >= maxPages) break
            val result = client.sync(
                policyKey = policyKey,
                inboxId = inboxId,
                syncKey = syncKey,
                getChanges = true,
                windowSize = windowSize,
            )
            items += result.items
            record(storage, result.items)
            syncKey = result.syncKey
            storage.setExtraString(MAIL_SYNC_KEY, syncKey)
            moreAvailable = result.moreAvailable
        } while (moreAvailable)

        return items
    }

    private fun internetMessageIdKey(internetMessageId: String?): String? {
        val normalized = normalizeInternetMessageId(internetMessageId) ?: return null
        return "mid:" + hash(normalized)
    }

    fun fingerprint(
        subject: String?,
        dateReceived: String?,
        from: String?,
    ): String? {
        val date = normalizeDate(dateReceived) ?: return null
        val normalizedSubject = normalizeSubject(subject)
        val normalizedFrom = normalizeAddress(from)

        val raw = "$date\n$normalizedSubject\n$normalizedFrom"
        return hash(raw)
    }

    private fun normalizeSubject(value: String?): String =
        value
            .orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()

    private fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    private fun normalizeDate(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // EAS commonly truncates DateReceived to whole seconds while EWS can expose
        // fractional seconds. Normalize both protocols to epoch seconds so the same
        // message doesn't fail ID mapping solely because of sub-second precision.
        return runCatching { Instant.parse(text).epochSecond.toString() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().epochSecond.toString() }
            .getOrElse { text.lowercase() }
    }

    private fun normalizeAddress(value: String?): String {
        val text = value.orEmpty().trim()
        val angle = Regex("<([^<>]+)>").find(text)?.groupValues?.getOrNull(1)
        return (angle ?: text)
            .trim()
            .trim('"')
            .lowercase()
    }

    private fun readMap(storage: BackendStorage): LinkedHashMap<String, String> {
        val encoded = storage.getExtraString(READ_MAP_KEY).orEmpty()
        val result = linkedMapOf<String, String>()
        encoded.lineSequence().forEach { line ->
            val separator = line.indexOf('\t')
            if (separator <= 0 || separator == line.lastIndex) return@forEach

            val fingerprint = line.substring(0, separator)
            val encodedId = line.substring(separator + 1)
            val serverId = if (encodedId == AMBIGUOUS) {
                AMBIGUOUS
            } else {
                runCatching {
                    String(
                        Base64.getUrlDecoder().decode(encodedId),
                        StandardCharsets.UTF_8,
                    )
                }.getOrNull() ?: return@forEach
            }
            result[fingerprint] = serverId
        }
        return result
    }

    private fun writeMap(storage: BackendStorage, map: LinkedHashMap<String, String>) {
        val encoded = map.entries.joinToString(separator = "\n") { (fingerprint, serverId) ->
            val encodedId = if (serverId == AMBIGUOUS) {
                AMBIGUOUS
            } else {
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(serverId.toByteArray(StandardCharsets.UTF_8))
            }
            "$fingerprint\t$encodedId"
        }
        storage.setExtraString(READ_MAP_KEY, encoded)
    }
}
