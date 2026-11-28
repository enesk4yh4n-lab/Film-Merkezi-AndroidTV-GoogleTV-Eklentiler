package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Catalogue contracts from the numbered distribution packages. */
internal class TrackedCatalog(private val site: SiteSpec) {
    companion object {
        val sites = setOf("p008", "p012", "p016", "p017", "p018", "p019")
        private val episodePath = Regex("""^/dizi/([a-z0-9-]+)/sezon-(\d+)/bolum-(\d+)/?$""")
        private val gomEpisode = Regex("""^/([a-z0-9-]+)-(\d+)-sezon-(\d+)-bolum/?$""")
        private val gomAnime = Regex("""^/anime/([a-z0-9-]+)/sezon-(\d+)/bolum-(\d+)/?$""")
        private val avsarEpisode = Regex("""^/watch/([a-z0-9-]+)/s(\d+)e(\d+)/?$""")
        private val trEpisode = Regex("""^/([a-z0-9-]+?)(?:-(\d+)-sezon)?-(\d+)-bolum(?:-[a-z0-9-]+)?/?$""")
        private val yearPattern = Regex("""\b((?:18|19|20)\d{2})\b""")
    }

    private fun path(url: String) = url.toHttpUrl().encodedPath.trimEnd('/')
    private fun year(value: String) = yearPattern.find(value)?.value?.toIntOrNull()?.takeIf { it >= 1888 }
    private fun rating(value: String) = value.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }
    private fun text(node: Element?, selector: String) = HtmlData.text(node, selector)
    private fun jsonNames(value: Any?) = when (value) {
        is JSONArray -> value.values().filterIsInstance<String>()
        is String -> listOf(value)
        else -> emptyList()
    }.map(TextKey::clean).filter(String::isNotBlank)

    fun list(body: String, url: String): CatalogPage {
        HtmlData.verify(body)
        if (site.id == "p019" && path(url) == "/api/search") return search19(body, url)
        val doc = HtmlData.document(body, url)
        val scope = doc.selectFirst(if (site.id == "p012") "#content" else "main") ?: doc
        if (site.id == "p012") return listTr(doc, scope, url)
        val rows = scope.select(site.cards).mapNotNull { node ->
            val link = when (site.id) {
                "p019" -> node.parent()?.takeIf { it.normalName() == "a" }
                "p016" -> node.selectFirst("a.nf2-poster[href]")
                "p017" -> node.selectFirst(".movie-title a[href], a.video-icon[href]")
                else -> node.takeIf { it.normalName() == "a" }
            } ?: return@mapNotNull null
            val address = site.catalogUrl(link.attr("href"), url) ?: return@mapNotNull null
            if (address.toHttpUrl().query != null || !site.contentPattern.matches(address.toHttpUrl().encodedPath)) return@mapNotNull null
            val poster = HtmlData.image(node, url)
            val kind = when (site.id) {
                "p008" -> if (path(address).startsWith("/film/")) ContentKind.MOVIE else ContentKind.SERIES
                "p017" -> {
                    if (avsarEpisode.matches(path(address))) return@mapNotNull null
                    when (text(node, ".movie-badge")) {
                        "Film" -> ContentKind.MOVIE
                        "Dizi" -> ContentKind.SERIES
                        else -> when {
                            "/series/" in poster -> ContentKind.SERIES
                            "/movies/" in poster -> ContentKind.MOVIE
                            else -> return@mapNotNull null
                        }
                    }
                }
                else -> site.kind(address).takeUnless { it == ContentKind.EPISODE } ?: return@mapNotNull null
            }
            val title = TextKey.title(text(node, site.title).ifBlank {
                link.attr("aria-label").ifBlank { node.selectFirst("img")?.attr("alt").orEmpty() }
            })
            if (title.isBlank()) return@mapNotNull null
            val released = when (site.id) {
                "p008" -> year(text(node, ".year,.release-year,time,p.text-content-muted"))
                "p019" -> year(node.ownText().ifBlank { node.children().firstOrNull()?.text()?.take(32).orEmpty() })
                else -> year(text(node, site.year))
            }
            val original = text(node, ".movie-original-title,.nf2-card-original")
            val genres = node.select(".nf2-card-facts__genres a,.nf2-card-facts__genres span,.movie-category-text").map { it.text() }.filter(String::isNotBlank)
            val score = if (site.id == "p008") ".badge-rating" else site.imdb
            Card(ContentRef(site.id, address, kind), title, poster, released, rating(text(node, score)),
                text(node, site.language), originalTitle = original, genres = genres)
        }
        return CatalogPage((rows + collectionCards(scope, url)).distinctBy { it.ref.id }.take(80), next(doc, url))
    }

    fun collectionCards(scope: Element, url: String): List<Card> {
        if (site.id != "p016") return emptyList()
        return scope.select("a[href*='/secki/']").mapNotNull { link ->
            val address = site.catalogUrl(link.attr("href"), url) ?: return@mapNotNull null
            if (!path(address).matches(Regex("""^/secki/[a-z0-9-]+$"""))) return@mapNotNull null
            val title = text(link, "h2,h3,.nf-cur-directory-card__title").ifBlank { link.attr("aria-label") }
                .ifBlank { link.attr("title") }.ifBlank { link.text() }
            if (title.isBlank()) return@mapNotNull null
            Card(ContentRef(site.id, address, ContentKind.COLLECTION), title, HtmlData.image(link, url))
        }.distinctBy { it.ref.id }
    }

    private fun listTr(doc: Document, scope: Element, url: String): CatalogPage {
        val index = doc.select("a[href*='/diziler/']").mapNotNull { link ->
            val address = site.catalogUrl(link.attr("href"), url) ?: return@mapNotNull null
            if (!path(address).matches(Regex("""^/diziler/[a-z0-9-]+$"""))) return@mapNotNull null
            val label = seriesTitle(link.attr("title").ifBlank { link.text() })
            (TextKey.fold(label) to address).takeIf { label.isNotBlank() }
        }.groupBy({ it.first }, { it.second })
        val rows = scope.select(site.cards).mapNotNull { box ->
            val link = box.selectFirst("a[href]") ?: return@mapNotNull null
            val title = seriesTitle(text(box, site.title))
            if (title.isBlank()) return@mapNotNull null
            val address = site.catalogUrl(link.attr("href"), url) ?: return@mapNotNull null
            val stem = trEpisode.matchEntire(path(address))?.groupValues?.get(1)
            val parent = if (path(address).startsWith("/diziler/")) address else {
                val choices = index[TextKey.fold(title)].orEmpty().distinctBy(::path)
                choices.singleOrNull() ?: index.values.flatten().filter { path(it) == "/diziler/$stem" }.distinctBy(::path).singleOrNull()
            } ?: return@mapNotNull null
            if (!path(parent).matches(Regex("""^/diziler/[a-z0-9-]+$"""))) return@mapNotNull null
            Card(ContentRef(site.id, parent, ContentKind.SERIES), title, HtmlData.image(box, url))
        }
        return CatalogPage(rows.distinctBy { it.ref.id }.take(80), next(doc, url))
    }

    private fun seriesTitle(value: String) = TextKey.title(
        value.replace(Regex("""\s*\|\s*Trdiziizle\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?:\s+(?:son bölüm|izle|hd))+$""", RegexOption.IGNORE_CASE), "")
    )

    private fun search19(body: String, url: String): CatalogPage {
        val data = JSONObject(body).optJSONArray("data")
            ?: throw DataFailure(FailureKind.PARSE, "Arama sonuçları okunamadı", "search")
        val rows = data.values().take(80).filterIsInstance<JSONObject>().mapNotNull { row ->
            val slug = row.string("slug").trim('/')
            val kind = when (row.string("contentableType").substringAfterLast('\\')) {
                "Movie" -> ContentKind.MOVIE
                "TVSeries" -> ContentKind.SERIES
                else -> return@mapNotNull null
            }
            if (!site.contentPattern.matches("/$slug/") || episodePath.matches("/$slug/")) return@mapNotNull null
            val page = site.catalogUrl("/$slug/", url) ?: return@mapNotNull null
            if (site.kind(page) != kind) return@mapNotNull null
            val title = row.string("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Card(ContentRef(site.id, page, kind), title, publicUrl(row.string("posterUrl"), url).orEmpty(),
                year(row.string("releaseYear")), rating(row.opt("imdbRating")?.toString().orEmpty()))
        }
        return CatalogPage(rows.distinctBy { it.ref.id })
    }

    private fun next(doc: Document, url: String): PageCursor? {
        if (site.id == "p019") return null
        val current = url.toHttpUrl()
        fun page(link: okhttp3.HttpUrl) = listOf("page", "paged").firstNotNullOfOrNull { link.queryParameter(it)?.toIntOrNull() }
            ?: Regex("""/(?:page|sayfa)/(\d+)/?$""").find(link.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val next = page(current) + 1
        return doc.select("main a[rel=next], a.next, .sayfalama a[href], .pagination a[href], .pagination-wrap a[href]").firstNotNullOfOrNull {
            val address = site.catalogUrl(it.attr("href"), url)?.toHttpUrl() ?: return@firstNotNullOfOrNull null
            if (address == current || page(address) != next ||
                listOf("q", "s", "search").any { key -> current.queryParameter(key) != address.queryParameter(key) }) return@firstNotNullOfOrNull null
            PageCursor(site.id, address.toString(), next)
        }
    }

    fun detail(body: String, seed: Card): Detail {
        HtmlData.verify(body)
        if (seed.ref.kind == ContentKind.COLLECTION) return collectionDetail(body, seed)
        val url = seed.ref.pageUrl
        val doc = HtmlData.document(body, url)
        val current = path(url)
        val bound = doc.selectFirst("meta[property=og:url], link[rel=canonical]")?.let {
            site.catalogUrl(it.attr(if (it.normalName() == "meta") "content" else "href"), url)
        }
        if (bound != null && path(bound) != current) throw DataFailure(FailureKind.PARSE, "İçerik adresi eşleşmedi", "detail")
        val type = when (seed.ref.kind) {
            ContentKind.MOVIE -> "Movie"
            ContentKind.SERIES -> "TVSeries"
            else -> "TVEpisode"
        }
        val heading = text(doc, site.detailTitle)
        val schemas = HtmlData.schemas(doc).filter { it.string("@type") == type }
        val schema = schemas.firstOrNull { site.catalogUrl(it.string("url"), url)?.let(::path) == current }
            ?: schemas.filter {
                val name = TextKey.fold(it.string("name").substringBefore(" | "))
                val titles = listOf(seed.title, heading).filter(String::isNotBlank).flatMap { label ->
                    listOf(TextKey.fold(label), TextKey.fold(TextKey.title(label)))
                }
                bound != null && it.string("url").isBlank() && name in titles
            }.singleOrNull()
        if (schema == null && (heading.isBlank() && seed.ref.kind != ContentKind.EPISODE || bound == null && site.id in setOf("p017", "p018", "p019")))
            throw DataFailure(FailureKind.PARSE, "İçerik sayfasının ana bilgisi bulunamadı", "detail")
        val title = TextKey.title(schema?.string("name").orEmpty().substringBefore(" | ").ifBlank { heading }.ifBlank { seed.title })
        val original = schema?.let { jsonNames(it.opt("alternateName")).firstOrNull() }.orEmpty()
            .ifBlank { text(doc, ".nf3-original-title, .movie-original-title") }.ifBlank { seed.originalTitle }
        val released = schema?.let {
            year(it.string("startDate")) ?: year(it.string("releaseDate")) ?: when (site.id) {
                "p008", "p017", "p018" -> year(it.string("datePublished"))
                "p016", "p019" -> year(it.string("dateCreated"))
                else -> null
            }
        }
        val genres = schema?.let { jsonNames(it.opt("genre")) }.orEmpty().ifEmpty {
            doc.select(site.genres).filter { it.closest("header,footer,nav,aside,form") == null }.map { it.text() }.distinct().take(25)
        }
        val imdbScope = when (site.id) {
            "p018" -> ".movie-header-section .movie-meta-row a[href]"
            "p019" -> "main .hdf-panel a[href]"
            else -> "main [itemprop=sameAs][href]"
        }
        val imdbIds = doc.select(imdbScope).filter {
            it.parents().none { node -> node.normalName() in setOf("aside", "footer") ||
                Regex("recommend|related|benzer|oneri").containsMatchIn(TextKey.fold(node.className() + " " + node.id())) }
        }.map { ContentMetadata.imdb(it.attr("href")) }.filter(String::isNotBlank).distinct()
        val imdbId = imdbIds.singleOrNull().orEmpty()
        val card = seed.copy(title = title, year = released ?: seed.year, originalTitle = original, genres = genres,
            imdbId = imdbId, identityEvidence = IdentityEvidence(url, seed.ref.kind, released ?: seed.year, imdbId, original,
                emptyList(), title, typeVerified = schema != null, yearVerified = released != null,
                detailChecked = true, imdbConflict = imdbIds.size > 1,
                reason = if (released != null) "primary-release-year" else ""),
            poster = HtmlData.image(doc.selectFirst(".av-show__body img, .nf3-film-intro img"), url).ifBlank { seed.poster },
            imdb = rating(text(doc, ".nf3-poster-badge--imdb")) ?: schema?.optJSONObject("aggregateRating")
                ?.opt("ratingValue")?.toString()?.let(::rating) ?: seed.imdb)
        val episodes = if (card.ref.kind == ContentKind.SERIES) episodes(doc, card) else emptyList()
        val seasonPages = if (site.id == "p008" && card.ref.kind == ContentKind.SERIES) doc.select("#bolumler a[href]").mapNotNull {
            val target = site.catalogUrl(it.attr("href"), url)?.toHttpUrl() ?: return@mapNotNull null
            if (path(target.toString()) != current || target.queryParameterNames != setOf("sezon")) return@mapNotNull null
            val season = target.queryParameter("sezon")?.toIntOrNull()?.takeIf { it in 1..1000 } ?: return@mapNotNull null
            target.toString().takeUnless { episodes.any { ep -> ep.number.season == season } }
        }.distinct().take(50) else emptyList()
        return Detail(card, schema?.string("description").orEmpty().ifBlank { text(doc, site.description) },
            genres, episodes = episodes, episodePages = seasonPages,
            options = if (card.ref.kind == ContentKind.SERIES) emptyList() else TrackedSources.options(site, doc, card.ref),
            seasons = (episodes.map { it.number.season } + seasonPages.mapNotNull {
                it.toHttpUrl().queryParameter("sezon")?.toIntOrNull()
            }).distinct().sorted())
    }

    private fun collectionDetail(body: String, card: Card): Detail {
        if (site.id != "p016") return CollectionParser.detail(body, card)
        val page = card.ref.pageUrl
        val doc = HtmlData.document(body, page)
        val groups = doc.select("main ol.nf6-marvel-list, main ol.nf5-marvel-list")
        val curated = doc.select("main article.nf-curation-card")
        if (groups.isEmpty() && curated.isEmpty()) return CollectionParser.detail(body, card)
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")?.let { site.catalogUrl(it, page) }
        if (canonical != null && path(canonical) != path(page))
            throw DataFailure(FailureKind.PARSE, "Koleksiyon adresi eşleşmedi", "collection")
        val entries = if (curated.isNotEmpty()) curated.mapNotNull { item ->
            val ordinal = text(item, ".nf-curation-card__index").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = text(item, ".nf-curation-card__copy h2,.nf-curation-card__copy h3").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val original = text(item, ".nf-curation-card__original")
            CollectionEntry(ordinal, title, year(text(item, ".nf-curation-card__metadata")) ?: 0, listOf(original).filter(String::isNotBlank))
        } else groups.select("li a.nf6-marvel-card").mapNotNull { item ->
            val label = text(item, "small")
            if (!TextKey.fold(label).endsWith(" film")) return@mapNotNull null
            val title = text(item, "strong").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val ordinal = text(item, ".nf6-marvel-card__order").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            CollectionEntry(ordinal, title, year(label) ?: 0)
        }
        if (entries.size < 2) throw DataFailure(FailureKind.PARSE, "Koleksiyonda doğrulanmış film listesi yok", "collection")
        return Detail(card.copy(title = text(doc, "main h1").ifBlank { card.title }), collectionItems = entries)
    }

    fun episodes(doc: Document, parent: Card): List<Episode> = doc.select(site.episodeSelector).mapNotNull { link ->
        val address = site.catalogUrl(link.attr("href"), parent.ref.pageUrl) ?: return@mapNotNull null
        val parentPath = path(parent.ref.pageUrl)
        val addressPath = path(address)
        val match = when (site.id) {
            "p008" -> gomEpisode.matchEntire(addressPath) ?: gomAnime.matchEntire(addressPath)
            "p012" -> trEpisode.matchEntire(addressPath)
            "p017" -> avsarEpisode.matchEntire(addressPath)
            else -> episodePath.matchEntire(addressPath)
        } ?: return@mapNotNull null
        val (slug, seasonText, numberText) = match.destructured
        if (slug.removeSuffix("-izle") != parentPath.substringAfterLast('/').removeSuffix("-izle")) return@mapNotNull null
        val season = if (seasonText.isBlank() && site.id == "p012") 1 else seasonText.toIntOrNull() ?: return@mapNotNull null
        val number = numberText.toIntOrNull() ?: return@mapNotNull null
        if (season !in 1..1000 || number !in 1..100000) return@mapNotNull null
        if (site.id == "p017" && link.closest("[data-season-panel]")?.attr("data-season-panel")?.toIntOrNull() != season) return@mapNotNull null
        val label = when (site.id) {
            "p008" -> text(link, "h3,.episode-title").ifBlank { link.text() }
            "p017" -> text(link, ".av-episode__label")
            "p018" -> text(link, ".episode-title")
            else -> link.text()
        }
        episode(parent, address, season, number, label)
    }.distinctBy { it.number }.sortedWith(compareBy({ it.number.season }, { it.number.number }))

    fun episode(parent: Card, address: String, season: Int, number: Int, label: String): Episode {
        val pair = EpisodeNumber(season, number)
        return Episode(Card(ContentRef(site.id, address, ContentKind.EPISODE),
            label.ifBlank { "${parent.title} · $season×$number" }, parent.poster, parent.year,
            episodeNumber = pair, seriesRef = parent.ref), parent.ref, pair)
    }
}
