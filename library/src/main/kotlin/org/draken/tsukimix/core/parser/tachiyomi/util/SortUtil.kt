package org.draken.tsukimix.core.parser.tachiyomi.util

import java.text.Collator
import java.util.Locale

private val collator by lazy {
	Collator.getInstance(Locale.getDefault()).apply {
		strength = Collator.PRIMARY
	}
}

fun String.compareToWithCollator(other: String): Int = collator.compare(this, other)
