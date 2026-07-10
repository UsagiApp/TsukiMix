package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

/**
 * Binary-compatible source contract. Keep this aligned with Mihon's source-api: extension APKs
 * invoke these members through the shared classloader.
 */
interface Source {
	val id: Long
	val name: String
	val lang: String get() = ""
	val supportsLatest: Boolean
	fun getFilterList(): FilterList = FilterList()
	suspend fun getPopularManga(page: Int): MangasPage
	suspend fun getLatestUpdates(page: Int): MangasPage
	suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage
	suspend fun getMangaUpdate(
		manga: SManga,
		chapters: List<SChapter>,
		fetchDetails: Boolean,
		fetchChapters: Boolean,
	): SMangaUpdate
	suspend fun getPageList(chapter: SChapter): List<Page>

	@Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
	fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException("Stub! Have no idea!")

	@Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
	fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException("Stub! Have no idea!")

	@Deprecated("Use the suspend API instead", ReplaceWith("getPageList"))
	fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException("Stub! Have no idea!")
}
