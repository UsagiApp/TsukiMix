@file:Suppress("unused")

package org.draken.tsukimix.core.parser.tachiyomi.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.draken.tsukimix.core.parser.external.model.Manga
import tsuki.model.Manga as TsukiManga
import tsuki.model.MangaChapter
import tsuki.model.MangaPage
import org.draken.tsukimix.core.parser.external.model.toManga as extToManga
import org.draken.tsukimix.core.parser.external.model.toMangaChapter as extToMangaChapter
import org.draken.tsukimix.core.parser.external.model.toMangaPage as extToMangaPage
import org.draken.tsukimix.core.parser.external.model.toSChapter as extToSChapter
import org.draken.tsukimix.core.parser.external.model.toSManga as extToSManga

typealias TachiyomiMangaSource = Manga

fun SManga.toManga(source: Manga, fallbackUrl: String? = null, fallbackTitle: String? = null): TsukiManga =
	extToManga(source, fallbackUrl, fallbackTitle)

fun TsukiManga.toSManga(): SManga = extToSManga()

fun SChapter.toMangaChapter(source: Manga, mangaTitle: String, fallbackIndex: Int = 0): MangaChapter =
	extToMangaChapter(source, mangaTitle, fallbackIndex)

fun MangaChapter.toSChapter(): SChapter = extToSChapter()

fun Page.toMangaPage(source: Manga, resolvedUrl: String): MangaPage =
	extToMangaPage(source, resolvedUrl)
