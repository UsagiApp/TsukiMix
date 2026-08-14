package org.draken.tsukimix.core.parser.tachiyomi.model

import eu.kanade.tachiyomi.source.CatalogueSource
import tsuki.model.ContentType
import tsuki.model.MangaSource
import java.util.Locale

data class TachiyomiMangaSource(
	val catalogueSource: CatalogueSource,
	val pkgName: String,
	val isNsfw: Boolean = false,
	val hasLanguageSuffix: Boolean = false,
	/** The extension label shown to users, from the installed APK or direct catalog record. */
	val extensionName: String? = null,
	/** True only when Android has installed this extension as a package. */
	val isPreInstalledApk: Boolean = false,
) : MangaSource {
	override val name: String
		get() = "EXTERNAL_${catalogueSource.id}"

	override val locale: String
		get() = catalogueSource.lang

	override val contentType: ContentType
		get() = if (isNsfw) ContentType.HENTAI else ContentType.MANGA

	override val title: String
		get() = catalogueSource.name

	val displayName: String
		get() = catalogueSource.name

	val languageDisplayName: String
		get() = Locale.forLanguageTag(locale).displayName

	val sourceId: Long
		get() = catalogueSource.id

	val supportsLatest: Boolean
		get() = catalogueSource.supportsLatest

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		val raw = other.name.removePrefix("EXTERNAL_").substringBefore(':')
		return raw.toLongOrNull() == sourceId
	}

	override fun hashCode(): Int = sourceId.hashCode()
}
