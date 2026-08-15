@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi

import android.content.Context
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.draken.tsukimix.core.parser.tachiyomi.model.MangaResult
import org.draken.tsukimix.core.parser.tachiyomi.model.Manga
import java.lang.ref.WeakReference
import tsuki.model.ContentType

class ExtensionManager(
	private val context: Context,
	private val loader: ExtensionLoader,
) {
	private val refreshMutex = Mutex()
	private val sourcesById = HashMap<Long, Manga>()
	private val sourcesByName = HashMap<String, Manga>()
	private val resolver = ExtensionLangResolver(context)

	private val _installedExtensions = MutableStateFlow<List<MangaResult.Success>>(emptyList())
	private val _failedExtensions = MutableStateFlow<List<MangaResult.Error>>(emptyList())
	private val _isLoading = MutableStateFlow(false)
	private val _isReady = MutableStateFlow(false)
	private val _sources = MutableStateFlow<List<Manga>>(emptyList())

	val installedExtensions: StateFlow<List<MangaResult.Success>> = _installedExtensions
	val failedExtensions: StateFlow<List<MangaResult.Error>> = _failedExtensions
	val isLoading: StateFlow<Boolean> = _isLoading
	val isReady: StateFlow<Boolean> = _isReady
	val sources: StateFlow<List<Manga>> = _sources

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
				val successes = results.filterIsInstance<MangaResult.Success>()
				_installedExtensions.value = successes
				_failedExtensions.value = results.filterIsInstance<MangaResult.Error>()
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

	fun getSourceById(sourceId: Long): Manga? = sourcesById[sourceId]

	fun getSourceByName(name: String): Manga? {
		return sourcesByName[name]
			?: name.removePrefix("EXTERNAL_").substringBefore(':').toLongOrNull()?.let(sourcesById::get)
	}

	fun getSources(): List<Manga> = sourcesById.values.toList()

	fun getActiveSources(): List<Manga> = resolver.selectActive(_sources.value)

	fun getLanguage(source: Manga): List<Manga> {
		return resolver.getVariants(source, _sources.value)
	}

	fun getActiveLanguage(source: Manga): String? {
		return resolver.getActiveLanguage(source, _sources.value)
	}

	fun setActiveLanguage(source: Manga, language: String) {
		resolver.setActiveLanguage(source, language)
	}

	fun resolve(source: Manga): Manga {
		return resolver.resolve(source, _sources.value)
	}

	fun getSourcesByLanguage(): Map<String, List<CatalogueSource>> {
		return _installedExtensions.value
			.flatMap { it.catalogueSources }
			.groupBy { it.lang }
	}

	private fun publish(successes: List<MangaResult.Success>) {
		val wrapped = successes.flatMap { success ->
			val counts = success.catalogueSources.groupingBy { it.name }.eachCount()
			success.catalogueSources.map { source ->
				Manga(
					catalogueSource = source,
					pkgName = success.pkgName,
					contentType = if (success.isNsfw) ContentType.HENTAI else ContentType.MANGA,
					hasLanguageSuffix = (counts[source.name] ?: 0) > 1,
					extName = success.appName,
					isPreInstalled = true,
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
		private var activeInstance = WeakReference<ExtensionManager>(null)

		fun getByName(name: String): Manga? = activeInstance.get()?.getSourceByName(name)
	}
}
