@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.draken.tsukimix.core.parser.external.preference.AndroidPreferenceStore
import org.draken.tsukimix.core.parser.external.preference.PreferenceStore
import tsuki.network.UserAgents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

private const val DEFAULT_USER_AGENT = UserAgents.CHROME_MOBILE

class ExtensionBridge(
	private val context: Context,
	private val httpClient: OkHttpClient,
	private val defaultUserAgentProvider: () -> String,
	private val javaScriptEvaluator: suspend (String) -> String?,
) {
	@Volatile
	private var initialized = false

	private val androidCookieJar = AndroidCookieJar()

	@Synchronized
	fun initialize() {
		if (initialized) return
		val networkHelper = ExtensionNetworkHelper(
			context = context,
			baseClient = httpClient,
			androidCookieJar = androidCookieJar,
			userAgentProvider = defaultUserAgentProvider,
		)
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
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
				addSingletonFactory<ProtoBuf> { ProtoBuf }
				addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(javaScriptEvaluator) }
			}
		})
		initialized = true
	}
}
