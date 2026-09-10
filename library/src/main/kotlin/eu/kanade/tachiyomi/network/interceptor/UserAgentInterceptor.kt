package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import org.draken.tsukimix.core.parser.external.ExtensionSourceSettings
import tsuki.network.CommonHeaders.USER_AGENT

class UserAgentInterceptor(
	private val defaultUserAgentProvider: () -> String,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val r = chain.request()
		val ua = ExtensionSourceSettings.getUa(r.url.host)
			?: r.header(USER_AGENT)?.takeIf { it.isNotEmpty() } ?: defaultUserAgentProvider()
		return chain.proceed(
			if (ua == r.header(USER_AGENT)) r else r.newBuilder().header(USER_AGENT, ua).build()
		)
	}
}
