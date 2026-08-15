@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import org.draken.tsukimix.core.parser.tachiyomi.model.DirectTachiyomiFailure
import org.draken.tsukimix.core.parser.tachiyomi.model.DirectTachiyomiInstalled
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiExtensionArtifact
import org.draken.tsukimix.core.parser.tachiyomi.model.MangaResult
import org.draken.tsukimix.core.parser.tachiyomi.model.Manga
import org.draken.tsukimix.core.parser.tachiyomi.model.contentTypeFromManifest
import org.json.JSONArray
import tsuki.model.ContentType
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class NativeExtManager(
	context: Context,
	private val httpClient: OkHttpClient,
	private val injektBridge: ExtensionBridge,
) {
	private val appContext = context.applicationContext ?: context
	private val mutex = Mutex()
	private val classLoaders = ConcurrentHashMap<String, ClassLoader>()
	private val sourceByName = ConcurrentHashMap<String, Manga>()
	private val sourceById = ConcurrentHashMap<Long, Manga>()
	private val resolver = ExtensionLangResolver(appContext)

	private val client by lazy {
		httpClient.newBuilder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.build()
	}

	private val directory = File(appContext.filesDir, "tachiyomi-direct").also { it.mkdirs() }
	private val dexDir = File(appContext.codeCacheDir, "tachiyomi-direct-dex").also { it.mkdirs() }
	private val metaFile = File(directory, "installed.json")

	private val _sources = MutableStateFlow<List<Manga>>(emptyList())
	private val _installed = MutableStateFlow<List<DirectTachiyomiInstalled>>(emptyList())
	private val _failed = MutableStateFlow<List<DirectTachiyomiFailure>>(emptyList())
	private var ready = false

	val sources: StateFlow<List<Manga>> = _sources
	val installed: StateFlow<List<DirectTachiyomiInstalled>> = _installed
	val failed: StateFlow<List<DirectTachiyomiFailure>> = _failed

	@Volatile
	var lastInstallError: String? = null
		private set

	init {
		activeInstance = WeakReference(this)
	}

	suspend fun ensureReady(forceRefresh: Boolean = false) {
		if (!forceRefresh && ready) return
		mutex.withLock {
			if (!forceRefresh && ready) return@withLock
			reload()
			ready = true
		}
	}

	suspend fun install(artifact: TachiyomiExtensionArtifact): Boolean = mutex.withLock {
		withContext(Dispatchers.IO) {
			val pkg = artifact.packageName.trim().takeIf { PACKAGE_REGEX.matches(it) } ?: return@withContext false
			val staging = File(directory, "$pkg.staging.apk")
			val downloaded = File(directory, "$pkg.download")
			val destination = File(directory, "$pkg.apk")
			val candidates = listOfNotNull(artifact.apkUrl, artifact.jarUrl).distinct()
			if (candidates.isEmpty()) {
				lastInstallError = "No artifact URL"
				return@withContext false
			}

			var loaded: MangaResult.Success? = null
			for (url in candidates) {
				downloaded.delete(); staging.delete()
				if (!download(url, downloaded) || !prepareDex(downloaded, staging) || !makeReadOnly(staging)) continue
				val result = loadArtifact(staging, artifact)
				if (result is MangaResult.Success) {
					loaded = result
					break
				}
			}
			downloaded.delete()
			if (loaded == null) {
				staging.delete()
				lastInstallError = "Failed to load extension"
				return@withContext false
			}

			destination.delete()
			if (!staging.renameTo(destination)) {
				staging.delete()
				return@withContext false
			}

			val record = DirectTachiyomiInstalled(
				packageName = pkg,
				name = artifact.name,
				repositoryUrl = artifact.repositoryUrl,
				jarUrl = artifact.jarUrl,
				apkUrl = artifact.apkUrl,
				iconUrl = artifact.iconUrl,
				versionCode = loaded.versionCode,
				versionName = loaded.versionName,
				libVersion = loaded.libVersion,
				contentType = if (loaded.isNsfw) ContentType.HENTAI else artifact.contentType,
				sources = artifact.sources,
			)
			writeRecords(readRecords().filterNot { it.packageName == pkg } + record)
			reload()
			lastInstallError = null
			true
		}
	}

	suspend fun remove(packageName: String): Boolean = mutex.withLock {
		withContext(Dispatchers.IO) {
			val pkg = packageName.takeIf { PACKAGE_REGEX.matches(it) } ?: return@withContext false
			File(directory, "$pkg.apk").delete()
			classLoaders.remove(pkg)
			runCatching { File(dexDir, pkg).deleteRecursively() }
			writeRecords(readRecords().filterNot { it.packageName == pkg })
			reload()
			true
		}
	}

	fun getActiveSources(): List<Manga> = resolver.selectActive(_sources.value)
	fun owns(source: Manga): Boolean = _installed.value.any { it.packageName == source.pkgName }
	fun getSourceByName(name: String): Manga? = sourceByName[name]
	fun getSourceById(id: Long): Manga? = sourceById[id]
	fun resolve(source: Manga): Manga = resolver.resolve(source, _sources.value)
	fun getLanguage(source: Manga): List<Manga> = resolver.getVariants(source, _sources.value)
	fun getActiveLanguage(source: Manga): String? = resolver.getActiveLanguage(source, _sources.value)
	fun setActiveLanguage(source: Manga, language: String) = resolver.setActiveLanguage(source, language)

	private fun reload() {
		injektBridge.initialize()
		val records = readRecords()
		val successful = ArrayList<DirectTachiyomiInstalled>(records.size)
		val failures = ArrayList<DirectTachiyomiFailure>()
		val loadedSources = ArrayList<Manga>()
		sourceByName.clear(); sourceById.clear(); classLoaders.clear()

		for (record in records) {
			val file = File(directory, "${record.packageName}.apk")
			if (!file.exists() || !makeReadOnly(file)) {
				failures += DirectTachiyomiFailure(record.packageName, "Artifact unavailable")
				continue
			}
			val result = loadArtifact(file, record.toArtifact())
			if (result is MangaResult.Success) {
				val updated = record.copy(
					versionCode = result.versionCode,
					versionName = result.versionName,
					libVersion = result.libVersion,
					contentType = if (result.isNsfw) ContentType.HENTAI else record.contentType,
					sources = record.sources.ifEmpty { record.toArtifact().sources },
				)
				successful += updated
				result.catalogueSources.forEach { source ->
					val wrapped = Manga(
						catalogueSource = source,
						pkgName = updated.packageName,
						contentType = updated.contentType,
						extName = updated.name,
					)
					loadedSources += wrapped
					sourceById[wrapped.sourceId] = wrapped
					sourceByName[wrapped.name] = wrapped
				}
			} else {
				failures += DirectTachiyomiFailure(
					record.packageName,
					(result as? MangaResult.Error)?.message ?: "Load failed"
				)
			}
		}

		val suffixCount = loadedSources.groupingBy { it.pkgName to it.displayName }.eachCount()
		val normalized = loadedSources.map { it.copy(hasLanguageSuffix = (suffixCount[it.pkgName to it.displayName] ?: 0) > 1) }
		sourceByName.clear(); sourceById.clear()
		normalized.forEach {
			sourceByName[it.name] = it
			sourceById[it.sourceId] = it
		}
		_sources.value = normalized
		_installed.value = successful
		_failed.value = failures
	}

	private fun loadArtifact(file: File, artifact: TachiyomiExtensionArtifact): MangaResult {
		val pkgInfo = getPackageInfo(file)
		val pkg = pkgInfo?.packageName?.takeIf { PACKAGE_REGEX.matches(it) } ?: artifact.packageName
		val meta = pkgInfo?.applicationInfo?.metaData ?: Bundle().apply {
			putString("tachiyomi.extension.class", "$pkg.ExtensionGenerated")
			artifact.extensionLib?.let { putDouble("tachiyomix.extensionLib", it) }
			putInt("tachiyomix.contentWarning", if (artifact.contentType == ContentType.HENTAI) 2 else 0)
		}
		val verName = pkgInfo?.versionName ?: artifact.versionName ?: "0.0.0"
		val libVer = readLibVersion(meta, verName) ?: artifact.extensionLib
			?: return MangaResult.Error(pkg, "Missing lib version")
		if (libVer !in 1.4..1.6) return MangaResult.Error(pkg, "Incompatible lib version: $libVer")

		val classNames = meta.getString("tachiyomi.extension.class") ?: meta.getString("tachiyomi.extension.factory")
			?: return MangaResult.Error(pkg, "Missing source class")
		val isNsfw = (contentTypeFromManifest(meta) ?: artifact.contentType) == ContentType.HENTAI

		val loader = runCatching {
			val optDir = File(dexDir, pkg).also { it.mkdirs() }
			DirectDexClassLoader(file.absolutePath, optDir.absolutePath, null, appContext.classLoader)
		}.getOrElse { return MangaResult.Error(pkg, "ClassLoader error: ${it.message}", it) }

		return runCatching {
			val sources = loadSources(pkg, classNames, loader)
			if (sources.isEmpty()) error("No sources")
			classLoaders[pkg] = loader
			MangaResult.Success(
				pkgName = pkg,
				appName = artifact.name,
				versionCode = pkgInfo?.let(PackageInfoCompat::getLongVersionCode) ?: artifact.versionCode ?: 0L,
				versionName = verName,
				libVersion = libVer,
				lang = sources.mapNotNull { (it as? CatalogueSource)?.lang }.distinct().let { if (it.size == 1) it.first() else "all" },
				isNsfw = isNsfw,
				sources = sources,
			)
		}.getOrElse { MangaResult.Error(pkg, "Source load error: ${it.message}", it) }
	}

	private fun loadSources(pkg: String, names: String, loader: ClassLoader): List<Source> =
		names.split(';', ':', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { if (it.startsWith('.')) pkg + it else it }.flatMap { cls ->
			when (val inst = loader.loadClass(cls).getDeclaredConstructor().newInstance()) {
				is Source -> listOf(inst)
				is SourceFactory -> inst.createSources()
				else -> error("Unknown source type: ${inst.javaClass.name}")
			}
		}

	private fun getPackageInfo(file: File): PackageInfo? = runCatching {
		@Suppress("DEPRECATION")
		appContext.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS)
	}.getOrNull()

	private fun download(url: String, dest: File): Boolean = runCatching {
		val req = Request.Builder().url(url).header("User-Agent", "Usagi-TachiyomiExtension/1.0").get().build()
		client.newCall(req).execute().use { res ->
			if (!res.isSuccessful) return@use false
			val body = res.body
			body.byteStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
			true
		}
	}.getOrDefault(false)

	@SuppressLint("SetWorldWritable")
	private fun prepareDex(input: File, output: File): Boolean = runCatching {
		output.setWritable(true, false); output.delete()
		ZipFile(input).use { zip ->
			val hasManifest = zip.getEntry("AndroidManifest.xml") != null
			val hasDex = zip.entries().asSequence().any { it.name.matches(DEX_REGEX) }
			if (hasManifest && hasDex) {
				input.copyTo(output, overwrite = true)
				return@use normalizeLegacyDex(output)
			}
			val nested = zip.entries().asSequence().firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
			if (nested != null) {
				zip.getInputStream(nested).use { s -> output.outputStream().use { t -> s.copyTo(t) } }
				return@use ZipFile(output).use { it.getEntry("AndroidManifest.xml") != null && it.entries().asSequence().any { e -> e.name.matches(DEX_REGEX) } } && normalizeLegacyDex(output)
			}
			false
		}
	}.getOrDefault(false)

	private fun normalizeLegacyDex(file: File): Boolean {
		if (Build.VERSION.SDK_INT >= 26) return true
		val tmp = File(file.parentFile, "${file.name}.tmp")
		return runCatching {
			ZipFile(file).use { zip ->
				ZipOutputStream(tmp.outputStream().buffered()).use { out ->
					val buf = ByteArray(8192)
					zip.entries().asSequence().forEach { entry ->
						out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
						if (entry.name.matches(DEX_REGEX)) {
							out.write(downgradeDex035(zip.getInputStream(entry).use { it.readBytes() }))
						} else {
							zip.getInputStream(entry).use { input ->
								var r: Int
								while (input.read(buf).also { r = it } >= 0) out.write(buf, 0, r)
							}
						}
						out.closeEntry()
					}
				}
			}
			tmp.renameTo(file)
		}.getOrElse { tmp.delete(); false }
	}

	private fun downgradeDex035(bytes: ByteArray): ByteArray {
		if (bytes.size < 32 || !bytes.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) return bytes
		if (bytes[4] != '0'.code.toByte() || bytes[5] != '3'.code.toByte() || bytes[6] != '8'.code.toByte()) return bytes
		bytes[6] = '5'.code.toByte()
		MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)).copyInto(bytes, 12)
		val cs = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
		for (i in 0 until 4) bytes[8 + i] = (cs ushr (i * 8)).toByte()
		return bytes
	}

	@SuppressLint("SetWorldReadable")
	private fun makeReadOnly(file: File): Boolean {
		if (!file.exists() || !file.isFile) return false
		file.setReadable(true, false)
		file.setWritable(false, false)
		return !file.canWrite()
	}

	private fun readRecords(): List<DirectTachiyomiInstalled> {
		val text = metaFile.takeIf { it.exists() }?.readText().orEmpty()
		val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
		return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(
			DirectTachiyomiInstalled::fromJson) }
	}

	private fun writeRecords(records: List<DirectTachiyomiInstalled>) {
		val arr = JSONArray(records.distinctBy { it.packageName }.map { it.toJson() })
		metaFile.writeText(arr.toString())
	}

	private fun readLibVersion(meta: Bundle, verName: String): Double? {
		val raw = runCatching { meta.get("tachiyomix.extensionLib") }.getOrNull()
		val num = when (raw) {
			is Number -> raw.toDouble()
			is String -> raw.toDoubleOrNull()
			else -> verName.substringBeforeLast('.').toDoubleOrNull()
		} ?: return null
		return when {
			abs(num - 1.4) < 0.01 -> 1.4
			abs(num - 1.6) < 0.01 -> 1.6
			else -> num
		}
	}

	companion object {
		private val DEX_MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
		private val DEX_REGEX = Regex("classes(\\d*)?\\.dex")
		private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

		@Volatile
		private var activeInstance = WeakReference<NativeExtManager>(null)

		fun getByName(name: String): Manga? = activeInstance.get()?.getSourceByName(name)
	}
}

private class DirectDexClassLoader(dexPath: String, optDir: String, libPath: String?, parent: ClassLoader) :
	DexClassLoader(dexPath, optDir, libPath, parent) {
	private val sys = getSystemClassLoader()

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		val cls = findLoadedClass(name)
			?: runCatching { sys?.loadClass(name) }.getOrNull()
			?: runCatching { findClass(name) }.getOrElse { super.loadClass(name, false) }
		if (resolve) resolveClass(cls)
		return cls
	}
}
