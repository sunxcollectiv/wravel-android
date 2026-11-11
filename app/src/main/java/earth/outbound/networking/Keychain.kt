//
//  Keychain.kt
//  outbound
//

package earth.outbound.networking

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import earth.outbound.OutboundApp
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal secure store for small secrets like the auth token, standing in for the
 * iOS Keychain: values are encrypted with a key held in the Android Keystore (which
 * never leaves the device's secure hardware) and kept in private preferences.
 * One caller at a time, so two can't each make the key and lose the other's secret.
 */
object Keychain {
    private const val KEY_ALIAS = "earth.outbound.keychain"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val preferences: SharedPreferences by lazy {
        OutboundApp.context.getSharedPreferences("keychain", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun string(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        return try {
            val (iv, encrypted) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(encrypted).decodeToString()
        } catch (_: Exception) {
            // The key is gone or no longer usable (the keystore was reset, say): the
            // secret can't be read any more, so it's as good as not there.
            null
        }
    }

    @Synchronized
    fun set(value: String?, key: String) {
        if (value == null) {
            preferences.edit().remove(key).apply()
            return
        }
        val stored = try {
            encrypt(value)
        } catch (_: Exception) {
            // A key the keystore can no longer use is replaced, once, with a new one.
            try {
                keyStore().deleteEntry(KEY_ALIAS)
                encrypt(value)
            } catch (_: Exception) {
                null
            }
        }
        // Never left holding an older value: one that can't be saved is forgotten.
        preferences.edit().apply { if (stored != null) putString(key, stored) else remove(key) }.apply()
    }

    /** `value` encrypted, as its random IV and ciphertext in Base64 with a colon between. */
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        return listOf(cipher.iv, encrypted).joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** The key in the keystore, made the first time it's needed. */
    private fun secretKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
