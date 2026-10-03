package it.directmail.backend.exchange

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal data class EasWbxmlNode(
    val page: Int,
    val tag: Int,
    val text: String,
    val children: List<EasWbxmlNode>,
    val rawContent: ByteArray = ByteArray(0),
) {
    fun firstChild(page: Int, tag: Int): EasWbxmlNode? =
        children.firstOrNull { it.page == page && it.tag == tag }

    fun childText(page: Int, tag: Int): String? =
        firstChild(page, tag)?.text?.takeIf { it.isNotEmpty() }

    fun childBytes(page: Int, tag: Int): ByteArray? =
        firstChild(page, tag)?.let { child ->
            child.rawContent.takeIf { it.isNotEmpty() }
                ?: child.text.toByteArray(StandardCharsets.UTF_8).takeIf { it.isNotEmpty() }
        }

    fun descendants(page: Int, tag: Int): List<EasWbxmlNode> {
        val result = mutableListOf<EasWbxmlNode>()
        fun visit(node: EasWbxmlNode) {
            if (node.page == page && node.tag == tag) result += node
            node.children.forEach(::visit)
        }
        visit(this)
        return result
    }
}

internal object EasWbxml {
    const val PAGE_AIRSYNC = 0
    const val PAGE_EMAIL = 2
    const val PAGE_FOLDER_HIERARCHY = 7
    const val PAGE_PING = 13
    const val PAGE_PROVISION = 14
    const val PAGE_AIRSYNC_BASE = 17
    const val PAGE_ITEM_OPERATIONS = 20

    // AirSync
    const val SYNC_ROOT = 0x05
    const val SYNC_RESPONSES = 0x06
    const val SYNC_ADD = 0x07
    const val SYNC_CHANGE = 0x08
    const val SYNC_SYNC_KEY = 0x0B
    const val SYNC_SERVER_ID = 0x0D
    const val SYNC_STATUS = 0x0E
    const val SYNC_COLLECTION = 0x0F
    const val SYNC_COLLECTION_ID = 0x12
    const val SYNC_GET_CHANGES = 0x13
    const val SYNC_MORE_AVAILABLE = 0x14
    const val SYNC_WINDOW_SIZE = 0x15
    const val SYNC_COMMANDS = 0x16
    const val SYNC_OPTIONS = 0x17
    const val SYNC_COLLECTIONS = 0x1C
    const val SYNC_APPLICATION_DATA = 0x1D
    const val SYNC_MIME_SUPPORT = 0x22

    // AirSyncBase
    const val ASB_BODY_PREFERENCE = 0x05
    const val ASB_TYPE = 0x06
    const val ASB_TRUNCATION_SIZE = 0x07
    const val ASB_BODY = 0x0A
    const val ASB_DATA = 0x0B

    // ItemOperations
    const val IO_ROOT = 0x05
    const val IO_FETCH = 0x06
    const val IO_STORE = 0x07
    const val IO_OPTIONS = 0x08
    const val IO_PROPERTIES = 0x0B
    const val IO_STATUS = 0x0D
    const val IO_RESPONSE = 0x0E

    // Email
    const val EMAIL_DATE_RECEIVED = 0x0F
    const val EMAIL_SUBJECT = 0x14
    const val EMAIL_READ = 0x15
    const val EMAIL_FROM = 0x18

    // FolderHierarchy
    const val FH_DISPLAY_NAME = 0x07
    const val FH_SERVER_ID = 0x08
    const val FH_PARENT_ID = 0x09
    const val FH_TYPE = 0x0A
    const val FH_STATUS = 0x0C
    const val FH_CHANGES = 0x0E
    const val FH_ADD = 0x0F
    const val FH_SYNC_KEY = 0x12
    const val FH_FOLDER_SYNC = 0x16

    // Ping
    const val PING_ROOT = 0x05
    const val PING_STATUS = 0x07
    const val PING_HEARTBEAT = 0x08
    const val PING_FOLDERS = 0x09
    const val PING_FOLDER = 0x0A
    const val PING_ID = 0x0B
    const val PING_CLASS = 0x0C

    // Provision
    const val PROVISION_ROOT = 0x05
    const val PROVISION_POLICIES = 0x06
    const val PROVISION_POLICY = 0x07
    const val PROVISION_POLICY_TYPE = 0x08
    const val PROVISION_POLICY_KEY = 0x09
    const val PROVISION_STATUS = 0x0B

