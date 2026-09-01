@file:Suppress("unused", "DEPRECATION")

package org.draken.tsukimix.core.parser.external

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import dalvik.system.DexFile
import androidx.core.content.pm.PackageInfoCompat
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
import org.draken.tsukimix.core.parser.external.model.ExtFailure
import org.draken.tsukimix.core.parser.external.model.ExtInstalled
import org.draken.tsukimix.core.parser.external.model.ExtArtifact
import org.draken.tsukimix.core.parser.external.model.MangaResult
import org.draken.tsukimix.core.parser.external.model.Manga
import org.draken.tsukimix.core.parser.external.model.contentTypeFromManifest
import org.json.JSONArray
import tsuki.model.ContentType
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.abs
import androidx.core.graphics.createBitmap
import tsuki.network.CommonHeaders
import tsuki.network.UserAgents

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
	private val _installed = MutableStateFlow<List<ExtInstalled>>(emptyList())
	private val _failed = MutableStateFlow<List<ExtFailure>>(emptyList())
	private var ready = false

	val sources: StateFlow<List<Manga>> = _sources
	val installed: StateFlow<List<ExtInstalled>> = _installed
	val failed: StateFlow<List<ExtFailure>> = _failed

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

	suspend fun install(artifact: ExtArtifact): Boolean = mutex.withLock {
		withContext(Dispatchers.IO) {
			val pkg = artifact.packageName.trim().takeIf { PACKAGE_REGEX.matches(it) }
				?: return@withContext false
			val staging = File(directory, "$pkg.staging.apk")
			val downloaded = File(directory, "$pkg.download")
			val destination = File(directory, "$pkg.apk")
			val candidates = listOfNotNull(artifact.apkUrl, artifact.jarUrl).distinct()
			if (candidates.isEmpty()) {
				lastInstallError = "No artifact URL"
				return@withContext false
			}

			var loaded: MangaResult.Success? = null
			var lastError = "Failed to load extension"
			for (url in candidates) {
				downloaded.delete(); staging.delete()
				if (!download(url, downloaded)) { lastError = "Download failed: $url"; continue }
				if (!prepareDex(downloaded, staging) || !makeReadOnly(staging)) {
					lastError = "Unsupported package format: $url"
					continue
				}
				when (val result = loadArtifact(staging, artifact)) {
					is MangaResult.Success -> { loaded = result; break }
					is MangaResult.Error -> lastError = result.message
					else -> lastError = "Untrusted extension"
				}
			}
			downloaded.delete()
			if (loaded == null) {
				staging.delete()
				lastInstallError = lastError
				return@withContext false
			}

			destination.delete()
			if (!staging.renameTo(destination)) {
				staging.delete()
				return@withContext false
			}

			val iconUrl = artifact.iconUrl?.takeIf { it.isNotBlank() } ?: extractIcon(destination, pkg)
			val record = ExtInstalled(
				packageName = pkg,
				name = artifact.name,
				repositoryUrl = artifact.repositoryUrl,
				jarUrl = artifact.jarUrl,
				apkUrl = artifact.apkUrl,
				iconUrl = iconUrl,
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

	suspend fun installLocal(sourceFile: File, originalName: String? = null): Boolean =
		mutex.withLock {
		withContext(Dispatchers.IO) {
			if (!sourceFile.exists() || sourceFile.length() == 0L) {
				lastInstallError = "Source file does not exist or is empty"
				return@withContext false
			}
			val tempInput = File(directory, "temp_import_${System.currentTimeMillis()}.apk")
			val staging = File(directory, "temp_staging_${System.currentTimeMillis()}.apk")
			try {
				sourceFile.copyTo(tempInput, overwrite = true)
				if (!prepareDex(tempInput, staging) || !makeReadOnly(staging)) {
					tempInput.delete(); staging.delete()
					lastInstallError = "Failed to prepare extension package"
					return@withContext false
				}
				val dummyPkg = "temp.import"
				val dummyArtifact = ExtArtifact(
					repositoryUrl = "local://file",
					name = originalName?.removeSuffix(".apk")?.removeSuffix(".jar") ?: "Local Extension",
					packageName = dummyPkg,
					jarUrl = null,
					apkUrl = null,
					iconUrl = null,
					extensionLib = null,
					versionCode = null,
					versionName = null,
				)
				val result = loadArtifact(staging, dummyArtifact)
				if (result !is MangaResult.Success) {
					tempInput.delete(); staging.delete()
					lastInstallError = (result as? MangaResult.Error)?.message ?: "Failed to load extension"
					return@withContext false
				}
				val realPkg = result.pkgName
				val destination = File(directory, "$realPkg.apk")
				destination.delete()
				if (!staging.renameTo(destination)) {
					staging.delete()
					return@withContext false
				}
				val iconUrl = extractIcon(destination, realPkg)
				val record = ExtInstalled(
					packageName = realPkg,
					name = result.appName.ifBlank {
						originalName?.removeSuffix(".apk")?.removeSuffix(".jar") ?: realPkg
					},
					repositoryUrl = "local://$realPkg",
					jarUrl = null,
					apkUrl = null,
					iconUrl = iconUrl,
					versionCode = result.versionCode,
					versionName = result.versionName,
					libVersion = result.libVersion,
					contentType = if (result.isNsfw) ContentType.HENTAI else ContentType.MANGA,
					sources = result.catalogueSources.map { s ->
						org.draken.tsukimix.core.parser.external.model.ExtSource(
							id = s.id,
							name = s.name,
							language = s.lang,
							homeUrl = (s as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl,
							contentType = if (result.isNsfw) ContentType.HENTAI else ContentType.MANGA,
						)
					},
				)
				writeRecords(readRecords().filterNot { it.packageName == realPkg } + record)
				reload()
				lastInstallError = null
				true
			} finally {
				tempInput.delete()
				staging.delete()
			}
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
	fun refresh(source: Manga): Manga {
		val record = readRecords().firstOrNull { it.packageName == source.pkgName } ?: return source
		val file = File(directory, "${record.packageName}.apk")
		if (!file.exists()) return source
		val result = loadArtifact(file, record.toArtifact())
		if (result is MangaResult.Success) {
			result.catalogueSources.firstOrNull { it.id == source.sourceId }?.let { new ->
				val updated = source.copy(catalogueSource = new)
				sourceById[updated.sourceId] = updated
				sourceByName[updated.name] = updated
				return updated
			}
		}
		return source
	}

	private fun reload() {
		injektBridge.initialize()
		val records = readRecords()
		val successful = ArrayList<ExtInstalled>(records.size)
		val failures = ArrayList<ExtFailure>()
		val loadedSources = ArrayList<Manga>()
		sourceByName.clear(); sourceById.clear(); classLoaders.clear()

		for (record in records) {
			val file = File(directory, "${record.packageName}.apk")
			if (!file.exists() || !makeReadOnly(file)) {
				failures += ExtFailure(record.packageName, "Artifact unavailable")
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
				failures += ExtFailure(
					record.packageName,
					(result as? MangaResult.Error)?.message ?: "Load failed",
				)
			}
		}

		val suffixCount = loadedSources.groupingBy { it.pkgName to it.displayName }.eachCount()
		val normalized = loadedSources.map {
			it.copy(hasLanguageSuffix = (suffixCount[it.pkgName to it.displayName] ?: 0) > 1)
		}
		sourceByName.clear(); sourceById.clear()
		normalized.forEach {
			sourceByName[it.name] = it
			sourceById[it.sourceId] = it
		}
		_sources.value = normalized
		_installed.value = successful
		_failed.value = failures
	}

	private fun loadArtifact(file: File, artifact: ExtArtifact): MangaResult {
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

		val classNames = meta.getString("tachiyomi.extension.class")
			?: meta.getString("tachiyomi.extension.factory")
			?: return MangaResult.Error(pkg, "Missing source class")
		val isNsfw = (contentTypeFromManifest(meta) ?: artifact.contentType) == ContentType.HENTAI

		val loader = runCatching {
			val optDir = File(dexDir, pkg).also { it.mkdirs() }
			val timeDex = getDex(appContext)
			val dexPath = if (timeDex != null && timeDex.exists()) {
				"${file.absolutePath}${File.pathSeparator}${timeDex.absolutePath}"
			} else file.absolutePath
			DirectDexClassLoader(dexPath, optDir.absolutePath, null, appContext.classLoader)
		}.getOrElse { return MangaResult.Error(pkg, "ClassLoader error: ${it.message}", it) }

		return runCatching {
			val sources = loadSources(pkg, classNames, loader)
			if (sources.isEmpty()) error("No sources")
			classLoaders[pkg] = loader
			val rawAppName = getArchiveLabel(file, pkgInfo) ?: artifact.name
			val cleanAppName = rawAppName.removePrefix("Tachiyomi: ").removePrefix("Tachiyomi - ").trim()
				.ifBlank { sources.firstOrNull()?.name ?: artifact.name }
			MangaResult.Success(
				pkgName = pkg,
				appName = cleanAppName,
				versionCode = pkgInfo?.let(PackageInfoCompat::getLongVersionCode) ?: artifact.versionCode ?: 0L,
				versionName = verName,
				libVersion = libVer,
				lang = sources.mapNotNull { (it as? CatalogueSource)?.lang }.distinct()
					.let { if (it.size == 1) it.first() else "all" },
				isNsfw = isNsfw,
				sources = sources,
			)
		}.getOrElse {
			// Release the dex the loader mmap-ed so it does not leak until finalization.
			closeDexQuietly(loader)
			MangaResult.Error(pkg, "Source load error: ${it.message}", it)
		}
	}

	private fun closeDexQuietly(loader: ClassLoader) = runCatching {
		val pathList = ClassLoader::class.java.getDeclaredField("pathList")
			.apply { isAccessible = true }.get(loader)
		val elements = pathList?.javaClass?.getDeclaredField("dexElements")
			?.apply { isAccessible = true }?.get(pathList) as? Array<*> ?: return@runCatching
		for (elementRaw in elements) {
			val element = elementRaw ?: continue
			val dexFile = element.javaClass.getDeclaredField("dexFile")
				.apply { isAccessible = true }.get(element) as? DexFile ?: continue
			runCatching { dexFile.close() }
		}
	}

	private fun getArchiveLabel(file: File, pkgInfo: PackageInfo?): String? = runCatching {
		val appInfo = pkgInfo?.applicationInfo ?: return@runCatching null
		appInfo.sourceDir = file.absolutePath
		appInfo.publicSourceDir = file.absolutePath
		val nonLoc = appInfo.nonLocalizedLabel?.toString()?.trim()
		if (!nonLoc.isNullOrBlank()) return@runCatching nonLoc
		if (appInfo.labelRes != 0) {
			val res = appContext.packageManager.getResourcesForApplication(appInfo)
			val str = res.getString(appInfo.labelRes).trim()
			if (str.isNotBlank()) return@runCatching str
		}
		appInfo.loadLabel(appContext.packageManager).toString().trim()
	}.getOrNull()

	private fun extractIcon(file: File, pkg: String): String? = runCatching {
		val iconDir = File(directory, "icons").also { it.mkdirs() }
		val destIcon = File(iconDir, "$pkg.png")
		if (destIcon.exists() && destIcon.length() > 0) return destIcon.toURI().toString()

		val pkgInfo = getPackageInfo(file)
		val appInfo = pkgInfo?.applicationInfo
		if (appInfo != null) {
			appInfo.sourceDir = file.absolutePath
			appInfo.publicSourceDir = file.absolutePath
			val iconRes = appInfo.icon.takeIf { it != 0 } ?: 0
			if (iconRes != 0) {
				val iconDrawable = runCatching {
					val res = appContext.packageManager.getResourcesForApplication(appInfo)
					res.getDrawable(iconRes, null)
				}.getOrNull()
				if (iconDrawable != null) {
					val bitmap = if (iconDrawable is android.graphics.drawable.BitmapDrawable) {
						iconDrawable.bitmap
					} else {
						val w = iconDrawable.intrinsicWidth.takeIf { it > 0 } ?: 96
						val h = iconDrawable.intrinsicHeight.takeIf { it > 0 } ?: 96
						val bmp = createBitmap(w, h)
						val canvas = android.graphics.Canvas(bmp)
						iconDrawable.setBounds(0, 0, w, h)
						iconDrawable.draw(canvas)
						bmp
					}
					destIcon.outputStream().use { out ->
						bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
					}
					return destIcon.toURI().toString()
				}
			}
		}

		ZipFile(file).use { zip ->
			val entry = zip.entries().asSequence().firstOrNull {
				it.name.startsWith("res/") && (it.name.endsWith(".png") || it.name.endsWith(".webp"))
			} ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".png") }
			if (entry != null) {
				zip.getInputStream(entry).use { input ->
					destIcon.outputStream().use { output -> input.copyTo(output) }
				}
				return destIcon.toURI().toString()
			}
		}
		null
	}.getOrNull()

	private fun loadSources(pkg: String, names: String, loader: ClassLoader): List<Source> =
		names.split(';', ':', ',').map { it.trim() }.filter { it.isNotEmpty() }.map {
			if (it.startsWith('.')) pkg + it else it
		}.flatMap { cls ->
			when (val inst = loader.loadClass(cls).getDeclaredConstructor().newInstance()) {
				is Source -> listOf(inst)
				is SourceFactory -> inst.createSources()
				else -> error("Unknown source type: ${inst.javaClass.name}")
			}
		}

	private fun getPackageInfo(file: File): PackageInfo? {
		val direct = runCatching {
			@Suppress("DEPRECATION")
			appContext.packageManager.getPackageArchiveInfo(
				file.absolutePath, PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS,
			)
		}.getOrNull()
		if (direct != null || android.os.Build.VERSION.SDK_INT >= 26) return direct
		// On Android 5-7 the platform parser rejects extension APKs whose manifest declares a
		// newer minSdkVersion (ships minSdk 26 with 1.6 lib), so read the manifest ourselves.
		return ManifestResolver.parse(file)
	}

	private fun download(url: String, dest: File): Boolean = runCatching {
		val req = Request.Builder().url(url)
			.header(CommonHeaders.USER_AGENT, UserAgents.KOTATSU).get().build()
		client.newCall(req).execute().use { res ->
			if (!res.isSuccessful) return@use false
			val body = res.body
			body.byteStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
			true
		}
	}.getOrDefault(false)


	private fun readRecords(): List<ExtInstalled> {
		val text = metaFile.takeIf { it.exists() }?.readText().orEmpty()
		val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
		return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(
			ExtInstalled::fromJson,
		) }
	}

	private fun writeRecords(records: List<ExtInstalled>) {
		val arr = JSONArray(records.distinctBy { it.packageName }.map { it.toJson() })
		metaFile.writeText(arr.toString())
	}

	private fun readLibVersion(meta: Bundle, verName: String): Double? {
		val raw = runCatching { meta.get("tachiyomix.extensionLib") }.getOrNull()
		val num = when (raw) {
			is Number -> raw.toDouble().let {
				if (it > 1000000) java.lang.Float.intBitsToFloat(it.toInt()).toDouble() else it
			}
			is String -> raw.toDoubleOrNull()?.let {
				if (it > 1000000) java.lang.Float.intBitsToFloat(it.toInt()).toDouble() else it
			}
			else -> verName.substringBeforeLast('.').toDoubleOrNull()
		} ?: return null
		return when {
			abs(num - 1.4) < 0.01 -> 1.4
			abs(num - 1.6) < 0.01 -> 1.6
			else -> num
		}
	}

	companion object {
		private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

		@Volatile
		private var activeInstance = WeakReference<NativeExtManager>(null)

		fun getByName(name: String): Manga? = activeInstance.get()?.getSourceByName(name)
	}
}
