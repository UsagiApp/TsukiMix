package org.draken.tsukimix.core.parser.tachiyomi

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource
import java.util.Locale

fun interface TachiyomiDisabledSourceProvider {
	suspend fun getDisabledSourceNames(): Set<String>
}

fun interface TachiyomiSourcesPublisher {
	fun publish(sources: List<TachiyomiMangaSource>)
}

class TachiyomiRuntime(
	private val installedManager: TachiyomiExtensionManager,
	private val directManager: DirectTachiyomiExtensionManager,
	private val disabledSourceProvider: TachiyomiDisabledSourceProvider = TachiyomiDisabledSourceProvider { emptySet() },
	private val sourcesPublisher: TachiyomiSourcesPublisher? = null,
) {
	private val disabledSourceNames = MutableStateFlow<Set<String>>(emptySet())

	val sources: Flow<List<TachiyomiMangaSource>> =
		combine(installedManager.sources, directManager.sources, disabledSourceNames) { installed, direct, disabled ->
			(direct + installed).distinctBy { it.sourceId }.filterNot { it.name in disabled }
		}.distinctUntilChanged()

	suspend fun refreshSourceState() {
		disabledSourceNames.value = disabledSourceProvider.getDisabledSourceNames()
	}

	suspend fun ensureReady(forceRefresh: Boolean = false) {
		installedManager.ensureReady(forceRefresh)
		directManager.ensureReady(forceRefresh)
		refreshSourceState()
		sourcesPublisher?.publish(getActiveSources())
	}

	fun getActiveSources(): List<TachiyomiMangaSource> =
		merge(installedManager.getActiveSources(), directManager.getActiveSources())
			.filterNot { it.name in disabledSourceNames.value }

	fun getSourceByName(name: String): TachiyomiMangaSource? =
		directManager.getSourceByName(name) ?: installedManager.getSourceByName(name)

	fun getSourceById(id: Long): TachiyomiMangaSource? =
		directManager.getSourceById(id) ?: installedManager.getSourceById(id)

	fun resolve(source: TachiyomiMangaSource): TachiyomiMangaSource =
		if (directManager.owns(source)) directManager.resolve(source) else installedManager.resolve(source)

	fun getLanguage(source: TachiyomiMangaSource): List<TachiyomiMangaSource> =
		if (directManager.owns(source)) directManager.getLanguage(source) else installedManager.getLanguage(source)

	fun getActiveLanguage(source: TachiyomiMangaSource): String? =
		if (directManager.owns(source)) directManager.getActiveLanguage(source) else installedManager.getActiveLanguage(source)

	fun setActiveLanguage(
		source: TachiyomiMangaSource,
		language: String,
	) {
		if (directManager.owns(source)) directManager.setActiveLanguage(source, language) else installedManager.setActiveLanguage(source, language)
	}

	fun addLangToPref(
		screen: PreferenceScreen,
		source: TachiyomiMangaSource,
		title: CharSequence,
		onChanged: () -> Unit,
	) {
		val variants = getLanguage(source).distinctBy { it.locale.lowercase(Locale.ROOT) }.sortedBy { it.languageDisplayName }
		if (variants.size <= 1) return
		ListPreference(screen.context).apply {
			key = "language"
			order = 1
			isPersistent = false
			isIconSpaceReserved = false
			entries = variants.map { it.languageDisplayName }.toTypedArray()
			entryValues = variants.map { it.locale }.toTypedArray()
			value = getActiveLanguage(source) ?: variants.first().locale
			summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
			this.title = title
			dialogTitle = title
			setOnPreferenceChangeListener { _, newValue ->
				val language = newValue as? String ?: return@setOnPreferenceChangeListener false
				setActiveLanguage(source, language)
				onChanged()
				true
			}
			screen.addPreference(this)
		}
	}

	private fun merge(vararg lists: List<TachiyomiMangaSource>): List<TachiyomiMangaSource> =
		lists.asSequence().flatten().distinctBy { it.sourceId }.toList()
}
