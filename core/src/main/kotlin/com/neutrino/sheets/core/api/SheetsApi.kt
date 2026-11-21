package com.neutrino.sheets.core.api

import com.neutrino.sheets.core.model.DeviceSession
import com.neutrino.sheets.core.model.SheetFileInfo
import com.neutrino.sheets.core.model.ShareRole
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.DriveDate
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderContentsDto
import com.neutrino.shared.core.api.FolderDto
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.api.apiJson
import com.neutrino.shared.core.api.httpError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Sheets has no backend of its own: Drive's folder, file, trash and key APIs, and the account's
 * sessions. Listings ask for Drive's `office` category — the narrowest filter that provably holds an
 * `.xlsx` (`type=sheet` is pinned to the bespoke JSON that predates OOXML and lists a drive as empty)
 * — and the caller narrows to spreadsheets.
 */
class SheetsApi(http: OkHttpClient, auth: AuthClient) : NeutrinoApi(http, auth) {

    /** One folder's contents; only the root is paged, since a page cut before narrowing can come back empty. */
    suspend fun folderContents(folderId: String?): FolderContentsDto {
        val u = if (folderId != null) url("/drive/folders/$folderId").newBuilder().addQueryParameter("type", TYPE).build()
        else url("/drive/folders/${rootFolderId()}").newBuilder().addQueryParameter("limit", PAGE.toString()).addQueryParameter("offset", "0")
            .addQueryParameter("orderBy", "createdAt").addQueryParameter("direction", "desc").addQueryParameter("type", TYPE).build()
        return live(json(FolderContentsDto.serializer(), get(u)))
    }

    suspend fun recent(): FolderContentsDto = live(json(FolderContentsDto.serializer(), get(url("/drive/recent").newBuilder().addQueryParameter("type", TYPE).addQueryParameter("limit", PAGE.toString()).build())))

    /** `limit` matters: `/starred` defaults to five, which five documents can fill. */
    suspend fun starred(): FolderContentsDto = live(json(FolderContentsDto.serializer(), get(url("/drive/starred").newBuilder().addQueryParameter("type", TYPE).addQueryParameter("limit", PAGE.toString()).build())))

    suspend fun sharedWithMe(): FolderContentsDto = live(json(FolderContentsDto.serializer(), get(url("/drive/shared-with-me").newBuilder().addQueryParameter("type", TYPE).build())))

    suspend fun trash(): TrashContentsDto = json(TrashContentsDto.serializer(), get(url("/drive/trash").newBuilder().addQueryParameter("type", TYPE).build()))

    private fun live(listing: FolderContentsDto) = listing.copy(files = listing.files.filter { it.deletedAt.isNullOrEmpty() })

    suspend fun fileInfo(fileId: String): SheetFileInfo? {
        val dto = try {
            json(FileInfoDto.serializer(), get("/drive/files/$fileId/info"))
        } catch (e: ApiException.Http) {
            if (e.status == 404 || e.status == 403) return null
            throw e
        }
        return SheetFileInfo(dto.id, dto.name, dto.sizeBytes ?: 0, dto.folderId, dto.mimeType, DriveDate.parse(dto.updatedAt) ?: Instant.EPOCH,
            DriveDate.parse(dto.deletedAt), ShareRole.of(dto.yourRole ?: "owner"), dto.contentVersion)
    }

    suspend fun updateFolder(id: String, name: String? = null, isStarred: Boolean? = null): FolderDto =
        json(FolderDto.serializer(), method("PATCH", "/drive/folders/$id", buildJsonObject { name?.let { put("name", it) }; isStarred?.let { put("isStarred", it) } }))

    suspend fun updateFile(id: String, name: String? = null, isStarred: Boolean? = null): FileDto =
        json(FileDto.serializer(), method("PATCH", "/drive/files/$id", buildJsonObject { name?.let { put("name", it) }; isStarred?.let { put("isStarred", it) } }))

    suspend fun bulkTrash(fileIds: List<String>, folderIds: List<String>) {
        text(method("POST", "/drive/bulk/trash", buildJsonObject { put("fileIds", strings(fileIds)); put("folderIds", strings(folderIds)) }))
    }

    suspend fun bulkMove(fileIds: List<String>, folderIds: List<String>, target: String?) {
        text(method("POST", "/drive/bulk/move", buildJsonObject {
            put("fileIds", strings(fileIds)); put("folderIds", strings(folderIds)); put("targetFolderId", target?.let(::JsonPrimitive) ?: JsonNull)
        }))
    }

