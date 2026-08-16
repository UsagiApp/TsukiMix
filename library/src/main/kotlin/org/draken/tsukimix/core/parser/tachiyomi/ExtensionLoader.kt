@file:Suppress("unused", "QueryPermissionsNeeded")

package org.draken.tsukimix.core.parser.tachiyomi

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.draken.tsukimix.core.parser.tachiyomi.model.TachiyomiExtensionInfo
import org.draken.tsukimix.core.parser.tachiyomi.model.MangaResult

class ExtensionLoader(
	private val injektBridge: () -> ExtensionBridge,
) {
	suspend fun loadExtensions(context: Context): List<MangaResult> = withContext(Dispatchers.IO) {
		injektBridge().initialize()
		getInstalledPackages(context.packageManager)
			.filter(::isPackageAnExtension)
			.map { pkgInfo -> loadExtension(context, pkgInfo) }
	}

	suspend fun loadExtension(context: Context, packageName: String): MangaResult? = withContext(Dispatchers.IO) {
		injektBridge().initialize()
		val pkgInfo = try {
			context.packageManager.getPackageInfo(packageName, packageFlags)
		} catch (_: PackageManager.NameNotFoundException) {
			null
		} ?: return@withContext null
		if (isPackageAnExtension(pkgInfo)) loadExtension(context, pkgInfo) else null
	}

	fun getInstalledExtensions(context: Context): List<TachiyomiExtensionInfo> {
		val pkgManager = context.packageManager
		return getInstalledPackages(pkgManager)
			.filter(::isPackageAnExtension)
			.mapNotNull { extractExtensionInfo(it, pkgManager) }
	}

	private fun extractExtensionInfo(pkgInfo: PackageInfo, pkgManager: PackageManager): TachiyomiExtensionInfo? {
		val appInfo = pkgInfo.applicationInfo ?: return null
		val metaData = appInfo.metaData ?: return null
		val versionName = pkgInfo.versionName ?: return null
		val libVersion = readLibVersion(metaData, versionName) ?: return null
		val sourceClassName = metaData.getString(METADATA_SOURCE_CLASS)
			?: metaData.getString(METADATA_SOURCE_FACTORY)
			?: return null
		val appName = runCatching { appInfo.loadLabel(pkgManager).toString() }
			.getOrDefault(pkgInfo.packageName.substringAfterLast('.'))
		return TachiyomiExtensionInfo(
			pkgName = pkgInfo.packageName,
			appName = appName,
			versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
			versionName = versionName,
			libVersion = libVersion,
			lang = extractLanguage(pkgInfo.packageName),
			isNsfw = readNsfwFlag(metaData),
			sourceClassName = sourceClassName,
			apkPath = appInfo.sourceDir ?: return null,
		)
	}

	private fun loadExtension(context: Context, pkgInfo: PackageInfo): MangaResult {
		val appInfo = pkgInfo.applicationInfo
			?: return buildLoggedError(pkgInfo.packageName, "No ApplicationInfo")
		val metaData = appInfo.metaData
			?: return buildLoggedError(pkgInfo.packageName, "No manifest metadata")
		val versionName = pkgInfo.versionName
			?: return buildLoggedError(pkgInfo.packageName, "No version name")
		val libVersion = readLibVersion(metaData, versionName)
			?: return buildLoggedError(pkgInfo.packageName, "Invalid lib version: $versionName")
		if (libVersion !in LIB_VERSION_MIN..LIB_VERSION_MAX) {
			return buildLoggedError(pkgInfo.packageName, "Incompatible lib version: $libVersion")
		}
		val sourceClassNames = metaData.getString(METADATA_SOURCE_CLASS)
			?: metaData.getString(METADATA_SOURCE_FACTORY)
			?: return buildLoggedError(pkgInfo.packageName, "No source class metadata")
		val appName = runCatching { appInfo.loadLabel(context.packageManager).toString() }
			.getOrDefault(pkgInfo.packageName)
		val signatures = getSignatures(pkgInfo)
		if (signatures.isEmpty()) {
			return buildLoggedError(pkgInfo.packageName, "Extension APK is unsigned")
		}
		val classLoader = try {
			ChildFirstPathClassLoader(appInfo.sourceDir, appInfo.nativeLibraryDir, context.classLoader)
		} catch (t: Throwable) {
			return buildLoggedError(pkgInfo.packageName, "Failed to create class loader", t)
		}
		val sources = try {
			loadSources(pkgInfo.packageName, sourceClassNames, classLoader)
		} catch (t: Throwable) {
			return buildLoggedError(pkgInfo.packageName, "Failed to load sources", t)
		}
		if (sources.isEmpty()) {
			return buildLoggedError(pkgInfo.packageName, "No sources loaded")
		}
		return MangaResult.Success(
			pkgName = pkgInfo.packageName,
			appName = appName,
			versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
			versionName = versionName,
			libVersion = libVersion,
			lang = sources.mapNotNull { (it as? CatalogueSource)?.lang }.toSet().let {
				when (it.size) {
					0 -> ""
					1 -> it.first()
					else -> "all"
				}
			},
			isNsfw = readNsfwFlag(metaData),
			sources = sources,
		)
	}

	private fun loadSources(pkgName: String, sourceClassNames: String, classLoader: ClassLoader): List<Source> {
		return sourceClassNames
			.split(';', ':', ',')
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.map { if (it.startsWith('.')) pkgName + it else it }
			.flatMap { className ->
				when (val instance = classLoader.loadClass(className).getDeclaredConstructor().newInstance()) {
					is Source -> listOf(instance)
					is SourceFactory -> instance.createSources()
					else -> error("Unknown source class type: ${instance.javaClass.name}")
				}
			}
	}

	private fun buildLoggedError(pkgName: String, message: String, exception: Throwable? = null): MangaResult.Error {
		return MangaResult.Error(pkgName, message, exception)
	}

	private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
		val metaData = pkgInfo.applicationInfo?.metaData
		val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
		val hasSource = metaData?.containsKey(METADATA_SOURCE_CLASS) == true ||
			metaData?.containsKey(METADATA_SOURCE_FACTORY) == true
		return hasFeature || hasSource
	}

	@Suppress("DEPRECATION")
	private fun readLibVersion(metaData: Bundle, versionName: String): Double? {
		val raw = try {
			metaData.get(METADATA_EXTENSION_LIB)
		} catch (_: Throwable) {
			null
		}
		val libVer = when (raw) {
			is Double -> raw
			is Float -> raw.toString().toDoubleOrNull() ?: raw.toDouble()
			is Number -> raw.toDouble()
			is String -> raw.toDoubleOrNull()
			else -> null
		}
		return libVer?.takeUnless { it == 0.0 }
			?: versionName.substringBeforeLast('.').toDoubleOrNull()
			?: versionName.split('.').take(2).joinToString(".").toDoubleOrNull()
	}

	private fun readNsfwFlag(metaData: Bundle): Boolean {
		if (metaData.getInt(METADATA_CONTENT_WARNING, 0) > 0) return true
		val cwStr = metaData.getString(METADATA_CONTENT_WARNING)
		if (!cwStr.isNullOrBlank() && org.draken.tsukimix.core.parser.tachiyomi.model.contentTypeFromCatalog(cwStr) == tsuki.model.ContentType.HENTAI) return true
		if (!metaData.containsKey(METADATA_NSFW)) return false
		val raw = metaData.get(METADATA_NSFW)
		return org.draken.tsukimix.core.parser.tachiyomi.model.contentTypeFromCatalog(raw) == tsuki.model.ContentType.HENTAI
	}

	private fun extractLanguage(packageName: String): String {
		val parts = packageName.split('.')
		val extIndex = parts.indexOfLast { it == "extension" }
		return parts.getOrNull(extIndex + 1)?.takeIf { it.isNotBlank() } ?: parts.lastOrNull() ?: "all"
	}

	@Suppress("DEPRECATION")
	private fun getSignatures(pkgInfo: PackageInfo): List<String> {
		val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			val signingInfo = pkgInfo.signingInfo ?: return emptyList()
			if (signingInfo.hasMultipleSigners()) {
				signingInfo.apkContentsSigners
			} else {
				signingInfo.signingCertificateHistory
			}
		} else {
			pkgInfo.signatures
		}
		return signatures.orEmpty().map { Hash.sha256(it.toByteArray()) }
	}

	private fun getInstalledPackages(packageManager: PackageManager): List<PackageInfo> {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(packageFlags.toLong()))
		} else {
			packageManager.getInstalledPackages(packageFlags)
		}
	}

	private companion object {
		const val EXTENSION_FEATURE = "tachiyomi.extension"
		const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
		const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
		const val METADATA_NSFW = "tachiyomi.extension.nsfw"
		const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
		const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"
		const val LIB_VERSION_MIN = 1.4
		const val LIB_VERSION_MAX = 1.6
		val packageFlags = PackageManager.GET_META_DATA or
			PackageManager.GET_CONFIGURATIONS or
			@Suppress("DEPRECATION") PackageManager.GET_SIGNATURES or
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
	}
}
