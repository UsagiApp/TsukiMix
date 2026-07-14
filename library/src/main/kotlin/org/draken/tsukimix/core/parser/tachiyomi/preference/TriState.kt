package org.draken.tsukimix.core.parser.tachiyomi.preference

enum class TriState {
	DISABLED,
	ENABLED_IS,
	ENABLED_NOT,
	;

	fun next(): TriState = when (this) {
		DISABLED -> ENABLED_IS
		ENABLED_IS -> ENABLED_NOT
		ENABLED_NOT -> DISABLED
	}
}
