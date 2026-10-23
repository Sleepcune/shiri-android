package com.hubra.app

import com.hubra.app.data.BackupCipher
import kotlin.test.*

class BackupCipherTest {
    @Test fun roundTripKeepsUtf8Data() {
        val plain = "校园卡、餐食、课表".toByteArray(Charsets.UTF_8)
        assertContentEquals(plain, BackupCipher.decrypt(BackupCipher.encrypt(plain, "strong-passphrase"), "strong-passphrase"))
    }
    @Test fun wrongPasswordCannotDecrypt() {
        val encrypted = BackupCipher.encrypt("data".toByteArray(), "correct-password")
        assertFails { BackupCipher.decrypt(encrypted, "incorrect-password") }
    }
    @Test fun TamperingFailsAuthentication() {
        val encrypted = BackupCipher.encrypt("data".toByteArray(), "password-long")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertFails { BackupCipher.decrypt(encrypted, "password-long") }
    }
}
