@file:Suppress("UNCHECKED_CAST", "unused")

package eu.kanade.tachiyomi.network

import android.content.Context
import app.cash.quickjs.QuickJs
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Util for evaluating JavaScript in sources.
 *
 * @since extension-lib 1.4
 */
class JavaScriptEngine internal constructor(
	private val evaluator: (suspend (String) -> String?)? = null,
) {

	constructor(context: Context) : this(null)

	suspend fun <T> evaluate(script: String): T {
		val eval = evaluator
		if (eval != null) {
			return runCatching {
				QuickJs.create().use { it.evaluate(script) as T }
			}.getOrElse {
				val value = eval(script)?.let { JSONTokener(it).nextValue() }
				(if (value == JSONObject.NULL) null else value) as T
			}
		}
		return QuickJs.create().use {
			it.evaluate(script) as T
		}
	}
}
