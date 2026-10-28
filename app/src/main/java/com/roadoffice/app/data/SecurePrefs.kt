package com.roadoffice.app.data

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
 * 개인 식별 정보(성명·부서 등) 설정값 암호화 저장 — Keystore AES-256-GCM, 값마다 새 IV.
 * 숫자 설정(km 단가)처럼 민감하지 않은 값은 일반 SharedPreferences 를 쓴다.
 */
class SecurePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("roadoffice_secure", Context.MODE_PRIVATE)

    fun getString(key: String): String? = prefs.getString(key, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun putString(key: String, value: String) = prefs.edit().putString(key, encrypt(value)).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }

    private companion object { const val ALIAS = "roadoffice_prefs_key" }
}
