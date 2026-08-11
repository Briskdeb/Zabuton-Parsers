package org.koitharu.kotatsu.parsers.site.galleryadults.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded

/** Local Futon catalogue source. Configure domain to your HTTPS Tailscale host. */
@MangaSourceParser("FUTON", "Futon", "en", type = ContentType.HENTAI)
internal class Futon(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.FUTON, 24) {

	override val configKeyDomain = ConfigKey.Domain("futon.tailnet.ts.net")
	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
		isAuthorSearchSupported = true,
		isTagsExclusionSupported = true,
		isSearchWithFiltersSupported = true,
	)
	override val availableSortOrders: Set<SortOrder> = setOf(
		SortOrder.ADDED,
		SortOrder.ADDED_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.POPULARITY,
	)

	private val apiBase get() = "https://$domain"
	private suspend fun api(path: String): JSONObject = webClient.httpGet("$apiBase$path").parseJson()

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val tags = api("/v1/tags").getJSONArray("items").toTags()
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val sort = when (order) {
			SortOrder.ADDED_ASC, SortOrder.UPDATED_ASC -> "added_asc"
			SortOrder.ALPHABETICAL -> "title"
			SortOrder.ALPHABETICAL_DESC -> "title_desc"
			SortOrder.POPULARITY -> "popular"
			else -> "added"
		}
		val query = mutableListOf("page=$page", "per_page=24", "sort=$sort", "q=${filter.query.orEmpty().urlEncoded()}")
		filter.tags.forEach { query += "include_tag=${it.key.urlEncoded()}" }
		filter.tagsExclude.forEach { query += "exclude_tag=${it.key.urlEncoded()}" }
		filter.author?.takeIf { it.isNotBlank() }?.let { query += "author=${it.urlEncoded()}" }
		return api("/v1/search?${query.joinToString("&")}").getJSONArray("items").toMangaList()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val item = api("/v1/items/${manga.url.urlEncoded()}")
		val tags = item.getJSONArray("tags").toTags()
		val authors = item.getJSONArray("authors").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
		return manga.copy(
			title = item.getString("title"),
			altTitles = setOfNotNull(item.optString("alt_title").ifBlank { null }),
			tags = tags,
			authors = authors,
			description = item.optString("description").ifBlank { null },
			chapters = listOf(MangaChapter(manga.id, manga.title, 0f, 0, manga.url, null, 0, "Local", source)),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = api("/v1/items/${chapter.url.urlEncoded()}/pages").getJSONArray("items")
		return (0 until pages.length()).map { index ->
			val item = pages.getJSONObject(index)
			MangaPage(generateUid(item.getString("url")), item.getString("url"), null, source)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getRelatedManga(seed: Manga): List<Manga> =
		api("/v1/items/${seed.url.urlEncoded()}/recommendations").getJSONArray("items").toMangaList()

	private fun JSONArray.toTags(): Set<MangaTag> = (0 until length()).map { index ->
		val item = get(index)
		when (item) {
			is JSONObject -> MangaTag(item.getString("title"), item.getString("id"), source)
			else -> MangaTag(item.toString(), item.toString(), source)
		}
	}.toSet()

	private fun JSONArray.toMangaList(): List<Manga> = (0 until length()).map { index ->
		val item = getJSONObject(index)
		val id = item.getString("id")
		Manga(
			id = generateUid(id),
			title = item.getString("title"),
			altTitles = setOfNotNull(item.optString("alt_title").ifBlank { null }),
			url = id,
			publicUrl = item.optString("source_url").ifBlank { "$apiBase/v1/items/$id" },
			rating = 0f,
			contentRating = ContentRating.ADULT,
			coverUrl = item.optString("cover_url").ifBlank { null },
			tags = item.optJSONArray("tags")?.toTags().orEmpty(),
			state = null,
			authors = item.optJSONArray("authors")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty(),
			source = source,
		)
	}
}
