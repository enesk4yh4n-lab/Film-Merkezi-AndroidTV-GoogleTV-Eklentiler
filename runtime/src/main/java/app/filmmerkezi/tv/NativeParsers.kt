package app.filmmerkezi.tv

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.DataNode
import java.net.URI
import java.util.Locale

/** Android-only experimental ports of the two catalog parsers.
 * The original JS is the test oracle, not an implementation dependency of these methods.
 * Results must match the recorded oracle exactly before this approach can be selected. */
object NativeParsers {
    private const val FILM = "https://filmizle.ink"
    private const val HDF = "https://www.hdfilmcehennemi.nl"
    private fun clean(v: Any?): String = if (v == null || v === JSONObject.NULL) "" else v.toString().replace(Regex("\\s+"), " ").trim()
    private fun raw(n: Node?): String = when (n) { null -> ""; is TextNode -> n.wholeText; is DataNode -> n.wholeData; else -> n.childNodes().joinToString("") { raw(it) } }
    private fun text(n: Element?, selector: String? = null) = clean(raw(if (selector == null) n else n?.selectFirst(selector)))
    private fun url(v: String?, base: String): String = try {
        val s = clean(v)
        if (s.isEmpty() || Regex("^(javascript|data|blob|file):", RegexOption.IGNORE_CASE).containsMatchIn(s)) ""
        else URI(base).resolve(s.replace(" ", "%20")).normalize().toASCIIString().substringBefore('#').let { if (it.startsWith("https://") || it.startsWith("http://")) it else "" }
    } catch (_: Exception) { "" }
    private fun safe(v: String?, page: String = "$FILM/"): String = url(v, page).let { s -> try { val u = URI(s); if ("${u.scheme}://${u.rawAuthority}" == FILM && u.userInfo == null) s else "" } catch (_: Exception) { "" } }
    private fun hdfUrl(v: String?, page: String) = url(v, page).let { if (Regex("^https://(www\\.)?hdfilmcehennemi\\.nl/", RegexOption.IGNORE_CASE).containsMatchIn(it)) it else "" }
    private fun number(v: String): Any? = Regex("\\d+(?:[.,]\\d+)?").find(v)?.value?.replace(',', '.')?.toDoubleOrNull()
    private fun nullable(v: Any?) = v ?: JSONObject.NULL
    private fun arr(items: List<Any?>) = JSONArray(items)
    private fun filmId(page: String, type: String): String {
        val original = URI(page).rawPath
        val path = if (type != "series" && type != "collection" && Regex("^/[^/]+/[1-9]\\d*/$").matches(original)) original.replace(Regex("/[1-9]\\d*/$"), "/") else original
        return "filmizle:" + when (type) { "series" -> "series:"; "collection" -> "collection:"; else -> "movie:" } + path.trim('/')
    }
    private fun hdfId(page: String, type: String): String {
        val p = page.replace(Regex("^https?://[^/]+", RegexOption.IGNORE_CASE), "").substringBefore('?').substringBefore('#').trim('/')
        return if (type == "series" || p.startsWith("dizi/")) "hdf:series:" + p.removePrefix("dizi/").replace(Regex("-izle(?:-\\d+)?$"), "") else "hdf:$p"
    }
    private fun image(n: Element?, page: String, film: Boolean, thumbnail:Boolean=false): String {
        val img = if (!film && n?.tagName() == "img") n else n?.selectFirst("img")
        if(thumbnail)app.filmmerkezi.tv.core.CatalogPosters.thumbnail(img,page).takeIf{it.isNotEmpty()}?.let{return it}
        return url(img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: (if (film) img?.attr("data-litespeed-src")?.takeIf { it.isNotEmpty() } else null) ?: img?.attr("data-catalog-src"), page)
    }
    private fun folded(s: String): String = clean(s).replace('İ', 'i').lowercase(Locale.ROOT).replace("\u0307", "").map { c -> when (c) { 'ı','ì','í','î','ï' -> 'i'; 'ç','ć','č' -> 'c'; 'ğ','ģ' -> 'g'; 'ö','ò','ó','ô','õ','ø' -> 'o'; 'ş','ś','š' -> 's'; 'ü','ù','ú','û' -> 'u'; 'â','à','á','ä','ã','å' -> 'a'; 'é','è','ê','ë' -> 'e'; 'ñ' -> 'n'; else -> c } }.joinToString("")
    private fun flags(value: String): Pair<Boolean, Boolean> {
        val s = folded(value).replace(Regex("[&/+(),-]"), " ").replace(Regex("\\bve\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val dual = Regex("^(?:dual|(?:dual )?(?:turkce )?dublaj(?:li)? (?:turkce )?altyazi(?:li)?)$").matches(s)
        return (dual || Regex("^(?:turkce )?dublaj(?:li)?$").matches(s)) to (dual || Regex("^(?:turkce )?altyazi(?:li)?$").matches(s))
    }
    private fun lang(n: Element?, menu: Boolean = false): String {
        var dub = false; var sub = false
        n?.select(if (menu) ".language-tab .language-link" else "[title]")?.forEach {
            if (!menu || !it.hasAttr("disabled") && it.attr("aria-disabled") != "true") {
                val f = flags(if (menu) text(it) else it.attr("title")); dub = dub || f.first; sub = sub || f.second
            }
        }
        return if (dub && sub) "Dublaj & Altyazılı" else if (dub) "Türkçe Dublaj" else if (sub) "Türkçe Altyazılı" else ""
    }
    private fun aliases(title: String): JSONArray {
        val out = mutableListOf<String>(); val m = Regex("\\(([^()]+)\\)").find(title)
        if (m != null && !Regex("^\\d{4}$").matches(m.groupValues[1])) out.add(m.groupValues[1])
        val parts = title.split(Regex("\\s+[–—-]\\s+"))
        if (parts.size == 2) parts.forEach { if (!Regex("^[0-9 ]*$").matches(folded(it)) && !out.contains(it)) out.add(it) }
        return JSONArray(out)
    }
    private fun yearHint(title: String, page: String): Any? {
        Regex("\\s((?:19|20)\\d{2})$").find(folded(title).replace(Regex("[-–—:|’'\"!?().,]+"), " ").trim())?.let { return it.groupValues[1].toInt() }
        val values = Regex("(?:^|[-/])((?:19|20)\\d{2})(?=[-/]|$)").findAll(URI(page).rawPath).map { it.groupValues[1].toInt() }.distinct().toList()
        return values.singleOrNull()
    }
    private fun series(title: String, n: Element, hint: String?) = Regex("\\b\\d+\\s*\\.?\\s*sezon\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) || text(n, ".v100-meta-row").contains("Dizi", true) || hint == "series"
    private data class Envelope(val doc: Document, val schema: List<JSONObject>)
    private fun envelope(body: String): Envelope {
        var html = body
        if (Regex("^\\s*[{\\[]").containsMatchIn(body)) {
            val data = try { JSONTokener(body).nextValue() } catch (_: Exception) { error("Katalog yanıtı okunamadı.") }
            if (data is JSONObject) {
                if (data.has("error") && data.opt("error") != false) error("Katalog: " + clean(data.opt("error")).take(150))
                html = if (data.has("html")) data.getString("html") else data.optJSONArray("results")?.strings()?.joinToString("\n") ?: ""
            } else html = ""
        }
        val schema = mutableListOf<JSONObject>()
        Regex("<script\\b[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE).findAll(html).forEach {
            try { when (val s = JSONTokener(it.groupValues[1]).nextValue()) { is JSONArray -> schema.addAll(s.objects()); is JSONObject -> if (s.optJSONArray("@graph") != null) schema.addAll(s.getJSONArray("@graph").objects()) else schema.add(s) } } catch (_: Exception) { }
        }
        html = html.replace(Regex("<(script|style|iframe|object|video|audio)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(\\s)src(\\s*=)", RegexOption.IGNORE_CASE), "$1data-catalog-src$2")
            .replace(Regex("(\\s)srcset(\\s*=)", RegexOption.IGNORE_CASE), "$1data-catalog-srcset$2")
            .replace(Regex("\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE), "")
        return Envelope(Jsoup.parse(html), schema)
    }
    private fun filmCards(d: Element, page: String, hint: String?): JSONArray {
        val out = JSONArray(); val seen = HashSet<String>()
        d.select("a.v100-card, .custom-card-wrapper, .v50-card, .news-card, .blog-card").forEach { box ->
            val a = if (box.tagName() == "a") box else box.selectFirst(".movie-title a, a[href]")
            val u = safe(a?.attr("href"), page); val title = text(box, ".v100-title, .movie-title, .v50-details h3, .article-title, h2, h3").ifEmpty { clean(a?.attr("title")) }
            if (u.isEmpty() || title.isEmpty() || !seen.add(u)) return@forEach
            val article = URI(u).path.contains("/haber/")
            val collection=app.filmmerkezi.tv.core.CatalogContent.collectionLink(title,u)
            if (article && !collection) return@forEach
            val type = if (collection) "collection" else if (series(title, box, hint)) "series" else "movie"
            val year = Regex("\\b(?:19|20)\\d{2}\\b").find(text(box, ".movie-release"))?.value?.toInt()
            out.put(objectOf("contentId" to filmId(u, type), "providerId" to "filmizle", "type" to type, "pageUrl" to u, "title" to title,
                "titleAliases" to aliases(title), "yearHint" to if (type == "movie") yearHint(title, u) else null, "poster" to image(box, page, true,thumbnail=true),
                "year" to year, "imdb" to number(text(box, ".v100-imdb-tag, .v50-imdb-tag, .imdb")), "language" to lang(box)))
        }; return out
    }
    private fun filmList(body: String, page: String, hint: String?): JSONObject {
        val d = envelope(body).doc; var u = safe(d.selectFirst("a.loadnavi, a[rel=next], .v50-pagination a.next")?.attr("href"), page)
        val sort = Regex("[?&]sort=(date|views|imdb|comments)(?:&|$)").find(page)?.groupValues?.get(1)
        if (u.isNotEmpty() && sort != null && !u.contains(Regex("[?&]sort="))) u += (if (u.contains('?')) "&" else "?") + "sort=$sort"
        return objectOf("cards" to filmCards(d, page, hint), "next" to if (u.isNotEmpty() && u != page) objectOf("kind" to "url", "url" to u) else null, "options" to JSONObject())
    }
    private fun filmHome(body: String): JSONObject {
        val d = envelope(body).doc; val cards = filmCards(d, "$FILM/", null).objects(); val genres = JSONArray(); val seen = HashSet<String>()
        d.select("a.v100-view-all").forEach { a -> val u = safe(a.attr("href")); if (u.contains("/Kategori/tur/") && seen.add(u)) genres.put(objectOf("value" to u, "label" to text(a.parent(), "h3").ifEmpty { text(a) })) }
        check(cards.isNotEmpty()) { "Filmizle katalog biçimi tanınamadı." }
        return objectOf("hero" to cards[0], "movies" to arr(cards.filter { it.optString("type") == "movie" }.take(30)), "series" to arr(cards.filter { it.optString("type") == "series" }.take(18)), "genres" to genres,
            "links" to objectOf("new" to "$FILM/Kategori/en-populer-filmler/", "recommended" to "$FILM/Kategori/en-populer-filmler/?sort=views", "rated" to "$FILM/Kategori/en-populer-filmler/?sort=imdb", "liked" to "$FILM/Kategori/en-populer-filmler/?sort=comments"))
    }
    private fun filmDetail(body: String, card: JSONObject): JSONObject {
        val page = card.getString("pageUrl"); val d = envelope(body).doc
        if (card.optString("type") == "collection" || URI(page).path.contains("/haber/")) {
            val scope = d.selectFirst(".news-content.article-content") ?: error("Bu yazıda sıralı film koleksiyonu bulunamadı.")
            val items = JSONArray(); scope.select("h2,h3").forEach { h -> Regex("^(\\d+)\\.\\s*(.+?)\\s*\\(((?:19|20)\\d{2})\\)").find(text(h))?.let { m ->
                val t = clean(m.groupValues[2]); val parts = t.split(Regex("\\s*[-–]\\s*")); items.put(objectOf("ordinal" to m.groupValues[1].toInt(), "title" to t, "year" to m.groupValues[3].toInt(), "titleAliases" to JSONArray(if (parts.size > 1) parts else emptyList<String>()))) } }
            check(items.length() >= 2) { "Bu yazıda doğrulanabilir sıralı film listesi bulunamadı." }
            return objectOf("contentId" to filmId(page, "collection"), "legacyContentIds" to JSONArray().put(filmId(page, "movie")), "providerId" to "filmizle", "type" to "collection", "pageUrl" to page,
                "title" to text(d, "h1").ifEmpty { card.optString("title") }, "poster" to card.optString("poster"), "description" to "Filmleri bu koleksiyondaki sırayla keşfedin ve izleyin.", "collectionItems" to arr(items.objects().take(200)),
                "genres" to JSONArray(), "cast" to JSONArray(), "seasons" to JSONArray(), "episodes" to JSONArray(), "sources" to JSONArray(), "subtitles" to JSONArray())
        }
        val title = text(d, ".p-v12-main-title"); check(title.isNotEmpty()) { "Filmizle detay biçimi tanınamadı." }
        val r = JSONObject(card.toString()); r.put("title", title).put("titleAliases", aliases(title))
        Regex("\\(([^()]+)\\)").find(title)?.let { if (!Regex("^\\d{4}$").matches(it.groupValues[1])) r.put("originalTitle", clean(it.groupValues[1])) }
        r.put("description", text(d, ".p-v12-story-content")); setFallback(r, "poster", image(d.selectFirst(".p-v12-poster-main"), page, true), card)
        val type = if (series(title, d, card.optString("type")) || d.selectFirst(".p-v12-meta-info a[href*=/dizi-izle/]") != null) "series" else "movie"
        r.put("type", type).put("contentId", filmId(page, type))
        listOf("genres", "cast", "seasons", "episodes", "sources", "subtitles").forEach { r.put(it, JSONArray()) }
        var hint = yearHint(title, page); val meta = text(d, ".p-v12-meta-info > .meta-item")
        if (hint == null && Regex("^(?:19|20)\\d{2}$").matches(meta)) hint = meta.toInt()
        r.put("yearHint", nullable(hint)); setFallback(r, "imdb", number(text(d, ".p-v12-imdb-badge")), card)
        d.select(".p-v12-meta-info a[rel]").forEach { val name = text(it); if (name.isNotEmpty()) r.getJSONArray("genres").put(name) }
        if (type == "series") {
            val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toInt() ?: 1
            val links = mutableListOf<Pair<String, Int>>(); val current = text(d.selectFirst(".p-v12-ep-list .current, .p-v12-ep-list > span"))
            if (Regex("^[1-9]\\d*$").matches(current)) links.add(page to current.toInt())
            d.select(".p-v12-ep-list a.post-page-numbers").forEach { val u = safe(it.attr("href"), page); val n = text(it).toDoubleOrNull(); if (u.isNotEmpty() && n != null && n > 0 && n % 1.0 == 0.0) links.add(u to n.toInt()) }
            links.sortedBy { it.second }.distinctBy { it.second }.forEach { (u, n) ->
                r.getJSONArray("episodes").put(objectOf("contentId" to r.getString("contentId") + ":s$season:e$n", "providerId" to "filmizle", "pageUrl" to u, "title" to "$title · $n. Bölüm", "episodeTitle" to "$n. Bölüm", "type" to "episode",
                    "poster" to r.opt("poster"), "seriesTitle" to title, "seriesPageUrl" to page, "episode" to objectOf("seriesId" to r.getString("contentId"), "season" to season, "number" to n), "sources" to JSONArray()))
            }; r.put("seasons", JSONArray().put(season))
        }; return r
    }
    private fun setFallback(r: JSONObject, key: String, v: Any?, card: JSONObject) {
        val valid = v != null && v !== JSONObject.NULL && v != "" && v != 0.0
        if (valid) r.put(key, v) else if (card.has(key)) r.put(key, card.get(key)) else r.remove(key)
    }
    private fun hdfCards(d: Element, selector: String, page: String): JSONArray {
        val out = JSONArray(); val seen = HashSet<String>()
        d.select(selector).forEach { a ->
            val u = hdfUrl(a.attr("href"), page)
            if (u.isEmpty() || Regex("/sezon-\\d+/|\\d+-sezon-\\d+-bolum").containsMatchIn(u)) return@forEach
            val type = if (u.contains("/dizi/") || text(a, ".type").contains("dizi", true)) "series" else "movie"
            val key = hdfId(u, type); val title = text(a, ".poster-title, .mini-poster-title, .title").ifEmpty { clean(a.attr("title")) }.ifEmpty { clean(a.selectFirst("img")?.attr("alt")).replace(Regex(" izle$"), "") }
            if (title.isEmpty() || !seen.add(key)) return@forEach
            val y = text(a, ".year").ifEmpty { text(a, ".poster-meta > span:first-child") }.ifEmpty { text(a, ".mini-poster-meta") }
            out.put(objectOf("contentId" to key, "type" to type, "pageUrl" to u, "title" to title, "titleAliases" to JSONArray(title.split(" - ").map { clean(it) }), "poster" to image(a, page, false,thumbnail=true),
                "year" to Regex("\\b(?:19|20)\\d{2}\\b").find(y)?.value?.toInt(), "imdb" to number(text(a, ".imdb, .mini-poster-imdb")), "language" to text(a, ".poster-lang > span")))
        }; return out
    }
    private fun hdfList(body: String, pageUrl: String, context: JSONObject?): JSONObject {
        val d = envelope(body).doc; val page = context?.optInt("page", 1) ?: 1; val options = JSONObject()
        d.select("[data-dropdown-key][data-dropdown-value]").forEach { n -> val key = n.attr("data-dropdown-key"); if (key in listOf("type", "genres", "years", "imdb", "lang", "order")) { if (!options.has(key)) options.put(key, JSONArray()); options.getJSONArray(key).put(objectOf("value" to n.attr("data-dropdown-value"), "label" to text(n))) } }
        val cards = hdfCards(d, if (d.selectFirst("[data-page-items]") != null) "[data-page-items] a.poster" else "a.poster, a.search-result", pageUrl)
        var next: JSONObject? = null; val paging = d.selectFirst("[data-pages][data-page-action]")
        if (paging != null) { val total = paging.attr("data-pages").toIntOrNull() ?: 0; val action = paging.attr("data-page-action"); if (total > page && Regex("^[a-z0-9_-]+$").matches(action)) next = objectOf("kind" to "page", "page" to page + 1, "total" to total, "action" to action, "url" to "$HDF/load/page/${page + 1}/$action/") }
        else if (context?.optString("kind") == "page" && cards.length() > 0 && page < context.optInt("total")) next = objectOf("kind" to "page", "page" to page + 1, "total" to context.getInt("total"), "action" to context.getString("action"), "url" to "$HDF/load/page/${page + 1}/${context.getString("action")}/")
        d.selectFirst("a[rel=next]")?.let { val u = hdfUrl(it.attr("href"), pageUrl); if (u.isNotEmpty()) next = objectOf("kind" to "url", "page" to page + 1, "url" to u) }
        if ((d.selectFirst("[data-discover-more]") != null || context?.optString("kind") == "discover") && cards.length() > 0) next = objectOf("kind" to "discover", "page" to page + 1)
        return objectOf("cards" to cards, "next" to next, "options" to options)
    }
    private fun hdfHome(body: String, page: String): JSONObject {
        val d = envelope(body).doc; val all = hdfCards(d, "[data-page-items] a.poster", page); val popular = hdfCards(d, "a.mini-poster", page).objects().filter { it.optString("type") == "series" }; val hero = hdfCards(d, ".slider a.poster", page); val links = JSONObject()
        d.select(".section-tabs a[href]").forEach { a -> val t = text(a); val key = when { t.contains("Tavsiye") -> "recommended"; t.contains("Imdb") || t.contains("IMDb") -> "rated"; t.contains("Beğen") -> "liked"; t.contains("Yeni") -> "new"; else -> "" }; if (key.isNotEmpty()) links.put(key, hdfUrl(a.attr("href"), page)) }
        return objectOf("hero" to if (hero.length() > 0) hero.get(0) else if (all.length() > 0) all.get(0) else null, "movies" to all, "series" to arr(popular), "links" to links, "next" to hdfList(body, page, null).opt("next"))
    }
    private fun hdfDetail(body: String, card: JSONObject): JSONObject {
        val env = envelope(body); val d = env.doc; val s = env.schema.firstOrNull { it.optString("@type") in listOf("Movie", "TVSeries") } ?: JSONObject(); val page = card.getString("pageUrl"); val r = JSONObject(card.toString())
        setFallback(r, "title", clean(s.opt("name")).ifEmpty { text(d, "h1").replace(Regex("\\s*izle\\s*(\\(\\d{4}\\))?$", RegexOption.IGNORE_CASE), "") }, card)
        r.put("originalTitle", clean(s.opt("alternateName")).ifEmpty { text(d, ".post-original-title") })
        setFallback(r, "poster", image(d.selectFirst(".post-info-poster"), page, false).ifEmpty { url(s.optString("image"), page) }, card)
        r.put("description", text(d, ".post-info-content").ifEmpty { clean(s.opt("description")) })
        setFallback(r, "year", number(text(d, ".post-info-year-country a")), card)
        var duration = text(d, ".post-info-duration"); val sd = s.optString("duration").ifEmpty { s.optString("timeRequired") }; if (duration.isEmpty() && Regex("^PT\\d+M$").matches(sd)) duration = sd.substring(2, sd.length - 1) + " dakika"
        r.put("duration", duration)
        var genres = JSONArray(d.select(".post-info-genres a").map { text(it) }); if (genres.length() == 0) genres = s.optJSONArray("genre") ?: JSONArray().apply { if (s.optString("genre").isNotEmpty()) put(s.getString("genre")) }
        r.put("genres", genres); setFallback(r, "imdb", number(text(d, ".post-info-imdb-rating")), card); setFallback(r, "language", lang(d, true), card)
        r.put("cast", JSONArray((s.optJSONArray("actor")?.objects() ?: emptyList()).map { clean(it.opt("name")) }.filter { it.isNotEmpty() }))
        r.put("episodes", JSONArray()).put("seasons", JSONArray()); if (s.optString("@type") == "TVSeries") r.put("type", "series")
        if (r.optString("type") == "series") {
            val episodes = mutableListOf<JSONObject>(); val seen = HashSet<String>()
            d.select(".seasons a[href]").forEach { a -> val u = hdfUrl(a.attr("href"), page); val m = Regex("/sezon-(\\d+)/bolum-(\\d+)(?:[^/]*)/").find(u) ?: Regex("(\\d+)-sezon-(\\d+)-bolum").find(u)
                if (m != null) { val season = m.groupValues[1].toInt(); val n = m.groupValues[2].toInt(); if (seen.add("$season:$n")) episodes.add(objectOf("contentId" to r.getString("contentId") + ":s$season:e$n", "type" to "episode", "pageUrl" to u,
                    "title" to r.getString("title") + " · $season. Sezon $n. Bölüm", "episodeTitle" to text(a, ".mini-poster-title").ifEmpty { "$season. Sezon $n. Bölüm" }, "poster" to r.opt("poster"), "seriesPageUrl" to page, "seriesTitle" to r.getString("title"),
                    "episode" to objectOf("seriesId" to r.getString("contentId"), "season" to season, "number" to n), "sources" to JSONArray())) }
            }
            episodes.sortWith(compareBy({ it.getJSONObject("episode").getInt("season") }, { it.getJSONObject("episode").getInt("number") })); r.put("episodes", arr(episodes)); r.put("seasons", JSONArray(episodes.map { it.getJSONObject("episode").getInt("season") }.distinct()))
        }; return r
    }
    fun parse(request: JSONObject): Any? {
        val a = request.getJSONArray("args"); val body = a.getString(0)
        return when (request.getString("site") + ":" + request.getString("method")) {
            "filmizle:list" -> filmList(body, a.getString(1), if (a.length() > 2 && !a.isNull(2)) a.getString(2) else null)
            "filmizle:home" -> filmHome(body)
            "filmizle:detail" -> filmDetail(body, a.getJSONObject(1))
            "hdf:list" -> hdfList(body, a.getString(1), a.optJSONObject(2))
            "hdf:home" -> hdfHome(body, a.getString(1))
            "hdf:detail" -> hdfDetail(body, a.getJSONObject(1))
            else -> error("Unsupported native parser entry")
        }
    }
}
