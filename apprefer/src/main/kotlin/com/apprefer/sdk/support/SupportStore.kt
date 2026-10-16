package com.apprefer.sdk.support

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens stay in no-backup storage; the encryption key never leaves Android Keystore. */
internal class SecureSupportStore(private val context: Context, fileName: String = "apprefer-support-session") : SupportStore {
    private val file = java.io.File(context.noBackupFilesDir, fileName)
    private val deferred by lazy { SecureSupportStore(context, "apprefer-support-revocations") }
    override var pendingRevocations: String?
        get() = deferred.token
        set(value) { deferred.token = value }
    override var lastKey: String?
        get() = prefs.getString("lastKey", null)
        set(value) { prefs.edit().putString("lastKey", value).apply() }
    private val prefs = context.getSharedPreferences("apprefer_support", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("apprefer_support", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("apprefer_support", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override var token: String?
        get() {
            if (!file.exists()) return null
            return try {
                val saved = file.readText()
                if (saved.startsWith("rsa-chunks:")) {
                    val ks = legacyKeyStore()
                    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, ks.getKey("apprefer_support_legacy", null)) }
                    val output = java.io.ByteArrayOutputStream()
                    saved.removePrefix("rsa-chunks:").split(".").forEach { output.write(cipher.doFinal(Base64.decode(it, Base64.NO_WRAP))) }
                    return String(output.toByteArray(), Charsets.UTF_8)
                }
                if (saved.startsWith("rsa:")) {
                    val ks = legacyKeyStore()
                    return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                        init(Cipher.DECRYPT_MODE, ks.getKey("apprefer_support_legacy", null))
                        String(doFinal(Base64.decode(saved.removePrefix("rsa:"), Base64.NO_WRAP)), Charsets.UTF_8)
                    }
                }
                val bytes = Base64.decode(saved, Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8) }
            } catch (_: Exception) { file.delete(); null }
        }
        set(value) {
            if (value == null) { file.delete(); return }
            if (android.os.Build.VERSION.SDK_INT < 23) {
                val ks = legacyKeyStore()
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, ks.getCertificate("apprefer_support_legacy").publicKey) }
                write("rsa-chunks:" + value.toByteArray(Charsets.UTF_8).toList().chunked(100).joinToString(".") { chunk -> Base64.encodeToString(rsa.doFinal(chunk.toByteArray()), Base64.NO_WRAP) })
                return
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            write(Base64.encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
    private fun write(value: String) {
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    @Suppress("DEPRECATION") private fun legacyKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias("apprefer_support_legacy")) {
            val spec = android.security.KeyPairGeneratorSpec.Builder(context)
                .setAlias("apprefer_support_legacy")
                .setSubject(javax.security.auth.x500.X500Principal("CN=AppRefer Support"))
                .setSerialNumber(java.math.BigInteger.ONE)
                .setStartDate(java.util.Date(0))
                .setEndDate(java.util.Date(4102444800000L)).build()
            java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        return ks
    }
    override var pushToken: String?
        get() = prefs.getString("pushToken", null)
        set(value) { prefs.edit().putString("pushToken", value).apply() }
    override var profile: String?
        get() = prefs.getString("profile", null)
        set(value) { prefs.edit().putString("profile", value).apply() }
}
