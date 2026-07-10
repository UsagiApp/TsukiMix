package eu.kanade.tachiyomi.util.lang

import java.security.MessageDigest

object Hash {

	private val chars = charArrayOf(
		'0', '1', '2', '3', '4', '5', '6', '7',
		'8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
	)

	fun sha256(bytes: ByteArray): String = encodeHex(
		MessageDigest.getInstance("SHA-256").digest(bytes),
	)

	fun sha256(string: String): String = sha256(string.toByteArray())

	fun md5(bytes: ByteArray): String = encodeHex(
		MessageDigest.getInstance("MD5").digest(bytes),
	)

	fun md5(string: String): String = md5(string.toByteArray())

	private fun encodeHex(data: ByteArray): String {
		val out = CharArray(data.size * 2)
		var j = 0
		for (byte in data) {
			out[j++] = chars[(0xf0 and byte.toInt()).ushr(4)]
			out[j++] = chars[0x0f and byte.toInt()]
		}
		return String(out)
	}
}
