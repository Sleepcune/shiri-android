package com.hubra.app.data

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCipher {
    private val magic = byteArrayOf(72, 66, 82, 65, 1)
    private const val rounds = 210_000
    private fun derive(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun encrypt(plain: ByteArray, password: String): ByteArray {
        require(password.length >= 8) { "备份口令至少8个字符" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(37).put(magic).putInt(rounds).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(password, salt, rounds), GCMParameterSpec(128, iv)); updateAAD(header) }
        return header + cipher.doFinal(plain)
    }
    fun decrypt(encrypted: ByteArray, password: String): ByteArray {
        require(encrypted.size >= 53 && encrypted.take(5).toByteArray().contentEquals(magic)) { "不是有效的 Hubra 加密备份" }
        val header = encrypted.copyOfRange(0, 37)
        val iterations = ByteBuffer.wrap(header, 5, 4).int
        require(iterations in 100_000..1_000_000) { "备份密钥参数不支持" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, derive(password, header.copyOfRange(9, 25), iterations), GCMParameterSpec(128, header.copyOfRange(25, 37))); updateAAD(header)
        }
        return cipher.doFinal(encrypted.copyOfRange(37, encrypted.size))
    }
}
