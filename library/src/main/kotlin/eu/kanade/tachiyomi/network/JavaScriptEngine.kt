@file:Suppress("UNCHECKED_CAST", "unused")

package eu.kanade.tachiyomi.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Util for evaluating JavaScript in sources.
 *
 * @since extension-lib 1.4
 */
class JavaScriptEngine internal constructor(
	private val evaluator: suspend (String) -> String?,
) {

	constructor(context: Context) : this({ script -> evaluate(context, script) })

	suspend fun <T> evaluate(script: String): T {
		val value = evaluator(script)?.let { JSONTokener(it).nextValue() }
		return (if (value == JSONObject.NULL) null else value) as T
	}
}

@SuppressLint("SetJavaScriptEnabled")
private suspend fun evaluate(context: Context, script: String): String? {
	return withContext(Dispatchers.Main.immediate) {
		val webView = WebView(context.applicationContext)
		try {
			webView.settings.javaScriptEnabled = true
			suspendCancellableCoroutine { continuation ->
				webView.evaluateJavascript(script) { continuation.resume(it) }
			}
		} finally {
			webView.destroy()
		}
	}
}
