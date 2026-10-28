package com.roadoffice.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DB 암호키 관리 (Pleos 앱 보안 가이드: 키는 Keystore, 하드코딩·평문 저장 금지).
 *
 * - 32바이트 무작위 DB 암호키를 SecureRandom 으로 한 번 생성
 * - Android Keystore 의 AES-256-GCM 키로 감싸서(wrap) SharedPreferences 에 저장 (IV 는 매번 새로)
 * - Keystore 키는 기기 밖으로 나가지 않으므로 앱 데이터만 복사해서는 DB 를 열 수 없다
 */
class DbKeyStore(private val context: Context) {

    fun passphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_WRAPPED, null)?.let { return unwrap(it) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_WRAPPED, wrap(raw)).apply()
        return raw
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val out = c.iv + c.doFinal(raw)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(b64: String): ByteArray {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, IV_LEN)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        }
        return c.doFinal(all, IV_LEN, all.size - IV_LEN)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "roadoffice_db_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val PREFS = "roadoffice_keys"
        const val KEY_WRAPPED = "db_key_wrapped"
    }
}
