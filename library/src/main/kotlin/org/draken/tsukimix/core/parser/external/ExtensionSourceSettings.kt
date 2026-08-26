@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.draken.tsukimix.core.parser.external.model.Manga
import java.util.concurrent.ConcurrentHashMap

object ExtensionSourceSettings {

	const val KEY_DOMAIN = "domain"
	const val KEY_OVERRIDE_BASE_URL = "overrideBaseUrl"
	private const val KEY_DEFAULT_BASE_URL = "defaultBaseUrl"
	private const val KEY_SLOWDOWN = "slowdown"
	private val SOURCE_REGEX = "[^a-zA-Z0-9]".toRegex()

	private fun prefsName(source: Manga): String {
		return source.name.substringAfter(':').replace(SOURCE_REGEX, "_") + "_settings"
	}

	private fun isValidDomain(value: String): Boolean = runCatching {
		require(value.isNotEmpty())
		val parts = value.split(':')
		require(parts.size <= 2)
		val urlBuilder = HttpUrl.Builder()
		urlBuilder.host(parts.first())
		if (parts.size == 2) {
			urlBuilder.port(parts[1].toInt())
		}
	}.isSuccess

	fun preferences(context: Context, source: Manga): SharedPreferences {
		val configurableSource = source.catalogueSource as? ConfigurableSource
			?: return context.getSharedPreferences(prefsName(source), Context.MODE_PRIVATE)
		return try {
			configurableSource.getSourcePreferences()
		} catch (_: AbstractMethodError) {
			context.getSharedPreferences("source_${source.sourceId}", Context.MODE_PRIVATE)
		} catch (_: Throwable) {
			context.getSharedPreferences(prefsName(source), Context.MODE_PRIVATE)
		}
	}

	fun browserUrl(context: Context, source: Manga): String? {
		val httpSource = source.catalogueSource as? HttpSource ?: return null
		val domain = domain(context, source) ?: return httpSource.baseUrl
		return httpSource.baseUrl.toUri().buildUpon().authority(domain).build().toString()
	}

	fun refreshDomainOverride(context: Context, source: Manga) {
		val httpSource = source.catalogueSource as? HttpSource ?: return
		val prefs = preferences(context, source)
		val domain = domain(prefs)
		val defaultUrl = prefs.getString(KEY_DEFAULT_BASE_URL, null)?.toHttpUrlOrNull()
		if (prefs.contains(KEY_OVERRIDE_BASE_URL) && defaultUrl != null) {
			val targetUrl = domain?.let { defaultUrl.replaceAuthority(it) } ?: defaultUrl
			val target = targetUrl.origin
			if (prefs.getString(KEY_OVERRIDE_BASE_URL, null) != target) {
				prefs.edit { putString(KEY_OVERRIDE_BASE_URL, target) }
			}
		}
		val baseHost = defaultUrl?.host ?: httpSource.baseUrl.toHttpUrlOrNull()?.host ?: return
		TachiyomiDomainOverrides.set(baseHost, domain)
	}

	fun mergeDomainPreference(context: Context, source: Manga) {
		val prefs = preferences(context, source)
		if (!prefs.contains(KEY_DOMAIN)) {
			val current = prefs.getString(KEY_OVERRIDE_BASE_URL, null)?.toHttpUrlOrNull()
			val default = prefs.getString(KEY_DEFAULT_BASE_URL, null)?.toHttpUrlOrNull()
			if (current != null && current != default) {
				prefs.edit { putString(KEY_DOMAIN, current.authority) }
			}
		}
		refreshDomainOverride(context, source)
	}

	fun isSlowdownEnabled(context: Context, source: Manga): Boolean {
		return preferences(context, source).getBoolean(KEY_SLOWDOWN, false)
	}

	private fun domain(context: Context, source: Manga): String? {
		return domain(preferences(context, source))
	}

	private fun domain(prefs: SharedPreferences): String? {
		return prefs.getString(KEY_DOMAIN, null)
			?.trim()
			?.removePrefix("https://")
			?.removePrefix("http://")
			?.substringBefore('/')
			?.takeIf { isValidDomain(it) }
	}

	private val HttpUrl.origin: String
		get() = newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")

	private val HttpUrl.authority: String
		get() = origin.substringAfter("://")

	private fun HttpUrl.replaceAuthority(authority: String): HttpUrl? {
		val replacement = "$scheme://$authority".toHttpUrlOrNull() ?: return null
		return newBuilder().host(replacement.host).port(replacement.port).build()
	}
}

class TachiyomiDomainInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val domain = TachiyomiDomainOverrides.get(request.url.host)
			?: return chain.proceed(request)
		val replacement = "${request.url.scheme}://$domain".toHttpUrlOrNull()
			?: return chain.proceed(request)
		val newUrl = request.url.newBuilder().host(replacement.host).port(replacement.port).build()
		return chain.proceed(request.newBuilder().url(newUrl).build())
	}
}

private object TachiyomiDomainOverrides {
	private val domains = ConcurrentHashMap<String, String>()

	fun set(baseHost: String, domain: String?) {
		if (domain.isNullOrBlank()) {
			domains.remove(baseHost)
		} else {
			domains[baseHost] = domain
		}
	}

	fun get(baseHost: String): String? = domains[baseHost]
}
