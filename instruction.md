# Gebrauchsanweisung (Deutsch)

Dieses Dokument beschreibt, wie du das PoC lokal einrichtest und benutzt: Backend, Passenger (Fahrgast), Inspector (Kontrolleur) und optional Raspberry Pi als BLE-Beacon.

Alle Pfade beziehen sich auf das Projekt-Root (`/home/rsk/FHDW/Digi_Public_Transport`).

---

## Systemanforderungen
- Ein Rechner mit Node.js (>=16) und npm
- Python3 (für einfachen Static-Server) oder ein beliebiger Webserver
- Optional: Raspberry Pi mit BlueZ für BLE-Beacon
- Moderne Browser (Desktop/Mobile) mit WebCrypto-Unterstützung

---

## 1) Backend einrichten (lokal)

1. Öffne ein Terminal und wechsle in das Backend-Verzeichnis:

```bash
cd /home/rsk/FHDW/Digi_Public_Transport/backend
```

2. Abhängigkeiten installieren:

```bash
npm install
```

3. (Optional) Neue RSA-Schlüssel erzeugen (ersetzt die mitgelieferten Beispiel-Schlüssel):

```bash
npm run genkeys
```

4. Backend starten:

```bash
npm start
# Backend hört jetzt standardmäßig auf http://localhost:3000
```

Wichtige Endpunkte:
- `POST /device/register` — registriert `deviceId` und `devicePubKeyPem`.
- `POST /session/start` — erwartet `{deviceId, vehicleId}`, gibt `{token, sessionId}` zurück.
- `POST /session/end` — erwartet `{sessionId}`, beendet Session und liefert Tagesfare.
- `GET /.well-known/backend-public.pem` — liefert Backend-Public-Key (für Offline-Verifikation).

Business-Parameter (im Backend-Code einstellbar):
- Preis pro Session: 200 Cent (2,00 €)
- Tages-Cap: 600 Cent (6,00 €)

---

## 2) Android-App (Fahrgast) — Native Variante

**Hinweis**: Es gibt zwei Varianten — die native Android-App (diese) oder die Webapp-Variante (siehe unten). Die Android-App läuft im Hintergrund und scannt BLE.

### Android-App Setup & Build

Voraussetzungen:
- Android Studio (oder Gradle Command-Line)
- Android SDK 33+ (API Level 33+)
- Android Gradle Plugin 8.1.0+

1. Öffne das Android-Projekt:

```bash
cd /home/rsk/FHDW/Digi_Public_Transport/android
```

2. Öffne in Android Studio: File > Open > wähle das `android` Verzeichnis.

3. Starte den Build:

```bash
./gradlew build
# oder über Android Studio: Build > Build Bundle(s) / APK(s)
```

4. APK auf dein Gerät übertragen:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

5. App starten: Tippe auf „ÖPNV PoC"

### Verhalten der Android-App

- **Auto BLE-Scan**: Nach dem Start scannt die App automatisch nach dem Beacon `BUS_4711`.
- **Auto Session-Start**: Wenn Beacon erkannt wird → Session wird automatisch gestartet.
- **QR-Display**: Die App zeigt den rotierenden QR-Code (aktualisiert alle 5s).
- **Auto Session-End**: Wenn Beacon 20s lang verschwindet → Session endet automatisch.
- **Demo-Modus**: Buttons für manuelles Start/End (wenn Beacon nicht erreichbar).
- **Bill Today**: Button zum manuellen Abrufen der heutigen Fare.

### Berechtigungen

Beim ersten Start fragt die App nach Berechtigungen:
- Bluetooth, Bluetooth-Admin (Scan)
- Standort (erforderlich für BLE auf Android <12)
- Internet (für API-Calls)

**Wichtig**: Gib alle Berechtigungen frei, sonst funktioniert der BLE-Scan nicht.

### Konfiguration

Falls das Backend **nicht** auf `localhost:3000` läuft, ändere die IP in `SessionManager.kt`:

```kotlin
const val API_BASE = "http://192.168.1.10:3000"  // <- hier IP eintragen
```

Danach neu builden und APK neu installieren.

---

## 2b) Passenger (Fahrgast) einrichten — Webapp-Variante (Alternative)

**Alternative**: Falls du keine native App möchtest, nutze diese Webapp (läuft aber nicht im Hintergrund ohne Beacon-Sim).

Die Passenger-Webapp befindet sich in `web/passenger`.

1. Den Static-Server starten (Beispiel mit Python):

```bash
cd /home/rsk/FHDW/Digi_Public_Transport/web/passenger
python3 -m http.server 8001
# Öffne im Mobilgerät: http://<server-ip>:8001
```

Hinweis: Ersetze `<server-ip>` mit der IP des Rechners, auf dem der Static-Server läuft (z. B. `192.168.1.10`).

2. Verhalten der Webapp:
- Beim ersten Start erstellt die Webapp in der Browser-Umgebung ein ECDSA P-256 Keypair (WebCrypto) und speichert den privaten Schlüssel in `localStorage`.
- Die Public-Key wird als PEM beim Backend per `POST /device/register` angemeldet.
- `Session starten (im Bus)` ruft `POST /session/start` auf und erhält einen JWT (`token`) zurück.
- Die App signiert clientseitig die Kombination `token + '|' + slot` (ECDSA P-256, SHA-256) und rendert ein rotierendes QR (JSON: `{token, slot, sig}`).
- Die rohe QR-JSON wird unter dem QR angezeigt (zum Kopieren in die Inspector-App).
- `Session beenden` ruft `POST /session/end` und zeigt den aktuellen Tagesbetrag an.

