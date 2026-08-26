@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import org.draken.tsukimix.core.parser.external.model.Manga

class ExtensionLangPref {
	fun ExtensionManager.addLangToPref(
		screen: PreferenceScreen,
		source: Manga,
		title: CharSequence,
		onChanged: () -> Unit,
	) {
		val variants = getLanguage(source)
			.distinctBy { it.locale.lowercase() }
			.sortedBy { it.languageDisplayName }
		if (variants.size <= 1) return
		ListPreference(screen.context).apply {
			key = KEY_LANGUAGE
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

	companion object {
		const val KEY_LANGUAGE = "language"
	}
}