    fun syncRequest(
        folderId: String,
        syncKey: String,
        getChanges: Boolean,
        windowSize: Int = 50,
    ): ByteArray = Writer().apply {
        switchPage(PAGE_AIRSYNC)
        start(SYNC_ROOT)
        start(SYNC_COLLECTIONS)
        start(SYNC_COLLECTION)
        element(SYNC_SYNC_KEY, syncKey)
        element(SYNC_COLLECTION_ID, folderId)
        if (getChanges) {
            element(SYNC_GET_CHANGES, "1")
            element(SYNC_WINDOW_SIZE, windowSize.coerceIn(1, 100).toString())
            start(SYNC_OPTIONS)
            // Explicitly request normal Email metadata rather than MIME. Gromox then
            // returns the standard ApplicationData fields used to correlate its EAS
            // ServerId with the same EWS message.
            element(SYNC_MIME_SUPPORT, "0")
            end()
        }
        end()
        end()
        end()
    }.toByteArray()

    fun itemOperationsMimeHeaderRequest(
        folderId: String,
        serverId: String,
        truncationSize: Int = 8192,
    ): ByteArray = Writer().apply {
        switchPage(PAGE_ITEM_OPERATIONS)
        start(IO_ROOT)
        start(IO_FETCH)
        element(IO_STORE, "Mailbox")

        switchPage(PAGE_AIRSYNC)
        element(SYNC_COLLECTION_ID, folderId)
        element(SYNC_SERVER_ID, serverId)

        switchPage(PAGE_ITEM_OPERATIONS)
        start(IO_OPTIONS)

        switchPage(PAGE_AIRSYNC)
        element(SYNC_MIME_SUPPORT, "2")

        switchPage(PAGE_AIRSYNC_BASE)
        start(ASB_BODY_PREFERENCE)
        element(ASB_TYPE, "4")
        element(ASB_TRUNCATION_SIZE, truncationSize.coerceIn(1024, 32768).toString())
        end()

        switchPage(PAGE_ITEM_OPERATIONS)
        end()
        end()
        end()
    }.toByteArray()

    fun syncReadChangeRequest(
        folderId: String,
        syncKey: String,
        serverId: String,
        isRead: Boolean,
    ): ByteArray = Writer().apply {
        switchPage(PAGE_AIRSYNC)
        start(SYNC_ROOT)
        start(SYNC_COLLECTIONS)
        start(SYNC_COLLECTION)
        element(SYNC_SYNC_KEY, syncKey)
        element(SYNC_COLLECTION_ID, folderId)
        element(SYNC_GET_CHANGES, "0")
        start(SYNC_COMMANDS)
        start(SYNC_CHANGE)
        element(SYNC_SERVER_ID, serverId)
        start(SYNC_APPLICATION_DATA)
        switchPage(PAGE_EMAIL)
        element(EMAIL_READ, if (isRead) "1" else "0")
        end()
        switchPage(PAGE_AIRSYNC)
        end()
        end()
        end()
        end()
        end()
    }.toByteArray()

    fun provisionInitialRequest(): ByteArray = Writer().apply {
        switchPage(PAGE_PROVISION)
        start(PROVISION_ROOT)
        start(PROVISION_POLICIES)
        start(PROVISION_POLICY)
        element(PROVISION_POLICY_TYPE, "MS-EAS-Provisioning-WBXML")
        end()
        end()
        end()
    }.toByteArray()

    fun provisionAcknowledgeRequest(policyKey: String): ByteArray = Writer().apply {
        switchPage(PAGE_PROVISION)
        start(PROVISION_ROOT)
        start(PROVISION_POLICIES)
        start(PROVISION_POLICY)
        element(PROVISION_POLICY_TYPE, "MS-EAS-Provisioning-WBXML")
        element(PROVISION_POLICY_KEY, policyKey)
        element(PROVISION_STATUS, "1")
        end()
        end()
        end()
    }.toByteArray()

    fun folderSyncRequest(syncKey: String): ByteArray = Writer().apply {
        switchPage(PAGE_FOLDER_HIERARCHY)
        start(FH_FOLDER_SYNC)
        element(FH_SYNC_KEY, syncKey)
        end()
    }.toByteArray()

    fun pingRequest(folderId: String, heartbeatSeconds: Int): ByteArray = Writer().apply {
        switchPage(PAGE_PING)
        start(PING_ROOT)
        element(PING_HEARTBEAT, heartbeatSeconds.toString())
        start(PING_FOLDERS)
        start(PING_FOLDER)
        element(PING_ID, folderId)
        element(PING_CLASS, "Email")
        end()
        end()
        end()
    }.toByteArray()

