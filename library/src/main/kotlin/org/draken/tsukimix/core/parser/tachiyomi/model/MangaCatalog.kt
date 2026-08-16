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
) {
	val isNsfw: Boolean get() = contentType == ContentType.HENTAI
}

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
		put("contentWarning", if (isNsfw) "CONTENT_WARNING_NSFW" else "CONTENT_WARNING_SAFE")
		put("contentType", contentType.name)
		put("isNsfw", isNsfw)
		put("nsfw", if (isNsfw) 1 else 0)
		put("sources", JSONArray(sources.map { s ->
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

	companion object {
		fun fromJson(obj: JSONObject): DirectTachiyomiInstalled? = runCatching {
			val pkg = obj.optString("packageName").takeIf { it.isNotBlank() } ?: return null
			val libVer = obj.optDouble("libVersion", 1.4)
			val rawType = obj.opt("contentWarning") ?: obj.opt("contentRating") ?: obj.opt("contentType") ?: obj.opt("isNsfw") ?: obj.opt("nsfw")
			val type = contentTypeFromCatalog(rawType, libVer)
			val srcArr = obj.optJSONArray("sources")
			val srcList = (0 until (srcArr?.length() ?: 0)).mapNotNull { i ->
				val s = srcArr?.optJSONObject(i) ?: return@mapNotNull null
				val id = s.optLong("id").takeIf { it != 0L } ?: return@mapNotNull null
				val sRaw = s.opt("contentWarning") ?: s.opt("contentRating") ?: s.opt("contentType") ?: s.opt("isNsfw") ?: s.opt("nsfw")
				val sType = if (sRaw != null) contentTypeFromCatalog(sRaw, libVer, type) else type
				TachiyomiCatalogSource(id, s.optString("name", pkg), s.optString("language", "all"), s.optString("homeUrl").takeIf { it.isNotBlank() }, sType)
			}
			DirectTachiyomiInstalled(pkg, obj.optString("name", pkg), obj.optString("repositoryUrl"), obj.optString("jarUrl").takeIf { it.isNotBlank() }, obj.optString("apkUrl").takeIf { it.isNotBlank() }, obj.optString("iconUrl").takeIf { it.isNotBlank() }, obj.optLong("versionCode"), obj.optString("versionName", "0.0.0"), libVer, type, srcList)
		}.getOrNull()
	}
}

data class DirectTachiyomiFailure(
	val packageName: String,
	val message: String,
)

fun contentTypeFromCatalog(raw: Any?, lib: Double? = null, fallback: ContentType = ContentType.MANGA): ContentType {
	if (raw == null) return fallback
	if (raw is Boolean) return if (raw) ContentType.HENTAI else ContentType.MANGA
	if (raw is Number) return if (raw.toInt() > 0) ContentType.HENTAI else ContentType.MANGA
	val v = raw.toString().trim().uppercase()
	if (v in setOf(
		"HENTAI", "NSFW", "MIXED", "ADULT", "MATURE", "18+", "R18",
		"CONTENT_WARNING_NSFW", "CONTENT_WARNING_MIXED", "CONTENT_WARNING_ADULT",
		"1", "2", "TRUE"
	)) return ContentType.HENTAI
	if (v in setOf("MANGA", "SAFE", "CONTENT_WARNING_SAFE", "0", "FALSE")) return ContentType.MANGA
	val n = v.toIntOrNull()
	if (n != null) return if (n > 0) ContentType.HENTAI else ContentType.MANGA
	return fallback
}

internal fun contentTypeFromManifest(meta: Bundle): ContentType? {
	val cw = meta.getInt("tachiyomix.contentWarning", -1)
	if (cw == 0) return ContentType.MANGA
	if (cw > 0) return ContentType.HENTAI
	val cwStr = meta.getString("tachiyomix.contentWarning")
	if (!cwStr.isNullOrBlank()) {
		val parsed = contentTypeFromCatalog(cwStr, fallback = ContentType.MANGA)
		if (parsed != ContentType.MANGA || cwStr.equals("0", true) || cwStr.equals("SAFE", true)) return parsed
	}
	if (meta.containsKey("tachiyomi.extension.nsfw")) {
		val raw = meta.get("tachiyomi.extension.nsfw")
		return contentTypeFromCatalog(raw, fallback = ContentType.MANGA)
	}
	return null
}
