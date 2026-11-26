package cz.prazicka.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cz.prazicka.BuildConfig
import cz.prazicka.protocol.Proto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val host: String = "",
    val port: Int = Proto.PORT,
    val useSimulator: Boolean = BuildConfig.IS_TEST,
    val soundPrompts: Boolean = true,
    val soundLink: Boolean = true,
    val recordManual: Boolean = true,
    val checkUpdatesOnStart: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    val hiddenCurves: Set<String> = emptySet(),
    val lastFoundAt: Long = 0L,
    val lastManualSp: Double = 200.0,
)

/** Nastavení aplikace. Token se ukládá zvlášť, šifrovaný klíčem v Android Keystore. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("nastaveni", Context.MODE_PRIVATE)
    private val tokenStore = TokenStore(context)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read() = AppSettings(
        host = prefs.getString("host", "") ?: "",
        port = prefs.getInt("port", Proto.PORT),
        useSimulator = prefs.getBoolean("use_simulator", BuildConfig.IS_TEST) && BuildConfig.IS_TEST,
        soundPrompts = prefs.getBoolean("sound_prompts", true),
        soundLink = prefs.getBoolean("sound_link", true),
        recordManual = prefs.getBoolean("record_manual", true),
        checkUpdatesOnStart = prefs.getBoolean("check_updates", true),
        lastUpdateCheck = prefs.getLong("last_update_check", 0L),
        hiddenCurves = prefs.getStringSet("hidden_curves", emptySet()) ?: emptySet(),
        lastFoundAt = prefs.getLong("last_found_at", 0L),
        lastManualSp = prefs.getFloat("last_manual_sp", 200f).toDouble(),
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_settings.value)
        prefs.edit()
            .putString("host", s.host)
            .putInt("port", s.port)
            .putBoolean("use_simulator", s.useSimulator)
            .putBoolean("sound_prompts", s.soundPrompts)
            .putBoolean("sound_link", s.soundLink)
            .putBoolean("record_manual", s.recordManual)
            .putBoolean("check_updates", s.checkUpdatesOnStart)
            .putLong("last_update_check", s.lastUpdateCheck)
            .putStringSet("hidden_curves", s.hiddenCurves)
            .putLong("last_found_at", s.lastFoundAt)
            .putFloat("last_manual_sp", s.lastManualSp.toFloat())
            .apply()
        _settings.value = s
    }

    fun token(): String = tokenStore.load()
    fun saveToken(token: String) = tokenStore.save(token)
    fun hasToken(): Boolean = tokenStore.load().isNotEmpty()

    companion object {
        /** API: token 8–32 tisknutelných znaků ASCII bez mezer. */
        fun tokenValid(t: String) = t.length in 8..32 && t.all { it.code in 0x21..0x7E }
    }
}

private class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("token", Context.MODE_PRIVATE)
    private val alias = "prazicka_token_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(token: String) {
        if (token.isEmpty()) {
            prefs.edit().clear().apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val enc = c.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String = try {
        val iv = prefs.getString("iv", null)
        val data = prefs.getString("data", null)
        if (iv == null || data == null) {
            ""
        } else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        ""
    }
}
