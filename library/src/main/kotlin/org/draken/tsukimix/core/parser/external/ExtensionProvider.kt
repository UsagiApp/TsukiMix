@file:Suppress("unused")

package org.draken.tsukimix.core.parser.external

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.tsukimix.core.parser.external.model.ExtSource
import org.draken.tsukimix.core.parser.external.model.ExtArtifact
import org.draken.tsukimix.core.parser.external.model.contentTypeFromCatalog
import org.json.JSONArray
import org.json.JSONObject
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
			.connectTimeout(5, TimeUnit.SECONDS)
			.readTimeout(8, TimeUnit.SECONDS)
			.followRedirects(true)
			.build()
	}

	@Volatile
	var lastLoadError: String? = null
		private set

	fun saveRepository(input: String) {
		val entry = normalizeUrl(input) ?: input.trim().removeSuffix("/")
		if (entry.isBlank()) return
		val current = prefs.getStringSet("repositories", emptySet()).orEmpty()
		val key = canonicalKey(entry)
		val filtered = current.filterNot { canonicalKey(it) == key }.toSet()
		prefs.edit { putStringSet("repositories", filtered + entry) }
	}

	fun canonicalKey(input: String): String {
		val pair = parseOwnerRepo(input)
		if (pair != null) {
			val owner = pair.first
			val repo = pair.second.removeSuffix("-source").removeSuffix("-sources")
			return "$owner/$repo".lowercase()
		}
		val raw = input.trim().removeSuffix("/")
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
			?: names.keys().asSequence().firstOrNull { canonicalKey(it) == key }?.let {
				names.optString(it).takeIf { s -> s.isNotBlank() }
			}
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
		val raw = input.trim().removeSuffix("/")
		val url = normalizeUrl(input) ?: raw
		val key = canonicalKey(input)
		cacheFile(raw).delete()
		cacheFile(url).delete()
		val currentRepos = prefs.getStringSet("repositories", emptySet()).orEmpty()
		val toRemove = currentRepos.filter {
			val itRaw = it.trim().removeSuffix("/")
			itRaw == raw ||
				(normalizeUrl(it) ?: itRaw) == url ||
				canonicalKey(it) == key
		}.toSet()
		toRemove.forEach { cacheFile(it).delete() }
		val names = runCatching { JSONObject(prefs.getString("repository_names", "{}").orEmpty()) }.getOrElse { JSONObject() }
		names.remove(url)
		names.remove(raw)
		names.remove(key)
		val keysToRemove = names.keys().asSequence().filter { canonicalKey(it) == key }.toList()
		keysToRemove.forEach { names.remove(it) }
		prefs.edit {
			putStringSet("repositories", currentRepos - toRemove - raw - url)
			putString("repository_names", names.toString())
		}
	}

	fun ignorePackage(pkg: String) = prefs.edit {
		putStringSet("ignored_packages", prefs.getStringSet("ignored_packages", emptySet()).orEmpty() + pkg)
	}

	fun restorePackage(pkg: String) = prefs.edit {
		putStringSet("ignored_packages", prefs.getStringSet("ignored_packages", emptySet()).orEmpty() - pkg)
	}

	fun getSavedRepositories(): Set<String> {
		val current = prefs.getStringSet("repositories", emptySet()).orEmpty()
		val map = mutableMapOf<String, String>()
		for (repo in current) {
			val key = canonicalKey(repo)
			if (!map.containsKey(key)) {
				map[key] = normalizeUrl(repo) ?: repo
			}
		}
		return map.values.toSet()
	}

	suspend fun loadSavedCached(): List<ExtArtifact> = withContext(Dispatchers.IO) {
		val ignored = prefs.getStringSet("ignored_packages", emptySet()).orEmpty()
		getSavedRepositories()
			.flatMap(::readCachedArtifacts)
			.filterNot { it.packageName in ignored }
	}

	suspend fun loadSaved(): List<ExtArtifact> = withContext(Dispatchers.IO) {
		val ignored = prefs.getStringSet("ignored_packages", emptySet()).orEmpty()
		getSavedRepositories()
			.flatMap { url -> load(url).ifEmpty { readCachedArtifacts(url) } }
			.filterNot { it.packageName in ignored }
	}

	suspend fun load(input: String): List<ExtArtifact> = withContext(Dispatchers.IO) {
		val urls = candidateUrls(input)
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
					val rawBody = res.body.string()
					val body = decodeBody(url, rawBody)
					val trimmed = body.removePrefix("\uFEFF").trim()
					if (trimmed.startsWith("{")) {
						val obj = runCatching { JSONObject(trimmed) }.getOrNull()
						val meta = obj?.optJSONObject("meta")
						val repoName = meta?.optString("name")?.takeIf { it.isNotBlank() }
						if (repoName != null) {
							setRepositoryName(input, repoName)
						}
						val indexV2 = obj?.optString("index_v2")?.takeIf { it.isNotBlank() }
						if (indexV2 != null && !indexV2.endsWith(".pb", true)) {
							val v2Result = load(indexV2)
							if (v2Result.isNotEmpty() && !isDummyCatalog(v2Result)) return@use v2Result
						}
						if (url.endsWith("/repo.json")) {
							for (compUrl in listOf(
								url.replace("/repo.json", "/index.json"),
								url.replace("/repo.json", "/index.min.json")
							)) {
								val companionResult = runCatching {
									val req = Request.Builder().url(compUrl)
										.header("Accept", "application/json")
										.header("User-Agent", "Usagi/1.0")
										.build()
									client.newCall(req).execute().use { compRes ->
										if (compRes.isSuccessful) parse(compUrl, decodeBody(compUrl, compRes.body.string())) else emptyList()
									}
								}.getOrDefault(emptyList())
								if (companionResult.isNotEmpty() && !isDummyCatalog(companionResult)) return@use companionResult
							}
						}
					}
					parse(url, body)
				}
			}.getOrDefault(emptyList())

			if (result.isNotEmpty() && !isDummyCatalog(result)) {
				writeCachedArtifacts(input, result)
				val normalized = normalizeUrl(input)
				if (normalized != null && normalized != input) {
					writeCachedArtifacts(normalized, result)
				}
				lastLoadError = null
				return@withContext result
			}
		}
		lastLoadError = "Failed to load catalog from $input"
		emptyList()
	}

	private fun isDummyCatalog(artifacts: List<ExtArtifact>): Boolean =
		artifacts.isNotEmpty() && artifacts.all { it.name.contains("Outdated App", true) || it.name.contains("Update to Mihon", true) }

	private fun cacheFile(url: String): File {
		val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
		return File(cacheDir, "$hash.json")
	}

	private fun writeCachedArtifacts(url: String, artifacts: List<ExtArtifact>) = runCatching {
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

	private fun readCachedArtifacts(input: String): List<ExtArtifact> {
		val candidates = listOfNotNull(input, normalizeUrl(input)).distinct()
		for (key in candidates) {
			val file = cacheFile(key).takeIf { it.exists() } ?: continue
			val list = parseCachedFile(file, key)
			if (list.isNotEmpty()) return list
		}
		return emptyList()
	}

	private fun parseCachedFile(file: File, key: String): List<ExtArtifact> {
		return runCatching {
			val root = JSONObject(file.readText())
			val arr = root.optJSONArray("artifacts") ?: return emptyList()
			(0 until arr.length()).mapNotNull { i ->
				val obj = arr.optJSONObject(i) ?: return@mapNotNull null
				val pkg = obj.optString("pkg").ifBlank { obj.optString("packageName") }.takeIf { it.isNotBlank() }
					?: return@mapNotNull null
				val lib = obj.optDouble("extensionLib", Double.NaN).takeUnless { it.isNaN() }
				val rawNsfw = obj.opt("contentWarning") ?: obj.opt("contentRating")
					?: obj.opt("contentType") ?: obj.opt("isNsfw") ?: obj.opt("nsfw")
				val type = contentTypeFromCatalog(rawNsfw, lib)
				val srcArr = obj.optJSONArray("sources")
				val sources = (0 until (srcArr?.length() ?: 0)).mapNotNull { si ->
					val s = srcArr?.optJSONObject(si) ?: return@mapNotNull null
					val id = s.optLong("id").takeIf { it != 0L } ?: return@mapNotNull null
					val sRawNsfw = s.opt("contentWarning") ?: s.opt("contentRating") ?: s.opt("contentType")
						?: s.opt("isNsfw") ?: s.opt("nsfw")
					val sType = if (sRawNsfw != null) contentTypeFromCatalog(sRawNsfw, lib, type) else type
					ExtSource(
						id,
						s.optString("name", pkg),
						s.optString("language", "all").ifBlank { s.optString("lang", "all") },
						s.optString("homeUrl").takeIf { it.isNotBlank() }
							?: s.optString("baseUrl").takeIf { it.isNotBlank() },
						sType,
					)
				}
				ExtArtifact(
					root.optString("repositoryUrl", key),
					obj.optString("name", pkg),
					pkg,
					obj.optString("jarUrl").takeIf { it.isNotBlank() },
					obj.optString("apkUrl").takeIf { it.isNotBlank() },
					obj.optString("iconUrl").takeIf { it.isNotBlank() },
					lib,
					obj.optString("versionCode").toLongOrNull() ?: obj.optLong("code", -1L).takeIf { it != -1L },
					obj.optString("versionName").takeIf { it.isNotBlank() }
						?: obj.optString("version").takeIf { it.isNotBlank() },
					type,
					sources,
				)
			}
		}.getOrDefault(emptyList())
	}

	fun parseOwnerRepo(input: String): Pair<String, String>? {
		val raw = input.trim().removeSuffix("/")
		if (raw.startsWith("local:", ignoreCase = true) ||
			raw.startsWith("file:", ignoreCase = true) ||
			raw.startsWith("installed:", ignoreCase = true) ||
			raw.startsWith("content:", ignoreCase = true)
		) {
			return null
		}
		val gh = GITHUB_REGEX.matchEntire(raw) ?: RAW_GH_REGEX.matchEntire(raw)
		if (gh != null) {
			return gh.groupValues[1] to gh.groupValues[2]
		}
		val stripped = raw.removePrefix("https://").removePrefix("http://")
			.removePrefix("raw.githubusercontent.com/").removePrefix("github.com/").removePrefix("www.github.com/")
			.removePrefix("cdn.jsdelivr.net/gh/")
		val parts = stripped.split('/').filter { it.isNotBlank() }
		if (parts.size >= 2 && !parts[0].contains(':')) {
			val owner = parts[0]
			val repo = parts[1].substringBefore('@').removeSuffix(".git")
			return owner to repo
		}
		return null
	}

	fun normalizeUrl(input: String): String? {
		val pair = parseOwnerRepo(input)
		if (pair != null) {
			val owner = pair.first
			val repo = pair.second.removeSuffix("-source").removeSuffix("-sources")
			return "https://raw.githubusercontent.com/$owner/$repo/repo/index.json"
		}
		val raw = input.trim().removeSuffix("/")
		return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else null
	}

	private fun candidateUrls(input: String): List<String> {
		val raw = input.trim().removeSuffix("/")
		val pair = parseOwnerRepo(raw)
		val owner = pair?.first
		val repoName = pair?.second

		if (owner != null && repoName != null) {
			val baseRepo = repoName.removeSuffix("-source").removeSuffix("-sources")
			val repos = listOf(
				repoName,
				baseRepo,
				if (repoName == baseRepo) "$baseRepo-source" else repoName,
				if (repoName == baseRepo) "$baseRepo-sources" else repoName,
			).distinct()
			val branches = listOf("repo", "main", "master", "gh-pages")
			val files = listOf("index.json", "index.min.json", "repo.json")
			val list = mutableListOf<String>()
			if (raw.startsWith("http://") || raw.startsWith("https://")) {
				list.add(raw)
			}
			for (r in repos) {
				for (b in branches) {
					for (f in files) {
						list.add("https://raw.githubusercontent.com/$owner/$r/$b/$f")
						list.add("https://cdn.jsdelivr.net/gh/$owner/$r@$b/$f")
					}
				}
				for (f in files) {
					list.add("https://$owner.github.io/$r/$f")
				}
			}
			return list.distinct()
		}

		if (raw.startsWith("http://") || raw.startsWith("https://")) {
			val list = mutableListOf(raw)
			when {
				raw.endsWith("/index.json") -> {
					list.add(raw.replace("/index.json", "/index.min.json"))
					list.add(raw.replace("/index.json", "/repo.json"))
				}
				raw.endsWith("/index.min.json") -> {
					list.add(raw.replace("/index.min.json", "/index.json"))
					list.add(raw.replace("/index.min.json", "/repo.json"))
				}
				raw.endsWith("/repo.json") -> {
					list.add(raw.replace("/repo.json", "/index.json"))
					list.add(raw.replace("/repo.json", "/index.min.json"))
				}
				else -> {
					list.add("$raw/index.json")
					list.add("$raw/index.min.json")
					list.add("$raw/repo.json")
				}
			}
			return list.distinct()
		}
		return emptyList()
	}

	private fun decodeBody(url: String, body: String): String {
		if (!url.startsWith("https://api.github.com/repos/")) return body
		val content = runCatching { JSONObject(body).optString("content").filterNot(Char::isWhitespace) }.getOrNull()
		return if (!content.isNullOrBlank()) Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8) else body
	}

	private fun parse(repoUrl: String, body: String): List<ExtArtifact> = runCatching {
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
			val pkg = obj.optString("pkg").ifBlank { obj.optString("packageName") }.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			val res = obj.optJSONObject("resources")
			val name = obj.optString("name", pkg)
			val lib = obj.optDouble("extensionLib", Double.NaN).takeUnless { it.isNaN() }
				?: obj.optDouble("libVersion", Double.NaN).takeUnless { it.isNaN() }

			val rawNsfw = obj.opt("contentWarning") ?: obj.opt("contentRating")
				?: obj.opt("contentType") ?: obj.opt("isNsfw") ?: obj.opt("nsfw")
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
				val sRawNsfw = s.opt("contentWarning") ?: s.opt("contentRating")
					?: s.opt("contentType") ?: s.opt("isNsfw") ?: s.opt("nsfw")
				val sType = if (sRawNsfw != null) contentTypeFromCatalog(sRawNsfw, lib, type) else type
				ExtSource(
					id = id,
					name = s.optString("name", name),
					language = s.optString("lang").ifBlank { s.optString("language", "all") },
					homeUrl = s.optString("baseUrl").ifBlank { s.optString("homeUrl") }.takeIf { it.isNotBlank() },
					contentType = sType,
				)
			}
			ExtArtifact(
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
