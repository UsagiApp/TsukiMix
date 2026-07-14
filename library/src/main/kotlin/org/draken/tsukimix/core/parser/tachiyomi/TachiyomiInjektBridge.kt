package org.draken.tsukimix.core.parser.tachiyomi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.IgnoreGzipInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import org.draken.tsukimix.core.parser.tachiyomi.preference.AndroidPreferenceStore
import org.draken.tsukimix.core.parser.tachiyomi.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import java.util.concurrent.TimeUnit

private const val DEFAULT_USER_AGENT =
	"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

class TachiyomiNetworkHelper(
	private val context: Context,
	private val baseClient: OkHttpClient,
	private val androidCookieJar: AndroidCookieJar,
	private val userAgentProvider: () -> String,
) : NetworkHelper() {
	override val cookieJar: AndroidCookieJar
		get() = androidCookieJar

	override val nonCloudflareClient: OkHttpClient
		get() = client

	override val client: OkHttpClient = baseClient.newBuilder().apply {
		interceptors().clear()
		networkInterceptors().clear()
		callTimeout(2, TimeUnit.MINUTES)
		cookieJar(androidCookieJar)
		addInterceptor(UncaughtExceptionInterceptor())
		addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
		addInterceptor(TachiyomiDomainInterceptor())
		addNetworkInterceptor(IgnoreGzipInterceptor())
		addNetworkInterceptor(BrotliInterceptor)
		baseClient.interceptors
			.filterNot { it.javaClass.simpleName in skipInterceptors }
			.forEach(::addInterceptor)
		addInterceptor(CloudflareInterceptor(context, androidCookieJar, ::defaultUserAgentProvider))
		baseClient.networkInterceptors
			.filterNot { it.javaClass.simpleName == "CacheLimitInterceptor" }
			.forEach(::addNetworkInterceptor)
	}.build()

	@Deprecated("The regular client handles Cloudflare by default")
	override val cloudflareClient: OkHttpClient
		get() = client

	override fun defaultUserAgentProvider(): String = userAgentProvider()

	private companion object {
		val skipInterceptors = setOf(
			"GZipInterceptor",
			"CloudFlareInterceptor",
			"RateLimitInterceptor",
			"CommonHeadersInterceptor",
		)
	}
}

class TachiyomiInjektBridge(
	private val context: Context,
	private val httpClient: OkHttpClient,
	private val defaultUserAgentProvider: () -> String,
) {
	@Volatile
	private var initialized = false

	private val androidCookieJar = AndroidCookieJar()

	@Synchronized
	fun initialize() {
		if (initialized) return
		val networkHelper = TachiyomiNetworkHelper(
			context = context,
			baseClient = httpClient,
			androidCookieJar = androidCookieJar,
			userAgentProvider = defaultUserAgentProvider,
		)
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		val xml = XML {
			defaultPolicy { ignoreUnknownChildren() }
			autoPolymorphic = true
			xmlDeclMode = XmlDeclMode.Charset
			indent = 2
			xmlVersion = XmlVersion.XML10
		}
		val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
		val preferenceStore = AndroidPreferenceStore(context, sharedPreferences)
		val application = context.applicationContext as Application
		Injekt.importModule(object : InjektModule {
			override fun InjektRegistrar.registerInjectables() {
				addSingleton(application)
				addSingletonFactory<Context> { context.applicationContext }
				addSingletonFactory<NetworkHelper> { networkHelper }
				addSingletonFactory<OkHttpClient> { networkHelper.client }
				addSingletonFactory<CookieJar> { networkHelper.client.cookieJar }
				addSingletonFactory<AndroidCookieJar> { androidCookieJar }
				addSingletonFactory<SharedPreferences> { sharedPreferences }
				addSingletonFactory<PreferenceStore> { preferenceStore }
				addSingletonFactory<Json> { json }
				addSingletonFactory<StringFormat> { json }
				addSingletonFactory<SerialFormat> { json }
				addSingletonFactory<XML> { xml }
				addSingletonFactory<ProtoBuf> { ProtoBuf }
				addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(context) }
			}
		})
		initialized = true
	}
}
