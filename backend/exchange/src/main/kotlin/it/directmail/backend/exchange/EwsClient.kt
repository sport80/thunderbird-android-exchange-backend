package it.directmail.backend.exchange

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Message.RecipientType
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.internet.AddressHeaderBuilder
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeBodyPart
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.MimeMultipart
import com.fsck.k9.mail.internet.MimeUtility
import com.fsck.k9.mail.internet.TextBody
import com.fsck.k9.mailstore.BinaryMemoryBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

internal data class EwsFolder(
    val serverId: String,
    val displayName: String,
    val type: FolderType,
)

internal data class EwsItem(
    val serverId: String,
    val isRead: Boolean,
    val isFlagged: Boolean,
    val isAnswered: Boolean = false,
    val isForwarded: Boolean = false,
)

internal data class EwsItemPage(
    val items: List<EwsItem>,
    val includesLastItemInRange: Boolean,
    val nextOffset: Int?,
)

internal data class EwsSearchItem(
    val serverId: String,
    val subject: String?,
    val from: String?,
    val displayTo: String?,
    val displayCc: String?,
    val displayBcc: String?,
    val isRead: Boolean,
    val isFlagged: Boolean,
)

internal data class EwsSearchItemPage(
    val items: List<EwsSearchItem>,
    val includesLastItemInRange: Boolean,
    val nextOffset: Int?,
)

internal sealed interface EwsSyncChange

internal data class EwsSyncCreate(val item: EwsItem) : EwsSyncChange
internal data class EwsSyncUpdate(val item: EwsItem) : EwsSyncChange
internal data class EwsSyncDelete(val serverId: String) : EwsSyncChange
internal data class EwsSyncReadFlag(val serverId: String, val isRead: Boolean) : EwsSyncChange

internal data class EwsSyncPage(
    val syncState: String,
    val includesLastItemInRange: Boolean,
    val changes: List<EwsSyncChange>,
)

internal data class EwsReadMappingIdentity(
    val subject: String?,
    val dateReceived: String?,
    val from: String?,
    val internetMessageId: String?,
)

internal data class EwsSendResult(
    val sentCopyConfirmed: Boolean,
    val details: String,
)

internal data class EwsMessageDownload(
    val message: MimeMessage,
    val isPartial: Boolean,
)

private data class EwsAttachmentInfo(
    val attachmentId: String,
    val name: String?,
    val contentType: String?,
    val contentId: String?,
    val size: Int?,
    val isInline: Boolean,
)

internal class EwsHttpException(
    val statusCode: Int,
    val responseBody: String? = null,
) : IllegalStateException(
    buildString {
        append("EWS HTTP ")
        append(statusCode)
        responseBody?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(": ")
            append(it.take(HTTP_ERROR_BODY_LIMIT))
        }
    },
) {
    private companion object {
        const val HTTP_ERROR_BODY_LIMIT = 2_048
    }
}

private data class EwsFolderNode(
    val serverId: String,
    val parentServerId: String?,
    val displayName: String,
)

private data class EwsStandardFolder(
    val distinguishedId: String,
    val actualServerId: String,
    val displayName: String,
    val type: FolderType,
)

