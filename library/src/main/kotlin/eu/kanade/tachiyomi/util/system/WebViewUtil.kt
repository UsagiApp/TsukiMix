package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object WebViewUtil {
	private const val CHROME_PACKAGE = "com.android.chrome"
	private const val YOUTUBE_FOR_TV_PACKAGE = "com.google.android.youtube.tv"
	private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"

	const val MINIMUM_WEBVIEW_VERSION = 118

	fun getInferredUserAgent(context: Context): String {
		return WebView(context)
			.getDefaultUserAgentString()
			.replace("; Android .*?\\)".toRegex(), "; Android 10; K)")
			.replace("Version/.* Chrome/".toRegex(), "Chrome/")
			.replace("wv", "")
	}

	fun getVersion(context: Context): String {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return ""
		}
		val webView = WebView.getCurrentWebViewPackage() ?: return ""
		val label = webView.applicationInfo?.loadLabel(context.packageManager)?.toString().orEmpty()
		return "$label ${webView.versionName}"
	}

	fun supportsWebView(context: Context): Boolean {
		return runCatching { CookieManager.getInstance() }.isSuccess &&
			context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
	}

	fun spoofedPackageName(context: Context): String {
		val pm = context.packageManager
		return runCatching { pm.getPackageInfo(CHROME_PACKAGE, 0).packageName }
			.recoverCatching { pm.getPackageInfo(SYSTEM_SETTINGS_PACKAGE, 0).packageName }
			.recoverCatching { pm.getPackageInfo(YOUTUBE_FOR_TV_PACKAGE, 0).packageName }
			.getOrElse { pm.getInstalledPackages(0).random().packageName }
	}
}

fun WebView.isOutdated(): Boolean = getWebViewMajorVersion() < WebViewUtil.MINIMUM_WEBVIEW_VERSION

suspend fun WebView.getHtml(): String = suspendCancellableCoroutine { continuation ->
	evaluateJavascript("document.documentElement.outerHTML") { html ->
		continuation.resume(html)
	}
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setDefaultSettings() {
	with(settings) {
		javaScriptEnabled = true
		domStorageEnabled = true
		useWideViewPort = true
		loadWithOverviewMode = true
		cacheMode = WebSettings.LOAD_DEFAULT
		setSupportMultipleWindows(true)
		setSupportZoom(true)
		builtInZoomControls = true
		displayZoomControls = false
	}
	CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

private fun WebView.getWebViewMajorVersion(): Int {
	val match = """.*Chrome/(\d+)\..*""".toRegex().matchEntire(getDefaultUserAgentString())
	return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

private fun WebView.getDefaultUserAgentString(): String {
	val original = settings.userAgentString
	settings.userAgentString = null
	val default = settings.userAgentString
	settings.userAgentString = original
	return default
}
