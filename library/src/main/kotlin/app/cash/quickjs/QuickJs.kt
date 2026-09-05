@file:Suppress("unused")

package app.cash.quickjs

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QuickJs private constructor(
	private val context: Context,
) : Closeable {

	private val isClosed = AtomicBoolean(false)
	private val handler = Handler(Looper.getMainLooper())
	private var webView: WebView? = null

	@Throws(QuickJsException::class)
	@JvmOverloads
	fun evaluate(script: String, fileName: String = "?"): Any? {
		if (isClosed.get()) {
			throw QuickJsException("QuickJs is closed")
		}

		val latch = CountDownLatch(1)
		var resultHolder: Any? = null
		var errorHolder: Throwable? = null

		handler.post {
			if (isClosed.get()) {
				latch.countDown()
				return@post
			}
			try {
				val wv = getOrCreateWebView()
				wv.evaluateJavascript(script) { rawResult ->
					try {
						resultHolder = parseJsResult(rawResult)
					} catch (t: Throwable) {
						errorHolder = t
					} finally {
						latch.countDown()
					}
				}
			} catch (t: Throwable) {
				errorHolder = t
				latch.countDown()
			}
		}

		val completed = latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		if (!completed) {
			throw QuickJsException("JavaScript evaluation timed out: ${script.take(100)}")
		}
		val error = errorHolder
		if (error != null) {
			throw QuickJsException(error.message ?: "JavaScript evaluation error", error)
		}
		return resultHolder
	}

	@JvmOverloads
	fun compile(source: String, fileName: String = "?"): ByteArray {
		if (isClosed.get()) {
			throw QuickJsException("QuickJs is closed")
		}
		return source.toByteArray(Charsets.UTF_8)
	}

	fun execute(bytecode: ByteArray): Any? {
		if (isClosed.get()) {
			throw QuickJsException("QuickJs is closed")
		}
		val script = String(bytecode, Charsets.UTF_8)
		return evaluate(script)
	}

	override fun close() {
		if (isClosed.compareAndSet(false, true)) {
			handler.post {
				try {
					webView?.stopLoading()
					webView?.destroy()
					webView = null
				} catch (_: Throwable) {
				}
			}
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun getOrCreateWebView(): WebView {
		val existing = webView
		if (existing != null) {
			return existing
		}
		val wv = WebView(context.applicationContext ?: context).apply {
			settings.javaScriptEnabled = true
			settings.domStorageEnabled = true
		}
		webView = wv
		return wv
	}

	private fun parseJsResult(rawResult: String?): Any? {
		if (rawResult == null || rawResult == "null") {
			return null
		}
		return runCatching {
			val tokener = JSONTokener(rawResult)
			convertJsonValue(tokener.nextValue())
		}.getOrElse { rawResult }
	}

	private fun convertJsonValue(value: Any?): Any? {
		return when (value) {
			null, JSONObject.NULL -> null
			is JSONArray -> {
				Array(value.length()) { i ->
					convertJsonValue(value.opt(i))
				}
			}
			is JSONObject -> {
				val map = LinkedHashMap<String, Any?>()
				val keys = value.keys()
				while (keys.hasNext()) {
					val key = keys.next()
					map[key] = convertJsonValue(value.opt(key))
				}
				map
			}
			else -> value
		}
	}

	companion object {
		private const val DEFAULT_TIMEOUT_SECONDS = 30L

		@Volatile
		private var appContext: Context? = null

		@JvmStatic
		fun setContext(context: Context) {
			appContext = context.applicationContext ?: context
		}

		@JvmStatic
		fun create(): QuickJs {
			val ctx = appContext
				?: runCatching { Injekt.get<Context>() }.getOrNull()
				?: runCatching { Injekt.get<android.app.Application>() }.getOrNull()
				?: findApplicationContext()
				?: throw QuickJsException("Unable to initialize QuickJs: Context is not available")
			return QuickJs(ctx)
		}

		@SuppressLint("PrivateApi")
		private fun findApplicationContext(): Context? {
			return runCatching {
				val at = Class.forName("android.app.ActivityThread")
				at.getMethod("currentApplication").invoke(null) as? Context
			}.getOrNull()
		}
	}
}
