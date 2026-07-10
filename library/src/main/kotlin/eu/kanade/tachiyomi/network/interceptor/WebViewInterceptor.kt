package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class WebViewInterceptor(
	private val context: Context,
	private val defaultUserAgentProvider: () -> String,
) : Interceptor {

	private val initWebView by lazy {
		if (DeviceUtil.isMiui || (Build.VERSION.SDK_INT == Build.VERSION_CODES.S && DeviceUtil.isSamsung)) {
			return@lazy
		}
		runCatching { WebSettings.getDefaultUserAgent(context) }
	}

	abstract fun shouldIntercept(response: Response): Boolean

	abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		if (!shouldIntercept(response) || !WebViewUtil.supportsWebView(context)) {
			return response
		}
		initWebView
		return intercept(chain, request, response)
	}

	fun parseHeaders(headers: Headers): Map<String, String> {
		return headers
			.filter { (name, value) -> isRequestHeaderSafe(name, value) }
			.groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
			.mapValues { it.value.getOrNull(0).orEmpty() }
	}

	fun CountDownLatch.awaitFor30Seconds() {
		await(30, TimeUnit.SECONDS)
	}

	fun createWebView(request: Request): WebView {
		return WebView(context).apply {
			setDefaultSettings()
			settings.userAgentString = request.header("User-Agent") ?: defaultUserAgentProvider()
		}
	}
}

private fun isRequestHeaderSafe(rawName: String, rawValue: String): Boolean {
	val name = rawName.lowercase(Locale.ENGLISH)
	val value = rawValue.lowercase(Locale.ENGLISH)
	if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
	if (name == "connection" && value == "upgrade") return false
	return true
}

private val unsafeHeaderNames = listOf(
	"content-length",
	"host",
	"trailer",
	"te",
	"upgrade",
	"cookie2",
	"keep-alive",
	"transfer-encoding",
	"set-cookie",
)