internal class EwsClient(
    private val settings: ServerSettings,
) {
    private val endpoint: URL by lazy {
        val ewsPath = settings.extra["ewsPath"]?.takeIf { it.startsWith("/") } ?: DEFAULT_EWS_PATH
        val portPart = if (settings.port == 443) "" else ":${settings.port}"
        URL("https://${settings.host}$portPart$ewsPath")
    }
    fun getFolders(): List<EwsFolder> {
        val resolvedStandardFolders = STANDARD_FOLDERS.mapNotNull { (distinguishedId, type) ->
            runCatching { resolveStandardFolder(distinguishedId, type) }.getOrNull()
        }

        if (resolvedStandardFolders.none { it.type == FolderType.INBOX }) {
            error("EWS GetFolder: Inbox standard non risolta")
        }

        val standardByActualId = resolvedStandardFolders.associateBy { it.actualServerId }
        val standardFolders = resolvedStandardFolders.map { folder ->
            EwsFolder(
                serverId = folder.distinguishedId,
                displayName = folder.displayName,
                type = folder.type,
            )
        }

        val nodes = findMailFolders()
        val nodesById = nodes.associateBy { it.serverId }

        fun pathFor(node: EwsFolderNode, seen: Set<String> = emptySet()): String {
            if (node.serverId in seen) return node.displayName

            val parentId = node.parentServerId ?: return node.displayName
            val standardParent = standardByActualId[parentId]
            if (standardParent != null) {
                return "${standardParent.displayName}/${node.displayName}"
            }

            val parent = nodesById[parentId] ?: return node.displayName
            return "${pathFor(parent, seen + node.serverId)}/${node.displayName}"
        }

        val customFolders = nodes
            .filterNot { it.serverId in standardByActualId }
            .map { node ->
                EwsFolder(
                    serverId = node.serverId,
                    displayName = pathFor(node),
                    type = FolderType.REGULAR,
                )
            }

        return (standardFolders + customFolders).distinctBy { it.serverId }
    }

    private fun actionFlags(element: Element): Pair<Boolean, Boolean> {
        val iconIndex = extendedIntegerProperty(element, "0x1080")
        val lastVerb = extendedIntegerProperty(element, "0x1081")
        val messageStatus = extendedIntegerProperty(element, "0x0E17") ?: 0

        val answered =
            iconIndex == MAIL_ICON_REPLIED ||
                lastVerb == LAST_VERB_REPLY_TO ||
                lastVerb == LAST_VERB_REPLY_ALL ||
                (messageStatus and MSGSTATUS_ANSWERED) != 0
        val forwarded = iconIndex == MAIL_ICON_FORWARDED || lastVerb == LAST_VERB_FORWARDED
        return answered to forwarded
    }

    private fun extendedIntegerProperty(element: Element, propertyTag: String): Int? {
        val properties = element.getElementsByTagNameNS(TYPES, "ExtendedProperty")
        for (i in 0 until properties.length) {
            val property = properties.item(i) as? Element ?: continue
            val uri = property.getElementsByTagNameNS(TYPES, "ExtendedFieldURI").item(0) as? Element ?: continue
            if (!uri.getAttribute("PropertyTag").equals(propertyTag, ignoreCase = true)) continue
            return property.getElementsByTagNameNS(TYPES, "Value").item(0)?.textContent?.trim()?.toIntOrNull()
        }
        return null
    }
    fun syncFolderItems(
        folderServerId: String,
        syncState: String?,
        maxChanges: Int = SYNC_FOLDER_ITEMS_PAGE_SIZE,
    ): EwsSyncPage {
        val syncStateXml = syncState
            ?.takeIf { it.isNotBlank() }
            ?.let { "<m:SyncState>${xmlEscape(it)}</m:SyncState>" }
            .orEmpty()

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:SyncFolderItems>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="message:IsRead"/>
                      <t:FieldURI FieldURI="item:Flag"/>
                      <t:ExtendedFieldURI PropertyTag="0x1080" PropertyType="Integer"/>
                      <t:ExtendedFieldURI PropertyTag="0x1081" PropertyType="Integer"/>
                      <t:ExtendedFieldURI PropertyTag="0x0E17" PropertyType="Integer"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:SyncFolderId>
                    ${folderIdXml(folderServerId)}
                  </m:SyncFolderId>
                  $syncStateXml
                  <m:MaxChangesReturned>${maxChanges.coerceIn(1, 512)}</m:MaxChangesReturned>
                  <m:SyncScope>NormalItems</m:SyncScope>
                </m:SyncFolderItems>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "SyncFolderItems")

        val nextSyncState = document.getElementsByTagNameNS(MESSAGES, "SyncState")
            .item(0)
            ?.textContent
            ?.takeIf { it.isNotBlank() }
            ?: error("EWS SyncFolderItems: SyncState assente")
        val includesLast = document.getElementsByTagNameNS(MESSAGES, "IncludesLastItemInRange")
            .item(0)
            ?.textContent
            ?.equals("true", ignoreCase = true)
            ?: true

        val changes = mutableListOf<EwsSyncChange>()
        val changesElement = document.getElementsByTagNameNS(MESSAGES, "Changes").item(0) as? Element
        if (changesElement != null) {
            val children = changesElement.childNodes
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                val itemId = element.getElementsByTagNameNS(TYPES, "ItemId").item(0) as? Element
                val serverId = itemId
                    ?.getAttribute("Id")
                    ?.takeIf { it.isNotBlank() }
                    ?: continue

                when (element.localName) {
                    "Create" -> {
                        val isRead = element.getElementsByTagNameNS(TYPES, "IsRead")
                            .item(0)
                            ?.textContent
                            ?.equals("true", ignoreCase = true)
                            ?: false
                        val isFlagged = element.getElementsByTagNameNS(TYPES, "FlagStatus")
                            .item(0)
                            ?.textContent
                            ?.equals("Flagged", ignoreCase = true)
                            ?: false
                        val (isAnswered, isForwarded) = actionFlags(element)
                        changes += EwsSyncCreate(EwsItem(serverId, isRead, isFlagged, isAnswered, isForwarded))
                    }

                    "Update" -> {
                        val isRead = element.getElementsByTagNameNS(TYPES, "IsRead")
                            .item(0)
                            ?.textContent
                            ?.equals("true", ignoreCase = true)
                            ?: false
                        val isFlagged = element.getElementsByTagNameNS(TYPES, "FlagStatus")
                            .item(0)
                            ?.textContent
                            ?.equals("Flagged", ignoreCase = true)
                            ?: false
                        val (isAnswered, isForwarded) = actionFlags(element)
                        changes += EwsSyncUpdate(EwsItem(serverId, isRead, isFlagged, isAnswered, isForwarded))
                    }

                    "Delete" -> changes += EwsSyncDelete(serverId)

                    "ReadFlagChange" -> {
                        val isRead = element.getElementsByTagNameNS(TYPES, "IsRead")
                            .item(0)
                            ?.textContent
                            ?.equals("true", ignoreCase = true)
                            ?: false
                        changes += EwsSyncReadFlag(serverId, isRead)
                    }
                }
            }
        }

        return EwsSyncPage(
            syncState = nextSyncState,
            includesLastItemInRange = includesLast,
            changes = changes,
        )
    }

    fun findItems(folderServerId: String, maxEntries: Int, offset: Int = 0): EwsItemPage {
        val parentFolderId = folderIdXml(folderServerId)

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:FindItem Traversal="Shallow">
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                      <t:FieldURI FieldURI="message:From"/>
                      <t:FieldURI FieldURI="message:IsRead"/>
                      <t:FieldURI FieldURI="item:Flag"/>
                      <t:ExtendedFieldURI PropertyTag="0x1080" PropertyType="Integer"/>
                      <t:ExtendedFieldURI PropertyTag="0x1081" PropertyType="Integer"/>
                      <t:ExtendedFieldURI PropertyTag="0x0E17" PropertyType="Integer"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:IndexedPageItemView MaxEntriesReturned="${maxEntries.coerceIn(1, 250)}"
                    Offset="${offset.coerceAtLeast(0)}" BasePoint="Beginning"/>
                  <m:SortOrder>
                    <t:FieldOrder Order="Descending">
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                    </t:FieldOrder>
                  </m:SortOrder>
                  <m:ParentFolderIds>
                    $parentFolderId
                  </m:ParentFolderIds>
                </m:FindItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "FindItem")

        val rootFolder = document.getElementsByTagNameNS(MESSAGES, "RootFolder")
            .item(0) as? Element
        val includesLast = rootFolder
            ?.getAttribute("IncludesLastItemInRange")
            ?.equals("true", ignoreCase = true)
            ?: true

        val nextOffset = rootFolder
            ?.getAttribute("IndexedPagingOffset")
            ?.toIntOrNull()

        val messages = document.getElementsByTagNameNS(TYPES, "Message")
        val items = buildList {
            for (i in 0 until messages.length) {
                val message = messages.item(i) as? Element ?: continue
                val itemIds = message.getElementsByTagNameNS(TYPES, "ItemId")
                val itemId = itemIds.item(0) as? Element ?: continue
                val serverId = itemId.getAttribute("Id").takeIf { it.isNotBlank() } ?: continue
                val isRead = message.getElementsByTagNameNS(TYPES, "IsRead")
                    .item(0)
                    ?.textContent
                    ?.equals("true", ignoreCase = true)
                    ?: false
                val isFlagged = message.getElementsByTagNameNS(TYPES, "FlagStatus")
                    .item(0)
                    ?.textContent
                    ?.equals("Flagged", ignoreCase = true)
                    ?: false

                val (isAnswered, isForwarded) = actionFlags(message)
                add(
                    EwsItem(
                        serverId = serverId,
                        isRead = isRead,
                        isFlagged = isFlagged,
                        isAnswered = isAnswered,
                        isForwarded = isForwarded,
                    ),
                )
            }
        }

        return EwsItemPage(items, includesLast, nextOffset)
    }

    fun searchItems(
        folderServerId: String,
        query: String?,
        seenState: Boolean?,
        flaggedState: Boolean?,
        performFullTextSearch: Boolean,
    ): List<String> {
        val normalizedQuery = query
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
        val matches = linkedSetOf<String>()
        var offset = 0
        var pageCount = 0
        var includesLast = false

        while (!includesLast && pageCount++ < MAX_SEARCH_PAGES && matches.size < SEARCH_MATCH_LIMIT) {
            val page = findSearchItems(
                folderServerId = folderServerId,
                maxEntries = SEARCH_PAGE_SIZE,
                offset = offset,
            )

            val fullTextCandidates = mutableListOf<String>()
            page.items.forEach { item ->
                if (seenState != null && item.isRead != seenState) return@forEach
                if (flaggedState != null && item.isFlagged != flaggedState) return@forEach

                if (normalizedQuery == null || searchMetadataMatches(item, normalizedQuery)) {
                    matches += item.serverId
                } else if (performFullTextSearch) {
                    fullTextCandidates += item.serverId
                }
            }

            if (performFullTextSearch && normalizedQuery != null && fullTextCandidates.isNotEmpty()) {
                val messages = runCatching { getMimeMessages(fullTextCandidates) }.getOrDefault(emptyMap())
                fullTextCandidates.forEach { serverId ->
                    val message = messages[serverId] ?: return@forEach
                    if (messageContainsSearchText(message, normalizedQuery)) {
                        matches += serverId
                    }
                }
            }

            includesLast = page.includesLastItemInRange
            if (includesLast || page.items.isEmpty() || matches.size >= SEARCH_MATCH_LIMIT) break

            val nextOffset = page.nextOffset ?: (offset + page.items.size)
            if (nextOffset <= offset) break
            offset = nextOffset
        }

        return matches.take(SEARCH_MATCH_LIMIT)
    }

    private fun findSearchItems(
        folderServerId: String,
        maxEntries: Int,
        offset: Int,
    ): EwsSearchItemPage {
        val request = searchFindItemRequestXml(folderServerId, maxEntries, offset)
        val document = post(request)
        requireNoError(document, "FindItem search scan")

        val rootFolder = document.getElementsByTagNameNS(MESSAGES, "RootFolder")
            .item(0) as? Element
        val includesLast = rootFolder
            ?.getAttribute("IncludesLastItemInRange")
            ?.equals("true", ignoreCase = true)
            ?: true
        val nextOffset = rootFolder
            ?.getAttribute("IndexedPagingOffset")
            ?.toIntOrNull()

        val messages = document.getElementsByTagNameNS(TYPES, "Message")
        val items = buildList {
            for (i in 0 until messages.length) {
                val message = messages.item(i) as? Element ?: continue
                val itemId = message.getElementsByTagNameNS(TYPES, "ItemId").item(0) as? Element ?: continue
                val serverId = itemId.getAttribute("Id").takeIf { it.isNotBlank() } ?: continue
                val subject = message.getElementsByTagNameNS(TYPES, "Subject")
                    .item(0)
                    ?.textContent
                    ?.takeIf { it.isNotBlank() }
                val fromElement = message.getElementsByTagNameNS(TYPES, "From").item(0) as? Element
                val from = fromElement
                    ?.let { element ->
                        listOfNotNull(
                            element.getElementsByTagNameNS(TYPES, "Name").item(0)?.textContent,
                            element.getElementsByTagNameNS(TYPES, "EmailAddress").item(0)?.textContent,
                        )
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .takeIf { it.isNotBlank() }
                    }
                val displayTo = message.getElementsByTagNameNS(TYPES, "DisplayTo")
                    .item(0)
                    ?.textContent
                    ?.takeIf { it.isNotBlank() }
                val displayCc = message.getElementsByTagNameNS(TYPES, "DisplayCc")
                    .item(0)
                    ?.textContent
                    ?.takeIf { it.isNotBlank() }
                val displayBcc = message.getElementsByTagNameNS(TYPES, "DisplayBcc")
                    .item(0)
                    ?.textContent
                    ?.takeIf { it.isNotBlank() }
                val isRead = message.getElementsByTagNameNS(TYPES, "IsRead")
                    .item(0)
                    ?.textContent
                    ?.equals("true", ignoreCase = true)
                    ?: false
                val isFlagged = message.getElementsByTagNameNS(TYPES, "FlagStatus")
                    .item(0)
                    ?.textContent
                    ?.equals("Flagged", ignoreCase = true)
                    ?: false

                add(
                    EwsSearchItem(
                        serverId = serverId,
                        subject = subject,
                        from = from,
                        displayTo = displayTo,
                        displayCc = displayCc,
                        displayBcc = displayBcc,
                        isRead = isRead,
                        isFlagged = isFlagged,
                    ),
                )
            }
        }

        return EwsSearchItemPage(items, includesLast, nextOffset)
    }

    private fun searchFindItemRequestXml(folderServerId: String, maxEntries: Int, offset: Int): String {
        val parentFolderId = folderIdXml(folderServerId)
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:FindItem Traversal="Shallow">
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                      <t:FieldURI FieldURI="message:From"/>
                      <t:FieldURI FieldURI="item:DisplayTo"/>
                      <t:FieldURI FieldURI="item:DisplayCc"/>
                      <t:FieldURI FieldURI="item:DisplayBcc"/>
                      <t:FieldURI FieldURI="message:IsRead"/>
                      <t:FieldURI FieldURI="item:Flag"/>
                      <t:ExtendedFieldURI PropertyTag="0x1080" PropertyType="Integer"/>
                      <t:ExtendedFieldURI PropertyTag="0x1081" PropertyType="Integer"/>
                      <t:ExtendedFieldURI PropertyTag="0x0E17" PropertyType="Integer"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:IndexedPageItemView MaxEntriesReturned="${maxEntries.coerceIn(1, SEARCH_PAGE_SIZE)}"
                    Offset="${offset.coerceAtLeast(0)}" BasePoint="Beginning"/>
                  <m:SortOrder>
                    <t:FieldOrder Order="Descending">
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                    </t:FieldOrder>
                  </m:SortOrder>
                  <m:ParentFolderIds>
                    $parentFolderId
                  </m:ParentFolderIds>
                </m:FindItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun searchMetadataMatches(item: EwsSearchItem, normalizedQuery: String): Boolean {
        return sequenceOf(item.subject, item.from, item.displayTo, item.displayCc, item.displayBcc)
            .filterNotNull()
            .any { value -> value.lowercase(Locale.ROOT).contains(normalizedQuery) }
    }

    private fun messageContainsSearchText(message: MimeMessage, normalizedQuery: String): Boolean {
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
    fun getSyncMessages(itemIds: List<String>): Map<String, EwsMessageDownload> {
        if (itemIds.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, EwsMessageDownload>()
        itemIds.distinct().chunked(MIME_GET_BATCH_SIZE).forEach { batch ->
            val partial = runCatching { getPartialMessagesBatch(batch) }.getOrNull()
            if (partial != null && partial.size == batch.size) {
                partial.forEach { (serverId, message) ->
                    result[serverId] = EwsMessageDownload(message, isPartial = true)
                }
            } else {
                getMimeMessages(batch).forEach { (serverId, message) ->
                    result[serverId] = EwsMessageDownload(message, isPartial = false)
                }
            }
        }
        return result
    }

    fun getPartialMessage(itemId: String): EwsMessageDownload {
        val partial = runCatching { getPartialMessagesBatch(listOf(itemId))[itemId] }.getOrNull()
        return if (partial != null) {
            EwsMessageDownload(partial, isPartial = true)
        } else {
            EwsMessageDownload(getMimeMessage(itemId), isPartial = false)
        }
    }

    fun getCompleteMessage(itemId: String): MimeMessage {
        val request = completeGetItemRequestXml(itemId)
        val document = post(request)
        requireNoError(document, "GetItem complete")

        val messages = document.getElementsByTagNameNS(TYPES, "Message")
        val element = (0 until messages.length)
            .asSequence()
            .mapNotNull { index -> messages.item(index) as? Element }
            .firstOrNull { message ->
                val itemIdElement = message.getElementsByTagNameNS(TYPES, "ItemId").item(0) as? Element
                itemIdElement?.getAttribute("Id") == itemId
            }
            ?: error("EWS GetItem complete: messaggio $itemId assente")

        return buildCompleteMimeMessage(element, itemId)
    }

    private fun completeGetItemRequestXml(itemId: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:IncludeMimeContent>true</t:IncludeMimeContent>
                    <t:BodyType>Best</t:BodyType>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:FieldURI FieldURI="item:Body"/>
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                      <t:FieldURI FieldURI="item:DateTimeSent"/>
                      <t:FieldURI FieldURI="message:From"/>
                      <t:FieldURI FieldURI="message:ToRecipients"/>
                      <t:FieldURI FieldURI="message:CcRecipients"/>
                      <t:FieldURI FieldURI="message:BccRecipients"/>
                      <t:FieldURI FieldURI="message:InternetMessageId"/>
                      <t:FieldURI FieldURI="item:Attachments"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:ItemIds>
                    <t:ItemId Id="${xmlEscape(itemId)}"/>
                  </m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun buildCompleteMimeMessage(element: Element, serverId: String): MimeMessage {
        val mimeMessage = parseMimeContent(element, serverId)
        if (mimeMessage != null) {
            applyStructuredEnvelope(mimeMessage, element, serverId)
            repairPrimaryBodyFromStructuredEws(mimeMessage, element)
            return mimeMessage
        }

        // Compatibility fallback for EWS servers that don't return MimeContent even
        // when requested: preserve the structured path and fill every EWS attachment.
        return buildStructuredPartialMimeMessage(element, serverId).also(::fillAttachmentBodies)
    }

    private fun fillAttachmentBodies(part: Part) {
        when (val body = part.body) {
            is Multipart -> {
                for (i in 0 until body.count) {
                    fillAttachmentBodies(body.getBodyPart(i))
                }
            }

            else -> {
                if (body != null) return
                val attachmentId = part.serverExtra
                    ?.takeIf { it.isNotBlank() }
                    ?: part.getHeader(DIRECTMAIL_ATTACHMENT_ID_HEADER)
                        .firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                    ?: return

                val content = getAttachmentContent(attachmentId)
                MimeMessageHelper.setBody(part, BinaryMemoryBody(content, "8bit"))
            }
        }
    }

    private fun getPartialMessagesBatch(itemIds: List<String>): Map<String, MimeMessage> {
        val request = partialGetItemRequestXml(itemIds)
        val document = post(request)
        requireNoError(document, "GetItem partial")

        val messages = document.getElementsByTagNameNS(TYPES, "Message")
        val result = linkedMapOf<String, MimeMessage>()
        for (i in 0 until messages.length) {
            val element = messages.item(i) as? Element ?: continue
            val itemId = element.getElementsByTagNameNS(TYPES, "ItemId").item(0) as? Element ?: continue
            val serverId = itemId.getAttribute("Id").takeIf { it.isNotBlank() } ?: continue

            // Embedded EWS ItemAttachment needs a nested message MIME structure. Keep the
            // proven full-MIME path for this uncommon case instead of synthesizing it.
            if (element.getElementsByTagNameNS(TYPES, "ItemAttachment").length > 0) {
                error("EWS GetItem partial: ItemAttachment non supportato")
            }

            result[serverId] = buildPartialMimeMessage(element, serverId)
        }
        if (result.size != itemIds.size) {
            error("EWS GetItem partial: attesi ${itemIds.size} messaggi, ricevuti ${result.size}")
        }
        return result
    }

    private fun partialGetItemRequestXml(itemIds: List<String>): String {
        val itemIdsXml = itemIds.joinToString(separator = "") { itemId ->
            """<t:ItemId Id="${xmlEscape(itemId)}"/>"""
        }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:BodyType>Best</t:BodyType>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:FieldURI FieldURI="item:Body"/>
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                      <t:FieldURI FieldURI="item:DateTimeSent"/>
                      <t:FieldURI FieldURI="message:From"/>
                      <t:FieldURI FieldURI="message:ToRecipients"/>
                      <t:FieldURI FieldURI="message:CcRecipients"/>
                      <t:FieldURI FieldURI="message:BccRecipients"/>
                      <t:FieldURI FieldURI="message:InternetMessageId"/>
                      <t:FieldURI FieldURI="item:Attachments"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:ItemIds>$itemIdsXml</m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun buildPartialMimeMessage(element: Element, serverId: String): MimeMessage {
        val attachments = parseFileAttachments(element)

        // Gromox returns MimeContent together with BodyType=Best. When available, use it
        // as the authoritative MIME tree (multipart/alternative, multipart/related, CID,
        // Content-Disposition, etc.) and strip only EWS-addressable attachment bodies.
        // Other EWS implementations may omit MimeContent here; they use the structured
        // reconstruction below.
        val mimeMessage = parseMimeContent(element, serverId)
        if (mimeMessage != null) {
            applyStructuredEnvelope(mimeMessage, element, serverId)
            repairPrimaryBodyFromStructuredEws(mimeMessage, element)
            applyAttachmentPlaceholdersFromEws(mimeMessage, attachments)
            return mimeMessage
        }

        return buildStructuredPartialMimeMessage(element, serverId)
    }

    private fun buildStructuredPartialMimeMessage(element: Element, serverId: String): MimeMessage {
        val bodyElement = element.getElementsByTagNameNS(TYPES, "Body").item(0) as? Element
        val bodyText = bodyElement?.textContent.orEmpty()
        val bodyType = bodyElement?.getAttribute("BodyType").orEmpty()
        val bodyMimeType = if (bodyType.equals("HTML", ignoreCase = true)) "text/html" else "text/plain"
        val attachments = parseFileAttachments(element)
        val inlineAttachments = attachments.filter { it.isInline || !it.contentId.isNullOrBlank() }
        val regularAttachments = attachments.filterNot { it in inlineAttachments }

        val message = MimeMessage().apply {
            uid = serverId
        }
        applyStructuredEnvelope(message, element, serverId)

        val contentType = "$bodyMimeType; charset=utf-8"
        val bodyPart = MimeBodyPart(TextBody(bodyText), contentType)

        if (inlineAttachments.isNotEmpty() && bodyMimeType == "text/html") {
            val related = MimeMultipart.newInstance().apply {
                setSubType("related")
                addBodyPart(bodyPart)
                inlineAttachments.forEach { addBodyPart(createAttachmentPlaceholder(it)) }
            }

            if (regularAttachments.isEmpty()) {
                MimeMessageHelper.setBody(message, related)
            } else {
                val mixed = MimeMultipart.newInstance().apply {
                    addBodyPart(MimeBodyPart(related))
                    regularAttachments.forEach { addBodyPart(createAttachmentPlaceholder(it)) }
                }
                MimeMessageHelper.setBody(message, mixed)
            }
        } else if (attachments.isEmpty()) {
            MimeMessageHelper.setBody(message, TextBody(bodyText))
            message.setHeader("Content-Type", contentType)
        } else {
            val mixed = MimeMultipart.newInstance().apply {
                addBodyPart(bodyPart)
                attachments.forEach { addBodyPart(createAttachmentPlaceholder(it)) }
            }
            MimeMessageHelper.setBody(message, mixed)
        }

        return message
    }

    private fun parseMimeContent(element: Element, serverId: String): MimeMessage? {
        val encoded = element.getElementsByTagNameNS(TYPES, "MimeContent")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            val raw = Base64.getMimeDecoder().decode(encoded)
            MimeMessage.parseMimeMessage(ByteArrayInputStream(raw), false).apply {
                uid = serverId
            }
        }.getOrNull()
    }

    private fun applyStructuredEnvelope(message: MimeMessage, element: Element, serverId: String) {
        message.uid = serverId

        element.getElementsByTagNameNS(TYPES, "Subject")
            .item(0)
            ?.textContent
            ?.let { message.subject = it }

        mailboxAddresses(element, "From").firstOrNull()?.let(message::setFrom)
        setAddressHeader(message, "To", mailboxAddresses(element, "ToRecipients"))
        setAddressHeader(message, "Cc", mailboxAddresses(element, "CcRecipients"))
        setAddressHeader(message, "Bcc", mailboxAddresses(element, "BccRecipients"))

        element.getElementsByTagNameNS(TYPES, "InternetMessageId")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { message.setHeader("Message-ID", it) }

        parseEwsDate(element, "DateTimeSent")?.let { message.setSentDate(it, false) }
        parseEwsDate(element, "DateTimeReceived")?.let(message::setInternalDate)
    }

    private fun repairPrimaryBodyFromStructuredEws(message: MimeMessage, element: Element) {
        val bodyElement = element.getElementsByTagNameNS(TYPES, "Body").item(0) as? Element ?: return
        val bodyText = bodyElement.textContent ?: return
        val bodyMimeType = if (bodyElement.getAttribute("BodyType").equals("HTML", ignoreCase = true)) {
            "text/html"
        } else {
            "text/plain"
        }

        replaceFirstViewableBody(message, bodyMimeType, bodyText)
    }

    private fun replaceFirstViewableBody(part: Part, mimeType: String, bodyText: String): Boolean {
        val body = part.body
        if (body is Multipart) {
            for (index in 0 until body.count) {
                if (replaceFirstViewableBody(body.getBodyPart(index), mimeType, bodyText)) {
                    return true
                }
            }
            return false
        }

        val disposition = MimeUtility.getHeaderParameter(part.disposition, null)
        if (disposition.equals("attachment", ignoreCase = true)) return false
        if (!part.isMimeType(mimeType)) return false

        MimeMessageHelper.setBody(part, TextBody(bodyText))
        part.setHeader("Content-Type", "$mimeType; charset=utf-8")
        return true
    }

    private fun applyAttachmentPlaceholdersFromEws(message: MimeMessage, attachments: List<EwsAttachmentInfo>) {
        if (attachments.isEmpty()) return

        val unmatched = attachments.toMutableList()
        val leaves = mutableListOf<Part>()
        collectLeafParts(message, leaves)

        leaves.forEach { part ->
            if (isViewableTextPart(part)) return@forEach

            val match = findMatchingAttachment(part, unmatched) ?: return@forEach
            unmatched.remove(match)

            part.serverExtra = match.attachmentId
            part.setHeader(DIRECTMAIL_ATTACHMENT_ID_HEADER, match.attachmentId)

            if (part.contentId.isNullOrBlank() && !match.contentId.isNullOrBlank()) {
                val value = match.contentId.trim()
                val cid = if (value.startsWith("<") && value.endsWith(">")) value else "<$value>"
                part.setHeader("Content-ID", cid)
            }

            // Equivalent to IMAP BODYSTRUCTURE: keep the MIME part metadata and fetch
            // the payload later through Backend.fetchPart()/EWS GetAttachment.
            part.body = null
        }
    }

    private fun collectLeafParts(part: Part, output: MutableList<Part>) {
        val body = part.body
        if (body is Multipart) {
            for (index in 0 until body.count) {
                collectLeafParts(body.getBodyPart(index), output)
            }
        } else {
            output += part
        }
    }

    private fun isViewableTextPart(part: Part): Boolean {
        val disposition = MimeUtility.getHeaderParameter(part.disposition, null)
        if (disposition.equals("attachment", ignoreCase = true)) return false
        return part.isMimeType("text/plain") || part.isMimeType("text/html")
    }

    private fun findMatchingAttachment(
        part: Part,
        candidates: List<EwsAttachmentInfo>,
    ): EwsAttachmentInfo? {
        if (candidates.isEmpty()) return null

        val partContentId = normalizeContentId(part.contentId)
        if (partContentId != null) {
            candidates.firstOrNull { normalizeContentId(it.contentId) == partContentId }?.let { return it }
        }

        val fileName = MimeUtility.getHeaderParameter(part.disposition, "filename")
            ?: MimeUtility.getHeaderParameter(part.contentType, "name")
        if (!fileName.isNullOrBlank()) {
            candidates.firstOrNull { it.name?.equals(fileName, ignoreCase = true) == true }?.let { return it }
        }

        val mimeMatches = candidates.filter {
            it.contentType?.equals(part.mimeType, ignoreCase = true) == true
        }
        return mimeMatches.singleOrNull()
    }

    private fun normalizeContentId(value: String?): String? {
        return value
            ?.trim()
            ?.removePrefix("<")
            ?.removeSuffix(">")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
    }

    private fun parseFileAttachments(element: Element): List<EwsAttachmentInfo> {
        val nodes = element.getElementsByTagNameNS(TYPES, "FileAttachment")
        return buildList {
            for (i in 0 until nodes.length) {
                val attachment = nodes.item(i) as? Element ?: continue
                val idElement = attachment.getElementsByTagNameNS(TYPES, "AttachmentId").item(0) as? Element ?: continue
                val attachmentId = idElement.getAttribute("Id").takeIf { it.isNotBlank() } ?: continue
                add(
                    EwsAttachmentInfo(
                        attachmentId = attachmentId,
                        name = childText(attachment, "Name"),
                        contentType = childText(attachment, "ContentType"),
                        contentId = childText(attachment, "ContentId"),
                        size = childText(attachment, "Size")?.toIntOrNull(),
                        isInline = childText(attachment, "IsInline")?.equals("true", ignoreCase = true) == true,
                    ),
                )
            }
        }
    }

    private fun createAttachmentPlaceholder(attachment: EwsAttachmentInfo): MimeBodyPart {
        val contentType = attachment.contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val fileName = attachment.name?.takeIf { it.isNotBlank() }
        val quotedName = fileName?.let(::quoteMimeParameter)
        val typeHeader = if (quotedName != null) "$contentType; name=\"$quotedName\"" else contentType
        val disposition = buildString {
            append(if (attachment.isInline) "inline" else "attachment")
            if (quotedName != null) append("; filename=\"").append(quotedName).append("\"")
            attachment.size?.let { append("; size=").append(it) }
        }

        return MimeBodyPart().apply {
            // Thunderbird persists serverExtra in message_parts and later uses it to
            // identify the exact missing part in LocalFolder.addPartToMessage().
            // IMAP stores the BODYSTRUCTURE section id here; Exchange uses AttachmentId.
            serverExtra = attachment.attachmentId
            setHeader("Content-Type", typeHeader)
            setHeader("Content-Disposition", disposition)
            setHeader("Content-Transfer-Encoding", "8bit")
            setHeader(DIRECTMAIL_ATTACHMENT_ID_HEADER, attachment.attachmentId)
            attachment.contentId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { value ->
                    val cid = if (value.startsWith("<") && value.endsWith(">")) value else "<$value>"
                    setHeader("Content-ID", cid)
                }
        }
    }

    private fun mailboxAddresses(parent: Element, containerName: String): List<Address> {
        val container = parent.getElementsByTagNameNS(TYPES, containerName).item(0) as? Element ?: return emptyList()
        val nodes = container.getElementsByTagNameNS(TYPES, "Mailbox")
        return buildList {
            for (i in 0 until nodes.length) {
                val mailbox = nodes.item(i) as? Element ?: continue
                val email = childText(mailbox, "EmailAddress")?.takeIf { it.isNotBlank() } ?: continue
                add(Address(email, childText(mailbox, "Name")?.takeIf { it.isNotBlank() }))
            }
        }
    }

    private fun setAddressHeader(message: MimeMessage, name: String, addresses: List<Address>) {
        if (addresses.isNotEmpty()) {
            message.setHeader(name, AddressHeaderBuilder.createHeaderValue(addresses.toTypedArray()))
        }
    }

    private fun childText(parent: Element, localName: String): String? {
        return parent.getElementsByTagNameNS(TYPES, localName)
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parseEwsDate(parent: Element, localName: String): Date? {
        return childText(parent, localName)
            ?.let { runCatching { Date.from(java.time.Instant.parse(it)) }.getOrNull() }
    }

    private fun quoteMimeParameter(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    fun getAttachmentContent(attachmentId: String): ByteArray {
        val request = getAttachmentRequestXml(attachmentId)
        val document = post(request)
        requireNoError(document, "GetAttachment")
        val encoded = document.getElementsByTagNameNS(TYPES, "Content")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("EWS GetAttachment: Content assente")
        return Base64.getMimeDecoder().decode(encoded)
    }

    private fun getAttachmentRequestXml(attachmentId: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetAttachment>
                  <m:AttachmentIds>
                    <t:AttachmentId Id="${xmlEscape(attachmentId)}"/>
                  </m:AttachmentIds>
                </m:GetAttachment>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    fun getMimeMessage(itemId: String): MimeMessage {
        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:IncludeMimeContent>true</t:IncludeMimeContent>
                  </m:ItemShape>
                  <m:ItemIds>
                    <t:ItemId Id="${xmlEscape(itemId)}"/>
                  </m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "GetItem")

        val mimeNodes = document.getElementsByTagNameNS(TYPES, "MimeContent")
        val encoded = mimeNodes.item(0)?.textContent?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("EWS GetItem: MimeContent assente")

        val raw = Base64.getMimeDecoder().decode(encoded)
        return MimeMessage.parseMimeMessage(ByteArrayInputStream(raw), false).apply {
            uid = itemId
        }
    }

    fun getMimeMessages(itemIds: List<String>): Map<String, MimeMessage> {
        if (itemIds.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, MimeMessage>()
        itemIds.distinct().chunked(MIME_GET_BATCH_SIZE).forEach { batch ->
            val batchResult = runCatching { getMimeMessagesBatch(batch) }.getOrElse {
                batch.associateWith { itemId -> getMimeMessage(itemId) }
            }
            result.putAll(batchResult)
        }
        return result
    }

    private fun getMimeMessagesBatch(itemIds: List<String>): Map<String, MimeMessage> {
        val itemIdsXml = itemIds.joinToString(separator = "") { itemId ->
            """<t:ItemId Id="${xmlEscape(itemId)}"/>"""
        }

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:IncludeMimeContent>true</t:IncludeMimeContent>
                  </m:ItemShape>
                  <m:ItemIds>$itemIdsXml</m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "GetItem batch")

        val messages = document.getElementsByTagNameNS(TYPES, "Message")
        val result = linkedMapOf<String, MimeMessage>()
        for (i in 0 until messages.length) {
            val message = messages.item(i) as? Element ?: continue
            val itemId = message.getElementsByTagNameNS(TYPES, "ItemId")
                .item(0) as? Element ?: continue
            val serverId = itemId.getAttribute("Id").takeIf { it.isNotBlank() } ?: continue
            val encoded = message.getElementsByTagNameNS(TYPES, "MimeContent")
                .item(0)
                ?.textContent
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val raw = Base64.getMimeDecoder().decode(encoded)
            result[serverId] = MimeMessage.parseMimeMessage(ByteArrayInputStream(raw), false).apply {
                uid = serverId
            }
        }

        if (result.size != itemIds.size) {
            error("EWS GetItem batch: attesi ${itemIds.size} messaggi, ricevuti ${result.size}")
        }

        return result
    }

    private fun resolveStandardFolder(distinguishedId: String, type: FolderType): EwsStandardFolder {
        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetFolder>
                  <m:FolderShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="folder:DisplayName"/>
                    </t:AdditionalProperties>
                  </m:FolderShape>
                  <m:FolderIds>
                    <t:DistinguishedFolderId Id="${xmlEscape(distinguishedId)}"/>
                  </m:FolderIds>
                </m:GetFolder>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "GetFolder $distinguishedId")

        val folder = document.getElementsByTagNameNS(TYPES, "Folder").item(0) as? Element
            ?: error("EWS GetFolder $distinguishedId: Folder assente")
        val folderId = folder.getElementsByTagNameNS(TYPES, "FolderId").item(0) as? Element
            ?: error("EWS GetFolder $distinguishedId: FolderId assente")
        val actualServerId = folderId.getAttribute("Id").takeIf { it.isNotBlank() }
            ?: error("EWS GetFolder $distinguishedId: Id vuoto")
        val displayName = folder.getElementsByTagNameNS(TYPES, "DisplayName")
            .item(0)
            ?.textContent
            ?.takeIf { it.isNotBlank() }
            ?: distinguishedId

        return EwsStandardFolder(
            distinguishedId = distinguishedId,
            actualServerId = actualServerId,
            displayName = displayName,
            type = type,
        )
    }

    private fun findMailFolders(): List<EwsFolderNode> {
        val result = mutableListOf<EwsFolderNode>()
        var offset = 0
        var includesLast = false
        var guard = 0

        while (!includesLast && guard++ < MAX_FOLDER_PAGES) {
            val request = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                  xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
                  <s:Body>
                    <m:FindFolder Traversal="Deep">
                      <m:FolderShape>
                        <t:BaseShape>IdOnly</t:BaseShape>
                        <t:AdditionalProperties>
                          <t:FieldURI FieldURI="folder:ParentFolderId"/>
                          <t:FieldURI FieldURI="folder:DisplayName"/>
                        </t:AdditionalProperties>
                      </m:FolderShape>
                      <m:IndexedPageFolderView MaxEntriesReturned="$FOLDER_PAGE_SIZE"
                        Offset="$offset" BasePoint="Beginning"/>
                      <m:ParentFolderIds>
                        <t:DistinguishedFolderId Id="msgfolderroot"/>
                      </m:ParentFolderIds>
                    </m:FindFolder>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            val document = post(request)
            requireNoError(document, "FindFolder")

            val rootFolder = document.getElementsByTagNameNS(MESSAGES, "RootFolder")
                .item(0) as? Element
            includesLast = rootFolder
                ?.getAttribute("IncludesLastItemInRange")
                ?.equals("true", ignoreCase = true)
                ?: true

            val folders = document.getElementsByTagNameNS(TYPES, "Folder")
            var added = 0
            for (i in 0 until folders.length) {
                val folder = folders.item(i) as? Element ?: continue
                val folderId = folder.getElementsByTagNameNS(TYPES, "FolderId")
                    .item(0) as? Element ?: continue
                val serverId = folderId.getAttribute("Id").takeIf { it.isNotBlank() } ?: continue
                val parentId = (folder.getElementsByTagNameNS(TYPES, "ParentFolderId")
                    .item(0) as? Element)
                    ?.getAttribute("Id")
                    ?.takeIf { it.isNotBlank() }
                val displayName = folder.getElementsByTagNameNS(TYPES, "DisplayName")
                    .item(0)
                    ?.textContent
                    ?.takeIf { it.isNotBlank() }
                    ?: continue

                result += EwsFolderNode(
                    serverId = serverId,
                    parentServerId = parentId,
                    displayName = displayName,
                )
                added++
            }

            if (includesLast) break

            val nextOffset = rootFolder
                ?.getAttribute("IndexedPagingOffset")
                ?.toIntOrNull()
                ?: (offset + added)

            if (nextOffset <= offset || added == 0) break
            offset = nextOffset
        }

        return result.distinctBy { it.serverId }
    }

    fun getReadMappingIdentity(messageServerId: String): EwsReadMappingIdentity {
        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                      <t:FieldURI FieldURI="message:From"/>
                      <t:FieldURI FieldURI="message:InternetMessageId"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:ItemIds>
                    <t:ItemId Id="${xmlEscape(messageServerId)}"/>
                  </m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "GetItem Read identity")
        val message = document.getElementsByTagNameNS(TYPES, "Message").item(0) as? Element
            ?: error("EWS GetItem Read identity: Message assente")

        val subject = message.getElementsByTagNameNS(TYPES, "Subject")
            .item(0)
            ?.textContent
        val dateReceived = message.getElementsByTagNameNS(TYPES, "DateTimeReceived")
            .item(0)
            ?.textContent
        val fromElement = message.getElementsByTagNameNS(TYPES, "From").item(0) as? Element
        val from = fromElement
            ?.getElementsByTagNameNS(TYPES, "EmailAddress")
            ?.item(0)
            ?.textContent
        val internetMessageId = message.getElementsByTagNameNS(TYPES, "InternetMessageId")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return EwsReadMappingIdentity(
            subject = subject,
            dateReceived = dateReceived,
            from = from,
            internetMessageId = internetMessageId,
        )
    }

    fun getReadMappingFingerprint(messageServerId: String): String? {
        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="item:Subject"/>
                      <t:FieldURI FieldURI="item:DateTimeReceived"/>
                      <t:FieldURI FieldURI="message:From"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:ItemIds>
                    <t:ItemId Id="${xmlEscape(messageServerId)}"/>
                  </m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "GetItem Read mapping")
        val message = document.getElementsByTagNameNS(TYPES, "Message").item(0) as? Element
            ?: error("EWS GetItem Read mapping: Message assente")
        val subject = message.getElementsByTagNameNS(TYPES, "Subject")
            .item(0)
            ?.textContent
        val dateReceived = message.getElementsByTagNameNS(TYPES, "DateTimeReceived")
            .item(0)
            ?.textContent
        val fromElement = message.getElementsByTagNameNS(TYPES, "From").item(0) as? Element
        val from = fromElement
            ?.getElementsByTagNameNS(TYPES, "EmailAddress")
            ?.item(0)
            ?.textContent

        return EasReadStateStore.fingerprint(
            subject = subject,
            dateReceived = dateReceived,
            from = from,
        )
    }

    fun setReadState(messageServerIds: List<String>, isRead: Boolean): Boolean {
        messageServerIds
            .distinct()
            .chunked(READ_UPDATE_BATCH_SIZE)
            .forEach { batch ->
                try {
                    updateReadStateWithCompatibility(batch, isRead)
                } catch (e: IllegalStateException) {
                    if (!isItemNotFound(e) || batch.size == 1) {
                        if (isItemNotFound(e)) {
                            // A queued SEEN command may outlive the original EWS ItemId after
                            // the message was moved or deleted. The desired object no longer
                            // exists, so this command is already obsolete and can be completed.
                            return@forEach
                        }
                        throw e
                    }

                    // One stale ItemId makes a multi-GetItem fail as a whole. Retry items
                    // individually so valid read-state changes still reach the server.
                    batch.forEach { itemId ->
                        try {
                            updateReadStateWithCompatibility(listOf(itemId), isRead)
                        } catch (single: IllegalStateException) {
                            if (!isItemNotFound(single)) throw single
                        }
                    }
                }
            }

        return true
    }

    fun setAnsweredState(messageServerIds: List<String>, isAnswered: Boolean) {
        setMessageActionState(messageServerIds, MessageAction.ANSWERED, isAnswered)
    }

    fun setForwardedState(messageServerIds: List<String>, isForwarded: Boolean) {
        setMessageActionState(messageServerIds, MessageAction.FORWARDED, isForwarded)
    }

    private fun setMessageActionState(
        messageServerIds: List<String>,
        action: MessageAction,
        enabled: Boolean,
    ) {
        messageServerIds.distinct().chunked(FLAG_UPDATE_BATCH_SIZE).forEach { batch ->
            try {
                updateMessageActionState(batch, action, enabled)
            } catch (e: IllegalStateException) {
                if (!isItemNotFound(e) || batch.size == 1) {
                    if (isItemNotFound(e)) return@forEach
                    throw e
                }
                batch.forEach { itemId ->
                    try {
                        updateMessageActionState(listOf(itemId), action, enabled)
                    } catch (single: IllegalStateException) {
                        if (!isItemNotFound(single)) throw single
                    }
                }
            }
        }
    }

    private fun updateMessageActionState(
        messageServerIds: List<String>,
        action: MessageAction,
        enabled: Boolean,
    ) {
        val changeKeys = getItemChangeKeys(messageServerIds)
        val updateFields = messageActionUpdateFieldsXml(action, enabled)
        val changes = messageServerIds.joinToString(separator = "") { itemId ->
            val changeKey = changeKeys[itemId] ?: error("EWS GetItem: ChangeKey assente per $itemId")
            """
              <t:ItemChange>
                <t:ItemId Id="${xmlEscape(itemId)}" ChangeKey="${xmlEscape(changeKey)}"/>
                <t:Updates>$updateFields</t:Updates>
              </t:ItemChange>
            """.trimIndent()
        }

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:UpdateItem ConflictResolution="AutoResolve" MessageDisposition="SaveOnly">
                  <m:ItemChanges>$changes</m:ItemChanges>
                </m:UpdateItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        requireNoError(post(request), "UpdateItem ${action.name}")
    }

    private fun messageActionUpdateFieldsXml(action: MessageAction, enabled: Boolean): String {
        val iconIndex = if (enabled) action.iconIndex else 0
        val lastVerb = if (enabled) action.lastVerb else 0
        val timestamp = java.time.Instant.now().toString()
        return buildString {
            append(
                """
                <t:SetItemField>
                  <t:ExtendedFieldURI PropertyTag="0x1080" PropertyType="Integer"/>
                  <t:Message><t:ExtendedProperty>
                    <t:ExtendedFieldURI PropertyTag="0x1080" PropertyType="Integer"/>
                    <t:Value>$iconIndex</t:Value>
                  </t:ExtendedProperty></t:Message>
                </t:SetItemField>
                <t:SetItemField>
                  <t:ExtendedFieldURI PropertyTag="0x1081" PropertyType="Integer"/>
                  <t:Message><t:ExtendedProperty>
                    <t:ExtendedFieldURI PropertyTag="0x1081" PropertyType="Integer"/>
                    <t:Value>$lastVerb</t:Value>
                  </t:ExtendedProperty></t:Message>
                </t:SetItemField>
                """.trimIndent(),
            )
            if (enabled) {
                append(
                    """
                    <t:SetItemField>
                      <t:ExtendedFieldURI PropertyTag="0x1082" PropertyType="SystemTime"/>
                      <t:Message><t:ExtendedProperty>
                        <t:ExtendedFieldURI PropertyTag="0x1082" PropertyType="SystemTime"/>
                        <t:Value>$timestamp</t:Value>
                      </t:ExtendedProperty></t:Message>
                    </t:SetItemField>
                    """.trimIndent(),
                )
            }
        }
    }
    fun setFlaggedState(messageServerIds: List<String>, isFlagged: Boolean) {
        messageServerIds
            .distinct()
            .chunked(FLAG_UPDATE_BATCH_SIZE)
            .forEach { batch ->
                try {
                    updateFlaggedState(batch, isFlagged)
                } catch (e: IllegalStateException) {
                    if (!isItemNotFound(e) || batch.size == 1) {
                        if (isItemNotFound(e)) {
                            return@forEach
                        }
                        throw e
                    }

                    // One obsolete ItemId must not block valid star updates in the same batch.
                    batch.forEach { itemId ->
                        try {
                            updateFlaggedState(listOf(itemId), isFlagged)
                        } catch (single: IllegalStateException) {
                            if (!isItemNotFound(single)) throw single
                        }
                    }
                }
            }
    }

    private fun updateFlaggedState(messageServerIds: List<String>, isFlagged: Boolean) {
        val changeKeys = getItemChangeKeys(messageServerIds)
        val updateFields = flagUpdateFieldsXml(isFlagged)
        val changes = messageServerIds.joinToString(separator = "") { itemId ->
            val changeKey = changeKeys[itemId]
                ?: error("EWS GetItem: ChangeKey assente per $itemId")

            """
              <t:ItemChange>
                <t:ItemId Id="${xmlEscape(itemId)}" ChangeKey="${xmlEscape(changeKey)}"/>
                <t:Updates>$updateFields</t:Updates>
              </t:ItemChange>
            """.trimIndent()
        }

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:UpdateItem ConflictResolution="AutoResolve" MessageDisposition="SaveOnly">
                  <m:ItemChanges>$changes</m:ItemChanges>
                </m:UpdateItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        requireNoError(post(request), "UpdateItem Flag")
    }

    private fun flagUpdateFieldsXml(isFlagged: Boolean): String {
        val flagStatus = if (isFlagged) "Flagged" else "NotFlagged"
        val mapiFlagStatus = if (isFlagged) "2" else "0"
        val followUpIcon = if (isFlagged) "6" else "0"
        val flagRequest = if (isFlagged) "Follow up" else ""

        return """
            <t:SetItemField>
              <t:FieldURI FieldURI="item:Flag"/>
              <t:Message>
                <t:Flag><t:FlagStatus>$flagStatus</t:FlagStatus></t:Flag>
              </t:Message>
            </t:SetItemField>
            <t:SetItemField>
              <t:ExtendedFieldURI PropertyTag="0x1090" PropertyType="Integer"/>
              <t:Message>
                <t:ExtendedProperty>
                  <t:ExtendedFieldURI PropertyTag="0x1090" PropertyType="Integer"/>
                  <t:Value>$mapiFlagStatus</t:Value>
                </t:ExtendedProperty>
              </t:Message>
            </t:SetItemField>
            <t:SetItemField>
              <t:ExtendedFieldURI PropertyTag="0x1095" PropertyType="Integer"/>
              <t:Message>
                <t:ExtendedProperty>
                  <t:ExtendedFieldURI PropertyTag="0x1095" PropertyType="Integer"/>
                  <t:Value>$followUpIcon</t:Value>
                </t:ExtendedProperty>
              </t:Message>
            </t:SetItemField>
            <t:SetItemField>
              <t:ExtendedFieldURI DistinguishedPropertySetId="Common" PropertyId="34096" PropertyType="String"/>
              <t:Message>
                <t:ExtendedProperty>
                  <t:ExtendedFieldURI DistinguishedPropertySetId="Common" PropertyId="34096" PropertyType="String"/>
                  <t:Value>${xmlEscape(flagRequest)}</t:Value>
                </t:ExtendedProperty>
              </t:Message>
            </t:SetItemField>
            <t:SetItemField>
              <t:ExtendedFieldURI DistinguishedPropertySetId="Task" PropertyId="33025" PropertyType="Integer"/>
              <t:Message>
                <t:ExtendedProperty>
                  <t:ExtendedFieldURI DistinguishedPropertySetId="Task" PropertyId="33025" PropertyType="Integer"/>
                  <t:Value>0</t:Value>
                </t:ExtendedProperty>
              </t:Message>
            </t:SetItemField>
            <t:SetItemField>
              <t:ExtendedFieldURI DistinguishedPropertySetId="Task" PropertyId="33026" PropertyType="Double"/>
              <t:Message>
                <t:ExtendedProperty>
                  <t:ExtendedFieldURI DistinguishedPropertySetId="Task" PropertyId="33026" PropertyType="Double"/>
                  <t:Value>0</t:Value>
                </t:ExtendedProperty>
              </t:Message>
            </t:SetItemField>
            <t:SetItemField>
              <t:ExtendedFieldURI DistinguishedPropertySetId="Task" PropertyId="33052" PropertyType="Boolean"/>
              <t:Message>
                <t:ExtendedProperty>
                  <t:ExtendedFieldURI DistinguishedPropertySetId="Task" PropertyId="33052" PropertyType="Boolean"/>
                  <t:Value>false</t:Value>
                </t:ExtendedProperty>
              </t:Message>
            </t:SetItemField>
        """.trimIndent()
    }

    private fun updateReadStateWithCompatibility(
        messageServerIds: List<String>,
        isRead: Boolean,
    ) {
        try {
            updateReadState(messageServerIds, isRead, suppressReadReceipts = true)
        } catch (e: EwsHttpException) {
            if (e.statusCode != 403) throw e

            // Older/partial EWS implementations can reject the Exchange 2013 SP1
            // SuppressReadReceipts attribute even though UpdateItem/IsRead itself is
            // supported. Retry the same update using the baseline UpdateItem shape.
            updateReadState(messageServerIds, isRead, suppressReadReceipts = false)
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

    private fun updateReadState(
        messageServerIds: List<String>,
        isRead: Boolean,
        suppressReadReceipts: Boolean,
    ) {
        val changeKeys = getItemChangeKeys(messageServerIds)
        val changes = messageServerIds.joinToString(separator = "") { itemId ->
            val changeKey = changeKeys[itemId]
                ?: error("EWS GetItem: ChangeKey assente per $itemId")

            """
              <t:ItemChange>
                <t:ItemId Id="${xmlEscape(itemId)}" ChangeKey="${xmlEscape(changeKey)}"/>
                <t:Updates>
                  <t:SetItemField>
                    <t:FieldURI FieldURI="message:IsRead"/>
                    <t:Message><t:IsRead>$isRead</t:IsRead></t:Message>
                  </t:SetItemField>
                </t:Updates>
              </t:ItemChange>
            """.trimIndent()
        }
        val suppressAttribute = if (suppressReadReceipts) {
            " SuppressReadReceipts=\"true\""
        } else {
            ""
        }

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:UpdateItem ConflictResolution="AutoResolve" MessageDisposition="SaveOnly"$suppressAttribute>
                  <m:ItemChanges>$changes</m:ItemChanges>
                </m:UpdateItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        requireNoError(post(request), "UpdateItem IsRead")
    }

    fun markAllAsRead(folderServerId: String): Boolean {
        var offset = 0
        var includesLast = false
        var guard = 0

        while (!includesLast && guard++ < MAX_MARK_READ_PAGES) {
            val page = findItems(
                folderServerId = folderServerId,
                maxEntries = READ_UPDATE_BATCH_SIZE,
                offset = offset,
            )

            val unreadIds = page.items
                .filterNot { it.isRead }
                .map { it.serverId }

            if (unreadIds.isNotEmpty()) {
                setReadState(unreadIds, true)
            }

            includesLast = page.includesLastItemInRange
            if (includesLast || page.items.isEmpty()) break

            val nextOffset = page.nextOffset ?: (offset + page.items.size)
            if (nextOffset <= offset) break
            offset = nextOffset
        }

        return true
    }

    private fun getItemChangeKeys(messageServerIds: List<String>): Map<String, String> {
        val itemIds = messageServerIds.joinToString(separator = "") { itemId ->
            """<t:ItemId Id="${xmlEscape(itemId)}"/>"""
        }

        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>
                  <m:ItemIds>$itemIds</m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "GetItem ChangeKey")

        val messages = document.getElementsByTagNameNS(TYPES, "Message")
        return buildMap {
            for (i in 0 until messages.length) {
                val message = messages.item(i) as? Element ?: continue
                val itemId = message.getElementsByTagNameNS(TYPES, "ItemId")
                    .item(0) as? Element ?: continue
                val serverId = itemId.getAttribute("Id").takeIf { it.isNotBlank() } ?: continue
                val changeKey = itemId.getAttribute("ChangeKey").takeIf { it.isNotBlank() } ?: continue
                put(serverId, changeKey)
            }
        }
    }

    fun deleteItems(messageServerIds: List<String>) {
        messageServerIds
            .distinct()
            .chunked(ITEM_OPERATION_BATCH_SIZE)
            .forEach { batch ->
                val itemIds = batch.joinToString(separator = "") { itemId ->
                    """<t:ItemId Id="${xmlEscape(itemId)}"/>"""
                }

                val request = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                      xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                      xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                      <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
                      <s:Body>
                        <m:DeleteItem DeleteType="HardDelete" SuppressReadReceipts="true">
                          <m:ItemIds>$itemIds</m:ItemIds>
                        </m:DeleteItem>
                      </s:Body>
                    </s:Envelope>
                """.trimIndent()

                requireNoError(post(request), "DeleteItem")
            }
    }

    fun deleteAllItems(folderServerId: String) {
        var guard = 0

        while (guard++ < MAX_DELETE_PAGES) {
            val page = findItems(
                folderServerId = folderServerId,
                maxEntries = ITEM_OPERATION_BATCH_SIZE,
                offset = 0,
            )
            if (page.items.isEmpty()) return

            deleteItems(page.items.map { it.serverId })
            if (page.includesLastItemInRange) return
        }

        error("EWS DeleteItem: superato limite pagine durante svuotamento cartella")
    }

    fun moveItems(targetFolderServerId: String, messageServerIds: List<String>): Map<String, String> {
        return moveOrCopyItems("MoveItem", targetFolderServerId, messageServerIds)
    }

    fun copyItems(targetFolderServerId: String, messageServerIds: List<String>): Map<String, String> {
        return moveOrCopyItems("CopyItem", targetFolderServerId, messageServerIds)
    }

    private fun moveOrCopyItems(
        operation: String,
        targetFolderServerId: String,
        messageServerIds: List<String>,
    ): Map<String, String> {
        if (messageServerIds.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, String>()
        messageServerIds.distinct().chunked(ITEM_OPERATION_BATCH_SIZE).forEach { batch ->
            val itemIds = batch.joinToString(separator = "") { itemId ->
                """<t:ItemId Id="${xmlEscape(itemId)}"/>"""
            }
            val targetFolderId = folderIdXml(targetFolderServerId)

            val request = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                  xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
                  <s:Body>
                    <m:$operation>
                      <m:ToFolderId>$targetFolderId</m:ToFolderId>
                      <m:ItemIds>$itemIds</m:ItemIds>
                    </m:$operation>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            val document = post(request)
            requireNoError(document, operation)

            val returnedIds = document.getElementsByTagNameNS(TYPES, "ItemId")
            val newIds = buildList {
                for (i in 0 until returnedIds.length) {
                    val itemId = returnedIds.item(i) as? Element ?: continue
                    itemId.getAttribute("Id")
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }

            if (newIds.size != batch.size) {
                error("EWS $operation: attesi ${batch.size} nuovi ItemId, ricevuti ${newIds.size}")
            }

            batch.zip(newIds).forEach { (oldId, newId) ->
                result[oldId] = newId
            }
        }

        return result
    }

    fun findByMessageId(folderServerId: String, internetMessageId: String): String? {
        val expectedMessageId = internetMessageId.trim().takeIf { it.isNotEmpty() } ?: return null
        var offset = 0
        var pageCount = 0
        var includesLast = false

        while (!includesLast && pageCount++ < MAX_MESSAGE_ID_SCAN_PAGES) {
            val sortField = if (folderServerId == SENT_FOLDER_ID) {
                "item:DateTimeSent"
            } else {
                "item:DateTimeReceived"
            }
            val request = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                  xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
                  <s:Body>
                    <m:FindItem Traversal="Shallow">
                      <m:ItemShape>
                        <t:BaseShape>IdOnly</t:BaseShape>
                        <t:AdditionalProperties>
                          <t:FieldURI FieldURI="message:InternetMessageId"/>
                        </t:AdditionalProperties>
                      </m:ItemShape>
                      <m:IndexedPageItemView MaxEntriesReturned="$MESSAGE_ID_SCAN_PAGE_SIZE"
                        Offset="$offset" BasePoint="Beginning"/>
                      <m:SortOrder>
                        <t:FieldOrder Order="Descending">
                          <t:FieldURI FieldURI="$sortField"/>
                        </t:FieldOrder>
                      </m:SortOrder>
                      <m:ParentFolderIds>
                        ${folderIdXml(folderServerId)}
                      </m:ParentFolderIds>
                    </m:FindItem>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            val document = post(request)
            requireNoError(document, "FindItem InternetMessageId scan")

            val rootFolder = document.getElementsByTagNameNS(MESSAGES, "RootFolder")
                .item(0) as? Element
            includesLast = rootFolder
                ?.getAttribute("IncludesLastItemInRange")
                ?.equals("true", ignoreCase = true)
                ?: true
            val nextOffset = rootFolder
                ?.getAttribute("IndexedPagingOffset")
                ?.toIntOrNull()

            val messages = document.getElementsByTagNameNS(TYPES, "Message")
            for (i in 0 until messages.length) {
                val message = messages.item(i) as? Element ?: continue
                val remoteMessageId = message.getElementsByTagNameNS(TYPES, "InternetMessageId")
                    .item(0)
                    ?.textContent
                    ?.trim()
                    ?: continue
                if (!remoteMessageId.equals(expectedMessageId, ignoreCase = true)) continue

                val itemId = message.getElementsByTagNameNS(TYPES, "ItemId").item(0) as? Element ?: continue
                return itemId.getAttribute("Id").takeIf { it.isNotBlank() }
            }

            if (includesLast || messages.length == 0) break
            val candidateOffset = nextOffset ?: (offset + messages.length)
            if (candidateOffset <= offset) break
            offset = candidateOffset
        }

        return null
    }

    private fun folderIdXml(folderServerId: String): String {
        return if (folderServerId in STANDARD_FOLDER_IDS) {
            """<t:DistinguishedFolderId Id="${xmlEscape(folderServerId)}"/>"""
        } else {
            """<t:FolderId Id="${xmlEscape(folderServerId)}"/>"""
        }
    }

    fun sendMessage(message: Message): EwsSendResult {
        val hasRecipients =
            message.getRecipients(RecipientType.TO).isNotEmpty() ||
                message.getRecipients(RecipientType.CC).isNotEmpty() ||
                message.getRecipients(RecipientType.BCC).isNotEmpty()
        if (!hasRecipients) {
            error("EWS CreateItem: nessun destinatario")
        }

        val sentTime = messageSentDateTime(message)
        val mimeMessage = serializeMessageForMimeUpload(message)
        val createRequest = createMimeItemRequestXml(
            folderServerId = DRAFTS_FOLDER_ID,
            message = message,
            sentTime = sentTime,
            mimeMessage = mimeMessage,
        )

        val created = try {
            post(createRequest)
        } catch (e: Exception) {
            throw IllegalStateException(
                "CreateItem draft MimeContent (mimeBytes=${mimeMessage.size}): " +
                    "${e.message ?: "errore EWS"}",
                e,
            )
        }
        requireNoError(created, "CreateItem MimeContent")

        val itemIdNode = created.getElementsByTagNameNS(TYPES, "ItemId").item(0) as? Element
            ?: error("EWS CreateItem: ItemId assente")
        val itemId = itemIdNode.getAttribute("Id")
        val changeKey = itemIdNode.getAttribute("ChangeKey")
        if (itemId.isBlank()) error("EWS CreateItem: Id vuoto")

        val itemIdXml = if (changeKey.isBlank()) {
            """<t:ItemId Id="${xmlEscape(itemId)}"/>"""
        } else {
            """<t:ItemId Id="${xmlEscape(itemId)}" ChangeKey="${xmlEscape(changeKey)}"/>"""
        }

        val sendRequest = sendItemRequestXml(itemIdXml)
        try {
            requireNoError(post(sendRequest), "SendItem")
        } catch (e: Exception) {
            throw IllegalStateException("SendItem: ${e.message ?: "errore EWS"}", e)
        }

        val internetMessageId = message.messageId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { getInternetMessageId(itemId) }.getOrNull()

        if (!internetMessageId.isNullOrBlank()) {
            val standardSentCopy = findSentCopyAfterSend(internetMessageId)
            if (standardSentCopy != null) {
                runCatching { deleteItems(listOf(itemId)) }
                return EwsSendResult(
                    sentCopyConfirmed = true,
                    details = "Delivery EWS riuscita; copia Sent salvata da SendItem",
                )
            }
        }

        val compatibilityMove = runCatching {
            moveItems(SENT_FOLDER_ID, listOf(itemId))
        }
        compatibilityMove.getOrNull()
            ?.get(itemId)
            ?.let {
                if (!internetMessageId.isNullOrBlank()) {
                    runCatching {
                        findByMessageId(DRAFTS_FOLDER_ID, internetMessageId)
                            ?.let { duplicateDraftId -> deleteItems(listOf(duplicateDraftId)) }
                    }
                }
                return EwsSendResult(
                    sentCopyConfirmed = true,
                    details = "Delivery EWS riuscita; fallback compatibilità ha spostato lo staging in Sent",
                )
            }

        return EwsSendResult(
            sentCopyConfirmed = false,
            details = buildString {
                append("Delivery EWS riuscita; copia Sent non ancora confermata")
                compatibilityMove.exceptionOrNull()?.message?.let {
                    append("; fallback MoveItem=")
                    append(it)
                }
            },
        )
    }

    private fun sendItemRequestXml(itemIdXml: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:SendItem SaveItemToFolder="true">
                  <m:ItemIds>$itemIdXml</m:ItemIds>
                  <m:SavedItemFolderId>
                    <t:DistinguishedFolderId Id="sentitems"/>
                  </m:SavedItemFolderId>
                </m:SendItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun findSentCopyAfterSend(internetMessageId: String): String? {
        val delaysMs = longArrayOf(0L, 150L, 350L)
        for (delayMs in delaysMs) {
            if (delayMs > 0L) Thread.sleep(delayMs)
            val found = runCatching {
                findByMessageId(SENT_FOLDER_ID, internetMessageId)
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun getInternetMessageId(itemId: String): String? {
        val request = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:GetItem>
                  <m:ItemShape>
                    <t:BaseShape>IdOnly</t:BaseShape>
                    <t:AdditionalProperties>
                      <t:FieldURI FieldURI="message:InternetMessageId"/>
                    </t:AdditionalProperties>
                  </m:ItemShape>
                  <m:ItemIds>
                    <t:ItemId Id="${xmlEscape(itemId)}"/>
                  </m:ItemIds>
                </m:GetItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val document = post(request)
        requireNoError(document, "GetItem InternetMessageId")
        return document.getElementsByTagNameNS(TYPES, "InternetMessageId")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun saveMessage(folderServerId: String, message: Message): String {
        val sentTime = messageSentDateTime(message)
        val mimeMessage = serializeMessageForMimeUpload(message)
        val request = createMimeItemRequestXml(
            folderServerId = folderServerId,
            message = message,
            sentTime = sentTime,
            mimeMessage = mimeMessage,
        )

        val created = try {
            post(request)
        } catch (e: Exception) {
            throw IllegalStateException(
                "CreateItem SaveOnly MimeContent (mimeBytes=${mimeMessage.size}): " +
                    "${e.message ?: "errore EWS"}",
                e,
            )
        }
        requireNoError(created, "CreateItem SaveOnly MimeContent")

        val itemIdNode = created.getElementsByTagNameNS(TYPES, "ItemId").item(0) as? Element
            ?: error("EWS CreateItem SaveOnly: ItemId assente")
        return itemIdNode.getAttribute("Id")
            .takeIf { it.isNotBlank() }
            ?: error("EWS CreateItem SaveOnly: Id vuoto")
    }

    private fun serializeMessageForMimeUpload(message: Message): ByteArray {
        val preservedHeaders = DIRECTMAIL_LOCAL_ONLY_HEADERS.associateWith { name ->
            message.getHeader(name).toList()
        }

        try {
            DIRECTMAIL_LOCAL_ONLY_HEADERS.forEach(message::removeHeader)
            return ByteArrayOutputStream().use { output ->
                message.writeTo(output)
                output.toByteArray()
            }
        } finally {
            preservedHeaders.forEach { (name, values) ->
                values.forEach { value -> message.addHeader(name, value) }
            }
        }
    }

    private fun createMimeItemRequestXml(
        folderServerId: String,
        message: Message,
        sentTime: String,
        mimeMessage: ByteArray,
    ): String {
        val mimeContent = Base64.getEncoder().encodeToString(mimeMessage)
        val sentMetadataXml = sentMetadataPropertiesXml(message, sentTime)

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
              xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
              <s:Header><t:RequestServerVersion Version="Exchange2013"/></s:Header>
              <s:Body>
                <m:CreateItem MessageDisposition="SaveOnly">
                  <m:SavedItemFolderId>
                    ${folderIdXml(folderServerId)}
                  </m:SavedItemFolderId>
                  <m:Items>
                    <t:Message>
                      <t:MimeContent CharacterSet="UTF-8">${mimeContent}</t:MimeContent>
                      ${sentMetadataXml}
                    </t:Message>
                  </m:Items>
                </m:CreateItem>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun recipientsXml(addresses: Array<com.fsck.k9.mail.Address>): String {
        return addresses.joinToString(separator = "") { address ->
            """<t:Mailbox><t:EmailAddress>${xmlEscape(address.address)}</t:EmailAddress></t:Mailbox>"""
        }
    }

    private fun recipientsContainerXml(name: String, recipients: String): String {
        return if (recipients.isEmpty()) "" else "<t:$name>$recipients</t:$name>"
    }

    private fun attachmentName(part: Part, index: Int): String {
        return MimeUtility.getHeaderParameter(part.disposition, "filename")
            ?: MimeUtility.getHeaderParameter(part.contentType, "name")
            ?: "attachment-${index + 1}"
    }

    private fun post(xml: String): Document {
        val password = settings.password ?: error("Password Exchange mancante")
        val connection = endpoint.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("Accept", "text/xml")
            connection.setRequestProperty("User-Agent", "DirectMail/Thunderbird-Exchange")
            connection.setRequestProperty(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(
                    "${settings.username}:$password".toByteArray(StandardCharsets.UTF_8),
                ),
            )

            connection.outputStream.use {
                it.write(xml.toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)

            if (status !in 200..299) {
                val responseText = bytes.toString(StandardCharsets.UTF_8)
                throw EwsHttpException(status, responseText)
            }
            if (bytes.isEmpty()) error("EWS: risposta vuota")

            return parseXml(bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.setExpandEntityReferences(false) }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun requireNoError(document: Document, operation: String) {
        val codes = document.getElementsByTagNameNS(MESSAGES, "ResponseCode")
        if (codes.length == 0) error("EWS $operation: ResponseCode assente")

        for (i in 0 until codes.length) {
            val code = codes.item(i).textContent
            if (code != "NoError") error("EWS $operation: $code")
        }
    }

    private fun messageSentDateTime(message: Message): String {
        return (message.sentDate?.toInstant() ?: java.time.Instant.now()).toString()
    }

    private fun sentMetadataPropertiesXml(message: Message, sentTime: String): String {
        val internetMessageId = message.messageId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return buildString {
            append("<t:ExtendedProperty>")
            append("<t:ExtendedFieldURI PropertyTag=\"0x0039\" PropertyType=\"SystemTime\"/>")
            append("<t:Value>").append(xmlEscape(sentTime)).append("</t:Value>")
            append("</t:ExtendedProperty>")
            internetMessageId?.let {
                append("<t:ExtendedProperty>")
                append("<t:ExtendedFieldURI PropertyTag=\"0x1035\" PropertyType=\"String\"/>")
                append("<t:Value>").append(xmlEscape(it)).append("</t:Value>")
                append("</t:ExtendedProperty>")
            }
        }
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private enum class MessageAction(val iconIndex: Int, val lastVerb: Int) {
        ANSWERED(0x105, 102),
        FORWARDED(0x106, 104),
    }

    private companion object {
        const val MAIL_ICON_REPLIED = 0x105
        const val MAIL_ICON_FORWARDED = 0x106
        const val LAST_VERB_REPLY_TO = 102
        const val LAST_VERB_REPLY_ALL = 103
        const val LAST_VERB_FORWARDED = 104
        const val MSGSTATUS_ANSWERED = 0x200
        val DIRECTMAIL_LOCAL_ONLY_HEADERS = listOf(
            "X-DirectMail-Compose-Action",
            "X-DirectMail-Composed-Offset",
            "X-DirectMail-Composed-Length",
        )
        const val DIRECTMAIL_ATTACHMENT_ID_HEADER = "X-DirectMail-Ews-Attachment-Id"
        const val TYPES = "http://schemas.microsoft.com/exchange/services/2006/types"
        const val MESSAGES = "http://schemas.microsoft.com/exchange/services/2006/messages"
        const val DEFAULT_EWS_PATH = "/EWS/Exchange.asmx"
        const val DRAFTS_FOLDER_ID = "drafts"
        const val SENT_FOLDER_ID = "sentitems"
        const val FOLDER_PAGE_SIZE = 250
        const val SYNC_FOLDER_ITEMS_PAGE_SIZE = 512
        const val MAX_FOLDER_PAGES = 40
        const val READ_UPDATE_BATCH_SIZE = 100
        const val FLAG_UPDATE_BATCH_SIZE = 100
        const val MIME_GET_BATCH_SIZE = 20
        const val SEARCH_PAGE_SIZE = 250
        const val MAX_SEARCH_PAGES = 100
        const val SEARCH_MATCH_LIMIT = 500
        const val ITEM_OPERATION_BATCH_SIZE = 100
        const val ATTACHMENT_BATCH_SIZE = 10
        const val MESSAGE_ID_SCAN_PAGE_SIZE = 100
        const val MAX_MESSAGE_ID_SCAN_PAGES = 20
        const val MAX_DELETE_PAGES = 10_000
        const val MAX_MARK_READ_PAGES = 1000

        val STANDARD_FOLDERS = listOf(
            "inbox" to FolderType.INBOX,
            "sentitems" to FolderType.SENT,
            "drafts" to FolderType.DRAFTS,
            "deleteditems" to FolderType.TRASH,
            "junkemail" to FolderType.SPAM,
        )
        val STANDARD_FOLDER_IDS = STANDARD_FOLDERS.mapTo(mutableSetOf()) { it.first }
    }
}
