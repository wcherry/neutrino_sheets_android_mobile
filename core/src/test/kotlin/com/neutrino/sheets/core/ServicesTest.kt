package com.neutrino.sheets.core

import com.neutrino.sheets.core.api.SheetsApi
import com.neutrino.sheets.core.editor.SheetEditor
import com.neutrino.sheets.core.editor.SheetPasteboard
import com.neutrino.sheets.core.model.SheetsSection
import com.neutrino.sheets.core.service.OfflineCopies
import com.neutrino.sheets.core.service.SheetContentService
import com.neutrino.sheets.core.service.SheetsDriveService
import com.neutrino.sheets.core.workbook.CellAddress
import com.neutrino.sheets.core.workbook.CellRange
import com.neutrino.sheets.core.workbook.CellStylePatch
import com.neutrino.sheets.core.workbook.SheetClipboard
import com.neutrino.sheets.core.workbook.SheetFile
import com.neutrino.sheets.core.workbook.SheetSelection
import com.neutrino.shared.core.FakeServer
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import com.neutrino.shared.core.ooxml.XlsxCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The services and the editor session against an in-memory server holding real ciphertext. */
class ServicesTest {
    private lateinit var fake: FakeServer
    private lateinit var tmp: File
    private lateinit var api: SheetsApi
    private lateinit var keys: InMemoryKeyringStore
    private lateinit var content: SheetContentService
    private lateinit var offline: OfflineCopies
    private lateinit var drive: SheetsDriveService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val versions = HashMap<String, Long>()
    private var serverDown = false

    private val pasteboard = object : SheetPasteboard {
        var payload: SheetClipboard? = null
        var text: String? = null
        override fun write(clipboard: SheetClipboard?, text: String) { payload = clipboard; this.text = text }
        override fun readPayload() = payload
        override fun readText() = text
        override val hasContent get() = text != null
    }

    @Before
    fun setUp() = runBlocking {
        fake = FakeServer()
        tmp = Files.createTempDirectory("sheets").toFile()
        fake.extraRoutes = { r, _ -> route(r) }
        api = fake.client(config = NeutrinoApps.sheets) { http, auth -> SheetsApi(http, auth) }
        api.retryPolicy = com.neutrino.shared.core.api.RetryPolicy.NONE
        api.auth.login(fake.email, fake.password)
        keys = InMemoryKeyringStore()
        val check = DeviceKeyCheck(api, keys)
        KeyManager(api, keys, check).provision()
        offline = OfflineCopies(File(tmp, "offline"))
        content = SheetContentService(api, { keys.load(fake.userId) }, check, offline)
        drive = SheetsDriveService(api, content, offline, scope) { !serverDown }
    }

    @After
    fun tearDown() {
        runCatching { fake.shutdown() }
        tmp.deleteRecursively()
    }

    private fun fileJson(id: String) = fake.files[id]!!.let {
        """{"id":"$id","name":"${it.name}","folderId":null,"sizeBytes":${it.blob.size},"mimeType":"${it.mimeType}","isStarred":false,"updatedAt":"2026-01-0${minOf(9, versions[id]?.toInt() ?: 1)}T00:00:00","contentVersion":${versions[id] ?: 1}}"""
    }

