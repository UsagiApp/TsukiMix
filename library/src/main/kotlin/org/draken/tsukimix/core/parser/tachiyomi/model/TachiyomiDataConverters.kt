package org.draken.tsukimix.core.parser.tachiyomi.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import org.draken.tsukimix.core.parser.tachiyomi.chapter.ChapterRecognition
import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaPage
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import java.util.zip.CRC32

fun SManga.toManga(
	source: TachiyomiMangaSource,
	fallbackUrl: String? = null,
	fallbackTitle: String? = null,
): Manga {
	val safeUrl = safeUrl(fallbackUrl)
	val safeTitle = safeTitle(fallbackTitle ?: safeUrl)
	val publicUrl = runCatching {
		(source.catalogueSource as? HttpSource)?.getMangaUrl(this)
	}.getOrNull() ?: safeUrl
	return Manga(
		id = stableId(source.name, safeUrl.ifBlank { safeTitle }),
		title = safeTitle,
		altTitles = emptySet(),
		url = safeUrl,
		publicUrl = publicUrl,
		rating = RATING_UNKNOWN,
		contentRating = if (source.isNsfw) ContentRating.ADULT else null,
		coverUrl = runCatching { thumbnail_url }.getOrNull(),
		tags = safeGenres().mapTo(LinkedHashSet()) {
			MangaTag(title = it, key = it, source = source)
		},
		state = runCatching { status }.getOrDefault(SManga.UNKNOWN).toMangaState(),
		authors = setOfNotNull(
			runCatching { author }.getOrNull(),
			runCatching { artist }.getOrNull(),
		).filterTo(LinkedHashSet()) { it.isNotBlank() },
		largeCoverUrl = runCatching { thumbnail_url }.getOrNull(),
		description = runCatching { description }.getOrNull(),
		source = source,
	)
}

fun Manga.toSManga(): SManga = SManga.create().also {
	it.url = url
	it.title = title
	it.thumbnail_url = coverUrl
	it.author = authors.firstOrNull()
	it.artist = authors.drop(1).firstOrNull()
	it.description = description
	it.genre = tags.joinToString(", ") { tag -> tag.title }
	it.status = state.toSMangaStatus()
	it.initialized = true
}

fun SChapter.toMangaChapter(source: TachiyomiMangaSource, mangaTitle: String, fallbackIndex: Int = 0): MangaChapter {
	val safeUrl = runCatching { url }.getOrNull()?.takeIf { it.isNotBlank() }
		?: "$mangaTitle#$fallbackIndex"
	val safeName = runCatching { name }.getOrNull().orEmpty()
	val number = chapter_number.takeIf { it >= 0f }
		?: ChapterRecognition.parseChapterNumber(mangaTitle, safeName).toFloat().takeIf { it >= 0f }
		?: 0f
	return MangaChapter(
		id = stableId(source.name, safeUrl),
		title = safeName.takeIf { it.isNotBlank() },
		number = number,
		volume = 0,
		url = safeUrl,
		scanlator = runCatching { scanlator }.getOrNull(),
		uploadDate = runCatching { date_upload }.getOrDefault(0),
		branch = source.locale.takeIf { it.isNotBlank() },
		source = source,
	)
}

fun MangaChapter.toSChapter(): SChapter = SChapter.create().also {
	it.url = url
	it.name = title.orEmpty()
	it.chapter_number = number
	it.scanlator = scanlator
	it.date_upload = uploadDate
}

fun Page.toMangaPage(source: TachiyomiMangaSource, resolvedUrl: String): MangaPage = MangaPage(
	id = stableId(source.name, "$index:$resolvedUrl"),
	url = resolvedUrl,
	preview = null,
	source = source,
)

private fun Int.toMangaState(): MangaState? = when (this) {
	SManga.ONGOING -> MangaState.ONGOING
	SManga.COMPLETED -> MangaState.FINISHED
	SManga.LICENSED -> MangaState.RESTRICTED
	SManga.PUBLISHING_FINISHED -> MangaState.FINISHED
	SManga.CANCELLED -> MangaState.ABANDONED
	SManga.ON_HIATUS -> MangaState.PAUSED
	else -> null
}

private fun MangaState?.toSMangaStatus(): Int = when (this) {
	MangaState.ONGOING -> SManga.ONGOING
	MangaState.FINISHED -> SManga.COMPLETED
	MangaState.RESTRICTED -> SManga.LICENSED
	MangaState.ABANDONED -> SManga.CANCELLED
	MangaState.PAUSED -> SManga.ON_HIATUS
	else -> SManga.UNKNOWN
}

private fun SManga.safeUrl(fallback: String?): String {
	return runCatching { url }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback.orEmpty()
}

private fun SManga.safeTitle(fallback: String): String {
	return runCatching { title }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
}

private fun SManga.safeGenres(): List<String> {
	return runCatching { getGenres().orEmpty() }.getOrDefault(emptyList())
}

private fun stableId(vararg parts: String): Long {
	val crc = CRC32()
	parts.forEach {
		crc.update(it.toByteArray())
		crc.update(0)
	}
	return crc.value
}
