package it.directmail.backend.exchange

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import java.util.Locale
import net.thunderbird.core.common.mail.Flag

internal data class ExchangeSearchResult(
    val messageServerIds: List<String>,
    val flagsByServerId: Map<String, Set<Flag>>,
)

internal fun EwsClient.searchWithCompatibilityFallback(
    folderServerId: String,
    query: String?,
    seenState: Boolean?,
    flaggedState: Boolean?,
    performFullTextSearch: Boolean,
): ExchangeSearchResult {
    val primary = runCatching {
        searchItems(
            folderServerId = folderServerId,
            query = query,
            seenState = seenState,
            flaggedState = flaggedState,
            performFullTextSearch = performFullTextSearch,
        )
    }

    if (primary.isSuccess) {
        val ids = primary.getOrThrow()
        val flags = runCatching {
            collectFlagsForSearchResults(folderServerId, ids.toSet())
        }.getOrDefault(emptyMap())
        return ExchangeSearchResult(ids, flags)
    }

    val primaryFailure = primary.exceptionOrNull()
    return try {
        searchViaProvenFindItem(
            folderServerId = folderServerId,
            query = query,
            seenState = seenState,
            flaggedState = flaggedState,
            performFullTextSearch = performFullTextSearch,
        )
    } catch (fallbackFailure: Exception) {
        primaryFailure?.let(fallbackFailure::addSuppressed)
        throw fallbackFailure
    }
}

private fun EwsClient.searchViaProvenFindItem(
    folderServerId: String,
    query: String?,
    seenState: Boolean?,
    flaggedState: Boolean?,
    performFullTextSearch: Boolean,
): ExchangeSearchResult {
    val normalizedQuery = query
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)
    val matches = linkedSetOf<String>()
    val flags = linkedMapOf<String, Set<Flag>>()
    var offset = 0
    var pageCount = 0
    var includesLast = false

    while (!includesLast && pageCount++ < MAX_FALLBACK_SEARCH_PAGES && matches.size < FALLBACK_SEARCH_MATCH_LIMIT) {
        val page = findItems(
            folderServerId = folderServerId,
            maxEntries = FALLBACK_SEARCH_PAGE_SIZE,
            offset = offset,
        )
        val candidates = page.items.filter { item ->
            (seenState == null || item.isRead == seenState) &&
                (flaggedState == null || item.isFlagged == flaggedState)
        }

        if (normalizedQuery == null) {
            candidates.forEach { item ->
                if (matches.size < FALLBACK_SEARCH_MATCH_LIMIT) {
                    matches += item.serverId
                    flags[item.serverId] = searchFlagsForItem(item)
                }
            }
        } else {
            candidates.chunked(FALLBACK_MIME_BATCH_SIZE).forEach { batch ->
                if (matches.size >= FALLBACK_SEARCH_MATCH_LIMIT) return@forEach

                val ids = batch.map { it.serverId }
                val batchMessages = runCatching { getMimeMessages(ids) }.getOrDefault(emptyMap())

                batch.forEach { item ->
                    if (matches.size >= FALLBACK_SEARCH_MATCH_LIMIT) return@forEach
                    val message = batchMessages[item.serverId]
                        ?: runCatching { getMimeMessage(item.serverId) }.getOrNull()
                        ?: return@forEach

                    if (messageMatchesCompatibilitySearch(message, normalizedQuery, performFullTextSearch)) {
                        matches += item.serverId
                        flags[item.serverId] = searchFlagsForItem(item)
                    }
                }
            }
        }

        includesLast = page.includesLastItemInRange
        if (includesLast || page.items.isEmpty() || matches.size >= FALLBACK_SEARCH_MATCH_LIMIT) break

        val nextOffset = page.nextOffset ?: (offset + page.items.size)
        if (nextOffset <= offset) break
        offset = nextOffset
    }

    return ExchangeSearchResult(
        messageServerIds = matches.take(FALLBACK_SEARCH_MATCH_LIMIT),
        flagsByServerId = flags,
    )
}

private fun EwsClient.collectFlagsForSearchResults(
    folderServerId: String,
    targetIds: Set<String>,
): Map<String, Set<Flag>> {
    if (targetIds.isEmpty()) return emptyMap()

    val remaining = targetIds.toMutableSet()
    val flags = linkedMapOf<String, Set<Flag>>()
    var offset = 0
    var pageCount = 0
    var includesLast = false

    while (!includesLast && pageCount++ < MAX_FALLBACK_SEARCH_PAGES && remaining.isNotEmpty()) {
        val page = findItems(
            folderServerId = folderServerId,
            maxEntries = FALLBACK_SEARCH_PAGE_SIZE,
            offset = offset,
        )

        page.items.forEach { item ->
            if (item.serverId in remaining) {
                flags[item.serverId] = searchFlagsForItem(item)
                remaining -= item.serverId
            }
        }

        includesLast = page.includesLastItemInRange
        if (includesLast || page.items.isEmpty() || remaining.isEmpty()) break

        val nextOffset = page.nextOffset ?: (offset + page.items.size)
        if (nextOffset <= offset) break
        offset = nextOffset
    }

    return flags
}

internal fun searchFlagsForItem(item: EwsItem): Set<Flag> = buildSet {
    if (item.isRead) add(Flag.SEEN)
    if (item.isFlagged) add(Flag.FLAGGED)
    if (item.isAnswered) add(Flag.ANSWERED)
    if (item.isForwarded) add(Flag.FORWARDED)
}

internal fun messageMatchesCompatibilitySearch(
    message: Message,
    normalizedQuery: String,
    performFullTextSearch: Boolean,
): Boolean {
    val metadata = buildList {
        message.subject?.let(::add)
        message.from.joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
        message.getRecipients(RecipientType.TO).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
        message.getRecipients(RecipientType.CC).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
        message.getRecipients(RecipientType.BCC).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
    }

    if (metadata.any { it.lowercase(Locale.ROOT).contains(normalizedQuery) }) {
        return true
    }
    if (!performFullTextSearch) return false

    val textParts = listOfNotNull(
        MimeUtility.findFirstPartByMimeType(message, "text/plain"),
        MimeUtility.findFirstPartByMimeType(message, "text/html"),
    ).distinct()

    return textParts.any { part ->
        runCatching { MessageExtractor.getTextFromPart(part) }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.contains(normalizedQuery)
            ?: false
    }
}

private const val FALLBACK_SEARCH_PAGE_SIZE = 250
private const val MAX_FALLBACK_SEARCH_PAGES = 100
private const val FALLBACK_SEARCH_MATCH_LIMIT = 500
private const val FALLBACK_MIME_BATCH_SIZE = 20