    private fun route(r: RecordedRequest): MockResponse? {
        val path = r.requestUrl!!.encodedPath
        Regex("/api/v1/drive/files/([^/]+)/info").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            if (id !in fake.files) return fake.status(404)
            return fake.ok(fileJson(id).dropLast(1) + ""","yourRole":"owner"}""")
        }
        Regex("/api/v1/drive/files/([^/]+)/autosave").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            val current = versions[id] ?: 1
            val expected = r.requestUrl!!.queryParameter("expectedContentVersion")?.toLong()
            if (expected != null && expected != current) return fake.status(409).setBody("""{"error":{"code":"CONTENT_VERSION_CONFLICT","message":"expected $expected, found $current"}}""")
            val boundary = r.getHeader("Content-Type")!!.substringAfter("boundary=")
            MultipartReader(r.body, boundary).use { reader -> fake.files[id] = fake.files[id]!!.copy(blob = reader.nextPart()!!.body.readByteArray()) }
            versions[id] = current + 1
            return fake.ok(fileJson(id))
        }
        return null
    }

    private fun editor(id: String) = SheetEditor(drive.item(id)!!, content, scope, pasteboard, autosaveDelayMs = 0)

    @Test
    fun aSpreadsheetIsCreatedAsARealEncryptedXlsxAndListed() = runBlocking {
        val item = drive.createSpreadsheet("Budget", null)
        assertEquals("Budget.xlsx", item.name)
        assertEquals(XlsxCodec.MIME_TYPE, fake.files[item.id]!!.mimeType)
        assertFalse(XlsxCodec.isWorkbook(fake.files[item.id]!!.blob))
        drive.load(SheetsSection.HOME)
        assertEquals(listOf("Budget"), drive.items(SheetsSection.HOME).map { it.displayName })
        val loaded = content.load(item.id)
        assertEquals("Sheet1", loaded.file.sheets.single().name)
    }

    @Test
    fun editsSaveUnderTheGuardAndUndoRedo() = runBlocking {
        val item = drive.createSpreadsheet("Budget", null)
        val ed = editor(item.id)
        ed.load()
        assertTrue(ed.isEditable)
        ed.commitEdit("Rent", CellAddress(1, 1))
        ed.commitEdit("1200", CellAddress(2, 1))
        ed.select(SheetSelection(CellAddress(1, 1), CellAddress(2, 1)))
        ed.applyStyle(CellStylePatch.bold(true), "Bold")
        ed.save()
        assertFalse(ed.hasUnsavedChanges.value)
        val stored = SheetFile.decode(FileCrypto.decryptFile(fake.files[item.id]!!.blob, content.load(item.id).dek))
        assertEquals("1200", stored.sheets[0].cells["B1"]!!.raw)
        assertEquals("bold", stored.sheets[0].cells["A1"]!!.cellStyle!!.fontWeight)

        ed.undo()
        assertNull(ed.activeSheet!!.cells["A1"]!!.cellStyle)
        ed.redo()
        assertEquals("bold", ed.activeSheet!!.cells["A1"]!!.cellStyle!!.fontWeight)

        // Somebody else saves meanwhile: the next save is refused, and keeping mine overwrites on purpose.
        versions[item.id] = 99
        ed.commitEdit("Mine", CellAddress(1, 2))
        ed.save()
        assertEquals(99L, ed.conflict.value!!.serverVersion)
        ed.keepLocalCopy()
        ed.save()
        assertNull(ed.conflict.value)
        assertFalse(ed.hasUnsavedChanges.value)
    }

    @Test
    fun copyPasteAndStructureThroughTheSession() = runBlocking {
        val item = drive.createSpreadsheet("Grid", null)
        val ed = editor(item.id)
        ed.load()
        ed.commitEdit("1", CellAddress(1, 1))
        ed.commitEdit("=A1+1", CellAddress(1, 2))
        ed.select(SheetSelection(CellAddress(1, 1), CellAddress(1, 2)))
        ed.copySelection()
        ed.select(SheetSelection(CellAddress(3, 1)))
        ed.paste()
        assertEquals("=C1+1", ed.activeSheet!!.cells["C2"]!!.raw)
        ed.select(SheetSelection(CellAddress(1, 1)))
        ed.insertRows(before = true)
        assertEquals("1", ed.activeSheet!!.cells["A2"]!!.raw)
        ed.addSheet()
        assertEquals(listOf("Sheet1", "Sheet 2"), ed.sheetNames)
        ed.fill(CellRange(CellAddress(1, 1), CellAddress(1, 1)))
        ed.undo()
        assertEquals(1, ed.sheetNames.size)
    }

    @Test
    fun withTheServerUnreachableTheLastCopyOpensReadOnly() = runBlocking {
        val item = drive.createSpreadsheet("Trip", null)
        val ed = editor(item.id)
        ed.load()
        ed.commitEdit("Tokyo", CellAddress(1, 1))
        ed.save()
        assertNotNull(offline.copy(item.id))
        assertFalse(String(offline.copy(item.id)!!.ciphertext, Charsets.ISO_8859_1).contains("Tokyo"))

        // Unreachable, as a phone with no signal finds it: nothing is listening.
        val offlineEditor = editor(item.id)
        serverDown = true
        fake.shutdown()
        offlineEditor.load()
        assertNotNull(offlineEditor.loadError.value, offlineEditor.offlineCopyAt.value)
        assertFalse(offlineEditor.isEditable)
        assertEquals("Tokyo", offlineEditor.activeSheet!!.cells["A1"]!!.raw)
    }

    @Test
    fun duplicateCarriesEverything() = runBlocking {
        val item = drive.createSpreadsheet("Plan", null)
        val ed = editor(item.id)
        ed.load()
        ed.commitEdit("keep me", CellAddress(2, 2))
        ed.save()
        val copy = drive.duplicate(item.id)
        assertEquals("Plan copy.xlsx", copy.name)
        assertEquals("keep me", content.load(copy.id).file.sheets[0].cells["B2"]!!.raw)
    }
}
