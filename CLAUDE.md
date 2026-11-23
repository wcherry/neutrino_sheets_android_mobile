# CLAUDE.md — Neutrino Sheets for Android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. This is the
Android port of `neutrino_sheets_ios_mobile`, built on `../neutrino_shared_android` (consumed by
local path through `includeBuild`, unversioned — a change there reaches this app and every other
Android app at once). Its OAuth client id is `neutrino-sheets-android`
(`neutrino/migrations/00160_oauth__*`); its prefs/secrets prefix is `nsheet`. Never change or
reuse either.

- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Put logic in `:core` (JVM-tested), not `:app`. Every edit goes through `SheetEditor`, which
  records it for undo and autosaves it; the grid only draws and reports gestures.
- The file is `.xlsx`, a wire format shared with the web and iOS. Its codec lives in
  `neutrino_shared_android` (`ooxml/XlsxCodec.kt`), a line-for-line port of the web's
  `lib/ooxml/xlsx/{read,write,mapping}.ts`; change the web first, then the port, and run
  `scripts/crosscheck-web-xlsx.sh` there. Fields this app does not understand (charts,
  conditional formats, …) ride through `extra` maps — never drop them.
- Behaviour that the web also implements (formula reference shifts, fill series, table styles,
  number formats, sheet naming) follows the web's code, with the web's test cases ported into
  `core/src/test`. When iOS and the web disagree, the web wins.
- Formulas are not evaluated: a formula cell shows its cached value, or its text in grey when it
  has none. Cached values come from the web.
- Workbook bodies are E2EE through the shared code — never add crypto here.
- A composable that reads editor state must get it as a parameter. The editor object never
  changes, so Compose skips a child that reads `editor.something` itself — the format bar once
  kept showing the previous cell's bold.
- Status, decisions, the iOS → Android feature map and the next-session checklist:
  `agent_docs/android-port.md`.
