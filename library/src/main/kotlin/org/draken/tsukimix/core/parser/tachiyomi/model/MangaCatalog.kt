@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi.model

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import tsuki.model.ContentType

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

	fun toJson() = JSONObject().apply {
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
		put("sources", JSONArray(sources.map { s ->
			JSONObject().apply {
				put("id", s.id)
				put("name", s.name)
				put("language", s.language)
				put("homeUrl", s.homeUrl)
				put("contentType", s.contentType.name)
			}
		}))
	}

	companion object {
		fun fromJson(obj: JSONObject): DirectTachiyomiInstalled? = runCatching {
			val pkg = obj.optString("packageName").takeIf { it.isNotBlank() } ?: return null
			val libVer = obj.optDouble("libVersion", 1.4)
			val type = contentTypeFromCatalog(obj.optString("contentType").ifBlank { if (obj.optBoolean("isNsfw")) "HENTAI" else "MANGA" }, libVer)
			val srcArr = obj.optJSONArray("sources")
			val srcList = (0 until (srcArr?.length() ?: 0)).mapNotNull { i ->
				val s = srcArr?.optJSONObject(i) ?: return@mapNotNull null
				val id = s.optLong("id").takeIf { it != 0L } ?: return@mapNotNull null
				TachiyomiCatalogSource(id, s.optString("name", pkg), s.optString("language", "all"), s.optString("homeUrl").takeIf { it.isNotBlank() }, contentTypeFromCatalog(s.optString("contentType"), libVer, type))
			}
			DirectTachiyomiInstalled(pkg, obj.optString("name", pkg), obj.optString("repositoryUrl"), obj.optString("jarUrl").takeIf { it.isNotBlank() }, obj.optString("apkUrl").takeIf { it.isNotBlank() }, obj.optString("iconUrl").takeIf { it.isNotBlank() }, obj.optLong("versionCode"), obj.optString("versionName", "0.0.0"), libVer, type, srcList)
		}.getOrNull()
	}
}

data class DirectTachiyomiFailure(
	val packageName: String,
	val message: String,
)

internal fun contentTypeFromCatalog(raw: String?, lib: Double?, fallback: ContentType = ContentType.MANGA): ContentType {
	val v = raw?.trim()?.uppercase() ?: return fallback
	if (v in setOf("HENTAI", "NSFW", "MIXED", "CONTENT_WARNING_NSFW", "CONTENT_WARNING_MIXED")) return ContentType.HENTAI
	if (v in setOf("MANGA", "SAFE", "CONTENT_WARNING_SAFE")) return ContentType.MANGA
	val n = v.toIntOrNull() ?: return fallback
	return if ((lib ?: 1.6) >= 1.6) (if (n == 1) ContentType.MANGA else ContentType.HENTAI) else (if (n == 0) ContentType.MANGA else ContentType.HENTAI)
}

internal fun contentTypeFromManifest(meta: Bundle): ContentType? = when {
	meta.getInt("tachiyomix.contentWarning", -1) == 0 -> ContentType.MANGA
	meta.getInt("tachiyomix.contentWarning", -1) > 0 || meta.getInt("tachiyomi.extension.nsfw", 0) == 1 -> ContentType.HENTAI
	else -> null
}