    suspend fun restoreFromTrash(id: String, isFolder: Boolean) { text(method("POST", "/drive/trash/${if (isFolder) "folders" else "files"}/$id/restore", null as String?)) }
    suspend fun deleteForever(id: String, isFolder: Boolean) { text(method("DELETE", "/drive/trash/${if (isFolder) "folders" else "files"}/$id", null as String?)) }
    suspend fun emptyTrash() { text(method("DELETE", "/drive/trash", null as String?)) }

    suspend fun download(fileId: String): ByteArray = openBlob(fileId).use { r -> withContext(Dispatchers.IO) { r.body?.bytes() ?: ByteArray(0) } }

    /** Creates a file; every field precedes the blob, and the sealed key rides in the same request. */
    suspend fun create(ciphertext: ByteArray, fileName: String, folderId: String?, encryptedMetadata: String, sealedFileKey: String, keyVersion: Int): FileDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("encrypted_metadata", encryptedMetadata)
            folderId?.let { addFormDataPart("folder_id", it) }
            addFormDataPart("encrypted_file_key", sealedFileKey)
            addFormDataPart("key_version", keyVersion.toString())
            addFormDataPart("file", fileName, ciphertext.toRequestBody(XLSX))
        }.build()
        return upload(body)
    }

    /** Autosave; `expectedContentVersion` has the server refuse the write if anything landed since. */
    suspend fun autosave(fileId: String, fileName: String, ciphertext: ByteArray, expectedContentVersion: Long?): FileDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", fileName, ciphertext.toRequestBody(XLSX)).build()
        val u = url("/drive/files/$fileId/autosave").newBuilder().apply { expectedContentVersion?.let { addQueryParameter("expectedContentVersion", it.toString()) } }.build()
        return withContext(Dispatchers.IO) {
            send { Request.Builder().url(u).put(body) }.use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 409 && isConflict(text)) throw ContentVersionConflict(currentVersion(text))
                    throw httpError(response.code, text)
                }
                apiJson.decodeFromString(FileDto.serializer(), text)
            }
        }
    }

    suspend fun sessions(currentDeviceName: String): List<DeviceSession> =
        json(SessionsDto.serializer(), get("/auth/sessions")).sessions.map {
            DeviceSession(it.id, it.deviceName, it.userAgent, DriveDate.parse(it.createdAt) ?: Instant.EPOCH, DriveDate.parse(it.lastUsedAt), it.deviceName == currentDeviceName)
        }

    suspend fun revokeSession(id: String) { text(method("DELETE", "/auth/sessions/$id", null as String?)) }

    class ContentVersionConflict(val current: Long?) :
        Exception("This spreadsheet changed on another device since it was opened. Reload to get the latest version, or keep your copy.")

    companion object {
        const val TYPE = "office"
        const val PAGE = 500
        private val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".toMediaType()
        private fun strings(v: List<String>) = JsonArray(v.map(::JsonPrimitive))

        fun isConflict(body: String) = runCatching {
            apiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "CONTENT_VERSION_CONFLICT"
        }.getOrDefault(false)

        fun currentVersion(body: String): Long? = runCatching {
            val message = apiJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: return null
            message.substringAfter("found ", "").takeWhile { it.isDigit() }.toLongOrNull()
        }.getOrNull()
    }
}

@Serializable
data class FileInfoDto(val id: String, val name: String, val sizeBytes: Long? = null, val folderId: String? = null, val mimeType: String? = null,
    val updatedAt: String? = null, val deletedAt: String? = null, val yourRole: String? = null, val contentVersion: Long? = null)

@Serializable
data class TrashFileDto(val id: String, val name: String, val sizeBytes: Long? = null, val mimeType: String? = null, val deletedAt: String? = null)

@Serializable
data class TrashFolderDto(val id: String, val name: String, val deletedAt: String? = null)

@Serializable
data class TrashContentsDto(val files: List<TrashFileDto> = emptyList(), val folders: List<TrashFolderDto> = emptyList())

@Serializable
data class SessionDto(val id: String, val deviceName: String? = null, val userAgent: String? = null, val createdAt: String? = null, val lastUsedAt: String? = null)

@Serializable
data class SessionsDto(val sessions: List<SessionDto> = emptyList())
