/**
 * BLE Beacon für ÖPNV PoC
 * Sendet BLE-Advertising mit localName = 'BUS_4711'
 * Läuft auf Raspberry Pi mit BlueZ und Node.js
 */

const bleno = require('bleno');
const os = require('os');

const BEACON_NAME = 'BUS_4711';
const VEHICLE_ID = process.env.VEHICLE_ID || 'BUS_4711';
const LOG_PREFIX = '[BEACON]';

let isRunning = false;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 5;
const RECONNECT_DELAY = 3000; // 3 Sekunden

/**
 * Log mit Timestamp
 */
function log(message) {
  const timestamp = new Date().toISOString();
  console.log(`${timestamp} ${LOG_PREFIX} ${message}`);
}

function logError(message, err) {
  const timestamp = new Date().toISOString();
  console.error(`${timestamp} ${LOG_PREFIX} ERROR: ${message}`);
  if (err) {
    console.error(`${LOG_PREFIX}`, err.message);
  }
}

/**
 * Beacon starten
 */
function startAdvertising() {
  if (isRunning) {
    return;
  }

  try {
    bleno.startAdvertising(BEACON_NAME, [], (err) => {
      if (err) {
        logError('Failed to start advertising', err);
        scheduleReconnect();
      } else {
        isRunning = true;
        reconnectAttempts = 0;
        log(`✓ Beacon aktiv: ${BEACON_NAME} (Vehicle: ${VEHICLE_ID})`);
      }
    });
  } catch (err) {
    logError('Exception in startAdvertising', err);
    scheduleReconnect();
  }
}

/**
 * Beacon stoppen
 */
function stopAdvertising() {
  if (!isRunning) {
    return;
  }

  try {
    bleno.stopAdvertising((err) => {
      if (err) {
        logError('Failed to stop advertising', err);
      } else {
        isRunning = false;
        log('Beacon gestoppt');
      }
    });
  } catch (err) {
    logError('Exception in stopAdvertising', err);
  }
}

/**
 * Neuverbindung mit Exponential Backoff
 */
function scheduleReconnect() {
  if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
    logError(`Max reconnection attempts (${MAX_RECONNECT_ATTEMPTS}) erreicht. Beende Beacon.`);
    process.exit(1);
  }

  reconnectAttempts++;
  const delay = RECONNECT_DELAY * Math.pow(2, reconnectAttempts - 1);
  log(`Reconnect versuchen in ${delay}ms (Versuch ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})...`);

  setTimeout(() => {
    try {
      log('Versuche Bluetooth neu zu initialisieren...');
      startAdvertising();
    } catch (err) {
      logError('Exception during reconnect', err);
      scheduleReconnect();
    }
  }, delay);
}

/**
 * State-Change Handler
 */
bleno.on('stateChange', (state) => {
  log(`Bluetooth state: ${state}`);

  if (state === 'poweredOn') {
    startAdvertising();
  } else if (state === 'poweredOff' || state === 'unsupported') {
    stopAdvertising();
    logError(`Bluetooth ${state}. Versuche Reconnect...`);
    scheduleReconnect();
  }
});

/**
 * Fehlerbehandlung
 */
bleno.on('error', (err) => {
  logError('Bleno Error', err);
  stopAdvertising();
  scheduleReconnect();
});

bleno.on('advertisingStart', (err) => {
  if (err) {
    logError('Failed to start advertising', err);
  }
});

bleno.on('advertisingStop', () => {
  log('Advertising stopped');
  isRunning = false;
});

/**
 * Graceful Shutdown
 */
process.on('SIGINT', () => {
  log('SIGINT empfangen. Fahre Beacon herunter...');
  stopAdvertising();
  setTimeout(() => {
    log('Beacon beendet');
    process.exit(0);
  }, 1000);
});

process.on('SIGTERM', () => {
  log('SIGTERM empfangen. Fahre Beacon herunter...');
  stopAdvertising();
  setTimeout(() => {
    log('Beacon beendet');
    process.exit(0);
  }, 1000);
});

/**
 * Uncaught Exception Handler
 */
process.on('uncaughtException', (err) => {
  logError('Uncaught Exception', err);
  process.exit(1);
});

/**
 * Startup
 */
log(`========================================`);
log(`ÖPNV PoC - BLE Beacon`);
log(`========================================`);
log(`Beacon Name: ${BEACON_NAME}`);
log(`Vehicle ID: ${VEHICLE_ID}`);
log(`Hostname: ${os.hostname()}`);
log(`Platform: ${os.platform()} ${os.release()}`);
log(`========================================`);
