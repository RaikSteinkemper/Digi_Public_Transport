# Ticketless Transit - ÖPNV mit BLE Beacon

Proof-of-Concept für ticketlose Mobilität mit automatischer Fahrterfassung via BLE-Beacon.

**Technologie**: Android-App, Node.js Backend, BLE-Scanning, ECDSA P-256 Token, QR-Codes

---

## Struktur

- `/android` - Native Android-App (Kotlin, Material Design 3)
- `/backend` - Node.js Express Backend mit SQLite
- `/pi/beacon` - BLE-Beacon Service für Raspberry Pi
- `/web` - Legacy Webapps (Passenger, Inspector)

---

## Quick Start - Backend

```bash
# 1. Dependencies
cd backend
npm install

# 2. Start Backend (Port 3000)
npm start

# Backend lädt automatisch SQLite Database
```

---

## Android App Installation & Deployment

### Installation auf Smartphone

**Option 1: Via Android Studio**
1. Android-Handy via USB anschließen
2. Android Studio: `Run > Run 'app'` klicken
3. App wird automatisch gebaut und installiert

**Option 2: Manuell via ADB**
```bash
cd android
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### App bleibt dauerhaft auf Handy

✅ Nach Installation ist die App **permanent installiert**
- Funktioniert völlig unabhängig vom Entwicklungs-PC
- Verbindung zum PC kann getrennt werden
- App lädt nur über Backend (IP-Adresse, siehe unten)

✅ Updates: Neue APK installieren (überschreibt alte Version)

---

## Inspector App Installation (für Kontrolleure)

### Setup mit Public Key

Die Inspector-App (unter `/android2`) braucht den öffentlichen Schlüssel vom Backend:

1. **Public Key vom Backend auslesen**:
```bash
# Die Datei befindet sich unter:
cat backend/keys/public.pem

# Oder über HTTP direkt vom laufenden Backend:
curl http://192.168.2.146:3000/.well-known/backend-public.pem
```

2. **Public Key in die Inspector-App kopieren**:
```bash
# Linux/Mac:
cp backend/keys/public.pem android2/app/src/main/res/raw/backend_public.pem

# Windows (PowerShell):
copy backend\keys\public.pem android2\app\src\main\res\raw\backend_public.pem
```

3. **Inspector-App bauen und installieren**:
```bash
cd android2
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Public Key ändert sich NICHT beim Netzwerkwechsel!

✅ **Wichtig**: Der Public Key ist an die RSA-Schlüsselpaar gebunden, NICHT an die IP/Netzwerk!

Das bedeutet:
- Pi ins andere Netzwerk → IP ändert sich ❌, Public Key bleibt gleich ✅
- Backend neu starten → Public Key bleibt gleich ✅
- Backend umziehen → Public Key bleibt gleich ✅

**Der Public Key muss nur neu in die Inspector-App wenn**:
- Du `rm -rf backend/keys/` machst und `npm run genkeys` neu generierst
- → Dann musste neue `public.pem` wieder kopieren und App neu bauen

---

## Backend IP-Konfiguration

### Problem: Pi kommt ins andere Netzwerk

Wenn der Pi mit neuer IP ins andere Netzwerk kommt:

1. **IP des Pi herausfinden**:
```bash
# Auf dem Pi:
hostname -I

# Oder von anderem Gerät (Linux/Mac):
arp-scan --localnet
nmap 192.168.X.0/24
```

2. **IP in Android-App ändern** → `android/app/src/main/kotlin/com/opnv/poc/SessionManager.kt`:

```kotlin
private val BASE_URL = "http://192.168.2.146:3000"  // ← DIESE ZEILE ÄNDERN
```

Beispiel - neue IP `192.168.1.100`:
```kotlin
private val BASE_URL = "http://192.168.1.100:3000"
```

3. **App neu bauen und installieren**:
```bash
cd android
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

4. **Backend neu starten** (auf dem Pi):
```bash
sudo systemctl restart opnv-backend
```

### Empfehlung: Statische IP für Pi

Um dieses Problem zu vermeiden → **statische IP** für den Pi:

**Via Router (einfacher)**: MAC-Adresse des Pi an feste IP binden im Router-Admin-Panel.

**Via Pi selbst**: Datei `/etc/dhcpcd.conf` bearbeiten:
```bash
sudo nano /etc/dhcpcd.conf

