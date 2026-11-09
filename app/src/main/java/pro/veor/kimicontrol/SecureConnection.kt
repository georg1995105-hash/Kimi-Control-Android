package pro.veor.kimicontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API token is stored using a non-exportable key in Android Keystore.
 * Keys and network endpoints must never be committed to source control.
 */
class SecureConnection(private val context: Context) {
    private val prefs = context.getSharedPreferences("kimi_connection_v1", Context.MODE_PRIVATE)
    private val alias = "kimi_control_bearer_v1"

    fun endpoint(): String = prefs.getString("endpoint", "") ?: ""
    fun workdir(): String = prefs.getString("workdir", "") ?: ""
    fun lastSession(): String = prefs.getString("session", "") ?: ""
    fun saveSession(id: String) { prefs.edit().putString("session", id).apply() }
    fun token(): String {
        val cipherText = prefs.getString("ciphertext", null) ?: return ""
        val iv = prefs.getString("iv", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore may have been reset on device migration.
            ""
        }
    }
    fun save(endpoint: String, workdir: String, newToken: String?) {
        require(endpoint.startsWith("https://") && endpoint.length > 10) { "Нужен адрес https://" }
        require(workdir.startsWith("/") && workdir.length > 1) { "Нужен абсолютный путь рабочей папки" }
        val edit = prefs.edit().putString("endpoint", endpoint.trimEnd('/')).putString("workdir", workdir)
        if (!newToken.isNullOrBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.doFinal(newToken.toByteArray(Charsets.UTF_8))
            edit.putString("ciphertext", Base64.encodeToString(bytes, Base64.NO_WRAP))
            edit.putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        edit.apply()
    }
    fun clear() {
        prefs.edit().clear().apply()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val found = store.getKey(alias, null) as? SecretKey
        if (found != null) return found
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
