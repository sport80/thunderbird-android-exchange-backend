package it.directmail.backend.exchange

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class ExchangeBackendSendContractTest {
    @Test
    fun sendPathRemainsEwsOnly() {
        val source = exchangeBackendSource()
        val sendBody = source
            .substringAfter("override fun sendMessage(message: Message) {")
            .substringBefore("private fun setInboxReadStateViaEas(")

        assertThat(sendBody).contains("ewsClient.sendMessage(message)")
        assertThat(sendBody).doesNotContain("easClient.sendMail")
        assertThat(sendBody).doesNotContain("sendMailMime")
    }

    @Test
    fun sentPendingAppendOnlyLooksUpExistingServerCopy() {
        val source = exchangeBackendSource()
        val uploadBody = source
            .substringAfter("override fun uploadMessage(folderServerId: String, message: Message): String? {")
            .substringBefore("override fun sendMessage(message: Message) {")

        val sentBranch = uploadBody.substringBefore("return runEwsOperation(\"CreateItem SaveOnly\")")

        assertThat(sentBranch).contains("folderServerId == SENT_FOLDER_ID")
        assertThat(sentBranch).contains("ewsClient.findByMessageId(SENT_FOLDER_ID, internetMessageId)")
        assertThat(sentBranch).contains("ritentare PendingAppend")
        assertThat(sentBranch).doesNotContain("ewsClient.saveMessage")
        assertThat(sentBranch).doesNotContain("CreateItem")
        assertThat(sentBranch).doesNotContain("moveItems(")
    }

    @Test
    fun staleMoveItemIsRetiredAsPermanentWithoutOperationalDiagnostic() {
        val source = exchangeBackendSource()
        val retireBody = source
            .substringAfter("private fun moveItemsOrRetireMissing(")
            .substringBefore("private fun isItemNotFound(")
        val ewsWrapper = source
            .substringAfter("private fun <T> runEwsOperation(")
            .substringBefore("private companion object")

        assertThat(retireBody).contains("if (isItemNotFound(e))")
        assertThat(retireBody).contains("DirectMail obsolete pending")
        assertThat(retireBody).contains("true,")
        assertThat(ewsWrapper).contains("e.isPermanentFailure")
        assertThat(ewsWrapper).contains("startsWith(\"DirectMail obsolete pending\")")
        assertThat(ewsWrapper).contains("if (!retiredPending)")
    }

    @Test
    fun remoteFlagChangesNotifyThunderbirdController() {
        val source = exchangeBackendSource()
        val incrementalBody = source
            .substringAfter("private fun syncIncremental(")
            .substringBefore("private fun establishIncrementalSyncState(")

        assertThat(incrementalBody).contains("listener.syncFlagChanged(folderServerId, serverId)")
        assertThat(incrementalBody).contains("listener.syncFlagChanged(folderServerId, pending.item.serverId)")
    }

    @Test
    fun remoteSearchUsesCompatibilityFallbackAndPreservesServerFlags() {
        val source = exchangeBackendSource()
        val searchBody = source
            .substringAfter("override fun search(")
            .substringBefore("override fun fetchPart(")
        val downloadBody = source
            .substringAfter("private fun downloadPartialMessage(")
            .substringBefore("override fun setFlag(")

        assertThat(searchBody).contains("searchWithCompatibilityFallback")
        assertThat(searchBody).contains("remoteSearchFlags[folderServerId to serverId] = flags")
        assertThat(downloadBody).contains("remoteSearchFlags.remove(folderServerId to messageServerId)")
        assertThat(downloadBody).contains("flagsToPreserve?.let { applyPreservedDownloadFlags")
    }

    private fun exchangeBackendSource(): String {
        val relative = Path.of(
            "src/main/kotlin/it/directmail/backend/exchange/ExchangeBackend.kt",
        )
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath()

        repeat(8) {
            val direct = directory.resolve(relative)
            if (Files.isRegularFile(direct)) {
                return Files.readString(direct)
            }

            val nested = directory.resolve("backend/exchange").resolve(relative)
            if (Files.isRegularFile(nested)) {
                return Files.readString(nested)
            }

            directory = directory.parent ?: return@repeat
        }

        error("ExchangeBackend.kt non trovato dal working directory dei test")
    }
}
