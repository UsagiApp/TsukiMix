@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.draken.tsukimix.core.parser.tachiyomi.model.Manga
import java.util.Locale

fun interface TachiyomiDisabledSourceProvider {
	suspend fun getDisabledSourceNames(): Set<String>
}

fun interface TachiyomiSourcesPublisher {
	fun publish(sources: List<Manga>)
}

class TachiyomiRuntime(
	private val installedManager: ExtensionManager,
	private val directManager: NativeExtManager,
	private val disabledSourceProvider: TachiyomiDisabledSourceProvider = TachiyomiDisabledSourceProvider { emptySet() },
	private val sourcesPublisher: TachiyomiSourcesPublisher? = null,
) {
	private val disabledSourceNames = MutableStateFlow<Set<String>>(emptySet())

	val sources: Flow<List<Manga>> =
		combine(installedManager.sources, directManager.sources, disabledSourceNames) { inst, dir, dis ->
			(dir + inst).distinctBy { it.sourceId }.filterNot { it.name in dis }
		}.distinctUntilChanged()

	suspend fun refreshSourceState() {
		disabledSourceNames.value = disabledSourceProvider.getDisabledSourceNames()
	}

	suspend fun ensureReady(forceRefresh: Boolean = false) {
		installedManager.ensureReady(forceRefresh)
		directManager.ensureReady(forceRefresh)
		refreshSourceState()
		sourcesPublisher?.publish(getInstalledSources())
	}

	fun getInstalledSources(): List<Manga> =
		(directManager.getActiveSources() + installedManager.getActiveSources())
			.distinctBy { it.pkgName to it.displayName }

	fun getAllSources(): List<Manga> =
		(directManager.sources.value + installedManager.sources.value)
			.distinctBy { it.sourceId }

	fun getActiveSources(): List<Manga> =
		(directManager.getActiveSources() + installedManager.getActiveSources())
			.distinctBy { it.sourceId }
			.filterNot { it.name in disabledSourceNames.value }

	fun getSourceByName(name: String): Manga? = directManager.getSourceByName(name) ?: installedManager.getSourceByName(name)
	fun getSourceById(id: Long): Manga? = directManager.getSourceById(id) ?: installedManager.getSourceById(id)
	fun resolve(s: Manga): Manga = if (directManager.owns(s)) directManager.resolve(s) else installedManager.resolve(s)
	fun getLanguage(s: Manga): List<Manga> = if (directManager.owns(s)) directManager.getLanguage(s) else installedManager.getLanguage(s)
	fun getSiblingSources(s: Manga): List<Manga> = getLanguage(s)
	fun getActiveLanguage(s: Manga): String? = if (directManager.owns(s)) directManager.getActiveLanguage(s) else installedManager.getActiveLanguage(s)
	fun setActiveLanguage(s: Manga, lang: String) {
		if (directManager.owns(s)) directManager.setActiveLanguage(s, lang) else installedManager.setActiveLanguage(s, lang)
	}

	fun addLangToPref(screen: PreferenceScreen, source: Manga, title: CharSequence, onChanged: () -> Unit) {
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
				val lang = newValue as? String ?: return@setOnPreferenceChangeListener false
				setActiveLanguage(source, lang)
				onChanged()
				true
			}
			screen.addPreference(this)
		}
	}
}
