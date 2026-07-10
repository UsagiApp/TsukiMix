package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class UserAgentInterceptor(
	private val defaultUserAgentProvider: () -> String,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (!request.header("User-Agent").isNullOrEmpty()) {
			return chain.proceed(request)
		}
		return chain.proceed(
			request.newBuilder()
				.removeHeader("User-Agent")
				.addHeader("User-Agent", defaultUserAgentProvider())
				.build(),
		)
	}
}
