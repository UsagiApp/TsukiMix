@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.draken.tsukimix.core.parser.external.model.Manga
import tsuki.network.CommonHeaders
import tsuki.network.UserAgents
import java.util.concurrent.ConcurrentHashMap

private const val APP_PREF = "org.draken.usagi.settings.utils.AutoCompleteTextViewPreference"
private const val DEF_PROVIDER = "org.draken.usagi.settings.utils.EditTextDefaultSummaryProvider"

object ExtensionSourceSettings {

	const val KEY_DOMAIN = "domain"
	const val KEY_OVERRIDE_BASE_URL = "overrideBaseUrl"
	const val KEY_USER_AGENT = "user_agent"
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
		refreshUa(context, source)
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

	private val uaMap = ConcurrentHashMap<String, String>()
	private val UA_ARR = arrayOf(
		UserAgents.CHROME_MOBILE,
		UserAgents.CHROME_DESKTOP,
		UserAgents.FIREFOX_MOBILE,
		UserAgents.FIREFOX_DESKTOP,
	)

	fun getUa(host: String): String? = uaMap[host]
		?: uaMap[host.removePrefix("www.")]
		?: uaMap.entries.firstOrNull { host.endsWith(".${it.key}") }?.value

	fun setUa(host: String, ua: String?) {
		val h = host.removePrefix("www.")
		if (ua.isNullOrBlank()) { uaMap.remove(host); uaMap.remove(h) } else { uaMap[host] = ua; uaMap[h] = ua }
	}

	fun refreshUa(ctx: Context, s: Manga) {
		val http = s.catalogueSource as? HttpSource ?: return
		val sp = preferences(ctx, s)
		val ua = sp.getString(KEY_USER_AGENT, null)?.trim()?.takeIf { it.isNotEmpty() }
		val host = sp.getString(KEY_DEFAULT_BASE_URL, null)?.toHttpUrlOrNull()?.host
			?: http.baseUrl.toHttpUrlOrNull()?.host ?: return
		setUa(host, ua)
		domain(sp)?.let { setUa(it.substringBefore(':'), ua) }
	}

	fun addUaToPref(screen: PreferenceScreen, source: Manga) {
		screen.findPreference<Preference>(KEY_USER_AGENT)?.let { screen.removePreference(it) }
		val c = screen.context
		val def = runCatching { WebSettings.getDefaultUserAgent(c) }.getOrDefault(UserAgents.CHROME_MOBILE)
		// Call and add this UA option to source settings (from main app)
		val p = runCatching {
			(Class.forName(APP_PREF).getConstructor(Context::class.java).newInstance(c) as EditTextPreference)
				.also { it.javaClass.getMethod("setEntries", Array<String>::class.java).invoke(it, UA_ARR) }
		}.getOrElse { EditTextPreference(c) }.apply {
			key = KEY_USER_AGENT
			order = 6
			isIconSpaceReserved = false
			title = CommonHeaders.USER_AGENT
			dialogTitle = CommonHeaders.USER_AGENT
			val prov = runCatching {
				Class.forName(DEF_PROVIDER).getConstructor(String::class.java).newInstance(def)
			}.getOrNull()
			val m = prov?.javaClass?.getMethod("provideSummary", EditTextPreference::class.java)
			summaryProvider = Preference.SummaryProvider<EditTextPreference> {
				(m?.invoke(prov, it) as? CharSequence) ?: it.text?.trim()?.ifEmpty { null } ?: "Default: $def"
			}
			setOnBindEditTextListener { it.inputType = EditorInfo.TYPE_CLASS_TEXT; it.hint = def }
			setOnPreferenceChangeListener { _, v ->
				val ua = (v as? String)?.trim()?.ifEmpty { null }
				preferences(c, source).edit {
					if (ua == null) remove(KEY_USER_AGENT) else putString(KEY_USER_AGENT, ua)
				}
				refreshUa(c, source)
				true
			}
		}
		screen.addPreference(p)
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
