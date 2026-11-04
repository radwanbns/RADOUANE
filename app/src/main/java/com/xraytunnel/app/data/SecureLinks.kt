package com.xraytunnel.app.data

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypted application endpoints. R8 further obfuscates this implementation in release builds. */
internal object SecureLinks {
    private fun b64(value: String) = Base64.decode(value, Base64.NO_WRAP)

    // The key is split and reconstructed at runtime to avoid a plainly embedded URL/key pair.
    private val key: ByteArray by lazy {
        val a = b64("XsaktS1qYGj4sZKm91h+VqnOhodpgRAFoqjex1MoZ48=")
        val b = b64("HQ0zlLezA+6NhbZ4E432z7lOCXhQCXmF51mB3hQBlU0=")
        ByteArray(32) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    private fun decrypt(nonce: String, encrypted: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64(nonce)))
        return String(cipher.doFinal(b64(encrypted)), Charsets.UTF_8)
    }

    fun telegramUrl(): String = decrypt(
        "qnzxhXTHKkNFKi8H",
        "wErtbhDhvV23N/tzbj/igYzsyUIi8TT4ZNAuxt4XCNoKyy+yVbX459z0"
    )

    fun subscriptionUrl(): String = decrypt(
        "ES1pb7SI8UtdPQeg",
        "uoOp0tmyfm+ur5b7iAQ2kePQuuOVdZHhzPOPV5n7hN76Tjn1G3P9VFu0GHNqM/tGmT/b2m9G1Q1ngym4hsAKzUpU+4mc4AzrKCINuoiv8EzE4qzf7Ae2bBbxF/ZI27Lx"
    )

    fun telegramChannelUrl(): String = decrypt(
        "d5vb6S1d+HW3EEdO",
        "7GzU3lspMysnThdaSK2qtEEyk9v64PR4Way/KmiDBWwW4mERp+lp+woE"
    )

    fun youtubeUrl(): String = decrypt(
        "XyZPcMt4ppPnGtwn",
        "Wl3cHxaV9agR0Z8FbdgWEUHY8MRw5hfsG73xlUXhCUgmkGk90yHYsvzGkEq24NTZqWLgPcoRzZTh7gy5QQ=="
    )
}

