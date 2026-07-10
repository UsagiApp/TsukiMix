package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class IgnoreGzipInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.header("Accept-Encoding") != "gzip") {
			return chain.proceed(request)
		}
		return chain.proceed(
			request.newBuilder()
				.removeHeader("Accept-Encoding")
				.build(),
		)
	}
}
