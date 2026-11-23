# Neutrino Sheets for Android — port notes and handoff

**Started:** 2026-11-20. **Status:** the iOS Sheets editor's features, less the ones listed under
"Not ported". Run on an Android 14 emulator against a real local server. Never yet run on a physical
device or against the hosted server.

## 1. Decisions

| Decision | Why |
|---|---|
| Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 36` | Same toolkit and floor as the other Android apps, so the shared library has one target. |
| `:core` is plain Kotlin/JVM; `:app` is Android | The model, engines, editor, geometry, API client and offline copies are tested on the JVM against `FakeServer` from the shared test fixtures. |
| The file is `.xlsx`, under secretstream with a per-file DEK | What iOS and the web store (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, listed with Drive's `type=office`, narrowed to that MIME). |
| The `.xlsx` codec lives in `neutrino_shared_android` | A line-for-line port of the web's reader and writer (`ooxml/XlsxReader.kt`, `XlsxWriter.kt`), with `scripts/crosscheck-web-xlsx.sh` running the web's own code against it in both directions. The legacy `neutrino/model.json` part is read when its digest matches. |
| The model boundary is the web's `{"sheets":[…]}` JSON | Everything this app does not model (charts, conditional formats, variables, unknown cell fields) rides through `extra` maps and is written back untouched. |
| Grid: one Compose `Canvas` drawing with `android.graphics`; frozen panes are four clipped regions | Mirrors iOS `GridPainter`/`FrozenPaneLayout`. Geometry is the web's (`CELL_W` 100, `CELL_H` 28, headers 50 × 26) with prefix sums, so hit testing is a binary search and a hidden or filtered row has no height. |
| Gestures: pan with fling, pinch zoom (0.5–3×), tap, double tap to edit, long-press drag to extend, drag the fill handle | Android equivalents of the iOS grid's gestures. |
| Editing in a formula bar above the grid; Return commits and moves down | As iOS's `CellEntryField`. "123" switches to the number keyboard. |
| Autosave 3 s after the last edit (Settings: 1–30 s), guarded by `expectedContentVersion` | A 409 raises "Use Theirs / Keep Mine" instead of overwriting another device's save. |
| Offline: every opened workbook's ciphertext and sealed key are kept on the device | When the server cannot be reached, the editor opens the last copy **read-only** with a banner, as iOS does. Sheets queues no offline edits. |
| OAuth client `neutrino-sheets-android`, migration `00160` (shared with Notes and Photos) | Root CLAUDE.md rule 3. **Sign-in fails against any server without it.** |
| Prefix `nsheet` | Same namespace as iOS Sheets. |

## 2. Feature map (iOS → Android)

Ported:
- sign-in/registration, key setup, pairing, recovery kit, key code
- browser:
  - sections: My Drive, Favorites, Shared, Recent, Trash
  - folders and sort
  - actions: create, rename (keeps `.xlsx`), duplicate, move, star, trash, restore, delete forever, empty trash
- editor:
  - formula bar and name box; typing and clearing
  - formatting: bold, italic, strikethrough, text and fill colour, alignment, vertical alignment, wrap/clip, font family and size, borders
  - number formats, including presets, custom patterns with a preview, and currency/percent/number toggles
  - merge/unmerge, fill handle with series, copy/cut/paste (system clipboard as TSV, full fidelity within the app)
  - insert/delete rows and columns, resize, hide/show
  - sort A→Z / Z→A, filter by values (this device only), freeze panes to the selection
  - table styles: the web's gallery of 84 plus Blank
  - tabs: add, rename, colour, duplicate, move, delete
  - find & replace (match case, entire cell)
  - undo/redo (100 steps), range summary (sum, average, count)
- offline copies and the Offline tab
- settings: theme, autosave interval, gridlines, devices, app lock, report a bug
- send a copy as `.xlsx`
- links `/open/sheet/<id>` and `neutrinosheets://sheet/<id>`

**Not ported:**
- formula evaluation (as on iOS: a formula shows its cached value, or its text in grey until the web recalculates it)
- the Drive preview picture of the first page (`SheetPreviewer`); the Drive grid shows a generic icon for workbooks saved here
- printing (`SheetPrint`)
- conditional-format editing (rules are kept and shifted, not drawn or edited)
- charts (kept, not drawn)

## 3. Deviations from iOS

- **Formula references follow inserted and deleted rows and columns**, as on the web
  (`structuralShift.ts`'s `shiftFormula`, ported with all of its test cases). This covers references
  on other tabs that name the edited sheet, and formula rules in conditional formats. A reference to
  a deleted cell becomes `#REF!`. **iOS does not do this today:** its `StructuralShift.swift` moves
  cells but leaves formulas pointing at the old addresses. The fix belongs in iOS too.
- **Autosave is version-guarded** (see §1).
- New tabs are named `Sheet N`, as on the web.

## 4. What is verified (2026-11-21, macOS host)

- `./gradlew :core:test`: 22 tests.
  - The model and number formatting.
  - The engines: fill series, merge, sort, filters, find, clipboard and structure.
  - The web's 16 `shiftFormula` cases plus our own, including a formula with no cached value surviving an `.xlsx` round trip.
  - The services against a fake backend with real crypto: create, edit, save, a guarded conflict, undo, copy/paste, structure, the read-only offline copy when the server is gone, and duplicate.
- `neutrino_shared_android`:
  - `XlsxCodecTest` passes.
  - `crosscheck-web-xlsx.sh` passes in both directions.
  - The Drive, Calendar, Docs and Notes builds pass.
- On the emulator (Pixel 3a image, API 34) against a locally built server with migration 00160:
  - registration with a custom server, key setup, creating a workbook
  - typing text, numbers and a formula
  - bold and fill on a whole row, inserting a row and undoing it, adding a tab
  - reopening after a force-stop: everything persisted
  - the offline copy opening read-only with the server stopped
  - Send a Copy producing an `.xlsx` with both sheets in it

**Not verified:**
- a physical device or the hosted server
- a real on-screen keyboard (the emulator was fed key events)
- the fill handle and pinch zoom by hand (the engines are tested; the gestures were not driven)
- opening a workbook written on the web or iOS through the UI (the codec is cross-checked against the web's code)
- app links (they need `assetlinks.json` served)
- app lock with biometrics
- a release (R8) build at run time

## 5. Next session checklist

1. Merge and deploy `neutrino` `feature/android-notes-client` (migration 00160).
2. On a physical device with Gboard:
   - typing in the formula bar
   - fill handle, pinch zoom, long-press selection
3. Open a workbook made on the web, with formulas, merges, a table style and conditional formats. Edit it, then reopen it on the web: nothing lost, formulas recalculated.
4. Serve `deploy/assetlinks.json` with the release certificate. Check that `/open/sheet/<id>` opens here.
5. Build `./gradlew :app:assembleRelease` with a signing config and run it.
6. Port the formula shift (§3) to iOS `StructuralShift.swift`.
