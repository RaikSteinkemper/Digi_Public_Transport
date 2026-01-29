const sqlite3 = require('sqlite3').verbose();
const path = require('path');

const DB_PATH = path.join(__dirname, 'data.sqlite');

const db = new sqlite3.Database(DB_PATH);

function init() {
  db.serialize(() => {
    db.run(`
      CREATE TABLE IF NOT EXISTS devices (
        deviceId TEXT PRIMARY KEY,
        devicePubKeyPem TEXT,
        createdAt INTEGER
      )
    `);

    db.run(`
      CREATE TABLE IF NOT EXISTS sessions (
        sessionId TEXT PRIMARY KEY,
        deviceId TEXT,
        vehicleId TEXT,
        token TEXT,
        startTime INTEGER,
        endTime INTEGER
      )
    `);
  });
}

module.exports = { db, init };
