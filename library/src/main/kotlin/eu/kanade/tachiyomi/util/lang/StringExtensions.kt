package eu.kanade.tachiyomi.util.lang

import androidx.core.text.parseAsHtml
import java.nio.charset.StandardCharsets
import kotlin.math.floor

fun String.chop(count: Int, replacement: String = "…"): String {
	return if (length > count) {
		take(count - replacement.length) + replacement
	} else {
		this
	}
}

fun String.truncateCenter(count: Int, replacement: String = "..."): String {
	if (length <= count) return this
	val pieceLength = floor((count - replacement.length).div(2.0)).toInt()
	return "${take(pieceLength)}$replacement${takeLast(pieceLength)}"
}

fun String.compareToCaseInsensitiveNaturalOrder(other: String): Int {
	return compareValuesBy(this, other, { it.lowercase() }, { it })
}

fun String.byteSize(): Int = toByteArray(StandardCharsets.UTF_8).size

fun String.takeBytes(n: Int): String {
	val bytes = toByteArray(StandardCharsets.UTF_8)
	return if (bytes.size <= n) {
		this
	} else {
		bytes.decodeToString(endIndex = n).replace("\uFFFD", "")
	}
}

fun String.htmlDecode(): String = parseAsHtml().toString()