3. Wichtige Anpassungen (bei Bedarf):
- Wenn das Backend nicht auf `localhost:3000` läuft, öffne `web/passenger/passenger.js` und passe `API_BASE` (Zeile oben) an, z. B. `http://192.168.1.10:3000`.
- Sicherstellen, dass das Mobilgerät und das Backend im gleichen Netzwerk sind.

## 3) Inspector / Kontrolleur (Offline-Check)

Die Inspector-Webapp befindet sich in `web/inspector`.

1. Starte einen Static-Server:

```bash
cd /home/rsk/FHDW/Digi_Public_Transport/web/inspector
python3 -m http.server 8002
# Öffne: http://<server-ip>:8002
```

2. Verifikation (Offline-prinzip):
- Kopiere in der Passenger-App das rohe JSON unter dem QR (z. B. `{"token":"...","slot":12345,"sig":"..."}`).
- Füge dieses JSON in das Textfeld der Inspector-App ein und klicke `Verify Offline`.

3. Welche Checks macht der Inspector?
- Verifiziert JWT-Signatur (RS256) mit dem Backend-Public-Key, den er von `http://<backend-host>:3000/.well-known/backend-public.pem` lädt. (Wenn du komplett offline arbeiten willst, kopiere die PEM und binde sie lokal in die Inspector-Seite ein.)
- Prüft `validUntil` im JWT.
- Prüft, ob `slot` in `[nowSlot-1, nowSlot+1]` liegt (Slot = floor(nowUnix/30)).
- Verifiziert die Geräte-Signatur (ECDSA P-256) über die Payload `token + '|' + slot` mit dem `devicePubKey` aus dem JWT.

Ausgabe: `RESULT: GREEN` (gültig) oder `RESULT: RED` (ungültig) mit Fehlergrund.

Hinweis: Der Inspector lädt den Backend-Public-Key beim Verifizieren. Danach funktioniert die eigentliche Verifikation lokal (keine weiteren Backend-Calls nötig).

---

## 4) Raspberry Pi als BLE-Beacon (optional)

Zweck: Der Pi sendet BLE-Advertising mit `localName` = `BUS_4711`. Die Passenger-App im PoC simuliert Beacon-Präsenz per Button, deshalb ist das Beacon optional.

Empfohlener Ablauf (gekürzt):

1. Auf dem Pi BlueZ und Node.js installieren (je nach Distribution anders).

2. Im Repo ist ein Beispielskript: `pi/beacon/beacon.js` (benötigt `bleno`).

3. Installation und Start (Beispiel):

```bash
# auf dem Pi
sudo apt update
sudo apt install -y build-essential libcap2-bin
# Node.js installieren (z. B. apt oder nvm)
cd /home/pi/Digi_Public_Transport/pi/beacon
npm install bleno
# ggf. node mit CAP_NET_ADMIN erlauben (erfordert sudo):
sudo setcap 'cap_net_raw,cap_net_admin+eip' $(which node)
node beacon.js
# Ausgabe: Advertising BUS_4711
```

Probleme mit `bleno`: unterschiedliche BlueZ-Versionen und Berechtigungen sind häufige Fehlerquellen. Falls Probleme auftreten, starte mit `sudo node beacon.js` zum Testen.

---

## 5) Netz & Time-Sync Hinweise
- Slot-Checks sind zeitabhängig (30s Fenster). Stelle sicher, dass die Systemzeiten von Backend, Inspector und Passenger halbwegs synchron sind (NTP empfohlen).
- Wenn das Backend auf einer anderen Maschine läuft, aktualisiere `API_BASE` in der Passenger-App.

---

## 6) Troubleshooting (häufige Probleme)
- Fehler: `device not registered` beim Starten: Prüfe Netzwerk-Tab in DevTools; vergewissere dich, dass `POST /device/register` 200 zurückgegeben hat.
- Fehler: `token expired` im Inspector: Systemzeit auf den Geräten prüfen.
- Fehler: `slot stale`: Uhrzeiten synchronisieren oder Slot-Toleranz erweitern (Inspector-Logik).
- Beacon: `bleno` wirft Fehler: Prüfe BlueZ-Version, starte als `sudo` zum Test.

---

## 7) Erweiterungen (optional, Ideen)
- Camera-basiertes QR-Scanning in Inspector (z. B. `html5-qrcode`) für bequemes Scannen.
- Automatisches Start/Stop via Android BLE-Scan (wenn du eine Android-App hinzufügen willst).
- Backend: Persistenter Export der Tages-Abrechnungen; vielfältigere Fare-Rules (Zonen, Bestpreis-Logik). Die aktuelle Struktur ist so gehalten, dass ein späterer „SAP-Schritt“ (Session persistieren + Bestpreis-Berechnung) austauschbar ist.

---

Wenn du möchtest, kann ich jetzt noch:
- Die Inspector-App so ändern, dass der Backend-Public-Key lokal eingebettet ist (vollständiger Offline-Modus).
- Die Inspector-App mit Kamera-QR-Scanning erweitern.
- Die Passenger-App so anpassen, dass sie die rohe QR-JSON automatisch in die Zwischenablage kopiert.

Viel Erfolg beim Testen — sag mir kurz, welche Erweiterung du als nächstes wünschst.
