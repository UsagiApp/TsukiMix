package eu.kanade.tachiyomi.ui.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri

/**
 * Binary/runtime compatibility entry point for extensions which explicitly open Mihon's
 * WebViewActivity to solve a site challenge.
 *
 * The extension-facing extras are translated to DropSauce's existing browser activity, so this
 * restores Mihon's class/intent contract without adding or changing any normal application UI.
 */
class WebViewActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val url = intent?.getStringExtra(URL_KEY)
		if (!url.isNullOrBlank()) {
			val browserIntent = Intent().apply {
				setClassName(packageName, "org.draken.usagi.browser.BrowserActivity")
				data = url.toUri()
				putExtra("title", intent?.getStringExtra(TITLE_KEY))
			}
			startActivity(browserIntent)
		}
		finish()
	}

	private companion object {
		const val URL_KEY = "url_key"
		const val TITLE_KEY = "title_key"
	}
}
