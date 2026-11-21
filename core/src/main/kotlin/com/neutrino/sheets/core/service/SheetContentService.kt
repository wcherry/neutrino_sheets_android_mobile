package com.neutrino.sheets.core.service

import com.neutrino.sheets.core.api.SheetsApi
import com.neutrino.sheets.core.model.SheetFileInfo
import com.neutrino.sheets.core.model.SheetItem
import com.neutrino.sheets.core.workbook.SheetFile
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.openDek
import com.neutrino.shared.core.ooxml.XlsxCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.logging.Logger

data class SealedFileKey(val sealed: String, val keyVersion: Int)

sealed class SheetContentException(message: String) : Exception(message) {
    class MissingKeyVersion(val version: Int) : SheetContentException(
        "This spreadsheet needs encryption key version $version, which this device does not have. On a device that holds your key, " +
            "open Settings › Encryption and back up your older keys, then reopen this app.")
    class DecryptionFailed : SheetContentException("Failed to decrypt the spreadsheet.")
    class NotASpreadsheet : SheetContentException("This file isn’t a spreadsheet.")
    class NotFound : SheetContentException("That spreadsheet is no longer available.")
}

/**
 * Loads and saves a workbook: Drive's E2EE protocol through the shared crypto, the body a real
 * `.xlsx` through the shared codec. Every successful load leaves an encrypted copy on the device, and
 * a load that cannot reach the server opens that copy read-only — the iOS offline behaviour.
 */
class SheetContentService(
    private val api: SheetsApi,
    private val keyring: () -> Keyring?,
    private val keyCheck: DeviceKeyCheck,
    private val offline: OfflineCopies? = null,
) {
    private val log = Logger.getLogger("SheetContentService")
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Loaded(
        val file: SheetFile,
        val dek: ByteArray,
        val info: SheetFileInfo,
        /** The file had no key, or held plaintext: the first save seals it. */
        val needsInitialEncryption: Boolean,
        /** The package as stored, so parts nothing here understands are carried into the next save. */
        val sourcePackage: ByteArray?,
        /** Set when this is the device's copy because the server could not be reached. */
        val offlineCopyAt: Instant? = null,
    )

    data class SaveResult(val updatedAt: Instant, val sizeBytes: Long, val contentVersion: Long?)

    /** Creates a spreadsheet under a fresh DEK, holding a real `.xlsx` from its first byte. */
    suspend fun create(name: String, parentId: String?, body: SheetFile = SheetFile.empty): SheetItem {
        val (entry, version) = keyCheck.sealingVersion()
        val dek = FileCrypto.generateDek()
        val fileName = XlsxCodec.withExtension(name)
        val ciphertext = withContext(Dispatchers.Default) { FileCrypto.encryptFile(body.encoded(), dek) }
        val created = api.create(ciphertext, fileName, parentId, FileCrypto.encryptMetadata(fileName, SheetItem.MIME, dek),
            FileCrypto.sealDek(dek, entry.publicKey), version)
        return item(created)
    }

    suspend fun load(fileId: String): Loaded = try {
        loadFromServer(fileId)
    } catch (e: ApiException.Network) {
        openOfflineCopy(fileId) ?: throw e
    }

    private suspend fun loadFromServer(fileId: String): Loaded {
        val info = api.fileInfo(fileId) ?: throw SheetContentException.NotFound()
        if (!info.isSpreadsheet) throw SheetContentException.NotASpreadsheet()
        val sealed = api.fileKey(fileId)?.let { SealedFileKey(it.encryptedFileKey, it.keyVersion ?: 1) }
        val dek: ByteArray
        if (sealed != null) {
            dek = unsealDek(sealed, fileId)
        } else {
            // A file another client created with no key yet: give it one now, sealed to this account.
            dek = FileCrypto.generateDek()
            val (entry, version) = keyCheck.sealingVersion()
            api.setFileKey(fileId, FileCrypto.sealDek(dek, entry.publicKey), version)
        }
        val stored = api.download(fileId)
        var needsInitialEncryption = false
        val plaintext = try {
            decrypt(stored, dek)
        } catch (e: SheetContentException.DecryptionFailed) {
            // Only a key minted just now can meet plaintext: a sealed key that fails is a real failure.
            if (sealed != null) throw e
            needsInitialEncryption = true
            stored
        }
        val file = withContext(Dispatchers.Default) { SheetFile.decode(plaintext) }
        if (sealed != null && !needsInitialEncryption) offline?.store(fileId, stored, sealed, info)
        return Loaded(file, dek, info, needsInitialEncryption, plaintext.takeIf(XlsxCodec::isWorkbook))
    }

    private fun openOfflineCopy(fileId: String): Loaded? {
        val copy = offline?.copy(fileId) ?: return null
        val dek = unsealDek(copy.sealed)
        val plaintext = try {
            decrypt(copy.ciphertext, dek)
        } catch (e: SheetContentException.DecryptionFailed) {
            offline.remove(fileId)
            return null
        }
        return Loaded(SheetFile.decode(plaintext), dek, copy.info, false, plaintext.takeIf(XlsxCodec::isWorkbook), copy.storedAt)
    }

    /** Encrypts and autosaves, guarded by `expectedContentVersion`; refreshes the device's copy from the very bytes sent. */
    suspend fun save(file: SheetFile, item: SheetItem, dek: ByteArray, expectedContentVersion: Long?, keeping: ByteArray?): SaveResult {
        val ciphertext = withContext(Dispatchers.Default) { FileCrypto.encryptFile(file.encoded(keeping), dek) }
        val updated = api.autosave(item.id, item.name, ciphertext, expectedContentVersion)
        val result = SaveResult(DriveDate.parse(updated.updatedAt) ?: Instant.now(), updated.sizeBytes ?: ciphertext.size.toLong(), updated.contentVersion)
        offline?.refresh(item.id, ciphertext, result)
        return result
    }

    fun decrypt(data: ByteArray, dek: ByteArray): ByteArray = try {
        FileCrypto.decryptFile(data, dek)
    } catch (e: FileCrypto.CryptoException) {
        throw SheetContentException.DecryptionFailed()
    }

    fun unsealDek(sealed: SealedFileKey, fileId: String? = null): ByteArray {
        val ring = keyring() ?: throw KeyException.NoEncryptionKey()
        val (dek, version) = ring.openDek(sealed.sealed, sealed.keyVersion)
            ?: throw if (ring.entry(sealed.keyVersion) == null) SheetContentException.MissingKeyVersion(sealed.keyVersion) else SheetContentException.DecryptionFailed()
        if (version != sealed.keyVersion && fileId != null) {
            log.info("refiling $fileId's key under v$version")
            background.launch { runCatching { api.setFileKey(fileId, sealed.sealed, version) } }
        }
        return dek
    }

    companion object {
        fun item(file: FileDto, isShared: Boolean = false) = SheetItem(
            file.id, file.name, SheetItem.ItemType.FILE, file.folderId, file.sizeBytes, DriveDate.parse(file.updatedAt) ?: Instant.EPOCH,
            mimeType = file.mimeType, isStarred = if (isShared) false else file.isStarred ?: false, contentVersion = file.contentVersion, isShared = isShared,
        )
    }
}
