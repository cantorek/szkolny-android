/*
 * Vendored from https://github.com/wulkanowy/uonet-request-signer
 * at commit a99ca50a317a773d038a51d2d7bc5fe4be1a4ed3 (hebe-jvm module).
 *
 * MIT License - Copyright (c) 2019 Wulkanowy
 *
 * Vendored because JitPack no longer serves artifacts for that commit:
 * its builds succeed but publish under the coordinates "build:library:unspecified".
 */

package io.github.wulkanowy.signer.hebe

import com.migcomponents.migbase64.Base64
import java.security.KeyPairGenerator
import java.security.MessageDigest

fun generateKeyPair(): Triple<String, String, String> {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val keyPair = generator.generateKeyPair()
    val publicKey = keyPair.public.encoded
    val privateKey = keyPair.private.encoded

    val publicPem = Base64.encodeToString(publicKey, false)
    val privatePem = Base64.encodeToString(privateKey, false)
    val publicHash = MessageDigest.getInstance("MD5")
            .digest(publicPem.toByteArray())
            .joinToString("") { "%02x".format(it) }
    return Triple(publicPem, privatePem, publicHash)
}
