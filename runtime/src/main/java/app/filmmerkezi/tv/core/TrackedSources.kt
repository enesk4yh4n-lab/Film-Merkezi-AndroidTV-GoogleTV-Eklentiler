package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Passive inventory confined to the work's declared player. */
internal object TrackedSources {
    private val leaderEmbed = Regex("""^/embed-[a-z0-9]{8,32}\.html$""")
    private val leaderVod = Regex("""^/vod/[a-z0-9_-]{5,64}$""")

    fun options(site: SiteSpec, doc: Document, ref: ContentRef): List<SourceOption> {
        val rows = mutableListOf<SourceOption>()
        fun add(raw: String, label: String, extractor: String = "", fields: Map<String, String> = emptyMap()) {
            val url = publicUrl(raw, ref.pageUrl)?.toHttpUrl()?.takeIf { it.scheme == "https" && it.port == 443 } ?: return
            val id = site.id + "-" + TextKey.hash(url.toString() + fields)
            rows.add(SourceOption(id, ref, label, url.toString(), extractor, fields = fields))
        }
        fun frame(n: Element) = listOf("data-frame-src", "data-nfp-src", "data-src", "data-litespeed-src", "src")
            .firstNotNullOfOrNull { n.attr(it).takeIf(String::isNotBlank) }.orEmpty()
        when (site.id) {
            "p008", "filmizlebabam" -> {
                doc.select("[data-player] [data-pv]").forEach {
                    val id = it.attr("data-pv").takeIf { key -> key.matches(Regex("[A-Za-z0-9_-]{8,100}")) } ?: return@forEach
                    val player = it.closest("[data-player]") ?: return@forEach
                    val origins = player.select("script[src]").mapNotNull { script ->
                        val url = publicUrl(script.attr("src"), ref.pageUrl)?.toHttpUrl() ?: return@mapNotNull null
                        url.takeIf { it.host.matches(Regex("""(?:play[1-9]\d{0,2}\.)?pilavyerplay\.top""")) &&
                            it.encodedPath == "/assets/js/core.js" }?.host
                    }.distinct()
                    val host = if (origins.isEmpty() && site.id == "filmizlebabam") "pilavyerplay.top" else origins.singleOrNull()
                    if (host != null) add("https://$host/assets/js/s.php?s=$id", "Pilavyer", "pilavyer")
                }
                doc.select("[data-player] iframe[data-player-frame]").forEach {
                    val target = publicUrl(frame(it), ref.pageUrl)?.toHttpUrl() ?: return@forEach
                    val host = target.host
                    val path = target.encodedPath
                    if (site.id == "p008") {
                        if (host == "spidypro.com" && path.matches(Regex("""/embed/[a-zA-Z0-9_-]+""")))
                            add(target.toString(), "SpidyPro", "spidypro")
                        return@forEach
                    }
                    when {
                        host in setOf("vidara.to", "mxzhxco8dft.com") && path.matches(Regex("""/e/[A-Za-z0-9]{8,24}""")) ->
                            add(target.toString(), "Vidara", "vidara")
                        host == "dzen.ru" && path.startsWith("/embed/") -> add(target.toString(), "Dzen")
                        host == "ok.ru" && path.matches(Regex("""/videoembed/\d+/?""")) -> add(target.toString(), "OK.ru")
                        host == "vidmoly.org" && path.startsWith("/embed-") -> add(target.toString(), "Vidmoly")
                        YoutubeLinks.videoId(target.toString()) != null -> add(target.toString(), "YouTube", "youtube")
                    }
                }
            }
            "p012" -> doc.select("#content iframe").forEach {
                val raw = frame(it)
                val youtube = if (it.closest(".dizialani .video-container") != null) YoutubeLinks.frameUrl(raw, ref.pageUrl) else null
                if (youtube != null) {
                    add(youtube, "YouTube", "youtube")
                    return@forEach
                }
                val address = site.catalogUrl(raw, ref.pageUrl) ?: return@forEach
                val url = address.toHttpUrl()
                if (url.query == null && url.encodedPath.matches(Regex("""/player/oynat/[a-f0-9]{24,64}/?"""))) {
                    add(address, "JW", "page")
                } else if (url.encodedPath == "/player/telif/index.php") {
                    rows.add(SourceOption("r012-external", ref, "Dış izleme bağlantısı", address,
                        state = OptionState.UNSUPPORTED, reason = "Bu bölüm için site doğrudan video sunmuyor."))
                }
            }
            "p016" -> doc.select(".nf3-watch-card [data-nfp-player] iframe[data-nfp-src]").forEach {
                val raw = frame(it)
                val host = publicUrl(raw, ref.pageUrl)?.toHttpUrl()?.host ?: return@forEach
                val label = when (host) {
                    "player.nadirfilm.com" -> "NadirFilm"
                    "ok.ru" -> "OK.ru"
                    "dzen.ru" -> "Dzen"
                    "vidmoly.biz", "vidmoly.me", "vidmoly.net" -> "Vidmoly"
                    else -> return@forEach
                }
                val shell = it.closest("[data-nfp-player]")
                val subtitle = publicUrl(shell?.attr("data-nfp-subtitle").orEmpty(), ref.pageUrl)
                    ?.takeIf { site.catalogUrl(it) != null && it.toHttpUrl().encodedPath.endsWith(".srt", true) }
                add(raw, label, fields = subtitle?.let { key -> mapOf("caption" to key) }.orEmpty())
            }
            "p017" -> {
                val kind = if (ref.kind == ContentKind.EPISODE) "dizi" else "film"
                doc.select("main .av-player[data-kind=$kind] iframe").forEach {
                    val video = YoutubeLinks.frameUrl(frame(it), ref.pageUrl)?.let(YoutubeLinks::videoId)
                    if (video != null) add("https://www.youtube.com/embed/$video", "YouTube", "youtube")
                }
            }
            "p018" -> {
                val encoded = runCatching { Literals.after(doc.outerHtml(), """\bwindow\._vs\s*=\s*""") as? String }.getOrNull()
                    ?.takeIf { it.length <= 65536 } ?: return emptyList()
                val data = runCatching { JSONArray(Literals.b64(encoded)) }.getOrNull() ?: return emptyList()
                data.values().take(20).filterIsInstance<JSONObject>().forEach {
                    if (!it.string("id").matches(Regex("""default|custom_\d{1,2}"""))) return@forEach
                    val target = publicUrl(it.string("url"), ref.pageUrl)?.toHttpUrl() ?: return@forEach
                    if (target.host != "play.liderfilm.cc") return@forEach
                    when {
                        leaderVod.matches(target.encodedPath) -> add(target.toString(), "Lider HLS", "lider-vod")
                        leaderEmbed.matches(target.encodedPath) -> rows.add(SourceOption(
                            "r018-" + TextKey.hash(target.toString()), ref, "PlayerJS", target.toString(), "lider-playerjs"))
                    }
                }
            }
            "p019" -> {
                val declaration = doc.selectFirst("main [data-video-player]")?.attr("x-data").orEmpty()
                val json = jsonLiteral(declaration, """^videoPlayerData\(\s*JSON\.parse\(\s*""") ?: return emptyList()
                val current = ref.pageUrl.toHttpUrl().encodedPath.trim('/')
                for (language in json.keys().asSequence().filter { it in setOf("dual", "tr", "en", "dublaj", "altyazi", "dub", "sub", "original") }) {
                    json.optJSONArray(language)?.values()?.take(16)?.filterIsInstance<JSONObject>()?.forEach { row ->
                        val slug = row.string("slug")
                        val id = row.string("link")
                        if (slug.trim('/') != current || !id.matches(Regex("[a-zA-Z0-9]{8,64}"))) return@forEach
                        val template = row.string("template").takeIf { it.length in 1..4096 } ?: return@forEach
                        val html = runCatching { Literals.b64(template) }.getOrNull() ?: return@forEach
                        val iframe = HtmlData.document(html, ref.pageUrl).selectFirst("iframe") ?: return@forEach
                        val raw = frame(iframe).takeIf { "{url}" in it } ?: return@forEach
                        val target = publicUrl(raw.replace("{url}", id).replace("{slug}", slug), ref.pageUrl)?.toHttpUrl() ?: return@forEach
                        if (target.query != null || target.fragment != null) return@forEach
                        val languageLabel = if (language == "dual") "" else " / $language"
                        when (row.string("service_slug")) {
                            "vip" -> if (target.host == "vidload.top" && target.encodedPath == "/player/$id")
                                add(target.toString(), "VIP$languageLabel", "hdf2-vip", mapOf("video" to id, "language" to language))
                            "moly" -> if (target.host in setOf("vidmoly.net", "vidmoly.biz", "vidmoly.me") && target.encodedPath == "/embed-$id.html")
                                add(target.toString(), "Moly$languageLabel", "vidmoly", mapOf("language" to language))
                        }
                    }
                }
            }
        }
        return rows.distinctBy { it.id }
    }

    fun jsonLiteral(body: String, marker: String): JSONObject? = runCatching {
        (Literals.after(body, marker) as? String)?.takeIf { it.length <= 65536 }?.let(::JSONObject)
    }.getOrNull()
}
