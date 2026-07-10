package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.OkHttpClient

open class NetworkHelper protected constructor() {
	/**
	 * Keiyoushi's stub exposes NetworkHelper(Context). Extensions normally inject this class, but
	 * retaining the constructor prevents linkage failure in sources that instantiate a helper.
	 */
	constructor(context: Context) : this()

	open val client: OkHttpClient
		get() = throw UnsupportedOperationException("Host NetworkHelper must provide a client")

	/**
	 * A client that bypasses Cloudflare protection, for use with CDN requests
	 * that don't need the Cloudflare interceptor.
	 *
	 * @since extensions-lib 1.5
	 */
	open val nonCloudflareClient: OkHttpClient
		get() = client

	/**
	 * Exact Mihon return type is important: JVM method descriptors include return types, so exposing
	 * CookieJar here instead of AndroidCookieJar makes getCookieJar() unresolvable to extension APKs.
	 */
	open val cookieJar: AndroidCookieJar
		get() = throw UnsupportedOperationException("Host NetworkHelper must provide a cookie jar")

	@Deprecated("The regular client handles Cloudflare by default")
	open val cloudflareClient: OkHttpClient
		get() = client

	open fun defaultUserAgentProvider(): String = ""
}
