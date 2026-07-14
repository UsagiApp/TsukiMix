package org.draken.tsukimix.core.parser.tachiyomi

import android.content.Context
import android.content.SharedPreferences
import androidx.core.net.toUri
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource
import java.util.concurrent.ConcurrentHashMap

object TachiyomiSourceSettings {

	private const val KEY_DOMAIN = "domain"
	private const val KEY_SLOWDOWN = "slowdown"
	private val SOURCE_REGEX = "[^a-zA-Z0-9]".toRegex()

	private fun prefsName(source: TachiyomiMangaSource): String {
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

	fun preferences(context: Context, source: TachiyomiMangaSource): SharedPreferences {
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

	fun browserUrl(context: Context, source: TachiyomiMangaSource): String? {
		val httpSource = source.catalogueSource as? HttpSource ?: return null
		val domain = domain(context, source) ?: return httpSource.baseUrl
		return httpSource.baseUrl.toUri().buildUpon().authority(domain).build().toString()
	}

	fun refreshDomainOverride(context: Context, source: TachiyomiMangaSource) {
		val httpSource = source.catalogueSource as? HttpSource ?: return
		val baseHost = httpSource.baseUrl.toHttpUrlOrNull()?.host ?: return
		TachiyomiDomainOverrides.set(baseHost, domain(context, source))
	}

	fun isSlowdownEnabled(context: Context, source: TachiyomiMangaSource): Boolean {
		return preferences(context, source).getBoolean(KEY_SLOWDOWN, false)
	}

	private fun domain(context: Context, source: TachiyomiMangaSource): String? {
		val httpSource = source.catalogueSource as? HttpSource ?: return null
		val baseHost = httpSource.baseUrl.toHttpUrlOrNull()?.host ?: return null
		val raw = preferences(context, source).getString(KEY_DOMAIN, null)
			?.trim()
			?.removePrefix("https://")
			?.removePrefix("http://")
			?.substringBefore('/')
			?.takeIf { isValidDomain(it) }
		return raw?.takeUnless { it == baseHost }
	}
}

class TachiyomiDomainInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val domain = TachiyomiDomainOverrides.get(request.url.host)
			?: return chain.proceed(request)
		val newUrl = request.url.newBuilder().host(domain).build()
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