    fun decode(bytes: ByteArray): EasWbxmlNode {
        val reader = Reader(bytes)
        reader.readHeader()
        return reader.readDocument()
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        private var currentPage = 0

        init {
            // WBXML 1.3, unknown public identifier, UTF-8, empty string table.
            out.write(0x03)
            writeMbUInt(0x01)
            writeMbUInt(106)
            writeMbUInt(0)
        }

        fun switchPage(page: Int) {
            if (currentPage == page) return
            out.write(SWITCH_PAGE)
            out.write(page)
            currentPage = page
        }

        fun start(tag: Int) {
            require(tag in 0x05..0x3F) { "Invalid WBXML tag: $tag" }
            out.write(tag or CONTENT_BIT)
        }

        fun element(tag: Int, value: String) {
            start(tag)
            text(value)
            end()
        }

        fun text(value: String) {
            out.write(STR_I)
            out.write(value.toByteArray(StandardCharsets.UTF_8))
            out.write(0)
        }

        fun end() {
            out.write(END)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeMbUInt(value: Int) {
            var v = value
            val groups = IntArray(5)
            var count = 0
            groups[count++] = v and 0x7F
            v = v ushr 7
            while (v != 0) {
                groups[count++] = v and 0x7F
                v = v ushr 7
            }
            for (i in count - 1 downTo 0) {
                val continuation = if (i != 0) 0x80 else 0
                out.write(groups[i] or continuation)
            }
        }
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0
        private var currentPage = 0

        fun readHeader() {
            require(readByte() == 0x03) { "Unsupported WBXML version" }
            readMbUInt() // Public ID
            readMbUInt() // Charset
            val stringTableLength = readMbUInt()
            require(stringTableLength >= 0 && offset + stringTableLength <= data.size) {
                "Invalid WBXML string table"
            }
            offset += stringTableLength
        }

        fun readDocument(): EasWbxmlNode {
            while (peekByte() == SWITCH_PAGE) {
                readByte()
                currentPage = readByte()
            }
            return readNode()
        }

        private fun readNode(): EasWbxmlNode {
            val token = readByte()
            require(token != END && token != SWITCH_PAGE && token != STR_I) {
                "Invalid WBXML node token 0x${token.toString(16)}"
            }
            require(token and ATTR_BIT == 0) { "WBXML attributes are not supported" }

            val page = currentPage
            val tag = token and TAG_MASK
            val hasContent = token and CONTENT_BIT != 0
            if (!hasContent) return EasWbxmlNode(page, tag, "", emptyList())

            val text = StringBuilder()
            val rawContent = ByteArrayOutputStream()
            val children = mutableListOf<EasWbxmlNode>()

            while (offset < data.size) {
                when (val next = peekByte()) {
                    END -> {
                        readByte()
                        break
                    }
                    SWITCH_PAGE -> {
                        readByte()
                        currentPage = readByte()
                    }
                    STR_I -> {
                        readByte()
                        val value = readInlineString()
                        text.append(value)
                        rawContent.write(value.toByteArray(StandardCharsets.UTF_8))
                    }
                    OPAQUE -> {
                        readByte()
                        val length = readMbUInt()
                        require(length >= 0 && offset + length <= data.size) { "Invalid WBXML opaque length" }
                        rawContent.write(data, offset, length)
                        offset += length
                    }
                    else -> {
                        require(next != ENTITY && next != STR_T) {
                            "Unsupported WBXML token 0x${next.toString(16)}"
                        }
                        children += readNode()
                    }
                }
            }

            return EasWbxmlNode(
                page = page,
                tag = tag,
                text = text.toString(),
                children = children,
                rawContent = rawContent.toByteArray(),
            )
        }

        private fun readInlineString(): String {
            val start = offset
            while (offset < data.size && data[offset].toInt() != 0) offset++
            require(offset < data.size) { "Unterminated WBXML inline string" }
            val value = String(data, start, offset - start, StandardCharsets.UTF_8)
            offset++
            return value
        }

        private fun readMbUInt(): Int {
            var value = 0
            repeat(5) {
                val b = readByte()
                value = (value shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) return value
            }
            error("WBXML integer too long")
        }

        private fun peekByte(): Int {
            require(offset < data.size) { "Unexpected end of WBXML" }
            return data[offset].toInt() and 0xFF
        }

        private fun readByte(): Int {
            val value = peekByte()
            offset++
            return value
        }
    }

    private const val SWITCH_PAGE = 0x00
    private const val END = 0x01
    private const val ENTITY = 0x02
    private const val STR_I = 0x03
    private const val STR_T = 0x83
    private const val OPAQUE = 0xC3
    private const val CONTENT_BIT = 0x40
    private const val ATTR_BIT = 0x80
    private const val TAG_MASK = 0x3F
}
