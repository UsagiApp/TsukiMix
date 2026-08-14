package org.draken.tsukimix.core.parser.tachiyomi

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiLoadResult
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiMangaSource
import org.json.JSONArray
import tsuki.model.ContentType
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class DirectTachiyomiExtensionManager(
	private val context: Context,
	private val httpClient: OkHttpClient,
	private val injektBridge: TachiyomiInjektBridge,
) {
		private val refreshMutex = Mutex()
		private val installMutex = Mutex()
		private val classLoaders = ConcurrentHashMap<String, ClassLoader>()
		private val sourceByName = ConcurrentHashMap<String, TachiyomiMangaSource>()
		private val sourceById = ConcurrentHashMap<Long, TachiyomiMangaSource>()
		private val resolver = TachiyomiLanguageResolver(context)

		private val artifactClient by lazy {
			httpClient
				.newBuilder()
				.apply {
					interceptors().clear()
					networkInterceptors().clear()
					cache(null)
					retryOnConnectionFailure(true)
				}.build()
		}
		private val directory = File(context.filesDir, DIRECT_DIR).also { it.mkdirs() }

		private val dexDirectory = File(context.codeCacheDir, DEX_DIR).also { it.mkdirs() }
		private val metadataFile = File(directory, METADATA_FILE)

		private val _sources = MutableStateFlow<List<TachiyomiMangaSource>>(emptyList())
		private val _installed = MutableStateFlow<List<DirectTachiyomiInstalled>>(emptyList())
		private val _failed = MutableStateFlow<List<DirectTachiyomiFailure>>(emptyList())
		private var ready = false

		val sources: StateFlow<List<TachiyomiMangaSource>> = _sources
		val installed: StateFlow<List<DirectTachiyomiInstalled>> = _installed
		val failed: StateFlow<List<DirectTachiyomiFailure>> = _failed

		@Volatile
		var lastInstallError: String? = null
			private set

		init {
			activeInstance = this
		}

		suspend fun ensureReady(forceRefresh: Boolean = false) {
			if (!forceRefresh && ready) return
			refreshMutex.withLock {
				if (!forceRefresh && ready) return@withLock
				reload()
				ready = true
			}
		}

		suspend fun install(artifact: TachiyomiExtensionArtifact): Boolean =
			installMutex.withLock {
				withContext(Dispatchers.IO) {
					val packageName = artifact.packageName.trim().takeIf { PACKAGE_REGEX.matches(it) } ?: return@withContext false
					val staging = File(directory, "$packageName.staging.apk")

					val downloaded = File(directory, "$packageName.download")
					val destination = File(directory, "$packageName.apk")
					val backup = File(directory, "$packageName.backup")
					val candidates = listOfNotNull(artifact.apkUrl, artifact.jarUrl).distinct()
					if (candidates.isEmpty()) {
						lastInstallError = "Catalog entry has no APK artifact URL"
						return@withContext false
					}

					val errors = ArrayList<String>(candidates.size)
					var loaded: TachiyomiLoadResult.Success? = null

					for (url in candidates) {
						downloaded.delete()
						staging.delete()
						val downloadError = download(url, downloaded)
						if (downloadError != null) {
							errors += "$url → $downloadError"
							continue
						}
						if (!prepareDexArtifact(downloaded, staging)) {
							errors += "$url → Download is not an Android APK with DEX code"
							continue
						}
						if (!makeReadOnly(staging)) {
							errors += "$url → Cannot make staged APK read-only"
							staging.delete()
							continue
						}
						val result = loadArtifact(staging, artifact)

						if (result is TachiyomiLoadResult.Success) {
							loaded = result
							break
						}

						errors += "$url → ${(result as TachiyomiLoadResult.Error).message}"
					}
					downloaded.delete()
					if (loaded == null) {
						staging.delete()
						lastInstallError = errors.takeLast(2).joinToString("\n").ifBlank { "No compatible extension artifact could be loaded" }
						return@withContext false
					}

					if (backup.exists()) backup.delete()
					if (destination.exists() && !destination.renameTo(backup)) {
						staging.delete()
						return@withContext false
					}
					if (!staging.renameTo(destination)) {
						staging.delete()
						backup.renameTo(destination)
						return@withContext false
					}

					val record =
						DirectTachiyomiInstalled(
							packageName = packageName,
							name = artifact.name,
							repositoryUrl = artifact.repositoryUrl,
							jarUrl = artifact.jarUrl,
							apkUrl = artifact.apkUrl,
							versionCode = loaded.versionCode,
							versionName = loaded.versionName,
							libVersion = loaded.libVersion,
							contentType = if (loaded.isNsfw) ContentType.HENTAI else artifact.contentType,
							iconUrl = artifact.iconUrl,
							sources = artifact.sources,
						)
					writeRecords((readRecords().filterNot { it.packageName == packageName } + record))
					backup.delete()
					reload()
					lastInstallError = null
					true
				}
			}

		suspend fun remove(packageName: String): Boolean =
			installMutex.withLock {
				withContext(Dispatchers.IO) {
					val safe = packageName.takeIf { PACKAGE_REGEX.matches(it) } ?: return@withContext false
					val destination = File(directory, "$safe.apk")
					val removed = !destination.exists() || destination.delete()
					if (removed) {
						writeRecords(readRecords().filterNot { it.packageName == safe })
						reload()
					}
					removed
				}
			}

		fun getActiveSources(): List<TachiyomiMangaSource> = resolver.selectActive(_sources.value)

		fun owns(source: TachiyomiMangaSource): Boolean = _installed.value.any { it.packageName == source.pkgName }

		fun getSourceByName(name: String): TachiyomiMangaSource? = sourceByName[name]

		fun getSourceById(id: Long): TachiyomiMangaSource? = sourceById[id]

		fun resolve(source: TachiyomiMangaSource): TachiyomiMangaSource = resolver.resolve(source, _sources.value)

		fun getLanguage(source: TachiyomiMangaSource): List<TachiyomiMangaSource> = resolver.getVariants(source, _sources.value)

		fun getActiveLanguage(source: TachiyomiMangaSource): String? = resolver.getActiveLanguage(source, _sources.value)

		fun setActiveLanguage(
			source: TachiyomiMangaSource,
			language: String,
		) = resolver.setActiveLanguage(source, language)

		private suspend fun reload() {
			injektBridge.initialize()
			val records = readRecords()
			val successful = ArrayList<DirectTachiyomiInstalled>(records.size)
			val failures = ArrayList<DirectTachiyomiFailure>()
			val sources = ArrayList<TachiyomiMangaSource>()
			sourceByName.clear()
			sourceById.clear()
			classLoaders.clear()

			for (record in records) {
				val file = File(directory, "${record.packageName}.apk")
				if (!file.exists()) {
					failures += DirectTachiyomiFailure(record.packageName, "Artifact not found")
					continue
				}
				if (!makeReadOnly(file)) {
					failures += DirectTachiyomiFailure(record.packageName, "Artifact is writable and could not be made read-only")
					continue
				}
				val result = loadArtifact(file, record.toArtifact())

				if (result is TachiyomiLoadResult.Success) {
					val updated =
						record.copy(
							versionCode = result.versionCode,
							versionName = result.versionName,
							libVersion = result.libVersion,
							contentType = if (result.isNsfw) ContentType.HENTAI else record.contentType,
							iconUrl = record.iconUrl,
							sources = if (record.sources.isNotEmpty()) record.sources else record.toArtifact().sources,
						)
					successful += updated
					result.catalogueSources.forEach { source ->
						val wrapped =
							TachiyomiMangaSource(
								catalogueSource = source,
								pkgName = updated.packageName,
								isNsfw = updated.isNsfw,
								hasLanguageSuffix = false,
								extensionName = updated.name,
							)
						sources += wrapped
						sourceById[wrapped.sourceId] = wrapped
						sourceByName[wrapped.name] = wrapped
					}
				} else {
					failures += DirectTachiyomiFailure(record.packageName, (result as? TachiyomiLoadResult.Error)?.message ?: "Extension load failed")
				}
			}
			val withSuffix = sources.groupingBy { it.pkgName to it.displayName }.eachCount()
			val normalized = sources.map { it.copy(hasLanguageSuffix = (withSuffix[it.pkgName to it.displayName] ?: 0) > 1) }
			sourceByName.clear()
			sourceById.clear()
			normalized.forEach {
				sourceByName[it.name] = it
				sourceById[it.sourceId] = it
			}
			_sources.value = normalized

			_installed.value = successful

			_failed.value = failures
		}

		private fun loadArtifact(
			file: File,
			artifact: TachiyomiExtensionArtifact,
		): TachiyomiLoadResult {
			val packageInfo = getPackageInfo(file)
			val packageName = packageInfo?.packageName?.takeIf { PACKAGE_REGEX.matches(it) } ?: artifact.packageName
			val metadata = packageInfo?.applicationInfo?.metaData ?: catalogMetadata(packageName, artifact)

			val versionName = packageInfo?.versionName ?: artifact.versionName ?: "0.0.0"
			val libVersion =
				readLibVersion(metadata, versionName) ?: artifact.extensionLib
					?: return TachiyomiLoadResult.Error(artifact.packageName, "Missing extension library version")
			if (libVersion !in LIB_VERSION_MIN..LIB_VERSION_MAX) {
				return TachiyomiLoadResult.Error(artifact.packageName, "Incompatible extension library: $libVersion")
			}
			val sourceClassNames =
				metadata.getString(METADATA_SOURCE_CLASS)
					?: metadata.getString(METADATA_SOURCE_FACTORY)
					?: return TachiyomiLoadResult.Error(artifact.packageName, "Missing source class metadata")
							val effectiveContentType = contentTypeFromManifest(metadata) ?: artifact.contentType

			val loader =
				runCatching {
					val optimizedDirectory =
						File(dexDirectory, artifact.packageName).also { directory ->
							if (!directory.exists() && !directory.mkdirs()) {
								error("Cannot create optimized DEX directory: ${directory.absolutePath}")
							}
							if (!directory.isDirectory || !directory.canWrite()) {
								error("Optimized DEX directory is not writable: ${directory.absolutePath}")
							}
						}
					DirectDexClassLoader(
						file.absolutePath,
						optimizedDirectory.absolutePath,
						librarySearchPath = null,
						parent = context.classLoader,
					)
				}.getOrElse {
					return TachiyomiLoadResult.Error(
						artifact.packageName,
						"Cannot create extension classloader: ${it.describeFailure()}",
						it,
					)
				}

			return runCatching {
				val sources = loadSources(packageName, sourceClassNames, loader)

				if (sources.isEmpty()) error("No sources loaded")
				classLoaders[artifact.packageName] = loader
				TachiyomiLoadResult.Success(
					pkgName = artifact.packageName,
					appName = artifact.name,
					versionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: artifact.versionCode ?: 0L,
					versionName = versionName,
					libVersion = libVersion,
					lang = sources.mapNotNull { (it as? CatalogueSource)?.lang }.distinct().let { if (it.size == 1) it.first() else "all" },
					isNsfw = effectiveContentType == ContentType.HENTAI,
					sources = sources,
				)
			}.getOrElse { TachiyomiLoadResult.Error(artifact.packageName, "Failed to load extension: ${it.describeFailure()}", it) }
		}

		private fun Throwable.describeFailure(): String =
			generateSequence(this) { it.cause }
				.mapNotNull { error -> error.message?.takeIf { it.isNotBlank() } }
				.distinct()
				.joinToString(" <- ")
				.ifBlank { javaClass.simpleName }

		private fun loadSources(
			packageName: String,
			classNames: String,
			loader: ClassLoader,
		): List<Source> =
			classNames.split(';', ':', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { if (it.startsWith('.')) packageName + it else it }.flatMap { className ->
				when (val instance = loader.loadClass(className).getDeclaredConstructor().newInstance()) {
					is Source -> listOf(instance)
					is SourceFactory -> instance.createSources()
					else -> error("Unknown source class type: ${instance.javaClass.name}")
				}
			}

		private fun getPackageInfo(file: File): PackageInfo? =
			runCatching {
				@Suppress("DEPRECATION")
				context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
			}.getOrNull()

		private fun catalogMetadata(
			packageName: String,
			artifact: TachiyomiExtensionArtifact,
		): Bundle =
			Bundle().apply {
				putString(METADATA_SOURCE_CLASS, "$packageName.ExtensionGenerated")
				artifact.extensionLib?.let { putDouble(METADATA_EXTENSION_LIB, it) }
									putInt(
						METADATA_CONTENT_WARNING,
						if (artifact.contentType == ContentType.HENTAI) 2 else 0,
					)

			}

		private fun download(
			url: String,
			destination: File,
		): String? =
			runCatching {
				val request =
					Request
						.Builder()
						.url(url)
						.header("User-Agent", "Usagi-TachiyomiExtension/1.0")
						.get()
						.build()
				artifactClient.newCall(request).execute().use { response ->
					if (!response.isSuccessful) error("HTTP ${response.code} ${response.message}")
					val body = response.body ?: error("Empty response body")
					body.byteStream().use { input ->
						destination.outputStream().use { output -> input.copyTo(output) }
					}
				}
				null
			}.getOrElse { error -> error.message ?: error.javaClass.simpleName }

		private fun prepareDexArtifact(
			input: File,
			output: File,
		): Boolean =
			runCatching {
				output.setWritable(true, false)
				output.delete()
				ZipFile(input).use { archive ->
					if (archive.getEntry("AndroidManifest.xml") == null) return@use false
					val hasDex = archive.entries().asSequence().any { entry -> entry.name.matches(DEX_ENTRY_REGEX) }

					if (hasDex) {
						input.copyTo(output, overwrite = true)
						return@use normalizeLegacyDexArchive(output)
					}

					val nestedApk = archive.entries().asSequence().firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
					if (nestedApk != null) {
						archive.getInputStream(nestedApk).use { source -> output.outputStream().use { target -> source.copyTo(target) } }
						val validNested =
							ZipFile(output).use { nested ->
								nested.getEntry("AndroidManifest.xml") != null && nested.entries().asSequence().any { it.name.matches(DEX_ENTRY_REGEX) }
							}
						return@use validNested && normalizeLegacyDexArchive(output)
					}
					false
				}
			}.getOrDefault(false)

		private fun normalizeLegacyDexArchive(file: File): Boolean {
			if (Build.VERSION.SDK_INT >= 26) return true
			val transformed = File(file.parentFile, "${file.name}.legacy")
			return runCatching {
				ZipFile(file).use { archive ->
					ZipOutputStream(transformed.outputStream().buffered()).use { output ->
						archive.entries().asSequence().forEach { entry ->
							val bytes = archive.getInputStream(entry).use { it.readBytes() }
							val normalized = if (entry.name.matches(DEX_ENTRY_REGEX)) downgradeDex035(bytes) else bytes
							val target = ZipEntry(entry.name).apply { time = entry.time }
							output.putNextEntry(target)
							output.write(normalized)
							output.closeEntry()
						}
					}
				}
				if (!transformed.renameTo(file)) {
					file.delete()
					if (!transformed.renameTo(file)) error("Cannot replace APK with legacy DEX archive")
				}
				true
			}.getOrElse {
				transformed.delete()
				false
			}
		}

		private fun downgradeDex035(bytes: ByteArray): ByteArray {
			if (bytes.size < 32 || !bytes.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) return bytes
			if (bytes[4] != '0'.code.toByte() || bytes[5] != '3'.code.toByte() || bytes[6] != '8'.code.toByte()) return bytes
			bytes[6] = '5'.code.toByte()
			val signature = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size))
			signature.copyInto(bytes, 12)
			val checksum = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
			for (offset in 0 until 4) bytes[8 + offset] = (checksum ushr (offset * 8)).toByte()
			return bytes
		}

		private fun makeReadOnly(file: File): Boolean {
			if (!file.exists() || !file.isFile) return false
			file.setReadable(true, false)
			file.setWritable(false, false)
			return !file.canWrite()
		}

		private fun readRecords(): List<DirectTachiyomiInstalled> {
			val array = runCatching { JSONArray(metadataFile.takeIf { it.exists() }?.readText().orEmpty()) }.getOrNull() ?: return emptyList()
			return buildList {
				for (index in 0 until array.length()) {
					val obj = array.optJSONObject(index) ?: continue
					DirectTachiyomiInstalled.fromJson(obj)?.let(::add)
				}
			}
		}

		private fun writeRecords(records: List<DirectTachiyomiInstalled>) {
			val array = JSONArray()
			records.distinctBy { it.packageName }.forEach { array.put(it.toJson()) }
			metadataFile.writeText(array.toString())
		}

		private fun readLibVersion(
			metadata: Bundle,
			versionName: String,
		): Double? {
			val raw = runCatching { metadata.get(METADATA_EXTENSION_LIB) }.getOrNull()
			val parsed =
				when (raw) {
					is Number -> raw.toDouble()
					is String -> raw.toDoubleOrNull()
					else -> versionName.substringBeforeLast('.').toDoubleOrNull()
				}
			return normalizeLibVersion(parsed)
		}

		private fun normalizeLibVersion(value: Double?): Double? =
			value?.let {
				when {
					abs(it - 1.4) < 0.01 -> 1.4
					abs(it - 1.6) < 0.01 -> 1.6
					else -> it
				}
			}

		companion object {
			private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
			private val DEX_ENTRY_REGEX = Regex("classes(\\d*)?\\.dex")
			private const val DIRECT_DIR = "tachiyomi-direct"
			private const val DEX_DIR = "tachiyomi-direct-dex"
			private const val METADATA_FILE = "installed.json"

			private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
			private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
			private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
			private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
			private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"
			private const val LIB_VERSION_MIN = 1.4
			private const val LIB_VERSION_MAX = 1.6
			private const val PACKAGE_FLAGS = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
			private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

			@Volatile private var activeInstance: DirectTachiyomiExtensionManager? = null

			fun getByName(name: String): TachiyomiMangaSource? = activeInstance?.getSourceByName(name)
		}
	}

