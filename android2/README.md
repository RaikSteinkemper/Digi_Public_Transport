# Inspector App - Ticketless Transit

Android-App zum Validieren von QR-Code-Tokens für Kontrolleure.

## Features

✅ **QR-Code Scanner** - Echtzeit-Kamera-Scanning
✅ **Offline-Verifikation** - Braucht kein Backend!
✅ **JWT Token Validation** - RSA RS256 Signatur-Prüfung
✅ **Live Feedback** - Grün (gültig) oder Rot (ungültig)
✅ **Token-Details** - Zeigt Device-ID, Session-ID, Zeitstempel

## Setup

### 1. Public Key kopieren

Die Datei `backend_public.pem` vom Backend muss in den `raw` Ordner:

```bash
cp ../backend/.well-known/backend-public.pem app/src/main/res/raw/backend_public.pem
```

### 2. Build & Install

```bash
./gradlew.bat assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Wie es funktioniert

1. **Kontrolleur öffnet App** → Kamera startet automatisch
2. **QR-Code scannen** → ML Kit erkennt Barcode
3. **Token verifizieren** → JWT mit öffentlichem Schlüssel prüfen
4. **Resultat anzeigen** → ✅ Grün = gültig, ❌ Rot = ungültig

## Anforderungen

- Android 8.0+ (API 26)
- Kamera-Berechtigung
- `backend_public.pem` im `raw` Ordner

## Dependencies

- **androidx.camera** - Kamera-Framework
- **ML Kit Barcode Scanning** - QR-Code Erkennung
- **io.jsonwebtoken** - JWT Verifikation

## Sicherheit

✅ Token-Signatur verifiziert mit RSA RS256
✅ Nur öffentlicher Schlüssel benötigt (nicht geheim)
✅ Offline-Betrieb (kein Backend nötig)
✅ Fälschungssicher
