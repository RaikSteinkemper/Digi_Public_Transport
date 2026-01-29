# ÖPNV Ticketless PoC (Beacon + Rotating QR)

This repository contains a small proof-of-concept for "ticketless ÖPNV with Beacons". It is intentionally minimal and runs locally.

Structure:
- `/backend` - Node.js Express backend, SQLite, keygen
- `/web/passenger` - Passenger webapp (generate device key, register, start/end session, rotating QR)
- `/web/inspector` - Inspector webapp (offline verify QR JSON)
- `/pi/beacon` - Optional Pi beacon placeholder

Quick start (Linux, Node.js installed):

1) Install backend deps

```bash
cd backend
npm install
```

2) (Optional) generate RSA keys for backend (or use included sample keys)

```bash
npm run genkeys
```

3) Start backend

```bash
npm start
# backend listens on http://localhost:3000
```

4) Open passenger webapp in browser:

Open `web/passenger/index.html` in a browser (serve with a simple static server if needed, or open file://). Example using Python http.server:

```bash
cd web/passenger
python3 -m http.server 8001
# then open http://localhost:8001 in mobile browser
```

Passenger flow:
- On first open the app generates an ECDSA P-256 keypair and registers the public key at `POST /device/register`.
- Click "Session starten" to POST `/session/start` and receive a JWT session token.
- The app signs (client-side) a per-slot payload and renders a rotating QR code JSON `{token, slot, sig}`.

Inspector flow:
- Open `web/inspector` similarly via a static server and paste the QR JSON into the textarea.
- Click Verify Offline: the inspector fetches `/.well-known/backend-public.pem` from backend (should be reachable) and performs:
  1) JWT signature verification (RS256)
  2) validUntil check
  3) slot freshness check (nowSlot±1 allowed)
  4) device signature verification (ECDSA P-256)

Notes & security:
- This PoC stores a device public key in the JWT so the inspector can verify the device signature offline. In production, you would avoid embedding long keys or change protocol accordingly.
- The backend signs session tokens with an RSA key (RS256). The inspector uses the backend public key to verify the token offline.
- No real payments are implemented. Business logic for fare capping is implemented in the backend (PRICE_PER_SESSION=2.00 EUR, DAILY_CAP=6.00 EUR).

Next steps (optional):
- Add Android BLE scanning to auto start/end sessions.
- Improve inspector UX: camera-based QR scan (html5-qrcode).
- Harden key storage and token format for production.
# Digi_Public_Transport
Digital Public Transport in Germany with BLE Beacon and Smartphone
