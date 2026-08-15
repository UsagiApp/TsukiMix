@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi

import android.content.Context
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiLoadResult
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource
import java.lang.ref.WeakReference
import tsuki.model.ContentType

class TachiyomiExtensionManager(
	private val context: Context,
	private val loader: TachiyomiExtensionLoader,
) {
	private val refreshMutex = Mutex()
	private val sourcesById = HashMap<Long, TachiyomiMangaSource>()
	private val sourcesByName = HashMap<String, TachiyomiMangaSource>()
	private val resolver = TachiyomiLanguageResolver(context)

	private val _installedExtensions = MutableStateFlow<List<TachiyomiLoadResult.Success>>(emptyList())
	private val _failedExtensions = MutableStateFlow<List<TachiyomiLoadResult.Error>>(emptyList())
	private val _isLoading = MutableStateFlow(false)
	private val _isReady = MutableStateFlow(false)
	private val _sources = MutableStateFlow<List<TachiyomiMangaSource>>(emptyList())

	val installedExtensions: StateFlow<List<TachiyomiLoadResult.Success>> = _installedExtensions
	val failedExtensions: StateFlow<List<TachiyomiLoadResult.Error>> = _failedExtensions
	val isLoading: StateFlow<Boolean> = _isLoading
	val isReady: StateFlow<Boolean> = _isReady
	val sources: StateFlow<List<TachiyomiMangaSource>> = _sources

	init {
		activeInstance = WeakReference(this)
	}

	fun initialize() {
		activeInstance = WeakReference(this)
	}

	suspend fun loadExtensions() {
		refreshMutex.withLock {
			_isLoading.value = true
			try {
				val results = loader.loadExtensions(context)
				val successes = results.filterIsInstance<TachiyomiLoadResult.Success>()
				_installedExtensions.value = successes
				_failedExtensions.value = results.filterIsInstance<TachiyomiLoadResult.Error>()
				publish(successes)
				_isReady.value = true
			} finally {
				_isLoading.value = false
			}
		}
	}

	suspend fun ensureReady(forceRefresh: Boolean = false) {
		initialize()
		if (forceRefresh || (!_isReady.value && !_isLoading.value)) {
			loadExtensions()
		}
		if (_isLoading.value) {
			_isLoading.first { !it }
		}
	}

	fun getSourceById(sourceId: Long): TachiyomiMangaSource? = sourcesById[sourceId]

	fun getSourceByName(name: String): TachiyomiMangaSource? {
		return sourcesByName[name]
			?: name.removePrefix("EXTERNAL_").substringBefore(':').toLongOrNull()?.let(sourcesById::get)
	}

	fun getSources(): List<TachiyomiMangaSource> = sourcesById.values.toList()

	fun getActiveSources(): List<TachiyomiMangaSource> = resolver.selectActive(_sources.value)

	fun getLanguage(source: TachiyomiMangaSource): List<TachiyomiMangaSource> {
		return resolver.getVariants(source, _sources.value)
	}

	fun getActiveLanguage(source: TachiyomiMangaSource): String? {
		return resolver.getActiveLanguage(source, _sources.value)
	}

	fun setActiveLanguage(source: TachiyomiMangaSource, language: String) {
		resolver.setActiveLanguage(source, language)
	}

	fun resolve(source: TachiyomiMangaSource): TachiyomiMangaSource {
		return resolver.resolve(source, _sources.value)
	}

	fun getSourcesByLanguage(): Map<String, List<CatalogueSource>> {
		return _installedExtensions.value
			.flatMap { it.catalogueSources }
			.groupBy { it.lang }
	}

	private fun publish(successes: List<TachiyomiLoadResult.Success>) {
		val wrapped = successes.flatMap { success ->
			val counts = success.catalogueSources.groupingBy { it.name }.eachCount()
			success.catalogueSources.map { source ->
				TachiyomiMangaSource(
					catalogueSource = source,
					pkgName = success.pkgName,
					contentType = if (success.isNsfw) ContentType.HENTAI else ContentType.MANGA,
					hasLanguageSuffix = (counts[source.name] ?: 0) > 1,
					extensionName = success.appName,
					isPreInstalledApk = true,
				)
			}
		}
		sourcesById.clear()
		sourcesByName.clear()
		wrapped.forEach {
			sourcesById[it.sourceId] = it
			sourcesByName[it.name] = it
		}
		_sources.value = wrapped
	}

	companion object {
		@Volatile
		private var activeInstance = WeakReference<TachiyomiExtensionManager>(null)

		fun getByName(name: String): TachiyomiMangaSource? = activeInstance.get()?.getSourceByName(name)
	}
}
