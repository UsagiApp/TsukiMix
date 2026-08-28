@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import org.draken.tsukimix.core.parser.external.ExtensionBridge
import org.draken.tsukimix.core.parser.external.ExtensionLoader
import org.draken.tsukimix.core.parser.external.ExtensionManager
import org.draken.tsukimix.core.parser.external.ExtensionSourceSettings
import org.draken.tsukimix.core.parser.external.model.Manga
import java.util.Locale

typealias TachiyomiExtensionLoader = ExtensionLoader
typealias TachiyomiExtensionManager = ExtensionManager
typealias TachiyomiInjektBridge = ExtensionBridge
typealias TachiyomiSourceSettings = ExtensionSourceSettings

fun ExtensionManager.addLangToPref(
	screen: PreferenceScreen,
	source: Manga,
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
			val lang = newValue as? String ?: return@setOnPreferenceChangeListener false
			setActiveLanguage(source, lang)
			onChanged()
			true
		}
		screen.addPreference(this)
	}
}
