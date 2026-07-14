package org.draken.tsukimix.core.parser.tachiyomi.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source

sealed class TachiyomiLoadResult {
	data class Success(
		val pkgName: String,
		val appName: String,
		val versionCode: Long,
		val versionName: String,
		val libVersion: Double,
		val lang: String,
		val isNsfw: Boolean,
		val sources: List<Source>,
	) : TachiyomiLoadResult() {
		val catalogueSources: List<CatalogueSource>
			get() = sources.filterIsInstance<CatalogueSource>()
	}

	data class Error(
		val pkgName: String,
		val message: String,
		val exception: Throwable? = null,
	) : TachiyomiLoadResult()

	data class Untrusted(
		val pkgName: String,
		val appName: String,
		val versionCode: Long,
		val versionName: String,
	) : TachiyomiLoadResult()
}

data class TachiyomiExtensionInfo(
	val pkgName: String,
	val appName: String,
	val versionCode: Long,
	val versionName: String,
	val libVersion: Double,
	val lang: String,
	val isNsfw: Boolean,
	val sourceClassName: String,
	val apkPath: String,
)
