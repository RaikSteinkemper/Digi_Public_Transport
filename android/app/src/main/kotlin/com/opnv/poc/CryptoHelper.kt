package com.opnv.poc

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import android.util.Log

/**
 * CryptoHelper: Generiert ECDSA P-256 Keypairs, speichert in SharedPreferences,
 * und signiert Payloads für QR-Token.
 */
object CryptoHelper {
    private const val TAG = "CryptoHelper"
    private const val PRIV_KEY_PREF = "device_priv_key_pkcs8"
    private const val PUB_KEY_PREF = "device_pub_key_spki"

    /**
     * Generiert oder lädt vorhandenes ECDSA P-256 Keypair
     */
    fun getOrCreateKeyPair(): Pair<String, String> {
        val prefs = OPNVApp.prefs
        val privB64 = prefs.getString(PRIV_KEY_PREF, null)
        val pubB64 = prefs.getString(PUB_KEY_PREF, null)

        if (privB64 != null && pubB64 != null) {
            return Pair(privB64, pubB64)
        }

        Log.i(TAG, "Generating new ECDSA P-256 keypair...")
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1")) // P-256
        val kp = kpg.generateKeyPair()

        val privBytes = kp.private.encoded // PKCS#8
        val pubBytes = kp.public.encoded // SPKI

        val privB64New = Base64.getEncoder().encodeToString(privBytes)
        val pubB64New = Base64.getEncoder().encodeToString(pubBytes)

        prefs.edit().apply {
            putString(PRIV_KEY_PREF, privB64New)
            putString(PUB_KEY_PREF, pubB64New)
            apply()
        }

        return Pair(privB64New, pubB64New)
    }

    /**
     * Exportiert Public-Key als PEM
     */
    fun pubKeyB64ToPem(pubB64: String): String {
        val lines = pubB64.chunked(64)
        return "-----BEGIN PUBLIC KEY-----\n" +
                lines.joinToString("\n") +
                "\n-----END PUBLIC KEY-----\n"
    }

    /**
     * Signiert payload (token + "|" + slot) mit private key (ECDSA P-256 SHA-256)
     */
    fun signPayload(payload: String, privB64: String): String {
        try {
            val privBytes = Base64.getDecoder().decode(privB64)
            val kf = KeyFactory.getInstance("EC")
            val privKey = kf.generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(privBytes)
            )

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(privKey)
            sig.update(payload.toByteArray(Charsets.UTF_8))
            val sigBytes = sig.sign()

            return Base64.getEncoder().encodeToString(sigBytes)
        } catch (e: Exception) {
            Log.e(TAG, "signPayload error", e)
            throw e
        }
    }
}
