package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

	private val manager = CookieManager.getInstance()

	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		val urlString = url.toString()
		cookies.forEach { manager.setCookie(urlString, it.toString()) }
	}

	override fun loadForRequest(url: HttpUrl): List<Cookie> = get(url)

	fun get(url: HttpUrl): List<Cookie> {
		val cookies = manager.getCookie(url.toString())
		return if (cookies.isNullOrEmpty()) {
			emptyList()
		} else {
			cookies.split(";").mapNotNull { Cookie.parse(url, it) }
		}
	}

	fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
		val urlString = url.toString()
		val cookies = manager.getCookie(urlString) ?: return 0
		return cookies.split(";")
			.map { it.substringBefore("=").trim() }
			.filter { cookieNames == null || it in cookieNames }
			.onEach { manager.setCookie(urlString, "$it=;Max-Age=$maxAge") }
			.count()
	}

	fun removeAll() {
		manager.removeAllCookies {}
	}
}