# Am Ende hinzufügen:
interface eth0
static ip_address=192.168.2.146/24
static routers=192.168.2.1
static domain_name_servers=8.8.8.8 1.1.1.1

# Speichern (Ctrl+O, Enter, Ctrl+X)

# Pi neustarten
sudo reboot
```

Danach: IP ändert sich nicht mehr!

---

## Features der App

✅ **Automatische Fahrterfassung** - BLE-Beacon startet/beendet Session automatisch
✅ **"Rechnung Heute"** - Zeigt alle heutigen Fahrten mit Kosten
✅ **Intelligente Preisgestaltung** - 3€ pro Fahrt, max. 8€ Tagesticket
✅ **QR-Token** - Dynamischer QR-Code mit JWT-Token
✅ **Material Design 3** - Modernes, professionelles UI
✅ **Debug-Tools** - Fahrten löschen, Cache leeren für Tests

---

## API-Endpoints (Backend)

| Endpoint | Methode | Beschreibung |
|----------|---------|-------------|
| `/device/register` | POST | Device-Schlüssel registrieren |
| `/session/start` | POST | Fahrt starten |
| `/session/end` | POST | Fahrt beenden |
| `/trips/today` | GET | Heutige Fahrten abrufen |
| `/.well-known/backend-public.pem` | GET | Öffentlicher Schlüssel |
| `/debug/delete-today-trips` | POST | [DEBUG] Fahrten löschen |

---

## Sicherheit

- **JWT RS256**: Backend signiert Tokens mit RSA-Schlüssel
- **ECDSA P-256**: Device signiert lokal Transaktionen
- **Offline-Verify**: Inspector kann Tokens ohne Backend verifizieren
- **Schlüsselpersistierung**: Keys lokal in SharedPreferences (Android)

---

## Debugging

Alle Debug-Befehle für Backend und Beacon → siehe [debug.md](debug.md)

Häufige Befehle:
```bash
# Backend Logs
sudo journalctl -u opnv-backend -f

# Backend Status
sudo systemctl status opnv-backend

# Backend neustarten
sudo systemctl restart opnv-backend

# Port 3000 prüfen
sudo ss -tlnp | grep 3000
```

---

## Kosten-Logik

```
Pro Fahrt: 3,00€
Tagesticket: 8,00€ (Obergrenze)

Beispiel:
- 1 Fahrt = 3,00€
- 2 Fahrten = 6,00€
- 3 Fahrten = 8,00€ (cap, spart 1,00€!)
- 4 Fahrten = 8,00€ (cap, spart 4,00€!)
```

Die App zeigt beim "Rechnung Heute" Button, wie viel man durch den Tagesticket-Cap spart.

---

## Architektur

```
User Handy (Android)
    ↓ BLE-Scan
Beacon (Pi)
    ↓ API Call
Backend (Port 3000)
    ↓ Store
SQLite (opnv.db)
```

**Flow**:
1. Android-App scannt nach BLE-Beacon
2. Beacon erkannt → `POST /session/start`
3. App bekommt JWT-Token
4. QR-Code mit Token anzeigen
5. Nach Fahrt: `POST /session/end`
6. Finale Rechnung via `GET /trips/today`

---

## Entwicklung & Änderungen

**App neu bauen**:
```bash
cd android
./gradlew.bat clean build
```

**Backend-Änderungen testen**:
```bash
cd backend
npm start  # Testserver
```

**Backend als Service deployen**:
```bash
sudo systemctl start opnv-backend
sudo systemctl status opnv-backend
```

---

## Lizenz & Kontakt

PoC-Projekt für Demonstrationszwecke.

---

*Zuletzt aktualisiert: February 2026*

## PI im Netzwerk finden

arp -a
arp -a | findstr 10.209.75.    // oder anderes Subnetz

## Mit bestimmtem Netzwerk verbinde

sudo nmcli connection up "SSID"


## WLAN hinzufügen

sudo nmcli con add type wifi ifname wlan0 con-name "Railen_Static" ssid "Railen_PC" wifi-sec.key-mgmt wpa-psk wifi-sec.psk "Railen123!" ip4 192.168.137.10/24 gw4 192.168.137.1

Und wichtiger machen:

## Pi IP herausfinden

hostname -I
