package com.neutrino.sheets.core.service

import com.neutrino.sheets.core.model.SheetFileInfo
import com.neutrino.sheets.core.model.ShareRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * The last copy of every workbook opened here, kept as the server's ciphertext with its sealed key
 * and file info — never plaintext. When the server cannot be reached the editor opens this copy,
 * read-only, as iOS does: an offline edit to a spreadsheet would need a merge that does not exist.
 */
class OfflineCopies(private val directory: File) {

    @Serializable
    data class Entry(
        val id: String, val name: String, val sizeBytes: Long, val folderId: String? = null, val mimeType: String? = null,
        val updatedAt: Long, val yourRole: String, val contentVersion: Long? = null,
        val sealedKey: String, val keyVersion: Int, val storedAt: Long,
    ) {
        val info get() = SheetFileInfo(id, name, sizeBytes, folderId, mimeType, Instant.ofEpochMilli(updatedAt), null, ShareRole.of(yourRole), contentVersion)
    }

    class Copy(val info: SheetFileInfo, val ciphertext: ByteArray, val sealed: SealedFileKey, val storedAt: Instant)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    init {
        directory.mkdirs()
        _entries.value = runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), File(directory, INDEX).readText()) }.getOrDefault(emptyList())
    }

    fun store(id: String, ciphertext: ByteArray, sealed: SealedFileKey, info: SheetFileInfo) {
        write(blob(id), ciphertext)
        upsert(Entry(id, info.name, info.sizeBytes, info.folderId, info.mimeType, info.updatedAt.toEpochMilli(), info.yourRole.wire,
            info.contentVersion, sealed.sealed, sealed.keyVersion, System.currentTimeMillis()))
    }

    /** After a save from this device: the very ciphertext sent becomes the copy. */
    fun refresh(id: String, ciphertext: ByteArray, result: SheetContentService.SaveResult) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        write(blob(id), ciphertext)
        upsert(entry.copy(updatedAt = result.updatedAt.toEpochMilli(), sizeBytes = result.sizeBytes, contentVersion = result.contentVersion, storedAt = System.currentTimeMillis()))
    }

    fun copy(id: String): Copy? {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return null
        val file = blob(id).takeIf { it.exists() } ?: return null
        return Copy(entry.info, file.readBytes(), SealedFileKey(entry.sealedKey, entry.keyVersion), Instant.ofEpochMilli(entry.storedAt))
    }

    fun rename(id: String, name: String) {
        _entries.value.firstOrNull { it.id == id }?.let { upsert(it.copy(name = name)) }
    }

    fun remove(id: String) {
        blob(id).delete()
        _entries.value = _entries.value.filter { it.id != id }
        save()
    }

    fun removeAll() {
        directory.listFiles()?.forEach { it.delete() }
        _entries.value = emptyList()
    }

    val bytesOnDisk: Long get() = directory.listFiles { f -> f.name.endsWith(".bin") }?.sumOf { it.length() } ?: 0

    private fun upsert(entry: Entry) {
        _entries.value = (_entries.value.filter { it.id != entry.id } + entry).sortedByDescending { it.storedAt }
        save()
    }

    private fun save() = runCatching { write(File(directory, INDEX), json.encodeToString(ListSerializer(Entry.serializer()), _entries.value).toByteArray()) }

    private fun blob(id: String) = File(directory, "${id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.bin")

    private fun write(file: File, data: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object { private const val INDEX = "index.json" }
}
