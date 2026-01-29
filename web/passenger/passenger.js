// Passenger webapp: creates device keypair, registers device, starts/ends sessions,
// and generates rotating QR (every 30s). Uses WebCrypto for ECDSA P-256 keys.

const API_BASE = 'http://localhost:3000';

function str2ab(str){
  const buf = new TextEncoder().encode(str);
  return buf;
}

function ab2b64(buf){
  return btoa(String.fromCharCode(...new Uint8Array(buf)));
}

function exportSPKI(key) {
  return window.crypto.subtle.exportKey('spki', key).then(k => {
    const b64 = ab2b64(k);
    const pem = '-----BEGIN PUBLIC KEY-----\n' + b64.match(/.{1,64}/g).join('\n') + '\n-----END PUBLIC KEY-----\n';
    return pem;
  });
}

async function genOrLoadDevice() {
  let deviceId = localStorage.getItem('deviceId');
  let priv = localStorage.getItem('devicePrivJwk');
  if (deviceId && priv) {
    const jwk = JSON.parse(priv);
    const key = await crypto.subtle.importKey('jwk', jwk, {name:'ECDSA', namedCurve:'P-256'}, true, ['sign']);
    return { deviceId, privKey: key };
  }

  deviceId = 'dev-' + Math.random().toString(36).substring(2,10);
  const keyPair = await crypto.subtle.generateKey({name:'ECDSA', namedCurve:'P-256'}, true, ['sign','verify']);
  const jwk = await crypto.subtle.exportKey('jwk', keyPair.privateKey);
  localStorage.setItem('deviceId', deviceId);
  localStorage.setItem('devicePrivJwk', JSON.stringify(jwk));
  // export public and register
  const pubPem = await exportSPKI(keyPair.publicKey);
  await fetch(API_BASE + '/device/register', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({deviceId, devicePubKeyPem: pubPem})});
  return { deviceId, privKey: keyPair.privateKey };
}

let device = null;
let currentToken = null;
let currentSessionId = null;
let qrInterval = null;

async function start() {
  document.getElementById('status').innerText = 'Starting...';
  device = await genOrLoadDevice();
  document.getElementById('status').innerText = 'Device: ' + device.deviceId;
}

async function sessionStart() {
  if (!device) await start();
  const vehicleId = document.getElementById('vehicleId').value || 'BUS_4711';
  const resp = await fetch(API_BASE + '/session/start', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({deviceId: device.deviceId, vehicleId})});
  const j = await resp.json();
  if (j.token) {
    currentToken = j.token; currentSessionId = j.sessionId;
    localStorage.setItem('sessionToken', currentToken);
    localStorage.setItem('sessionId', currentSessionId);
    document.getElementById('tokenArea').innerText = currentToken;
    startQRRotation();
    document.getElementById('status').innerText = 'Session active: ' + currentSessionId;
  } else {
    alert('error: ' + JSON.stringify(j));
  }
}

async function sessionEnd() {
  const sessionId = currentSessionId || localStorage.getItem('sessionId');
  if (!sessionId) return alert('no session');
  const resp = await fetch(API_BASE + '/session/end', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({sessionId})});
  const j = await resp.json();
  clearQRRotation();
  localStorage.removeItem('sessionToken');
  localStorage.removeItem('sessionId');
  currentToken = null; currentSessionId = null;
  document.getElementById('tokenArea').innerText = '';
  document.getElementById('status').innerText = 'Session ended. Fare today: ' + (j.totalCents/100).toFixed(2) + ' EUR ' + (j.capped ? '(capped)' : '');
}

function startQRRotation(){
  generateQR();
  if (qrInterval) clearInterval(qrInterval);
  qrInterval = setInterval(generateQR, 1000 * 5); // update often for demo (actual slot=30s)
}

function clearQRRotation(){
  if (qrInterval) clearInterval(qrInterval);
  document.getElementById('qr').innerHTML = '';
}

async function generateQR(){
  const token = currentToken || localStorage.getItem('sessionToken');
  if (!token) return;
  const slot = Math.floor(Date.now() / 1000 / 30);
  const payloadStr = token + '|' + slot;
  const data = str2ab(payloadStr);
  // sign with device priv key using ECDSA SHA-256
  const sig = await crypto.subtle.sign({name:'ECDSA', hash:{name:'SHA-256'}}, device.privKey, data);
  const sigB64 = ab2b64(sig);
  const qrJson = { token, slot, sig: sigB64 };
  const qrText = JSON.stringify(qrJson);
  document.getElementById('qr').innerHTML = '';
  new QRCode(document.getElementById('qr'), {
    text: qrText, width: 220, height: 220
  });
  // also show raw JSON for inspector copy/paste (offline verification)
  const pre = document.getElementById('qrJsonText');
  if (pre) pre.innerText = qrText;
}

document.getElementById('startBtn').addEventListener('click', sessionStart);
document.getElementById('endBtn').addEventListener('click', sessionEnd);

start();
