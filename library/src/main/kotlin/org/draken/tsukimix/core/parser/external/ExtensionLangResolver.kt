package org.draken.tsukimix.core.parser.external

import android.content.Context
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.preference.PreferenceManager
import org.draken.tsukimix.core.parser.external.model.Manga
import java.util.Locale

internal class ExtensionLangResolver(context: Context) {

	private val appContext = context.applicationContext
	private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

	fun getVariants(
        source: Manga,
        sources: List<Manga>,
	): List<Manga> = sources.filter { it.hasSameIdentity(source) }

	fun getActiveLanguage(
        source: Manga,
        sources: List<Manga>,
	): String? = selectLanguage(getVariants(source, sources))

	fun selectActive(sources: List<Manga>): List<Manga> {
		return sources.groupBy { it.pkgName to it.displayName }.values.map { variants ->
			val language = selectLanguage(variants)
			variants.firstOrNull { it.locale.equals(language, ignoreCase = true) } ?: variants.first()
		}
	}

	fun resolve(
        source: Manga,
        sources: List<Manga>,
	): Manga {
		val variants = getVariants(source, sources)
		val language = selectLanguage(variants)
		return variants.firstOrNull { it.locale.equals(language, ignoreCase = true) } ?: source
	}

	fun setActiveLanguage(source: Manga, language: String) {
		val suffix = source.preferenceSuffix
		val current = prefs.getStringSet(KEY_ACTIVE_LANGUAGES, emptySet()).orEmpty()
		prefs.edit {
			putStringSet(
				KEY_ACTIVE_LANGUAGES,
				current.filterNot { it.endsWith(suffix) }.toSet() + (language + suffix),
			)
		}
	}

	private fun selectLanguage(variants: List<Manga>): String? {
		if (variants.isEmpty()) return null
		val languages = variants.map { it.locale }
		val stored = prefs.getStringSet(KEY_ACTIVE_LANGUAGES, emptySet()).orEmpty()
			.firstOrNull { it.endsWith(variants.first().preferenceSuffix) }
			?.substringBefore('\n')
			?.takeIf { it.isNotEmpty() }
		return languages.match(stored)
			?: languages.match(appLanguage)
			?: languages.match("en")
			?: languages.first()
	}

	private val appLanguage: String
		get() = ConfigurationCompat.getLocales(appContext.resources.configuration).get(0)?.language
			?: Locale.getDefault().language

	private val Manga.preferenceSuffix: String
		get() = "\n$pkgName\n$displayName"

	private fun Manga.hasSameIdentity(other: Manga): Boolean {
		return pkgName == other.pkgName && displayName == other.displayName
	}

	private fun List<String>.match(target: String?): String? {
		if (target.isNullOrBlank()) return null
		val baseTarget = target.substringBefore('-').substringBefore('_')
		return firstOrNull { it.equals(target, ignoreCase = true) }
			?: firstOrNull {
				it.substringBefore('-').substringBefore('_').equals(baseTarget, ignoreCase = true)
			}
	}

	private companion object {
		const val KEY_ACTIVE_LANGUAGES = "tachiyomi_source_languages"
	}
}
