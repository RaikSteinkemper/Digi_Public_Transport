// Inspector: verifies QR JSON offline
// Steps:
// 1) Verify JWT signature (RS256) with backend public key fetched from /.well-known/backend-public.pem
// 2) Check validUntil
// 3) Check slot within nowSlot-1..nowSlot+1
// 4) Verify device signature: SHA256(token + '|' + slot) signed by devicePubKey (from JWT)

const OUT = document.getElementById('out');

function log(...args){ OUT.innerText = args.join(' ') + '\n' + OUT.innerText; }

function b64ToArrayBuffer(b64){
  const bin = atob(b64);
  const len = bin.length; const buf = new Uint8Array(len);
  for (let i=0;i<len;i++) buf[i]=bin.charCodeAt(i);
  return buf.buffer;
}

function pemToArrayBuffer(pem) {
  const lines = pem.split('\n');
  const b64 = lines.filter(l => l && !l.includes('BEGIN') && !l.includes('END')).join('');
  return b64ToArrayBuffer(b64);
}

async function importRsaPublicKey(pem) {
  const spki = pemToArrayBuffer(pem);
  return crypto.subtle.importKey('spki', spki, {name:'RSASSA-PKCS1-v1_5', hash:'SHA-256'}, false, ['verify']);
}

async function importEcdsaPubKeyFromSpkiPem(pem) {
  const spki = pemToArrayBuffer(pem);
  return crypto.subtle.importKey('spki', spki, {name:'ECDSA', namedCurve:'P-256'}, false, ['verify']);
}

function base64UrlDecode(str){
  str = str.replace(/-/g, '+').replace(/_/g, '/');
  while (str.length % 4) str += '=';
  return atob(str);
}

async function verifyJWT(token, backendPubKeyPem){
  const parts = token.split('.');
  if (parts.length !== 3) throw new Error('invalid jwt');
  const header64 = parts[0], payload64 = parts[1], sig64 = parts[2];
  const data = new TextEncoder().encode(header64 + '.' + payload64);
  const sig = b64ToArrayBuffer(sig64.replace(/-/g,'+').replace(/_/g,'/'));
  const pubKey = await importRsaPublicKey(backendPubKeyPem);
  const ok = await crypto.subtle.verify({name:'RSASSA-PKCS1-v1_5'}, pubKey, sig, data);
  const payloadJson = JSON.parse(base64UrlDecode(payload64));
  return { ok, payload: payloadJson };
}

async function verifyDeviceSig(token, slot, sigB64, devicePubPem){
  // payload to hash: token + '|' + slot; device signed this raw data using ECDSA SHA-256
  const payload = token + '|' + slot;
  const data = new TextEncoder().encode(payload);
  const sig = b64ToArrayBuffer(sigB64);
  const pub = await importEcdsaPubKeyFromSpkiPem(devicePubPem);
  const ok = await crypto.subtle.verify({name:'ECDSA', hash:'SHA-256'}, pub, sig, data);
  return ok;
}

document.getElementById('verifyBtn').addEventListener('click', async ()=>{
  OUT.innerText = '';
  try{
    const txt = document.getElementById('qrInput').value.trim();
    const qr = JSON.parse(txt);
    log('Parsed QR JSON', JSON.stringify(qr));
    const backendPub = await (await fetch('http://localhost:3000/.well-known/backend-public.pem')).text();
    log('Fetched backend public key');
    const { ok: jwtOk, payload } = await verifyJWT(qr.token, backendPub);
    if (!jwtOk) return log('RESULT: RED - JWT signature invalid');
    log('JWT signature valid');

    const now = Math.floor(Date.now()/1000);
    if (payload.validUntil && payload.validUntil < now) return log('RESULT: RED - token expired');
    log('Token validUntil OK');

    const nowSlot = Math.floor(now / 30);
    if (Math.abs(qr.slot - nowSlot) > 1) return log('RESULT: RED - slot stale');
    log('Slot within acceptable range');

    // devicePubKey is embedded in JWT payload
    const devicePubPem = payload.devicePubKey;
    const devOk = await verifyDeviceSig(qr.token, qr.slot, qr.sig, devicePubPem);
    if (!devOk) return log('RESULT: RED - device signature invalid');
    log('Device signature valid');

    log('RESULT: GREEN - proof OK. sessionId=' + payload.sessionId + ' device=' + payload.deviceId + ' vehicle=' + payload.vehicleId);
  } catch (e){
    log('ERROR: ' + e.message);
  }
});
