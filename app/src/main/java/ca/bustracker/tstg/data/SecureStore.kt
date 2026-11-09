package ca.bustracker.tstg.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ca.bustracker.tstg.util.Constants
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the portal password encrypted with a key that lives in the Android
 * Keystore. The key is non-exportable, so a copy of the app's preferences
 * cannot be decrypted off-device.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(Constants.SECURE_PREF_FILE, Context.MODE_PRIVATE)

    fun save(credentials: Credentials) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val payload = JSONObject()
            .put("email", credentials.email)
            .put("password", credentials.password)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val ciphertext = cipher.doFinal(payload)
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_PAYLOAD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Credentials? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
            val json = JSONObject(plain)
            Credentials(
                email = json.getString("email"),
                password = json.getString("password"),
            )
        }.getOrElse {
            // The keystore key was invalidated (restore, lock-screen change,
            // etc.). Drop the stale ciphertext rather than crash.
            clear()
            null
        }
    }

    fun hasCredentials(): Boolean = prefs.contains(KEY_PAYLOAD)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bus_tracker_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_IV = "credentials_iv"
        const val KEY_PAYLOAD = "credentials_payload"
    }
}
