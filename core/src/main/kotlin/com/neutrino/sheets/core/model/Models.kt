package com.neutrino.sheets.core.model

import com.neutrino.shared.core.ooxml.XlsxCodec
import java.text.Collator
import java.time.Instant
import java.util.Locale

/** A folder or a spreadsheet in the user's Drive. */
data class SheetItem(
    val id: String,
    val name: String,
    val type: ItemType,
    /** Null is the root of the drive. */
    val parentId: String?,
    val size: Long? = null,
    val modifiedAt: Instant,
    val isTrashed: Boolean = false,
    val mimeType: String? = null,
    val isStarred: Boolean = false,
    val contentVersion: Long? = null,
    /** Reached through `shared-with-me`: owned by somebody else. */
    val isShared: Boolean = false,
) {
    enum class ItemType { FOLDER, FILE }

    val isFolder get() = type == ItemType.FOLDER
    val isSpreadsheet get() = type == ItemType.FILE && isSpreadsheet(mimeType)

    /** `Budget.xlsx` reads as "Budget", as the web library shows it. */
    val displayName get() = if (isFolder) name else XlsxCodec.strippingExtension(name)

    companion object {
        const val MIME = XlsxCodec.MIME_TYPE
        fun isSpreadsheet(mimeType: String?) = mimeType == MIME
    }
}

enum class SheetsSection(val title: String) {
    HOME("Home"), RECENT("Recent"), FAVORITES("Favorites"), SHARED("Shared"), TRASH("Trash");

    val supportsFolderNavigation get() = this == HOME
    val defaultSortOrder get() = if (this == RECENT) SheetSortOrder.NEWEST else SheetSortOrder.NAME_ASCENDING

    val emptyMessage: String
        get() = when (this) {
            HOME -> "No spreadsheets yet. Tap + to create one."
            RECENT -> "Spreadsheets you open or edit will appear here."
            FAVORITES -> "Star a spreadsheet to keep it here."
            SHARED -> "Spreadsheets other people share with you will appear here."
            TRASH -> "Trash is empty."
        }
}

enum class SheetSortOrder(val title: String) {
    NEWEST("Newest First"), OLDEST("Oldest First"), NAME_ASCENDING("Name (A–Z)"), NAME_DESCENDING("Name (Z–A)");

    fun sorted(items: List<SheetItem>): List<SheetItem> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        return items.sortedWith { l, r ->
            if (l.type != r.type) return@sortedWith if (l.isFolder) -1 else 1
            if (this == NEWEST && l.modifiedAt != r.modifiedAt) return@sortedWith r.modifiedAt.compareTo(l.modifiedAt)
            if (this == OLDEST && l.modifiedAt != r.modifiedAt) return@sortedWith l.modifiedAt.compareTo(r.modifiedAt)
            val byName = collator.compare(l.displayName, r.displayName)
            if (byName != 0) return@sortedWith if (this == NAME_DESCENDING) -byName else byName
            l.id.compareTo(r.id)
        }
    }
}

enum class ShareRole(val title: String) {
    OWNER("Owner"), EDITOR("Editor"), COMMENTER("Commenter"), VIEWER("Viewer");

    val canEdit get() = this == OWNER || this == EDITOR
    val wire get() = name.lowercase()

    companion object {
        fun of(server: String?) = entries.firstOrNull { it.wire == server?.lowercase() } ?: VIEWER
    }
}

/** `/files/{id}/info`: the file as the caller may reach it, with their role. */
data class SheetFileInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val folderId: String?,
    val mimeType: String?,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val yourRole: ShareRole,
    val contentVersion: Long? = null,
) {
    val isLive get() = deletedAt == null
    val isSpreadsheet get() = SheetItem.isSpreadsheet(mimeType)
    val isEditable get() = yourRole.canEdit

    fun asItem() = SheetItem(id, name, SheetItem.ItemType.FILE, folderId, sizeBytes, updatedAt, mimeType = mimeType,
        contentVersion = contentVersion, isShared = yourRole != ShareRole.OWNER)
}

data class DeviceSession(val id: String, val deviceName: String?, val userAgent: String?, val createdAt: Instant, val lastUsedAt: Instant?, val isCurrentDevice: Boolean)
