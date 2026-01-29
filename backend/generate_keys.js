const { generateKeyPairSync } = require('crypto');
const fs = require('fs');
const path = require('path');

const outDir = path.join(__dirname, 'keys');
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' }
});

fs.writeFileSync(path.join(outDir, 'private.pem'), privateKey);
fs.writeFileSync(path.join(outDir, 'public.pem'), publicKey);

console.log('Generated keys in', outDir);
