# Installing Neutrino Sheets for Android

Neutrino Sheets is not on Google Play yet, and there is no release signing key, so the only
build is the **debug APK**. You build it from source and install it with `adb`. This guide covers
the emulator and a physical phone.

Requires Android 8.0 (API 26) or later. The app is `com.neutrino.sheets`.

## 1. Prerequisites

| What | Why |
|---|---|
| JDK 17 | Gradle 8.14 does not run on newer JDKs (25 fails). |
| Android SDK with platform **API 36** and `platform-tools` (`adb`) | Android Studio installs both; or use `sdkmanager "platforms;android-36" "platform-tools"`. |
| `neutrino_shared_android` checked out **next to** this repo | The build pulls it in with `includeBuild("../neutrino_shared_android")`. |
| A Neutrino server with migration `00160_oauth__*` | Registers the OAuth client `neutrino-sheets-android`. Without it sign-in fails with an opaque auth error. |

Directory layout:

```
getneutrino.app/
├── neutrino_shared_android/
└── neutrino_sheets_android_mobile/   ← this repo
```

If the SDK is not at the default location, set it in `local.properties`:

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

## 2. Build the APK

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Install it

### Emulator

Create and start an emulator (Android Studio › Device Manager; the app has been tested on a Pixel 3a
image, API 34), then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or build and install in one step: `./gradlew :app:installDebug`.

### Physical phone

1. On the phone, turn on **Developer options** (Settings › About phone › tap *Build number* seven
   times), then **USB debugging**.
2. Connect it over USB and accept the "Allow USB debugging?" prompt.
3. Check that `adb devices` lists it as `device` (not `unauthorized`).
4. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Without a cable: copy `app-debug.apk` to the phone and open it. Android asks you to allow installs
from that source (Files, Chrome, …) the first time.

With more than one device attached, choose one: `adb -s <serial> install -r …`.

## 4. Sign in

The sign-in screen has a server address. The default is `https://www.getneutrino.app`.

| Server | Address to enter |
|---|---|
| Hosted | leave the default |
| Local server, from the **emulator** | `http://10.0.2.2:8080` (the emulator's alias for your computer) |
| Local server, from a **phone** on the same Wi-Fi | `http://<your computer's LAN IP>:8080`; the server must listen on `0.0.0.0`, not `127.0.0.1` |

Plain `http://` is allowed for self-hosted and development servers. Encrypted content is protected
either way, but the sign-in token is not, so use `https://` for anything not on your own machine or
network.

## 5. First run

1. **Set up or restore your encryption key.** A new account creates one. An account that already
   has a key on another device restores it: pair with the web (Settings › Encryption › add a
   device, then compare the codes), scan the web's phone key code and enter its PIN, or use the
   recovery kit.
2. **Camera**, when asked: scanning a pairing or key-code QR code.
3. Optional: turn on app lock in Settings to require biometrics or the device PIN.

Every spreadsheet you open is kept on the phone, encrypted, and opens read-only when the server cannot be reached (the **Offline** tab lists them).

## 6. Updating

Build again and run `adb install -r …`. `-r` keeps your sign-in and data. Debug builds from
different computers are signed with different debug keys. If install fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall first (`adb uninstall com.neutrino.sheets`) and
sign in again.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Gradle fails with "Unsupported class file major version" | `JAVA_HOME` is not JDK 17. |
| `Included build '…/neutrino_shared_android' does not exist` | The shared repo is missing or not a sibling. |
| `SDK location not found` | Set `sdk.dir` in `local.properties` or `ANDROID_HOME`. |
| Sign-in fails with an auth / invalid client error | The server lacks migration `00160_oauth__*`. |
| "Could not connect" to a local server | Emulator: use `10.0.2.2`, not `localhost`. Phone: use the LAN IP and bind the server to `0.0.0.0`. |
| `adb devices` shows `unauthorized` | Accept the USB debugging prompt on the phone; reconnect the cable. |

Building and testing for development (unit tests, live server test) is in [`README.md`](README.md)
and `agent_docs/android-port.md`.
