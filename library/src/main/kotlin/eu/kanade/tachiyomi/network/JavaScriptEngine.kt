@file:Suppress("unused", "deprecation")

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
import kotlin.coroutines.resumeWithException

/**
 * Util for evaluating JavaScript in sources.
 *
 * @since extension-lib 1.4
 */
class JavaScriptEngine(private val context: Context) {

	@SuppressLint("SetJavaScriptEnabled")
    @Suppress("UNCHECKED_CAST")
	suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.Main) {
		suspendCancellableCoroutine { continuation ->
			val webView = WebView(context)
			webView.settings.javaScriptEnabled = true
			webView.evaluateJavascript(script) { result ->
				try {
					if (result == null || result == "null") {
						continuation.resume(null as T)
					} else {
						val token = JSONTokener(result).nextValue()
						val decoded = if (token == JSONObject.NULL) null else token
						continuation.resume(decoded as T)
					}
				} catch (e: Throwable) {
					continuation.resumeWithException(e)
				}
			}
		}
	}
}
