package org.koitharu.kotatsu.parsers.site.galleryadults.en

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANHWAREAD", "ManhwaRead", "en", type = ContentType.HENTAI)
internal class ManhwaRead(context: MangaLoaderContext) :
	GalleryAdultsParser(context, MangaParserSource.MANHWAREAD, "manhwaread.com", 24) {

	override fun getRequestHeaders(): Headers {
		return super.getRequestHeaders().newBuilder()
			.add("referer", "https://$domain/")
			.build()
	}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isAuthorSearchSupported = true,
			isYearSupported = true,
			isTagsExclusionSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = getOrCreateTagMap().values.toSet(),
			availableContentTypes = setOf(
				ContentType.MANHWA,
			),
		)
	}

	private var tagCache: Map<String, MangaTag>? = null
	private val tagMutex = Mutex()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = tagMutex.withLock {
		tagCache?.let { return@withLock it }

		val tags = mutableSetOf<MangaTag>()
		var offset = 0

		val mainPageDoc = webClient.httpGet("https://$domain/?s=").parseHtml()
		val tagsList = mainPageDoc.select("ul.tags-list[data-tax=manga_tag]").firstOrNull()
		val totalTags = tagsList?.attr("data-total")?.toIntOrNull() ?: 710

		while (offset < totalTags) {
			val url = "https://$domain/wp-admin/admin-ajax.php?" + buildString {
				append("action=search_manga_terms")
				append("&search=")
				append("&taxonomy=manga_tag")
				append("&offset=$offset")
				append("&extra_fields=")
				append("&hide_empty=1")
			}

			try {
				val response = webClient.httpGet(url).parseJson()
				val results = response.optJSONArray("results")

				if (results == null || results.length() == 0) {
					break
				}
				for (i in 0 until results.length()) {
					val item = results.getJSONObject(i)
					val id = item.getInt("id")
					val text = item.getString("text")

					tags.add(
						MangaTag(
							title = text.toTitleCase(),
							key = id.toString(),
							source = source,
						),
					)
				}

				offset += results.length()

			} catch (e: Exception) {
				offset += 40
			}
		}
		val tagMap = tags.associateBy { it.title }
		tagCache = tagMap
		tagMap
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.POPULARITY_YEAR,
		SortOrder.POPULARITY_FAVORITES,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
	)

	override val selectGallery = ".manga-grid .manga-item"
	override val selectGalleryLink = "a.manga-item__link"
	override val selectGalleryTitle = "a.manga-item__link"
	override val selectTitle = ".manga-titles h1"
	override val selectTag = "a[rel=tag][href^=/tag/]"
	override val selectAuthor = "a[href^=/author/]"
	override val selectLanguageChapter = ""
	override val selectUrlChapter = ""

	private val selectAltTitle = ".manga-titles h2"
	private val selectArtist = "a[href^=/artist/]"
	private val selectStatus = "span.manga-status"
	private val selectDetailsRating = ".rating__current"
	private val selectDescription = ".manga-desc__content"

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val url = buildString {
			append("https://$domain")

			val isSearch = filter.query != null ||
				filter.tags.isNotEmpty() ||
				filter.types.isNotEmpty() ||
				filter.year != YEAR_UNKNOWN ||
				filter.tagsExclude.isNotEmpty() ||
				!filter.author.isNullOrEmpty()

			when {
				isSearch -> {
					if (page > 1) {
						append("/page/$page")
					}
					append("/?")

					val queries = mutableListOf<String>()

					queries.add("s=${filter.query?.trim()?.urlEncoded() ?: ""}")
					queries.add("title-type=contains")
					queries.add("search-mode=AND")
					queries.add("release-type=in")
					queries.add("release=${if (filter.year != YEAR_UNKNOWN) filter.year else ""}")

					if (filter.author != null && filter.author.isNotEmpty()) {
						val authorId = getAuthorId(filter.author)
						if (authorId != null) {
							queries.add("artists[]=$authorId")
						}
					}

					filter.tags.forEach {
						queries.add("including[]=${it.key}")
					}

					filter.tagsExclude.forEach {
						queries.add("excluding[]=${it.key}")
					}

					queries.add("pages=0-1000")

					when (order) {
						SortOrder.UPDATED -> queries.add("sortby=new")
						SortOrder.UPDATED_ASC -> {
							queries.add("sortby=new")
							queries.add("order=asc")
						}

						SortOrder.POPULARITY -> queries.add("sortby=all_top")
						SortOrder.POPULARITY_ASC -> {
							queries.add("sortby=all_top")
							queries.add("order=asc")
						}

						SortOrder.POPULARITY_TODAY -> queries.add("sortby=daily_top")
						SortOrder.POPULARITY_WEEK -> queries.add("sortby=weekly_top")
						SortOrder.POPULARITY_MONTH -> queries.add("sortby=monthly_top")
						SortOrder.POPULARITY_YEAR -> queries.add("sortby=yearly_top")
						SortOrder.POPULARITY_FAVORITES -> queries.add("sortby=favorite")

						SortOrder.ALPHABETICAL -> queries.add("sortby=alphabet")
						SortOrder.ALPHABETICAL_DESC -> {
							queries.add("sortby=alphabet")
							queries.add("order=desc")
						}

						SortOrder.RATING -> queries.add("sortby=rating")
						SortOrder.RATING_ASC -> {
							queries.add("sortby=rating")
							queries.add("order=asc")
						}

						else -> {}
					}

					append(queries.joinToString("&"))
				}

				else -> {
					if (page > 1) {
						append("/page/$page")
					}
					append("/?s=")

					when (order) {
						SortOrder.UPDATED -> append("&sortby=new")
						SortOrder.UPDATED_ASC -> append("&sortby=new&order=asc")
						SortOrder.POPULARITY -> append("&sortby=all_top")
						SortOrder.POPULARITY_ASC -> append("&sortby=all_top&order=asc")
						SortOrder.POPULARITY_TODAY -> append("&sortby=daily_top")
						SortOrder.POPULARITY_WEEK -> append("&sortby=weekly_top")
						SortOrder.POPULARITY_MONTH -> append("&sortby=monthly_top")
						SortOrder.POPULARITY_YEAR -> append("&sortby=yearly_top")
						SortOrder.POPULARITY_FAVORITES -> append("&sortby=favorite")
						SortOrder.ALPHABETICAL -> append("&sortby=alphabet")
						SortOrder.ALPHABETICAL_DESC -> append("&sortby=alphabet&order=desc")
						SortOrder.RATING -> append("&sortby=rating")
						SortOrder.RATING_ASC -> append("&sortby=rating&order=asc")
						else -> {}
					}
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private suspend fun getAuthorId(authorName: String): String? {
		val jsonResponse = webClient.httpGet(
			"/wp-admin/admin-ajax.php?action=search_manga_terms&search=${authorName.urlEncoded()}&taxonomy=manga_artist"
				.toAbsoluteUrl(domain),
		).parseJson()

		val results = jsonResponse.get("results") as JSONArray
		for (i in 0 until results.length()) {
			val item = results.get(i) as JSONObject
			if (authorName.equals(item.get("text") as String, ignoreCase = true)) {
				return item.getString("id")
			}
		}
		return null
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(selectGallery).map { div ->
			val href = div.selectFirstOrThrow(selectGalleryLink).attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = div.select(selectGalleryTitle).text().cleanupTitle(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = div.selectFirst(selectGalleryImg)?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val tagMap = getOrCreateTagMap()

		val tags = doc.select(selectTag).mapNotNullToSet { link ->
			val tagName = link.select("span:nth-child(2)").text()
			tagMap[tagName.toTitleCase()]
		}

		val authors = doc.select(selectAuthor).mapNotNullToSet { link ->
			link.select("span:nth-child(2)").text().ifBlank { null }
		}

		val artists = doc.select(selectArtist).mapNotNullToSet { link ->
			link.select("span:nth-child(2)").text().ifBlank { null }
		}

		val statusElement = doc.selectFirst(selectStatus)
		val status = when (statusElement?.attr("data-status")) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			else -> null
		}

		val chaptersList = doc.select("#chaptersList .chapter-item")
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
		val chapters = chaptersList.mapIndexed { index, link ->
			val href = link.attrAsRelativeUrl("href")
			val name = link.selectFirst(".chapter-item__name")?.text() ?: "Chapter ${index + 1}"
			val dateStr = link.selectFirst(".chapter-item__date")?.text()
			val chapterNumber = name.removePrefix("Chapter ").trim().toFloatOrNull() ?: (index + 1).toFloat()

			MangaChapter(
				id = generateUid(href),
				title = name,
				number = chapterNumber,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(dateStr),
				branch = "English",
				source = source,
			)
		}

		val description = doc.selectFirst(selectDescription)?.text()

		return manga.copy(
			title = doc.select(selectTitle).text().cleanupTitle(),
			altTitles = doc.selectFirst(selectAltTitle)?.text()?.let { setOf(it) } ?: emptySet(),
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			largeCoverUrl = doc.selectFirst("#mangaSummary img")?.src(),
			tags = tags,
			rating = doc.selectFirst(selectDetailsRating)?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
			authors = authors + artists,
			description = description,
			state = status,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val scriptText = doc.select("script")
			.find { it.data().contains("var chapterData") }
			?.data() ?: return emptyList()

		val jsonStr = scriptText
			.substringAfter("var chapterData = ")
			.substringBefore(";")
			.trim()

		val json = JSONObject(jsonStr)
		val base = json.getString("base")
		val encodedData = json.getString("data")
		val decoded = String(Base64.getDecoder().decode(encodedData))
		val pagesData = JSONArray(decoded)

		return (0 until pagesData.length()).map { i ->
			val pageObj = pagesData.getJSONObject(i)
			val src = pageObj.getString("src")
			val url = "$base/$src"
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		return page.url
	}
}
