package org.draken.tsukimix.core.parser.tachiyomi

import android.content.Context
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ExtensionNetworkHelper(
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
