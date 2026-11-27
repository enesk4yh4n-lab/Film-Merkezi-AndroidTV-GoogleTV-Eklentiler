package app.filmmerkezi.addon.pilot

import app.filmmerkezi.tv.core.*
import org.json.*
import org.jsoup.Jsoup

internal class Broadcaster(private val site: SiteSpec, private val io: PilotIO,
    private val cache: MutableMap<String,Pair<Long,List<Card>>>) {
    private val base get()=site.base
    private fun getCached(key: String, load: ()->List<Card>): List<Card> {
        cache[key]?.takeIf{System.nanoTime()-it.first<300_000_000_000L}?.let{return it.second}
        val result=load(); if(cache.size>=4) cache.remove(cache.keys.first())
        cache[key]=System.nanoTime() to result; return result
    }
    private fun window(cards: List<Card>, key: String, next: PageCursor?, size: Int): CatalogPage {
        val offset=if(next==null) 0 else next.fields["offset"]?.toIntOrNull()?:fail("Sayfa konumu geçersiz")
        if(next!=null && (next.siteId!=site.id || next.url!=key || offset !in 0..cards.size)) fail("Sayfa devam bilgisi geçersiz")
        return CatalogPage(cards.drop(offset).take(size), if(offset+size<cards.size) PageCursor(site.id,key,fields=mapOf("offset" to "${offset+size}")) else null)
    }
    fun search(query: String, next: PageCursor?): CatalogPage {
        val term=TextKey.fold(query)
        if(term.isBlank()) return CatalogPage(emptyList())
        if(query.length>100) fail("Arama en fazla 100 karakter olabilir")
        return when(site.id) {
            "p002" -> {
                if(next!=null) fail("PuhuTV aramasında devam sayfası yok")
                val puhuTerm=clean(query).lowercase(java.util.Locale("tr")).replace("\u0307","")
                val j=io.json("https://appservice.puhutv.com/search/search?query=${encode(puhuTerm)}&v=2&source=web")
                val rows=if(j.optBoolean("recommendation")||j.optInt("total_count")==0) emptyList() else objects(j.optJSONArray("data")).flatMap{objects(it.optJSONArray("data"))}
                CatalogPage(rows.mapNotNull(::puhuCard).distinctBy{it.ref.id})
            }
            "p003" -> {
                if(next!=null) fail("NOW aramasında devam sayfası yok")
                val doc=Jsoup.parse(io.body(base+"/arama",mapOf("query" to term)))
                if(doc.selectFirst(".scrollable")==null) fail("NOW arama alanı bulunamadı")
                val cards=doc.select(".scrollable .container").flatMap { section ->
                    val grid=section.nextElementSibling()
                    if(TextKey.fold(section.selectFirst("h2")?.text().orEmpty())!="diziler" || grid?.hasClass("posters")!=true) emptyList()
                    else grid.select(".poster a[href]").mapNotNull { a ->
                        val url=io.page(a.attr("href")); val name=clean(a.selectFirst("img")?.attr("alt").orEmpty()).ifBlank{url.removeSuffix("/izle").substringAfterLast('/').replace('-',' ')}
                        if(!Regex("^${Regex.escape(base)}/[^/]+/izle$").matches(url) || !TextKey.fold(name).contains(term)) null
                        else Card(ContentRef(site.id,url,ContentKind.SERIES),name,image(a))
                    }
                }
                CatalogPage(cards.distinctBy{it.ref.id})
            }
            else -> window(starCatalog().filter{c->term.split(' ').all{TextKey.fold(c.title).contains(it)}},base+"/dizi?q="+encode(term),next,24)
        }
    }
    fun browse(kind: ContentKind, next: PageCursor?): CatalogPage {
        if(kind !in setOf(ContentKind.MOVIE,ContentKind.SERIES)) return CatalogPage(emptyList())
        return when(site.id) {
            "p002" -> {
                val url=base+if(kind==ContentKind.SERIES) "/dizi" else "/"
                val cards=getCached(url){parseList(io.body(url),url).cards}.filter{it.ref.kind==kind}
                window(cards,url,next,48)
            }
            "p003" -> if(kind==ContentKind.MOVIE) CatalogPage(emptyList()) else nowArchive(next)
            else -> if(kind==ContentKind.MOVIE) CatalogPage(emptyList()) else window(starCatalog(),base+"/dizi",next,24)
        }
    }
    fun parseList(body: String, url: String): CatalogPage {
        if(io.page(url).isBlank()) fail("Katalog adresi geçersiz")
        return CatalogPage(when(site.id) {
            "p002" -> {
                val p=io.nextData(body); val d=p.opt("data")
                val rows=when(d) {
                    is JSONArray -> objects(d)
                    is JSONObject -> when(val nested=d.opt("data")) {is JSONArray->objects(nested); is JSONObject->objects(nested.optJSONArray("container_items")).flatMap{objects(it.optJSONArray("items"))}; else->emptyList()}
                    else -> emptyList()
                }
                rows.mapNotNull(::puhuCard).distinctBy{it.ref.id}
            }
            "p003" -> nowSeries(body)
            else -> starCards(FlightData(body))
        })
    }
    fun options(): FilterOptions {
        if(site.id=="p003") return FilterOptions()
        val cards=if(site.id=="p005") starCatalog() else getCached(base+"/dizi"){parseList(io.body(base+"/dizi"),base+"/dizi").cards}
        return FilterOptions(mapOf("genre" to cards.flatMap{it.genres}.distinct().map{FilterValue(it,it)},"year" to cards.mapNotNull{it.year}.distinct().sortedDescending().map{FilterValue("$it","$it")}))
    }
    fun detail(card: Card): Detail = when(site.id) {"p002"->puhuDetail(card); "p003"->nowDetail(card); else->starDetail(card)}
    private fun option(card: Card, fields: Map<String,String> = emptyMap()) = SourceOption("${site.id}-"+TextKey.hash(card.ref.id),card.ref,site.name+" · Tam yayın",card.ref.pageUrl,site.id,
        fields=fields+buildMap{card.episodeNumber?.let{put("season","${it.season}");put("episode","${it.number}")};card.seriesRef?.let{put("seriesPage",it.pageUrl)}})
    private fun finish(input: Card, parent: Card, description: String, genres: List<String>, cast: List<String>, episodes: List<Episode>, movieFields: Map<String,String> = emptyMap()): Detail {
        val selected=if(input.ref.kind==ContentKind.EPISODE) episodes.firstOrNull{it.card.ref.pageUrl==io.page(input.ref.pageUrl)}?.card ?: fail("Seçilen bölüm bu dizinin doğrulanmış listesinde yok") else parent
        return Detail(selected,description,genres,cast,episodes=episodes,options=if(selected.ref.kind==ContentKind.SERIES) emptyList() else listOf(option(selected,movieFields)),seasons=episodes.map{it.number.season}.distinct().sorted())
    }
    private fun image(el: org.jsoup.nodes.Element): String {
        val img=el.selectFirst("img")?:return ""
        val raw=img.attr("data-src").ifBlank{img.attr("data-catalog-src")}.ifBlank{img.attr("src")}
        return runCatching{java.net.URI(base).resolve(raw).toString()}.getOrDefault("").takeIf{raw.isNotBlank()}?:""
    }
    private fun puhuCard(item: JSONObject): Card? {
        val m=item.optJSONObject("meta")?:JSONObject(); val type=item.text("type")
        val url=io.page(listOf(item.text("web_url"),m.text("web_url"),m.text("slug"),item.text("slug")).firstOrNull{it.isNotBlank()}.orEmpty())
        if(!Regex("(?:title|life)_(?:movie|serie)").matches(type)||item.text("id").isBlank()||!url.endsWith("-detay")||item.text("cta_external_url").isNotBlank()||url.contains("canli-yayin")) return null
        val title=clean(item.text("name")); if(title.isBlank()) return null
        val year=listOf("released_at","productionDate","year").map{m.optInt(it)}.firstOrNull{it in 1880..2200}
        val genres=objects(m.optJSONArray("genres")).map{clean(it.text("name").ifBlank{it.text("display_name")})}.filter{it.isNotBlank()}
        return Card(ContentRef(site.id,url,if(type.endsWith("serie")) ContentKind.SERIES else ContentKind.MOVIE),title,
            puhuImage(listOf("image_vertical_mobile","image_vertical_web_mobile","image").map{item.text(it)}.firstOrNull{it.isNotBlank()}.orEmpty()),year,m.optDouble("imdb_score").takeIf{it>0&&it<=10},genres=genres)
    }
    private fun puhuImage(raw: String): String {
        val u=runCatching{java.net.URI(raw)}.getOrNull()?:return raw
        if(u.scheme!="https"||u.host!="img-puhutv.mncdn.com") return raw
        val path=u.path.replace(Regex("^/mnresize/\\d+/\\d+"),"")
        return if(path.startsWith("/media/")) "https://img-puhutv.mncdn.com/mnresize/360/540$path" else raw
    }
    private fun puhuDetail(input: Card): Detail {
        val url=io.page(input.ref.pageUrl).ifBlank{fail("PuhuTV içerik adresi geçersiz")}
        val p=io.nextDataAt(url); val raw=(p.optJSONObject("details")?:p.optJSONObject("watchDetails"))?.optJSONObject("data")?:fail("PuhuTV içerik verisi yok")
        val parent=puhuCard(raw)?:fail("PuhuTV film/dizi verisi okunamadı"); val meta=raw.optJSONObject("meta")?:JSONObject()
        val episodes=mutableListOf<Episode>(); val seen=mutableSetOf<String>()
        if(parent.ref.kind==ContentKind.SERIES) {
            val seasons=objects(meta.optJSONArray("seasons")).filter{it.optInt("position")>0&&io.page(it.text("slug")).isNotBlank()}.sortedBy{it.optInt("position")}
            val initial=(p.optJSONObject("episodeData")?:p.optJSONObject("episodes"))?.optJSONObject("data")
            if(seasons.size>64) fail("PuhuTV sezon sınırı aşıldı; liste eksik gösterilmeyecek")
            for(season in seasons) {
                val data=if(initial!=null&&initial.optInt("id")==season.optInt("id")) initial else io.nextDataAt(io.page(season.text("slug"))).optJSONObject("episodes")?.optJSONObject("data")?:fail("PuhuTV sezonu okunamadı")
                val rows=data.optJSONArray("episodes")?:fail("PuhuTV bölüm listesi yok")
                if(data.optInt("episode_count")>rows.length()) fail("PuhuTV bölüm listesi eksik; yeniden deneyin")
                for(e in objects(rows)) {
                    val n=e.optJSONObject("meta")?.optInt("position")?:0; val ep=io.page(e.text("slug"))
                    if(e.text("type")!="asset_episode"||e.text("id").isBlank()||n !in 1..100000||ep.isBlank()||!seen.add(e.text("id"))) continue
                    val number=EpisodeNumber(season.getInt("position"),n)
                    val c=Card(ContentRef(site.id,ep,ContentKind.EPISODE),parent.title+" · "+clean(e.text("name")),puhuImage(e.text("image")).ifBlank{parent.poster},parent.year,episodeNumber=number,seriesRef=parent.ref,seriesIdentity=parent.ref.id)
                    episodes.add(Episode(c,parent.ref,number,clean(e.optJSONObject("meta")?.text("short_description").orEmpty())))
                }
            }
            if(episodes.isEmpty()) fail("PuhuTV oynatılabilir bölüm listesi yok")
        }
        return finish(input,parent,clean(meta.text("description").ifBlank{meta.text("short_description")}),parent.genres,
            objects(meta.optJSONArray("casts")).map{clean(it.text("name").ifBlank{it.text("display_name")})},episodes.sortedWith(compareBy({it.number.season},{it.number.number})),
            mapOf("seriesExternalId" to raw.text("id"),"watchPage" to io.page(raw.text("to_watch_asset_slug"))))
    }
    private fun nowSeries(body: String): List<Card> = Jsoup.parse(body).select("section.videos .list-item").mapNotNull { el ->
        val url=io.page(el.selectFirst("a[href]")?.attr("href").orEmpty()); val title=el.selectFirst(".program-name")?.text().orEmpty()
        if(!Regex("^${Regex.escape(base)}/[^/]+/izle$").matches(url)||title.isBlank()) null else Card(ContentRef(site.id,url,ContentKind.SERIES),title,image(el))
    }.distinctBy{it.ref.id}
    private fun nowArchive(next: PageCursor?): CatalogPage {
        val page=next?.page?:0
        if(page !in 0..200 || next!=null&&(next.siteId!=site.id||next.url!=base+"/ajax/archive")) fail("NOW arşiv sayfası geçersiz")
        if(page==0) {
            val body=io.body(base+"/dizi-arsivi"); val cards=nowSeries(body); val el=Jsoup.parse(body).selectFirst(".ajax-load-more-archive[data-type=series]")
            val n=el?.attr("data-page")?.toIntOrNull()?:0
            return CatalogPage(cards,if((el?.attr("data-rows")?.toIntOrNull()?:0)>cards.size&&n>0) PageCursor(site.id,base+"/ajax/archive",n) else null)
        }
        val j=io.json(base+"/ajax/archive",mapOf("page" to "$page","type" to "series","count" to "10","orderBy" to "id","sorting" to "desc"))
        if(j.opt("data") !is String || j.optInt("count",-1)<0) fail("NOW arşiv yanıtı geçersiz")
        return CatalogPage(nowSeries("<section class='videos'>"+j.getString("data")+"</section>"),if((page+1)*10<j.getInt("count")) PageCursor(site.id,base+"/ajax/archive",page+1) else null)
    }
    private fun nowDetail(input: Card): Detail {
        val url=io.page(input.ref.pageUrl); val slug=Regex("^${Regex.escape(base)}/([^/]+)/(?:izle|bolum/\\d+)$").matchEntire(url)?.groupValues?.get(1)?:fail("NOW dizi adresi geçersiz")
        val doc=Jsoup.parse(io.body(base+"/$slug/bolumler")); val btn=doc.selectFirst(".ajax-load-more-video[data-program-id][data-type=2]")?:fail("NOW bölüm listesi bulunamadı")
        val id=btn.attr("data-program-id"); val count=btn.attr("data-count").toIntOrNull()?:0
        val seasons=doc.select("#video-filter-changer option").mapNotNull{it.attr("value").toIntOrNull()?.takeIf{n->n in 1..1000}}.distinct().sorted()
        if(!Regex("\\d+").matches(id)||count !in 1..100||seasons.isEmpty()) fail("NOW sezon/bölüm bilgisi geçersiz")
        val parent=Card(ContentRef(site.id,base+"/$slug/izle",ContentKind.SERIES),doc.selectFirst("h1")?.text()?.replace(Regex("\\s*[-–]\\s*BÖLÜMLER$",RegexOption.IGNORE_CASE),"")?.ifBlank{input.title}?:input.title,doc.selectFirst("meta[property=og:image]")?.attr("content")?:input.poster)
        val episodes=mutableListOf<Episode>(); val seen=mutableSetOf<String>(); var requests=0
        for(season in seasons) { var page=0
            do {
                if(++requests>40) fail("NOW bölüm tarama sınırı aşıldı; eksik liste gösterilmeyecek")
                val j=io.json(base+"/ajax/videos",mapOf("filter" to "season","program_id" to id,"season" to "$season","page" to "$page","type" to "2","count" to "$count","orderBy" to "id","sorting" to "DESC"))
                if(j.optInt("code")!=200||j.opt("data") !is String||j.optInt("count",-1)<0) fail("NOW bölüm yanıtı geçersiz")
                var added=0
                for(a in Jsoup.parse(j.getString("data")).select("a[href]")) {
                    val ep=io.page(a.attr("href")); val n=Regex("^${Regex.escape(base)}/${Regex.escape(slug)}/bolum/(\\d+)$").matchEntire(ep)?.groupValues?.get(1)?.toIntOrNull()?:continue
                    if(n !in 1..100000||!seen.add(ep)) continue
                    val number=EpisodeNumber(season,n); val card=Card(ContentRef(site.id,ep,ContentKind.EPISODE),parent.title+" · $n. Bölüm",parent.poster,episodeNumber=number,seriesRef=parent.ref,seriesIdentity=parent.ref.id)
                    episodes.add(Episode(card,parent.ref,number)); added++
                }
                page++
                val more=page*count<j.getInt("count")
                if(more&&added==0) fail("NOW tekrarlanan/eksik bölüm sayfası döndürdü")
            } while(more)
        }
        return finish(input,parent,"",emptyList(),emptyList(),episodes.sortedWith(compareBy({it.number.season},{it.number.number})))
    }
    private fun starCard(d: FlightData, item: Any): Card? {
        val url=io.page(d.text(d.field(item,"url"))); val title=d.text(d.field(item,"name"))
        if(d.field(item,"resourceType")!="TvSeries"||!Regex("[a-fA-F0-9]{24}").matches(d.text(d.field(item,"_id")))||!Regex("^${Regex.escape(base)}/dizi/[^/]+$").matches(url)||title.isBlank()) return null
        return Card(ContentRef(site.id,url,ContentKind.SERIES),title,d.image(d.field(item,"poster")),genres=d.names(d.field(item,"genres")))
    }
    private fun starCards(d: FlightData): List<Card> {
        val groups=d.find(true){v->val a=d.field(v,"items") as? JSONArray; a!=null&&a.length()>0&&d.field(a.opt(0),"resourceType")=="TvSeries"}
        val best=groups.mapNotNull{d.field(it,"items") as? JSONArray}.maxByOrNull{it.length()}?:fail("Star TV dizi kataloğu bulunamadı")
        return (0 until best.length()).mapNotNull{starCard(d,best.get(it))}.distinctBy{it.ref.id}.also{if(it.isEmpty()) fail("Star TV kataloğu boş")}
    }
    private fun starCatalog()=getCached(base+"/dizi"){starCards(FlightData(io.body(base+"/dizi")))}
    private fun starDetail(input: Card): Detail {
        val url=io.page(input.ref.pageUrl); val parentUrl=Regex("^(${Regex.escape(base)}/dizi/[^/]+)(?:/bolumler/[^/]+)?$").matchEntire(url)?.groupValues?.get(1)?:fail("Star TV dizi adresi geçersiz")
        val d=FlightData(io.body(parentUrl)); val item=d.selected("TvSeries",parentUrl,io::page); val parent=starCard(d,item)?:fail("Star TV dizi verisi bozuk"); val id=d.text(d.field(item,"_id"))
        val episodes=mutableListOf<Episode>(); val seen=mutableSetOf<String>(); var complete=false
        for(page in 0 until 64) {
            val j=io.json(base+"/api/tv-series/$id/episodes?sort=episodeNo%20asc&skip=${page*48}&limit=48")
            val items=j.optJSONArray("items")?:fail("Star TV bölüm yanıtı okunamadı"); if(items.length()>48) fail("Star TV bölüm sayfası sınırı aştı")
            var added=0
            for(e in objects(items)) {
                val ep=io.page(e.text("url")); val n=e.optInt("episodeNo"); val s=e.optInt("seasonNo")
                if(e.text("resourceType")!="Episode"||e.text("tvSeriesId")!=id||!Regex("[a-fA-F0-9]{24}").matches(e.text("_id"))||!ep.startsWith(parentUrl+"/bolumler/")||n !in 1..100000||s !in 1..1000||!seen.add(e.text("_id"))) continue
                val number=EpisodeNumber(s,n); val card=Card(ContentRef(site.id,ep,ContentKind.EPISODE),parent.title+" · "+e.text("title").ifBlank{"$n. Bölüm"},d.image(e.opt("image")).ifBlank{parent.poster},episodeNumber=number,seriesRef=parent.ref,seriesIdentity=parent.ref.id)
                episodes.add(Episode(card,parent.ref,number)); added++
            }
            if(items.length()<48) {complete=true; break}
            if(added==0) fail("Star TV tekrarlanan bölüm sayfası döndürdü")
        }
        if(!complete) fail("Star TV bölüm tarama sınırı aşıldı; eksik liste gösterilmeyecek")
        return finish(input,parent,d.text(d.field(item,"description")?:d.field(item,"summary")),parent.genres,d.names(d.field(item,"cast")),episodes.sortedWith(compareBy({it.number.season},{it.number.number})))
    }
}
