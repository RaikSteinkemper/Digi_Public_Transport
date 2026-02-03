# Debug Befehle für ÖPNV Backend und Beacon Services

## Service Status überprüfen

### Backend Status
```bash
sudo systemctl status opnv-backend
# oder
sudo systemctl status backend
```

### Beacon Status
```bash
sudo systemctl status opnv-beacon
# oder
sudo systemctl status beacon
```

### Alle ÖPNV Services auflisten
```bash
sudo systemctl list-units | grep -i opnv
```

---

## Service Neu starten

### Backend neu starten
```bash
sudo systemctl restart opnv-backend
# oder
sudo systemctl restart backend
```

### Beacon neu starten
```bash
sudo systemctl restart opnv-beacon
# oder
sudo systemctl restart beacon
```

### Beide Services neu starten
```bash
sudo systemctl restart opnv-backend opnv-beacon
```

---

## Logs anzeigen (journalctl)

### Backend Logs - Live (Follow)
```bash
sudo journalctl -u opnv-backend -f
# oder
sudo journalctl -u backend -f
```

### Backend Logs - Letzte 50 Zeilen
```bash
sudo journalctl -u opnv-backend -n 50
```

### Backend Logs - Letzte Stunde
```bash
sudo journalctl -u opnv-backend --since "1 hour ago"
```

### Backend Logs - Mit Timestamps und ausführlich
```bash
sudo journalctl -u opnv-backend -o short-precise
```

### Beacon Logs - Live (Follow)
```bash
sudo journalctl -u opnv-beacon -f
# oder
sudo journalctl -u beacon -f
```

### Beacon Logs - Letzte 50 Zeilen
```bash
sudo journalctl -u opnv-beacon -n 50
```

### Beacon Logs - Letzte Stunde
```bash
sudo journalctl -u opnv-beacon --since "1 hour ago"
```

---

## Service Fehlersuche

### Backend starten und Output sehen
```bash
sudo systemctl start opnv-backend
sudo journalctl -u opnv-backend -f
```

### Beacon starten und Output sehen
```bash
sudo systemctl start opnv-beacon
sudo journalctl -u opnv-beacon -f
```

### Fehler anzeigen (Error-Level)
```bash
sudo journalctl -u opnv-backend -p err
sudo journalctl -u opnv-beacon -p err
```

### Warnings und Errors
```bash
sudo journalctl -u opnv-backend -p warning
sudo journalctl -u opnv-beacon -p warning
```

---

## Service Verwaltung

### Service aktivieren (beim Start automatisch starten)
```bash
sudo systemctl enable opnv-backend
sudo systemctl enable opnv-beacon
```

### Service deaktivieren (nicht beim Start starten)
```bash
sudo systemctl disable opnv-backend
sudo systemctl disable opnv-beacon
```

### Service stoppen
```bash
sudo systemctl stop opnv-backend
sudo systemctl stop opnv-beacon
```

### Service Status überprüfen (Alle Infos)
```bash
sudo systemctl show opnv-backend
sudo systemctl show opnv-beacon
```

---

## Netzwerk Debugging

### Prüfe ob Backend Port 3000 antwortet
```bash
curl -v http://localhost:3000/.well-known/backend-public.pem
# oder von anderem Host:
curl -v http://192.168.2.146:3000/.well-known/backend-public.pem
```

### Prüfe ob /trips/today Endpoint antwortet
```bash
curl -v http://localhost:3000/trips/today?deviceId=dev-12345678
```

### Prüfe alle offenen Ports (Backend sollte auf 3000 sein)
```bash
sudo netstat -tlnp | grep node
# oder mit ss:
sudo ss -tlnp | grep node
```

---

## Schnelle Debugging-Session

### Alle Logs der letzten 5 Minuten anzeigen
```bash
sudo journalctl --since "5 minutes ago"
```

### Nur Backend + Beacon Logs (parallelisiert)
```bash
sudo journalctl -u opnv-backend -u opnv-beacon -f
```

### Service neu starten und sofort Logs anzeigen
```bash
sudo systemctl restart opnv-backend && sudo journalctl -u opnv-backend -f
```

---

## Häufige Probleme

### Backend will nicht starten - Logs prüfen
```bash
sudo journalctl -u opnv-backend -n 50 -o short-precise
```

### Port 3000 wird verwendet
```bash
sudo lsof -i :3000
# oder
sudo ss -tlnp | grep 3000
```

### Beacon BLE Issues
```bash
sudo journalctl -u opnv-beacon -f
# und parallel in anderem Terminal:
sudo hciconfig  # Bluetooth Status
```

### Database Lock (SQLite)
```bash
sudo journalctl -u opnv-backend | grep -i "database"
```

---

## Service Datei Locations

Meist unter `/etc/systemd/system/`:
```bash
ls -la /etc/systemd/system/ | grep opnv
# oder
ls -la /etc/systemd/system/ | grep -E "backend|beacon"
```

Service Datei anschauen:
```bash
sudo cat /etc/systemd/system/opnv-backend.service
sudo cat /etc/systemd/system/opnv-beacon.service
```
