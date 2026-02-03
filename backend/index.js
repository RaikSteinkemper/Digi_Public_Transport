const express = require('express');
const bodyParser = require('body-parser');
const fs = require('fs');
const path = require('path');
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const { db, init } = require('./db');

const PRICE_PER_SESSION = 300; // cents (3.00 EUR per trip)
const DAILY_CAP = 800; // cents (8.00 EUR day ticket)

const app = express();
app.use(bodyParser.json());
app.use(require('cors')());

init();

const KEYS_DIR = path.join(__dirname, 'keys');
const PRIVATE_KEY = fs.readFileSync(path.join(KEYS_DIR, 'private.pem'), 'utf8');
const PUBLIC_KEY = fs.readFileSync(path.join(KEYS_DIR, 'public.pem'), 'utf8');

// Register device
app.post('/device/register', (req, res) => {
  const { deviceId, devicePubKeyPem } = req.body;
  if (!deviceId || !devicePubKeyPem) return res.status(400).json({ error: 'deviceId and devicePubKeyPem required' });
  const now = Math.floor(Date.now() / 1000);
  db.run(
    `INSERT OR REPLACE INTO devices(deviceId, devicePubKeyPem, createdAt) VALUES(?,?,?)`,
    [deviceId, devicePubKeyPem, now],
    function (err) {
      if (err) return res.status(500).json({ error: err.message });
      res.json({ ok: true });
    }
  );
});

// Start session
app.post('/session/start', (req, res) => {
  const { deviceId, vehicleId } = req.body;
  if (!deviceId || !vehicleId) return res.status(400).json({ error: 'deviceId and vehicleId required' });
  db.get(`SELECT devicePubKeyPem FROM devices WHERE deviceId = ?`, [deviceId], (err, row) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!row) return res.status(404).json({ error: 'device not registered' });

    const sessionId = uuidv4();
    const now = Math.floor(Date.now() / 1000);
    const validUntil = now + 60 * 60 * 12; // valid 12h
    const dayKey = Math.floor(now / 86400);

    const payload = {
      iss: 'opnv-poc-backend',
      sessionId,
      vehicleId,
      deviceId,
      validUntil,
      devicePubKey: row.devicePubKeyPem,
      dayKey
    };

    // Sign JWT with RS256
    const token = jwt.sign(payload, PRIVATE_KEY, { algorithm: 'RS256' });

    db.run(
      `INSERT INTO sessions(sessionId, deviceId, vehicleId, token, startTime, endTime) VALUES(?,?,?,?,?,?)`,
      [sessionId, deviceId, vehicleId, token, now, null],
      function (err) {
        if (err) return res.status(500).json({ error: err.message });
        res.json({ token, sessionId });
      }
    );
  });
});

// End session
app.post('/session/end', (req, res) => {
  const { sessionId } = req.body;
  if (!sessionId) return res.status(400).json({ error: 'sessionId required' });
  const now = Math.floor(Date.now() / 1000);
  db.get(`SELECT sessionId, deviceId, startTime, endTime FROM sessions WHERE sessionId = ?`, [sessionId], (err, row) => {
    if (err) return res.status(500).json({ error: err.message });
    if (!row) return res.status(404).json({ error: 'session not found' });
    if (row.endTime) return res.json({ ok: true, alreadyEnded: true });

    db.run(`UPDATE sessions SET endTime = ? WHERE sessionId = ?`, [now, sessionId], (err) => {
      if (err) return res.status(500).json({ error: err.message });

      // compute fare for this device for today
      const dayStart = Math.floor(new Date().setUTCHours(0,0,0,0) / 1000);
      const dayEnd = dayStart + 86400;
      db.all(
        `SELECT COUNT(*) AS cnt FROM sessions WHERE deviceId = ? AND endTime IS NOT NULL AND endTime >= ? AND endTime < ?`,
        [row.deviceId, dayStart, dayEnd],
        (err, rows) => {
          if (err) return res.status(500).json({ error: err.message });
          const cnt = rows && rows[0] ? rows[0].cnt : 0;
          let total = cnt * PRICE_PER_SESSION;
          let capped = false;
          if (total > DAILY_CAP) { total = DAILY_CAP; capped = true; }
          res.json({ ok: true, totalCents: total, capped });
        }
      );
    });
  });
});

app.get('/fare/today', (req, res) => {
  const { deviceId } = req.query;
  if (!deviceId) return res.status(400).json({ error: 'deviceId required' });
  const dayStart = Math.floor(new Date().setUTCHours(0,0,0,0) / 1000);
  const dayEnd = dayStart + 86400;
  db.all(
    `SELECT COUNT(*) AS cnt FROM sessions WHERE deviceId = ? AND endTime IS NOT NULL AND endTime >= ? AND endTime < ?`,
    [deviceId, dayStart, dayEnd],
    (err, rows) => {
      if (err) return res.status(500).json({ error: err.message });
      const cnt = rows && rows[0] ? rows[0].cnt : 0;
      let total = cnt * PRICE_PER_SESSION;
      let capped = false;
      if (total > DAILY_CAP) { total = DAILY_CAP; capped = true; }
      res.json({ totalCents: total, capped });
    }
  );
});

// Get detailed trips for today
app.get('/trips/today', (req, res) => {
  const { deviceId } = req.query;
  if (!deviceId) return res.status(400).json({ error: 'deviceId required' });
  const dayStart = Math.floor(new Date().setUTCHours(0,0,0,0) / 1000);
  const dayEnd = dayStart + 86400;
  db.all(
    `SELECT sessionId, vehicleId, startTime, endTime FROM sessions WHERE deviceId = ? AND endTime IS NOT NULL AND endTime >= ? AND endTime < ? ORDER BY startTime ASC`,
    [deviceId, dayStart, dayEnd],
    (err, rows) => {
      if (err) return res.status(500).json({ error: err.message });
      const trips = rows || [];
      const tripCount = trips.length;
      let totalCents = tripCount * PRICE_PER_SESSION;
      let capped = false;
      if (totalCents > DAILY_CAP) { 
        totalCents = DAILY_CAP; 
        capped = true; 
      }
      res.json({ 
        trips: trips.map(t => ({
          sessionId: t.sessionId,
          vehicleId: t.vehicleId,
          startTime: t.startTime,
          endTime: t.endTime
        })),
        tripCount,
        pricePerTrip: PRICE_PER_SESSION,
        subtotalCents: tripCount * PRICE_PER_SESSION,
        totalCents,
        capped,
        dayCap: DAILY_CAP
      });
    }
  );
});

// serve public key for inspector webapp
app.get('/.well-known/backend-public.pem', (req, res) => {
  res.type('application/x-pem-file');
  res.send(PUBLIC_KEY);
});

// DEBUG: Delete all trips for today
app.post('/debug/delete-today-trips', (req, res) => {
  const { deviceId } = req.body;
  if (!deviceId) return res.status(400).json({ error: 'deviceId required' });
  
  const dayStart = Math.floor(new Date().setUTCHours(0,0,0,0) / 1000);
  const dayEnd = dayStart + 86400;
  
  db.run(
    `DELETE FROM sessions WHERE deviceId = ? AND startTime >= ? AND startTime < ?`,
    [deviceId, dayStart, dayEnd],
    function (err) {
      if (err) return res.status(500).json({ error: err.message });
      console.log(`[DEBUG] Deleted ${this.changes} trips for device ${deviceId}`);
      res.json({ ok: true, deletedCount: this.changes });
    }
  );
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log('Backend listening on', PORT);
});
