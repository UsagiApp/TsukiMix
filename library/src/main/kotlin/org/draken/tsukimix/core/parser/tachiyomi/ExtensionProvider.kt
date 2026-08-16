@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiCatalogSource
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiExtensionArtifact
import org.draken.tsukimix.core.parser.tachiyomi.model.contentTypeFromCatalog
import org.json.JSONArray
import org.json.JSONObject
import tsuki.model.ContentType
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ExtensionProvider(
	context: Context,
	private val httpClient: OkHttpClient,
) {
	private val appContext = context.applicationContext ?: context
	private val prefs by lazy { appContext.getSharedPreferences("tachiyomi_catalogs", Context.MODE_PRIVATE) }
	private val cacheDir by lazy { File(appContext.filesDir, "tachiyomi-catalog-cache").also { it.mkdirs() } }
	private val client by lazy {
		httpClient.newBuilder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.followRedirects(true)
			.build()
	}

	@Volatile
	var lastLoadError: String? = null
		private set

	fun saveRepository(input: String) {
		val url = normalizeUrl(input) ?: return
		prefs.edit { putStringSet("repositories", prefs.getStringSet("repositories", emptySet()).orEmpty() + url) }
	}

	fun canonicalKey(input: String): String {
		val raw = input.trim().removeSuffix("/")
		val gh = GITHUB_REGEX.matchEntire(raw)
		if (gh != null) return "${gh.groupValues[1]}/${gh.groupValues[2]}".lowercase()
		val rawGh = RAW_GH_REGEX.matchEntire(raw)
		if (rawGh != null) return "${rawGh.groupValues[1]}/${rawGh.groupValues[2]}".lowercase()
		val parts = raw.removePrefix("https://").removePrefix("http://")
			.removePrefix("raw.githubusercontent.com/").removePrefix("github.com/").removePrefix("www.github.com/")
			.split('/').filter { it.isNotBlank() }
		if (parts.size >= 2) return "${parts[0]}/${parts[1]}".lowercase()
		return normalizeUrl(raw)?.lowercase() ?: raw.lowercase()
	}

	fun repositoryName(input: String): String? {
		val key = canonicalKey(input)
		val url = normalizeUrl(input) ?: input.trim().removeSuffix("/")
		val raw = input.trim().removeSuffix("/")
		val names = runCatching { JSONObject(prefs.getString("repository_names", "{}").orEmpty()) }.getOrNull() ?: return null
		return names.optString(key).takeIf { it.isNotBlank() }
			?: names.optString(url).takeIf { it.isNotBlank() }
			?: names.optString(raw).takeIf { it.isNotBlank() }
			?: names.keys().asSequence().firstOrNull { canonicalKey(it) == key }?.let { names.optString(it).takeIf { s -> s.isNotBlank() } }
	}

	fun setRepositoryName(input: String, name: String?) {
		val key = canonicalKey(input)
		val url = normalizeUrl(input) ?: input.trim().removeSuffix("/")
		val names = runCatching { JSONObject(prefs.getString("repository_names", "{}").orEmpty()) }.getOrElse { JSONObject() }
		if (name.isNullOrBlank()) {
			names.remove(key)
			names.remove(url)
			names.remove(input.trim().removeSuffix("/"))
			val keysToRemove = names.keys().asSequence().filter { canonicalKey(it) == key }.toList()
			keysToRemove.forEach { names.remove(it) }
		} else {
			names.put(key, name.trim())
			names.put(url, name.trim())
		}
		prefs.edit { putString("repository_names", names.toString()) }
	}

	fun removeRepository(input: String) {
		val url = normalizeUrl(input) ?: return
		val key = canonicalKey(input)
		cacheFile(url).delete()
		val names = runCatching { JSONObject(prefs.getString("repository_names", "{}").orEmpty()) }.getOrElse { JSONObject() }
		names.remove(url)
		names.remove(key)
		val keysToRemove = names.keys().asSequence().filter { canonicalKey(it) == key }.toList()
		keysToRemove.forEach { names.remove(it) }
		prefs.edit {
			putStringSet("repositories", prefs.getStringSet("repositories", emptySet()).orEmpty() - url)
			putString("repository_names", names.toString())
		}
	}

	fun ignorePackage(pkg: String) = prefs.edit {
		putStringSet("ignored_packages", prefs.getStringSet("ignored_packages", emptySet()).orEmpty() + pkg)
	}

	fun restorePackage(pkg: String) = prefs.edit {
		putStringSet("ignored_packages", prefs.getStringSet("ignored_packages", emptySet()).orEmpty() - pkg)
	}

	suspend fun loadSavedCached(): List<TachiyomiExtensionArtifact> = withContext(Dispatchers.IO) {
		val ignored = prefs.getStringSet("ignored_packages", emptySet()).orEmpty()
		prefs.getStringSet("repositories", emptySet()).orEmpty()
			.flatMap(::readCachedArtifacts)
			.filterNot { it.packageName in ignored }
	}

	suspend fun loadSaved(): List<TachiyomiExtensionArtifact> = withContext(Dispatchers.IO) {
		val ignored = prefs.getStringSet("ignored_packages", emptySet()).orEmpty()
		prefs.getStringSet("repositories", emptySet()).orEmpty()
			.flatMap { url -> load(url).ifEmpty { readCachedArtifacts(url) } }
			.filterNot { it.packageName in ignored }
	}

	suspend fun load(input: String): List<TachiyomiExtensionArtifact> = withContext(Dispatchers.IO) {
		val normalized = normalizeUrl(input)
		val urls = normalized?.let(::candidateUrls).orEmpty()
		if (urls.isEmpty()) {
			lastLoadError = "Invalid repository URL"
			return@withContext emptyList()
		}
		for (url in urls) {
			val request = Request.Builder().url(url)
				.header("Accept", "application/json")
				.header("User-Agent", "Usagi/1.0")
				.build()
			val result = runCatching {
				client.newCall(request).execute().use { res ->
					if (!res.isSuccessful) return@use emptyList()
					val body = decodeBody(url, res.body.string())
					parse(normalized ?: url, body)
				}
			}.getOrDefault(emptyList())

			if (result.isNotEmpty()) {
				writeCachedArtifacts(normalized ?: url, result)
				lastLoadError = null
				return@withContext result
			}
		}
		lastLoadError = "Failed to load catalog from $input"
		emptyList()
	}

	private fun cacheFile(url: String): File {
		val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
		return File(cacheDir, "$hash.json")
	}

	private fun writeCachedArtifacts(url: String, artifacts: List<TachiyomiExtensionArtifact>) = runCatching {
		val array = JSONArray(artifacts.map { art ->
			JSONObject().apply {
				put("name", art.name)
				put("packageName", art.packageName)
				put("jarUrl", art.jarUrl)
				put("apkUrl", art.apkUrl)
				put("iconUrl", art.iconUrl)
				put("extensionLib", art.extensionLib)
				put("versionCode", art.versionCode)
				put("versionName", art.versionName)
				put("contentWarning", if (art.isNsfw) "CONTENT_WARNING_NSFW" else "CONTENT_WARNING_SAFE")
				put("contentType", art.contentType.name)
				put("isNsfw", art.isNsfw)
				put("nsfw", if (art.isNsfw) 1 else 0)
				put("sources", JSONArray(art.sources.map { s ->
					JSONObject().apply {
						put("id", s.id)
						put("name", s.name)
						put("language", s.language)
						put("homeUrl", s.homeUrl)
						put("contentWarning", if (s.isNsfw) "CONTENT_WARNING_NSFW" else "CONTENT_WARNING_SAFE")
						put("contentType", s.contentType.name)
						put("isNsfw", s.isNsfw)
						put("nsfw", if (s.isNsfw) 1 else 0)
					}
				}))
			}
		})
		cacheFile(url).writeText(JSONObject().put("repositoryUrl", url).put("artifacts", array).toString())
	}

	private fun readCachedArtifacts(input: String): List<TachiyomiExtensionArtifact> {
		val url = normalizeUrl(input) ?: return emptyList()
		val file = cacheFile(url).takeIf { it.exists() } ?: return emptyList()
		return runCatching {
			val root = JSONObject(file.readText())
			val arr = root.optJSONArray("artifacts") ?: return emptyList()
			(0 until arr.length()).mapNotNull { i ->
				val obj = arr.optJSONObject(i) ?: return@mapNotNull null
				val pkg = obj.optString("pkg").ifBlank { obj.optString("packageName") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val lib = obj.optDouble("extensionLib", Double.NaN).takeUnless { it.isNaN() }
				val rawNsfw = obj.opt("contentWarning") ?: obj.opt("contentRating") ?: obj.opt("contentType") ?: obj.opt("isNsfw") ?: obj.opt("nsfw")
				val type = contentTypeFromCatalog(rawNsfw, lib)
				val srcArr = obj.optJSONArray("sources")
				val sources = (0 until (srcArr?.length() ?: 0)).mapNotNull { si ->
					val s = srcArr?.optJSONObject(si) ?: return@mapNotNull null
					val id = s.optLong("id").takeIf { it != 0L } ?: return@mapNotNull null
					val sRawNsfw = s.opt("contentWarning") ?: s.opt("contentRating") ?: s.opt("contentType") ?: s.opt("isNsfw") ?: s.opt("nsfw")
					val sType = if (sRawNsfw != null) contentTypeFromCatalog(sRawNsfw, lib, type) else type
					TachiyomiCatalogSource(
						id,
						s.optString("name", pkg),
						s.optString("language", "all").ifBlank { s.optString("lang", "all") },
						s.optString("homeUrl").takeIf { it.isNotBlank() } ?: s.optString("baseUrl").takeIf { it.isNotBlank() },
						sType,
					)
				}
				TachiyomiExtensionArtifact(
					root.optString("repositoryUrl", url),
					obj.optString("name", pkg),
					pkg,
					obj.optString("jarUrl").takeIf { it.isNotBlank() },
					obj.optString("apkUrl").takeIf { it.isNotBlank() },
					obj.optString("iconUrl").takeIf { it.isNotBlank() },
					lib,
					obj.optString("versionCode").toLongOrNull() ?: obj.optLong("code", -1L).takeIf { it != -1L },
					obj.optString("versionName").takeIf { it.isNotBlank() } ?: obj.optString("version").takeIf { it.isNotBlank() },
					type,
					sources,
				)
			}
		}.getOrDefault(emptyList())
	}

	fun normalizeUrl(input: String): String? {
		val raw = input.trim().removeSuffix("/")
		if (raw.isBlank()) return null
		val gh = GITHUB_REGEX.matchEntire(raw)
		if (gh != null) return "https://raw.githubusercontent.com/${gh.groupValues[1]}/${gh.groupValues[2]}/main/index.json"
		if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
		val parts = raw.removePrefix("github.com/").removePrefix("www.github.com/").split('/').filter { it.isNotBlank() }
		return if (parts.size == 2) "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/main/index.json" else null
	}

	private fun candidateUrls(url: String): List<String> {
		val m = RAW_GH_REGEX.matchEntire(url) ?: return listOf(url)
		val (owner, repo) = m.groupValues[1] to m.groupValues[2]
		return listOf(
			url, "https://cdn.jsdelivr.net/gh/$owner/$repo@main/index.json",
			"https://raw.githubusercontent.com/$owner/$repo/master/index.json",
			"https://api.github.com/repos/$owner/$repo/contents/index.json"
		).distinct()
	}

	private fun decodeBody(url: String, body: String): String {
		if (!url.startsWith("https://api.github.com/repos/")) return body
		val content = runCatching { JSONObject(body).optString("content").filterNot(Char::isWhitespace) }.getOrNull()
		return if (!content.isNullOrBlank()) Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8) else body
	}

	private fun parse(repoUrl: String, body: String): List<TachiyomiExtensionArtifact> = runCatching {
		val trimmed = body.removePrefix("\uFEFF").trim()
		val arr = when {
			trimmed.startsWith("[") -> JSONArray(trimmed)
			else -> {
				val root = JSONObject(trimmed)
				root.optJSONObject("extensionList")?.optJSONArray("extensions")
					?: root.optJSONArray("extensions")
					?: root.optJSONArray("extensionList")
					?: JSONArray()
			}
		}
		val baseRepoUrl = repoUrl.substringBeforeLast('/')
		(0 until arr.length()).mapNotNull { i ->
			val obj = arr.optJSONObject(i) ?: return@mapNotNull null
			val pkg = obj.optString("pkg").ifBlank { obj.optString("packageName") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val res = obj.optJSONObject("resources")
			val name = obj.optString("name", pkg)
			val lib = obj.optDouble("extensionLib", Double.NaN).takeUnless { it.isNaN() }
				?: obj.optDouble("libVersion", Double.NaN).takeUnless { it.isNaN() }

			val rawNsfw = obj.opt("contentWarning") ?: obj.opt("contentRating") ?: obj.opt("contentType") ?: obj.opt("isNsfw") ?: obj.opt("nsfw")
			val type = contentTypeFromCatalog(rawNsfw, lib)

			val apkRaw = obj.optString("apk").ifBlank { obj.optString("apkUrl") }.ifBlank { res?.optString("apkUrl").orEmpty() }
			val apkUrl = when {
				apkRaw.isBlank() -> null
				apkRaw.startsWith("http://") || apkRaw.startsWith("https://") -> apkRaw
				else -> "$baseRepoUrl/apk/$apkRaw"
			}
			val jarRaw = obj.optString("jar").ifBlank { obj.optString("jarUrl") }.ifBlank { res?.optString("jarUrl").orEmpty() }
			val jarUrl = when {
				jarRaw.isBlank() -> null
				jarRaw.startsWith("http://") || jarRaw.startsWith("https://") -> jarRaw
				else -> "$baseRepoUrl/jar/$jarRaw"
			}
			val iconRaw = obj.optString("iconUrl").ifBlank { res?.optString("iconUrl").orEmpty() }
			val iconUrl = when {
				iconRaw.isNotBlank() -> iconRaw
				else -> "$baseRepoUrl/icon/$pkg.png"
			}
			val code = obj.optLong("code", -1L).takeIf { it != -1L }
				?: obj.optLong("versionCode", -1L).takeIf { it != -1L }
				?: obj.optString("versionCode").toLongOrNull()
			val versionName = obj.optString("version").ifBlank { obj.optString("versionName") }.takeIf { it.isNotBlank() }

			val srcArr = obj.optJSONArray("sources")
			val sources = (0 until (srcArr?.length() ?: 0)).mapNotNull { si ->
				val s = srcArr?.optJSONObject(si) ?: return@mapNotNull null
				val id = s.optLong("id", 0L).takeIf { it != 0L } ?: s.optString("id").toLongOrNull() ?: return@mapNotNull null
				val sRawNsfw = s.opt("contentWarning") ?: s.opt("contentRating") ?: s.opt("contentType") ?: s.opt("isNsfw") ?: s.opt("nsfw")
				val sType = if (sRawNsfw != null) contentTypeFromCatalog(sRawNsfw, lib, type) else type
				TachiyomiCatalogSource(
					id = id,
					name = s.optString("name", name),
					language = s.optString("lang").ifBlank { s.optString("language", "all") },
					homeUrl = s.optString("baseUrl").ifBlank { s.optString("homeUrl") }.takeIf { it.isNotBlank() },
					contentType = sType,
				)
			}
			TachiyomiExtensionArtifact(
				repositoryUrl = repoUrl,
				name = name,
				packageName = pkg,
				jarUrl = jarUrl,
				apkUrl = apkUrl,
				iconUrl = iconUrl,
				extensionLib = lib,
				versionCode = code,
				versionName = versionName,
				contentType = type,
				sources = sources,
			)
		}
	}.getOrDefault(emptyList())

	private companion object {
		val GITHUB_REGEX = Regex("(?i)^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:/.*)?$")
		val RAW_GH_REGEX = Regex("(?i)^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/[^/]+/index\\.json$")
	}
}
