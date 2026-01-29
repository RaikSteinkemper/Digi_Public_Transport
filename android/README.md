# Android PoC App (Kotlin)

Minimale ÖPNV-Ticketlos App für Android mit:
- Background BLE-Scanning nach `BUS_4711`
- Auto Session-Start/End
- Rotating QR-Code (JWT + Slot + Device-Signature)
- Offline-Verifikation im Inspector (optional)

## Struktur

- `app/src/main/kotlin/com/opnv/poc/`
  - `MainActivity.kt` — UI (Status, QR, Buttons)
  - `BleManager.kt` — BLE-Scan & Discovery
  - `SessionManager.kt` — Session Start/End, API Calls
  - `QrGenerator.kt` — QR-Rendering
  - `CryptoHelper.kt` — ECDSA P-256 KeyPair, Signieren

- `app/src/main/AndroidManifest.xml` — Permissions, Services
- `app/src/main/res/` — Layouts, Strings
- `app/build.gradle.kts` — Dependencies

## Build & Run

```bash
# Prerequisites: Android Studio, SDK 33+, NDK (für BlueZ, optional)

cd android
./gradlew build
# oder via Android Studio:
# File > Open > select /android folder
# Build > Build Bundle(s) / APK(s)
```

APK installieren:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Permissions (AndroidManifest.xml)

- `BLUETOOTH`, `BLUETOOTH_ADMIN` — BLE API
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` — BLE Scanning (Android 12+)
- `ACCESS_FINE_LOCATION` — BLE (braucht Standort auf Android <12)
- `FOREGROUND_SERVICE` — Background Scanning

## Key Features

1. **BLE Scanner (Background)**
   - Scan startet nach App-Launch
   - Sucht nach Geräten mit localName = `BUS_4711`
   - Bei Fund: stabilitätsprüfung (z.B. 10s), dann Session-Start
   - Session-Token wird lokal gepuffert

2. **Session Auto Start/End**
   - Beacon erkannt (10s stable) → `POST /session/start`
   - Beacon weg (20s no signal) → `POST /session/end`

3. **QR-Code**
   - Zeigt `{sessionId, slot, sig}` als QR
   - Erneuert alle 5s
   - Kontrolleur scannt QR in Inspector-App

4. **Demo-Modus**
   - Buttons zum manuellen Start/End (für Test ohne echtem Beacon)

## Konfiguration

In `MainActivity.kt` (oben):
```kotlin
const val API_BASE = "http://192.168.1.10:3000"  // <- ändere IP
const val BEACON_NAME = "BUS_4711"
const val BEACON_SEARCH_TIMEOUT_MS = 20000
```

## Testing

1. Pi/Beacon läuft und advertist `BUS_4711`
2. App installieren, Permissions geben
3. App zeigt "Scanning..."
4. Wenn Beacon erkannt → "Session active"
5. QR wird angezeigt
6. Kontrolleur scannt mit Inspector-App

Für Demo ohne echten Beacon: nutze die Buttons im Demo-Modus.
