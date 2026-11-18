package dev.voxkey.bench

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffre les clés d'API au repos. La clé AES vit dans le Keystore Android : elle n'en sort pas,
 * donc un fichier de préférences copié hors du téléphone ne permet pas de lire la clé d'API.
 * Valeur rangée : `v1:` + base64(iv) + `:` + base64(chiffré).
 */
object Secrets {

    private const val ALIAS = "voxkey-cloud-keys"
    private const val PREFIX = "v1:"
    private const val PROVIDER = "AndroidKeyStore"

    private fun aes(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    // shortcut: si le Keystore refuse (erreur rare, appareil sans TEE), on range en clair plutôt
    // que de bloquer la dictée ; `open` reconnaît l'absence de préfixe.
    fun seal(plain: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes()) }
        PREFIX + Base64.encodeToString(c.iv, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault(plain)

    /** Valeur en clair (ancienne clé, ou Keystore indisponible) : rendue telle quelle. */
    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(":", limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, aes(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            }
            String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
        }.getOrDefault("")
    }
}
