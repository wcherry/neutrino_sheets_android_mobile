# Neutrino Sheets for Android

The Android port of `neutrino_sheets_ios_mobile`: end-to-end encrypted spreadsheets stored in
Neutrino Drive as `.xlsx`, with a grid editor (formatting, merges, fill, sort, filter, freeze,
tables, tabs, find & replace, undo) and read-only offline copies. Workbooks written here open on
the web and iOS, and the other way round. Everything goes through
[`wcherry/neutrino`](https://github.com/wcherry/neutrino) (`/api/v1`); the app has no backend of
its own.

- `core/` — plain Kotlin/JVM: the workbook model, its engines (fill, merge, sort, filter, find,
  structure, table styles, clipboard), the editor and its undo history, grid geometry, the Sheets
  API client, encrypted load/save and offline copies.
- `app/` — the Android app (Jetpack Compose; the grid is drawn on a `Canvas`).

The `.xlsx` reader and writer are not here: they are in `neutrino_shared_android`
(`neutrino-core/.../ooxml/`), checked against the web's own code by its `scripts/crosscheck-web-xlsx.sh`.

## Layout

This repository must sit next to `neutrino_shared_android`, which it builds from source:

```
getneutrino.app/
├── neutrino_shared_android/
└── neutrino_sheets_android_mobile/   ← this repo
```

## Build

To install the app on an emulator or phone, see [`INSTALL.md`](INSTALL.md).

Needs JDK 17 and the Android SDK (API 36).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test             # model, engines, formula shifts, services against a fake server
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

## Platform registration

- **OAuth client** `neutrino-sheets-android` is seeded by `neutrino/migrations/00160_oauth__*`.
  Sign-in fails against a server without it.
- **App config and brand** are `NeutrinoApps.sheets` (shared) and `SheetsBrand`
  (`app/.../ui/Components.kt`). The prefs/secrets prefix is `nsheet`, the iOS app's. Never change
  or reuse it.

From the emulator, a server on your computer is `http://10.0.2.2:8080`. See
`agent_docs/android-port.md` for decisions, what is verified, and what is left.