private fun contentTypeFromCatalog(
	raw: String?,
	extensionLib: Double?,
	fallback: ContentType = ContentType.MANGA,
): ContentType {
	val value = raw?.trim().orEmpty()
	if (value.isBlank()) return fallback
	return when {
		value.equals("MANGA", true) ||
			value.equals("CONTENT_WARNING_SAFE", true) ||
			value.equals("SAFE", true) -> {
			ContentType.MANGA
		}

		value.equals("HENTAI", true) ||
			value.equals("CONTENT_WARNING_MIXED", true) ||
			value.equals("MIXED", true) ||
			value.equals("CONTENT_WARNING_NSFW", true) ||
			value.equals("NSFW", true) -> {
			ContentType.HENTAI
		}

		else -> {
			val number = value.toIntOrNull() ?: return fallback
			if ((extensionLib ?: 1.6) >= 1.6) {
				if (number == 1) ContentType.MANGA else ContentType.HENTAI
			} else {
				if (number == 0) ContentType.MANGA else ContentType.HENTAI
			}
		}
	}
}

private fun contentTypeFromManifest(metadata: Bundle): ContentType? {
	val warning = runCatching { metadata.getInt("tachiyomix.contentWarning", Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
	val legacyNsfw = runCatching { metadata.getInt("tachiyomi.extension.nsfw", 0) }.getOrDefault(0)
	return when {
		warning == 0 -> ContentType.MANGA
		warning > 0 || legacyNsfw == 1 -> ContentType.HENTAI
		else -> null
	}
}

data class TachiyomiCatalogSource(
	val id: Long,
	val name: String,
	val language: String,
	val homeUrl: String?,
	val contentType: ContentType = ContentType.MANGA,
)

data class TachiyomiExtensionArtifact(
	val repositoryUrl: String,
	val name: String,
	val packageName: String,
	val jarUrl: String?,
	val apkUrl: String?,
	val iconUrl: String?,
	val extensionLib: Double?,
	val versionCode: Long?,
	val versionName: String?,
	val contentType: ContentType = ContentType.MANGA,
	val sources: List<TachiyomiCatalogSource> = emptyList(),
) {
	val isNsfw: Boolean get() = contentType == ContentType.HENTAI
}

data class DirectTachiyomiInstalled(
	val packageName: String,
	val name: String,
	val repositoryUrl: String,
	val jarUrl: String?,
	val apkUrl: String?,
	val iconUrl: String?,
	val versionCode: Long,
	val versionName: String,
	val libVersion: Double,
	val contentType: ContentType,
	val sources: List<TachiyomiCatalogSource> = emptyList(),
) {
	val isNsfw: Boolean get() = contentType == ContentType.HENTAI

	fun toArtifact() = TachiyomiExtensionArtifact(repositoryUrl, name, packageName, jarUrl, apkUrl, iconUrl, libVersion, versionCode, versionName, contentType, sources)

	fun toJson() =
		JSONObject().apply {
			put("packageName", packageName)
			put("name", name)
			put("repositoryUrl", repositoryUrl)
			put("jarUrl", jarUrl)

			put("apkUrl", apkUrl)
			put("iconUrl", iconUrl)
			put("versionCode", versionCode)
			put("versionName", versionName)
			put("libVersion", libVersion)
			put("contentType", contentType.name)
			put("isNsfw", isNsfw)
			put(
				"sources",
				JSONArray().apply {
					sources.forEach { source ->
						put(
							JSONObject().apply {
								put("id", source.id)
								put("name", source.name)
								put("language", source.language)
								put("homeUrl", source.homeUrl)
								put("contentType", source.contentType.name)
							},
						)
					}
				},
			)
		}

	companion object {
		fun fromJson(obj: JSONObject) =
			runCatching {
									val libVersion = obj.optDouble("libVersion", 1.4)
					val contentType =
						contentTypeFromCatalog(
							obj.optString("contentType").takeIf { it.isNotBlank() }
								?: obj.optString("contentRating").takeIf { it.isNotBlank() }
								?: if (obj.optBoolean("isNsfw")) "HENTAI" else "MANGA",
							libVersion,
						)
					DirectTachiyomiInstalled(

					packageName = obj.getString("packageName"),
					name = obj.optString("name", obj.getString("packageName")),
					repositoryUrl = obj.optString("repositoryUrl"),
					jarUrl = obj.optString("jarUrl").takeIf { it.isNotBlank() },
					apkUrl = obj.optString("apkUrl").takeIf { it.isNotBlank() },
					iconUrl = obj.optString("iconUrl").takeIf { it.isNotBlank() },
					versionCode = obj.optLong("versionCode", 0L),
					versionName = obj.optString("versionName", "0.0.0"),
					libVersion = libVersion,
						contentType = contentType,

					sources =
						buildList {
							val sourceArray = obj.optJSONArray("sources") ?: return@buildList
							for (index in 0 until sourceArray.length()) {
								val source = sourceArray.optJSONObject(index) ?: continue
																	add(
										TachiyomiCatalogSource(
											source.optLong("id"),
											source.optString("name"),
											source.optString("language", "all"),
											source.optString("homeUrl").takeIf { it.isNotBlank() },
											contentTypeFromCatalog(
												source.optString("contentType").takeIf { it.isNotBlank() }
													?: source.optString("contentRating").takeIf { it.isNotBlank() },
												libVersion,
												contentType,
											),
										),
									)

							}
						},
				)
			}.getOrNull()
	}
}

data class DirectTachiyomiFailure(
	val packageName: String,
	val message: String,
)

private const val CATALOG_KEY_REPOSITORIES = "repositories"
private const val CATALOG_KEY_IGNORED_PACKAGES = "ignored_packages"
private const val CATALOG_KEY_REPOSITORY_NAMES = "repository_names"
private const val CATALOG_CACHE_DIR = "tachiyomi-catalog-cache"

class TachiyomiExtensionCatalogProvider(
	private val context: Context,
	private val httpClient: OkHttpClient,
) {
		private val preferences by lazy { context.getSharedPreferences("tachiyomi_catalogs", Context.MODE_PRIVATE) }
		private val cacheDirectory by lazy { File(context.filesDir, CATALOG_CACHE_DIR).also { it.mkdirs() } }
		private val catalogClient by lazy {
			httpClient
				.newBuilder()
				.apply {
					interceptors().clear()
					networkInterceptors().clear()
					cache(null)
					retryOnConnectionFailure(true)
					followRedirects(true)
					followSslRedirects(true)
				}.build()
		}
		private val directCatalogClient by lazy {
			OkHttpClient
				.Builder()
				.connectTimeout(20, TimeUnit.SECONDS)
				.readTimeout(90, TimeUnit.SECONDS)
				.writeTimeout(20, TimeUnit.SECONDS)
				.retryOnConnectionFailure(true)
				.followRedirects(true)
				.followSslRedirects(true)
				.build()
		}

		@Volatile
		var lastLoadError: String? = null
			private set

		fun saveRepository(input: String) {
			val normalized = normalizeUrl(input) ?: return
			val current = preferences.getStringSet(CATALOG_KEY_REPOSITORIES, emptySet()).orEmpty()
			preferences.edit { putStringSet(CATALOG_KEY_REPOSITORIES, current + normalized) }
		}

		fun repositoryName(input: String): String? {
			val normalized = normalizeUrl(input) ?: return null
			val names = runCatching { JSONObject(preferences.getString(CATALOG_KEY_REPOSITORY_NAMES, "{}").orEmpty()) }.getOrNull() ?: return null
			return names.optString(normalized).takeIf { it.isNotBlank() }
		}

		fun setRepositoryName(
			input: String,
			name: String?,
		) {
			val normalized = normalizeUrl(input) ?: return
			val names = runCatching { JSONObject(preferences.getString(CATALOG_KEY_REPOSITORY_NAMES, "{}").orEmpty()) }.getOrElse { JSONObject() }
			if (name.isNullOrBlank()) names.remove(normalized) else names.put(normalized, name.trim())
			preferences.edit { putString(CATALOG_KEY_REPOSITORY_NAMES, names.toString()) }
		}

		fun removeRepository(input: String) {
			val normalized = normalizeUrl(input) ?: return
			val current = preferences.getStringSet(CATALOG_KEY_REPOSITORIES, emptySet()).orEmpty()
			val names = runCatching { JSONObject(preferences.getString(CATALOG_KEY_REPOSITORY_NAMES, "{}").orEmpty()) }.getOrElse { JSONObject() }
			names.remove(normalized)
			cacheFile(normalized).delete()
			preferences.edit {
				putStringSet(CATALOG_KEY_REPOSITORIES, current - normalized)
				putString(CATALOG_KEY_REPOSITORY_NAMES, names.toString())
			}
		}

		fun ignorePackage(packageName: String) {
			val current = preferences.getStringSet(CATALOG_KEY_IGNORED_PACKAGES, emptySet()).orEmpty()
			preferences.edit { putStringSet(CATALOG_KEY_IGNORED_PACKAGES, current + packageName) }
		}

		fun restorePackage(packageName: String) {
			val current = preferences.getStringSet(CATALOG_KEY_IGNORED_PACKAGES, emptySet()).orEmpty()
			preferences.edit { putStringSet(CATALOG_KEY_IGNORED_PACKAGES, current - packageName) }
		}

		suspend fun loadSavedCached(): List<TachiyomiExtensionArtifact> =
			withContext(Dispatchers.IO) {
				val ignored = preferences.getStringSet(CATALOG_KEY_IGNORED_PACKAGES, emptySet()).orEmpty()
				preferences
					.getStringSet(CATALOG_KEY_REPOSITORIES, emptySet())
					.orEmpty()
					.flatMap(::readCachedArtifacts)
					.filterNot { it.packageName in ignored }
			}

		suspend fun loadSaved(): List<TachiyomiExtensionArtifact> =
			withContext(Dispatchers.IO) {
				val ignored = preferences.getStringSet(CATALOG_KEY_IGNORED_PACKAGES, emptySet()).orEmpty()
				preferences
					.getStringSet(CATALOG_KEY_REPOSITORIES, emptySet())
					.orEmpty()
					.flatMap { url -> load(url).ifEmpty { readCachedArtifacts(url) } }
					.filterNot { it.packageName in ignored }
			}

		suspend fun load(input: String): List<TachiyomiExtensionArtifact> =
			withContext(Dispatchers.IO) {
				val normalized = normalizeUrl(input)
				val urls = normalized?.let(::candidateUrls).orEmpty()
				if (urls.isEmpty()) {
					lastLoadError = "Invalid repository URL"
					return@withContext emptyList()
				}
				val errors = ArrayList<String>(urls.size * 2)
				val clients = listOf("configured network" to catalogClient, "direct network" to directCatalogClient)
				for (url in urls) {
					val request =
						Request
							.Builder()
							.url(url)
							.header("Accept", "application/json")
							.header("User-Agent", "Usagi-TachiyomiCatalog/1.0")
							.get()
							.build()
					for ((clientName, client) in clients) {
						val result =
							runCatching {
								client.newCall(request).execute().use { response ->
									if (!response.isSuccessful) {
										errors += "$clientName: $url → HTTP ${response.code} ${response.message}"
										return@use emptyList<TachiyomiExtensionArtifact>()
									}
									val body = decodeCatalogBody(url, response.body?.string().orEmpty())
									val parsed = parse(normalized ?: url, body)
									if (parsed.isEmpty()) errors += "$clientName: $url → Catalog has no supported extensions"
									parsed
								}
							}.getOrElse { error ->
								errors += "$clientName: $url → ${error.message ?: error.javaClass.simpleName}"
								emptyList()
							}
						if (result.isNotEmpty()) {
							writeCachedArtifacts(normalized ?: url, result)
							lastLoadError = null
							return@withContext result
						}
					}
				}

				lastLoadError = errors.takeLast(3).joinToString("\n").ifBlank { "Catalog could not be parsed" }

				emptyList()
			}

		private fun cacheFile(repositoryUrl: String): File {
			val hash = MessageDigest.getInstance("SHA-256").digest(repositoryUrl.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
			return File(cacheDirectory, "$hash.json")
		}

		private fun writeCachedArtifacts(
			repositoryUrl: String,
			artifacts: List<TachiyomiExtensionArtifact>,
		) {
			runCatching {
				val cachedArtifacts = JSONArray()
				artifacts.forEach { artifact ->
					val cachedArtifact = JSONObject()
					cachedArtifact.put("name", artifact.name)
					cachedArtifact.put("packageName", artifact.packageName)
					artifact.jarUrl?.let { cachedArtifact.put("jarUrl", it) }
					artifact.apkUrl?.let { cachedArtifact.put("apkUrl", it) }
					artifact.iconUrl?.let { cachedArtifact.put("iconUrl", it) }
					artifact.extensionLib?.let { cachedArtifact.put("extensionLib", it) }
					artifact.versionCode?.let { cachedArtifact.put("versionCode", it) }
					artifact.versionName?.let { cachedArtifact.put("versionName", it) }
											cachedArtifact.put("contentType", artifact.contentType.name)

					val cachedSources = JSONArray()
					artifact.sources.forEach { source ->
						val cachedSource = JSONObject()
						cachedSource.put("id", source.id)
						cachedSource.put("name", source.name)
						cachedSource.put("language", source.language)
						source.homeUrl?.let { cachedSource.put("homeUrl", it) }
													cachedSource.put("contentType", source.contentType.name)

						cachedSources.put(cachedSource)
					}
					cachedArtifact.put("sources", cachedSources)
					cachedArtifacts.put(cachedArtifact)
				}
				val payload = JSONObject()
				payload.put("repositoryUrl", repositoryUrl)
				payload.put("artifacts", cachedArtifacts)
				cacheFile(repositoryUrl).writeText(payload.toString())
			}
		}

		private fun readCachedArtifacts(input: String): List<TachiyomiExtensionArtifact> {
			val repositoryUrl = normalizeUrl(input) ?: return emptyList()
			val root = runCatching { JSONObject(cacheFile(repositoryUrl).readText()) }.getOrNull() ?: return emptyList()
			val artifacts = root.optJSONArray("artifacts") ?: return emptyList()
			return buildList {
				for (index in 0 until artifacts.length()) {
					val artifact = artifacts.optJSONObject(index) ?: continue
					val packageName = artifact.optString("packageName").takeIf { it.isNotBlank() } ?: continue
					val extensionLib = artifact.optDouble("extensionLib", Double.NaN).takeUnless { it.isNaN() }
					val sources =
						buildList {
							val sourceArray = artifact.optJSONArray("sources") ?: return@buildList
							for (sourceIndex in 0 until sourceArray.length()) {
								val source = sourceArray.optJSONObject(sourceIndex) ?: continue
								add(
									TachiyomiCatalogSource(
										id = source.optLong("id"),
										name = source.optString("name", packageName),
										language = source.optString("language", "all"),
										homeUrl = source.optString("homeUrl").takeIf { it.isNotBlank() },
										contentType =
											contentTypeFromCatalog(
												source.optString("contentType").takeIf { it.isNotBlank() }
													?: source.optString("contentRating").takeIf { it.isNotBlank() },
												extensionLib,
											),
									),
								)
							}
						}
					add(
						TachiyomiExtensionArtifact(
							repositoryUrl = root.optString("repositoryUrl", repositoryUrl),
							name = artifact.optString("name", packageName),
							packageName = packageName,
							jarUrl = artifact.optString("jarUrl").takeIf { it.isNotBlank() },
							apkUrl = artifact.optString("apkUrl").takeIf { it.isNotBlank() },
							iconUrl = artifact.optString("iconUrl").takeIf { it.isNotBlank() },
							extensionLib = extensionLib,
							versionCode = artifact.optString("versionCode").toLongOrNull(),
							versionName = artifact.optString("versionName").takeIf { it.isNotBlank() },
															contentType =
									contentTypeFromCatalog(
										artifact.optString("contentType").takeIf { it.isNotBlank() }
											?: artifact.optString("contentRating").takeIf { it.isNotBlank() },
										extensionLib,
									),

							sources = sources,
						),
					)
				}
			}.distinctBy { it.packageName }
		}

		fun normalizeUrl(input: String): String? {
			val raw = input.trim().removeSuffix("/")
			if (raw.isBlank()) return null
			val github = GITHUB_REPOSITORY_REGEX.matchEntire(raw)
			if (github != null) return "https://raw.githubusercontent.com/${github.groupValues[1]}/${github.groupValues[2]}/main/index.json"
			if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
			val repo = raw.removePrefix("github.com/").removePrefix("www.github.com/")
			val parts = repo.split('/').filter { it.isNotBlank() }
			if (parts.size == 2) return "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/main/index.json"
			return null
		}

		private fun candidateUrls(normalized: String): List<String> {
			val rawGithub = RAW_GITHUB_INDEX_REGEX.matchEntire(normalized)
			if (rawGithub == null) return listOf(normalized)
			val owner = rawGithub.groupValues[1]
			val repository = rawGithub.groupValues[2]
			val refs = listOf("main", "master", "repo")
			val rawUrls = refs.map { ref -> "https://raw.githubusercontent.com/$owner/$repository/$ref/index.json" }
			val cdnUrls = refs.map { ref -> "https://cdn.jsdelivr.net/gh/$owner/$repository@$ref/index.json" }
			val apiUrls = refs.map { ref -> "https://api.github.com/repos/$owner/$repository/contents/index.json?ref=$ref" }
			return (rawUrls + cdnUrls + apiUrls + normalized).distinct()
		}

		private fun decodeCatalogBody(
			url: String,
			body: String,
		): String {
			if (!url.startsWith("https://api.github.com/repos/")) return body
			val response = JSONObject(body)
			if (!response.optString("encoding").equals("base64", true)) return body
			val content = response.optString("content").filterNot(Char::isWhitespace)
			if (content.isBlank()) error("GitHub Contents API returned an empty catalog")
			return Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8)
		}

		private companion object {
			val GITHUB_REPOSITORY_REGEX = Regex("(?i)^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:/.*)?$")
			val RAW_GITHUB_INDEX_REGEX = Regex("(?i)^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/[^/]+/index\\.json$")
		}

		private fun parse(
			repositoryUrl: String,
			body: String,
		): List<TachiyomiExtensionArtifact> =
			runCatching {
				val root = JSONObject(body.removePrefix("\uFEFF"))
				val extensionList = root.optJSONObject("extensionList")
				val extensions =
					extensionList?.optJSONArray("extensions")
						?: root.optJSONArray("extensions")
						?: JSONArray()

				buildList {
					for (index in 0 until extensions.length()) {
						val obj = extensions.optJSONObject(index) ?: continue
						val packageName = obj.optString("packageName").takeIf { it.isNotBlank() } ?: continue
						val resources = obj.optJSONObject("resources")
						val extensionLib = obj.optString("extensionLib").toDoubleOrNull()
													val catalogContentType =
								contentTypeFromCatalog(
									obj.opt("contentType")?.toString()?.takeIf { it.isNotBlank() }
										?: obj.opt("contentRating")?.toString()?.takeIf { it.isNotBlank() }
										?: obj.opt("contentWarning")?.toString()?.takeIf { it.isNotBlank() },
									extensionLib,
								)

						val sourceObjects = obj.optJSONArray("sources")
						val catalogSources =
							buildList {
								if (sourceObjects != null) {
									for (sourceIndex in 0 until sourceObjects.length()) {
										val source = sourceObjects.optJSONObject(sourceIndex) ?: continue
										val id = source.optString("id").toLongOrNull() ?: continue
										val language = source.optString("language", "all").takeIf { it.isNotBlank() } ?: "all"
																					val sourceContentType =
												source.opt("contentType")?.toString()?.takeIf { it.isNotBlank() }
													?: source.opt("contentRating")?.toString()?.takeIf { it.isNotBlank() }
													?: source.opt("contentWarning")?.toString()?.takeIf { it.isNotBlank() }
											add(
												TachiyomiCatalogSource(
													id,
													source.optString("name", packageName),
													language,
													source.optString("homeUrl").takeIf { it.isNotBlank() },
													contentTypeFromCatalog(sourceContentType, extensionLib),
												),
											)

									}
								}
							}
						add(
							TachiyomiExtensionArtifact(
								repositoryUrl = repositoryUrl,
								name = obj.optString("name", packageName),
								packageName = packageName,
								jarUrl = resources?.optString("jarUrl")?.takeIf { !it.isNullOrBlank() },
								apkUrl = resources?.optString("apkUrl")?.takeIf { !it.isNullOrBlank() },
								iconUrl = resources?.optString("iconUrl")?.takeIf { !it.isNullOrBlank() },
								extensionLib = extensionLib,
								versionCode = obj.optString("versionCode").toLongOrNull(),
								versionName = obj.optString("versionName").takeIf { it.isNotBlank() },
																	contentType = if (obj.optBoolean("isNsfw")) ContentType.HENTAI else catalogContentType,

								sources = catalogSources,
							),
						)
					}
				}
			}.getOrElse { error ->
				throw IllegalArgumentException("Catalog JSON parse failed: ${error.message ?: error.javaClass.simpleName}", error)
			}
	}

private class DirectDexClassLoader(
	dexPath: String,
	optimizedDirectory: String,
	librarySearchPath: String?,
	parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
	private val systemClassLoader = ClassLoader.getSystemClassLoader()

	override fun loadClass(
		name: String,
		resolve: Boolean,
	): Class<*> {
		var loaded = findLoadedClass(name)
		if (loaded == null) {
			loaded = runCatching { systemClassLoader?.loadClass(name) }.getOrNull()
		}
		if (loaded == null) {
			loaded = runCatching { findClass(name) }.getOrElse { super.loadClass(name, false) }
		}
		if (resolve) resolveClass(loaded)
		return loaded
	}
}

