package com.neutrino.sheets.core.service

import com.neutrino.shared.core.api.ApiException
import com.neutrino.sheets.core.api.SheetsApi
import com.neutrino.sheets.core.model.DeviceSession
import com.neutrino.sheets.core.model.SheetItem
import com.neutrino.sheets.core.model.SheetsSection
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderDto
import com.neutrino.shared.core.ooxml.XlsxCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class NotAvailable : Exception("That spreadsheet is no longer available.")
class NeedsConnection : Exception("That needs a connection.")

/**
 * Browses and organises spreadsheets through Drive's own APIs. Optimistic: the local model moves
 * first and is rolled back if the server refuses. Offline, changes are refused up front rather than
 * queued — the iOS app has no queue either, and silently dropping one would be worse. Main-thread confined.
 */
class SheetsDriveService(
    private val api: SheetsApi,
    private val content: SheetContentService,
    private val offline: OfflineCopies?,
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean,
) {
    val allItems = MutableStateFlow<List<SheetItem>>(emptyList())
    val recentItems = MutableStateFlow<List<SheetItem>>(emptyList())
    val starredItems = MutableStateFlow<List<SheetItem>>(emptyList())
    val sharedItems = MutableStateFlow<List<SheetItem>>(emptyList())
    val trashItems = MutableStateFlow<List<SheetItem>>(emptyList())
    private val _loading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _loading
    val error = MutableStateFlow<String?>(null)

    /** The last listing could not reach the server, though the device may think it is online. Shown inline, never as a dialog. */
    val unreachable = MutableStateFlow(false)

    fun items(section: SheetsSection, parentId: String? = null) = when (section) {
        SheetsSection.HOME -> allItems.value.filter { it.parentId == parentId }
        SheetsSection.RECENT -> recentItems.value
        SheetsSection.FAVORITES -> starredItems.value
        SheetsSection.SHARED -> sharedItems.value
        SheetsSection.TRASH -> trashItems.value
    }

    fun item(id: String) = (allItems.value + recentItems.value + starredItems.value + sharedItems.value + trashItems.value).firstOrNull { it.id == id }

    fun isDescendant(child: String?, folderId: String): Boolean {
        var current = child
        var guard = allItems.value.size + 1
        while (current != null && guard-- > 0) {
            if (current == folderId) return true
            current = allItems.value.firstOrNull { it.id == current }?.parentId
        }
        return false
    }

    /** For a link naming a spreadsheet no listing has loaded. */
    suspend fun fetchItem(id: String): SheetItem {
        val info = api.fileInfo(id)?.takeIf { it.isLive && it.isSpreadsheet } ?: throw NotAvailable()
        return info.asItem()
    }

    suspend fun load(section: SheetsSection, parentId: String? = null) {
        _loading.value = true
        error.value = null
        try {
            when (section) {
                SheetsSection.HOME -> {
                    val listing = api.folderContents(parentId)
                    val found = listing.folders.map(::folder) + sheets(listing.files)
                    allItems.update { items -> items.filter { it.parentId != parentId } + found.map { if (parentId == null) it.copy(parentId = null) else it } }
                }
                SheetsSection.RECENT -> recentItems.value = sheets(api.recent().files).take(RECENT_LIMIT)
                SheetsSection.FAVORITES -> api.starred().let { starredItems.value = it.folders.map(::folder) + sheets(it.files) }
                SheetsSection.SHARED -> api.sharedWithMe().let { l ->
                    sharedItems.value = l.folders.map { folder(it).copy(parentId = null, isStarred = false, isShared = true) } + sheets(l.files, shared = true)
                }
                SheetsSection.TRASH -> api.trash().let { l ->
                    trashItems.value = l.folders.map { SheetItem(it.id, it.name, SheetItem.ItemType.FOLDER, null, modifiedAt = DriveDate.parse(it.deletedAt) ?: Instant.EPOCH, isTrashed = true) } +
                        l.files.filter { SheetItem.isSpreadsheet(it.mimeType) }.map {
                            SheetItem(it.id, it.name, SheetItem.ItemType.FILE, null, it.sizeBytes, DriveDate.parse(it.deletedAt) ?: Instant.EPOCH, isTrashed = true, mimeType = it.mimeType)
                        }
                }
            }
            unreachable.value = false
        } catch (e: ApiException.Network) {
            unreachable.value = true
        } catch (e: Exception) {
            if (isOnline()) error.value = e.message
        } finally {
            _loading.value = false
        }
    }

    private fun sheets(files: List<FileDto>, shared: Boolean = false) = files.filter { SheetItem.isSpreadsheet(it.mimeType) }.map { SheetContentService.item(it, shared) }

    private fun requireOnline() { if (!isOnline()) throw NeedsConnection() }

    fun createFolder(name: String, parentId: String?) {
        if (!isOnline()) { error.value = NeedsConnection().message; return }
        val placeholder = SheetItem(UUID.randomUUID().toString(), name, SheetItem.ItemType.FOLDER, parentId, modifiedAt = Instant.now())
        allItems.update { it + placeholder }
        scope.launch {
            try {
                val created = folder(api.createFolder(name, parentId)).copy(parentId = parentId)
                allItems.update { items -> items.map { if (it.id == placeholder.id) created else it } }
            } catch (e: Exception) {
                allItems.update { items -> items.filter { it.id != placeholder.id } }
                error.value = e.message
            }
        }
    }

    suspend fun createSpreadsheet(name: String, parentId: String?): SheetItem {
        requireOnline()
        val item = content.create(name.trim().ifEmpty { "Untitled spreadsheet" }, parentId).copy(parentId = parentId)
        allItems.update { it + item }
        return item
    }

    /** Drive has no copy: decrypted and saved as a new file under a fresh key, everything it carries included. */
    suspend fun duplicate(itemId: String): SheetItem {
        requireOnline()
        val source = item(itemId)?.takeIf { it.isSpreadsheet } ?: throw NotAvailable()
        val loaded = content.load(source.id)
        val copy = content.create("${source.displayName} copy", if (source.isShared) null else source.parentId, loaded.file)
            .copy(parentId = if (source.isShared) null else source.parentId)
        allItems.update { it + copy }
        return copy
    }

    /** A spreadsheet keeps its `.xlsx` whatever was typed. */
    fun rename(itemId: String, newName: String) {
        val existing = item(itemId) ?: return
        if (!isOnline()) { error.value = NeedsConnection().message; return }
        val finalName = if (existing.isFolder) newName.trim() else XlsxCodec.withExtension(newName.trim())
        val previous = existing.name
        applyEverywhere(itemId) { it.copy(name = finalName) }
        offline?.rename(itemId, finalName)
        scope.launch {
            try {
                if (existing.isFolder) api.updateFolder(itemId, name = finalName) else api.updateFile(itemId, name = finalName)
            } catch (e: Exception) {
                applyEverywhere(itemId) { it.copy(name = previous) }
                offline?.rename(itemId, previous)
                error.value = e.message
            }
        }
    }

    fun setStarred(itemId: String, starred: Boolean) {
        val item = item(itemId) ?: return
        if (!isOnline()) { error.value = "Favorites need a connection."; return }
        applyStarred(item.copy(isStarred = starred))
        scope.launch {
            try {
                if (item.isFolder) api.updateFolder(itemId, isStarred = starred) else api.updateFile(itemId, isStarred = starred)
            } catch (e: Exception) {
                applyStarred(item.copy(isStarred = !starred))
                error.value = e.message
            }
        }
    }

    fun delete(itemId: String) {
        if (!isOnline()) { error.value = NeedsConnection().message; return }
        if (trashItems.value.any { it.id == itemId }) {
            val item = trashItems.value.first { it.id == itemId }
            trashItems.update { list -> list.filter { it.id != itemId } }
            scope.launch { runCatching { api.deleteForever(itemId, item.isFolder) }.onFailure { trashItems.update { l -> l + item }; error.value = it.message } }
            return
        }
        val item = item(itemId) ?: return
        allItems.update { l -> l.filter { it.id != itemId } }
        starredItems.update { l -> l.filter { it.id != itemId } }
        recentItems.update { l -> l.filter { it.id != itemId } }
        trashItems.update { it + item.copy(isTrashed = true, modifiedAt = Instant.now()) }
        offline?.remove(itemId)
        scope.launch {
            try {
                if (item.isFolder) api.bulkTrash(emptyList(), listOf(itemId)) else api.bulkTrash(listOf(itemId), emptyList())
            } catch (e: Exception) {
                trashItems.update { l -> l.filter { it.id != itemId } }
                allItems.update { it + item }
                if (item.isStarred) applyStarred(item)
                error.value = e.message
            }
        }
    }

    fun move(itemId: String, target: String?) {
        val item = allItems.value.firstOrNull { it.id == itemId } ?: return
        if (target != null && isDescendant(target, itemId)) return
        if (!isOnline()) { error.value = NeedsConnection().message; return }
        val previous = item.parentId
        allItems.update { l -> l.map { if (it.id == itemId) it.copy(parentId = target) else it } }
        scope.launch {
            try {
                if (item.isFolder) api.bulkMove(emptyList(), listOf(itemId), target) else api.bulkMove(listOf(itemId), emptyList(), target)
            } catch (e: Exception) {
                allItems.update { l -> l.map { if (it.id == itemId) it.copy(parentId = previous) else it } }
                error.value = e.message
            }
        }
    }

    fun restore(itemId: String) {
        val item = trashItems.value.firstOrNull { it.id == itemId } ?: return
        if (!isOnline()) { error.value = NeedsConnection().message; return }
        trashItems.update { l -> l.filter { it.id != itemId } }
        scope.launch { runCatching { api.restoreFromTrash(itemId, item.isFolder) }.onFailure { trashItems.update { l -> l + item }; error.value = it.message } }
    }

    fun emptyTrash() {
        if (!isOnline()) { error.value = NeedsConnection().message; return }
        val snapshot = trashItems.value
        trashItems.value = emptyList()
        scope.launch { runCatching { api.emptyTrash() }.onFailure { trashItems.value = snapshot; error.value = it.message } }
    }

    fun spreadsheetWasSaved(itemId: String, size: Long, modifiedAt: Instant, contentVersion: Long?) =
        applyEverywhere(itemId) { it.copy(size = size, modifiedAt = modifiedAt, contentVersion = contentVersion ?: it.contentVersion) }

    fun reset() {
        listOf(allItems, recentItems, starredItems, sharedItems, trashItems).forEach { it.value = emptyList() }
        error.value = null
    }

    private fun applyEverywhere(itemId: String, transform: (SheetItem) -> SheetItem) {
        for (flow in listOf(allItems, recentItems, starredItems, sharedItems, trashItems)) {
            if (flow.value.any { it.id == itemId }) flow.update { l -> l.map { if (it.id == itemId) transform(it) else it } }
        }
    }

    private fun applyStarred(item: SheetItem) {
        allItems.update { l -> l.map { if (it.id == item.id) it.copy(isStarred = item.isStarred) else it } }
        recentItems.update { l -> l.map { if (it.id == item.id) it.copy(isStarred = item.isStarred) else it } }
        starredItems.update { l ->
            if (item.isStarred) (if (l.any { it.id == item.id }) l.map { if (it.id == item.id) item else it } else listOf(item) + l) else l.filter { it.id != item.id }
        }
    }

    private fun folder(dto: FolderDto) = SheetItem(dto.id, dto.name, SheetItem.ItemType.FOLDER, dto.parentId, modifiedAt = DriveDate.parse(dto.updatedAt) ?: Instant.EPOCH, isStarred = dto.isStarred ?: false)

    companion object { const val RECENT_LIMIT = 50 }
}

/** The account's devices, this one first, and revoking them. */
class DeviceSessionService(private val api: SheetsApi, private val scope: CoroutineScope, private val currentDeviceName: () -> String) {
    val sessions = MutableStateFlow<List<DeviceSession>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    suspend fun load() {
        isLoading.value = true
        error.value = null
        try {
            sessions.value = api.sessions(currentDeviceName()).sortedWith(compareByDescending<DeviceSession> { it.isCurrentDevice }.thenByDescending { it.lastUsedAt ?: it.createdAt })
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            isLoading.value = false
        }
    }

    fun revoke(id: String) {
        val before = sessions.value
        sessions.value = before.filter { it.id != id }
        scope.launch { runCatching { api.revokeSession(id) }.onFailure { sessions.value = before; error.value = it.message } }
    }
}
