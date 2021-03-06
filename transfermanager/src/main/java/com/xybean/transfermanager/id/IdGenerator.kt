package com.xybean.transfermanager.id

import java.io.UnsupportedEncodingException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.experimental.and

/**
 * Author @xybean on 2018/7/24.
 */
interface IdGenerator {

    companion object {
        fun md5(string: String): String {
            val hash: ByteArray
            try {
                hash = MessageDigest.getInstance("MD5").digest(string.toByteArray(charset("UTF-8")))
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException("Huh, MD5 should be supported?", e)
            } catch (e: UnsupportedEncodingException) {
                throw RuntimeException("Huh, UTF-8 should be supported?", e)
            }

            val hex = StringBuilder(hash.size * 2)
            for (b in hash) {
                if (b and 0xFF.toByte() < 0x10) hex.append("0")
                hex.append(Integer.toHexString((b and 0xFF.toByte()).toInt()))
            }
            return hex.toString()
        }
    }

    fun getId(): Int

}