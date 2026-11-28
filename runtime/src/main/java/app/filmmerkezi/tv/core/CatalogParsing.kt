package app.filmmerkezi.tv.core

import app.filmmerkezi.tv.NativeParsers
import app.filmmerkezi.tv.objectOf
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrl

class CatalogParser(val site:SiteSpec) {
    fun seriesIndex(body:String,page:String,offset:Int=0):CatalogPage {
        require(offset in 0..100000)
        val d=HtmlData.document(body,page)
        val nodes=d.select(when(site.id){"fixfullfilmizle"->"select option[value]";"lovefilmizle"->"#list a[href]";else->"a[href*=\"/diziler/\"]"})
        val all=nodes.mapNotNull{n->
            val url=site.catalogUrl(n.attr(if(n.normalName()=="option")"value"else"href"),page)?:return@mapNotNull null
            val pattern=when(site.id){"fixfullfilmizle"->Regex("^/diziler/[a-z0-9-]+/$");"lovefilmizle"->Regex("^/tv-dizisi/[a-z0-9-]+/$");else->Regex("^/diziler/\\d+/[a-z0-9-]+$")}
            if(!pattern.matches(url.toHttpUrl().encodedPath))return@mapNotNull null
            val title=TextKey.title(n.text()).replace(Regex("\\s+son bölüm$",RegexOption.IGNORE_CASE),"")
            if(title.isBlank())return@mapNotNull null
            Card(ContentRef(site.id,url,ContentKind.SERIES),title,if(site.id=="fixfullfilmizle")""else HtmlData.image(n,page))
        }.distinctBy{it.ref.id}
        if(all.isEmpty())throw DataFailure(FailureKind.PARSE,"Dizi indeksi bulunamadı","catalog")
        return CatalogPage(all.drop(offset).take(60),if(all.size>offset+60)PageCursor(site.id,page,offset/60+2,mapOf("indexOffset" to (offset+60).toString()))else null)
    }
    private fun num(value:String)=Regex("\\d+(?:[.,]\\d+)?").find(value)?.value?.replace(',','.')?.toDoubleOrNull()
    private fun year(value:String)=Regex("\\b(?:19|20)\\d{2}\\b").find(value)?.value?.toInt()
    private fun language(n:Element):String {
        val explicit=HtmlData.text(n,site.language)
        val all=explicit+" "+n.select(".tr-dublaj, .tr-altyazi, .languages").joinToString(" "){it.text()+" "+it.className()}
        val icons=n.select("use").joinToString(" "){it.attr("href")+" "+it.attr("xlink:href")}
        val dub=Regex("dublaj|tr-dublaj|\\bdual\\b",RegexOption.IGNORE_CASE).containsMatchIn(all)||icons.contains("#icon_flag_tr")||(site.id=="hdfilmdelisi"&&n.select("svg rect[fill=\"#E30A17\"]").isNotEmpty())
        val sub=Regex("altyaz|tr-altyazi|\\bdual\\b",RegexOption.IGNORE_CASE).containsMatchIn(all)||icons.contains("#icon_subtitle")||(site.id=="hdfilmdelisi"&&n.select("svg.lucide-captions, svg.lucide-subtitles").isNotEmpty())
        return if(dub&&sub)"Dublaj & Altyazılı" else if(dub)"Türkçe Dublaj" else if(sub)"Türkçe Altyazılı" else ""
    }
    private fun legacy(method:String,body:String,url:String,card:Card?=null):JSONObject {
        val args=JSONArray().put(body)
        if(method=="detail")args.put(objectOf("contentId" to card!!.ref.id,"providerId" to site.id,"pageUrl" to url,"type" to card.ref.kind.name.lowercase(),"title" to card.title,"poster" to card.poster,"year" to card.year,"imdb" to card.imdb,"language" to card.language))
        else args.put(url)
        return NativeParsers.parse(objectOf("site" to if(site.id=="filmcibaba")"filmizle" else "hdf","method" to method,"args" to args)) as JSONObject
    }
    private fun fromJson(o:JSONObject,fallback:Card?=null):Card {
        val page=site.catalogUrl(o.string("pageUrl"))?:throw DataFailure(FailureKind.PARSE,"İçerik adresi geçersiz","parse")
        val kind=if(fallback?.ref?.kind==ContentKind.EPISODE)ContentKind.EPISODE
            else runCatching{ContentKind.valueOf(o.string("type").uppercase())}.getOrDefault(site.kind(page))
        return Card(ContentRef(site.id,if(kind==ContentKind.EPISODE)page else site.canonical(page),kind),o.string("title").ifBlank{fallback?.title?:""},
            o.string("poster").ifBlank{fallback?.poster?:""},o.opt("year")?.toString()?.toDoubleOrNull()?.toInt(),
            o.opt("imdb")?.toString()?.toDoubleOrNull(),o.string("language"),o.string("originalTitle"),o.string("imdbId"),
            o.optJSONArray("titleAliases")?.values()?.map{it.toString()}?:emptyList(),fallback?.seriesIdentity?:"",fallback?.episodeNumber,fallback?.seriesRef,yearHint=o.optInt("yearHint").takeIf{it in 1888..2100}?:fallback?.yearHint,sourceSeason=o.optInt("sourceSeason").takeIf{it in 1..1000}?:fallback?.sourceSeason?:if(kind==ContentKind.SERIES)SeriesIdentity.season(o.string("title"))else null)
    }
    fun list(body:String,page:String):CatalogPage {
        if(site.id in TrackedCatalog.sites)return TrackedCatalog(site).list(body,page)
        HtmlData.verify(body) // Reject challenge responses before schema-specific parsing.
        if(site.id=="dizipal")return DizipalParser.list(site,body,page)
        if(site.id=="diziboxum")return DiziboxumParser.list(site,body,page)
        if(site.id in setOf("filmcibaba","hdfilmcehennemi")) {
            val data=legacy("list",body,page)
            val cards=data.optJSONArray("cards")?.values()?.filterIsInstance<JSONObject>()?.map{fromJson(it)}?:emptyList()
            val n=data.optJSONObject("next")
            val cursor=n?.let{
                val p=it.optInt("page",2)
                when(it.string("kind")){
                    "discover"->PageCursor(site.id,site.base+"/film-robotu-1/",p,mapOf("page" to p.toString(),"type" to "1","genres" to "[]","years" to "","imdb" to "","lang" to "[]","order" to "posts.imdb desc"))
                    "load"->it.string("action").takeIf{a->a.matches(Regex("[A-Za-z0-9_-]+"))}?.let{a->PageCursor(site.id,site.base+"/load/page/$p/$a/",p)}
                    else->it.string("url").takeIf{u->u.isNotBlank()}?.let{u->PageCursor(site.id,u,p)}
                }
            }
            return CatalogPage(cards.take(60),cursor)
        }
        if(site.id=="hdfilmdelisi" && body.trimStart().startsWith("{"))return apiList(JSONObject(body),page)
        val d=HtmlData.document(body,page)
        val scope=if(site.id=="hdfilmdelisi")d else if(site.id=="turkcefilmler")d.selectFirst("main .lists")?:d.selectFirst("main")?:d else d.selectFirst("[data-search-results], #search-results, .search-results-grid, main")?:d
        val nodes=scope.select(site.cards)
        val parsed=nodes.mapNotNull { box ->
            val a=if(box.normalName()=="a")box else box.select("a[href]").firstOrNull { link ->
                site.catalogUrl(link.attr("href"),page)?.let{site.contentPattern.matches(it.toHttpUrl().encodedPath)}==true
            }
            val url=site.catalogUrl(a?.attr("href")?:"",page)?:return@mapNotNull null
            if(!site.contentPattern.matches(url.toHttpUrl().encodedPath))return@mapNotNull null
            if(site.id=="filmizlebabam" && site.kind(url)==ContentKind.EPISODE)return@mapNotNull null
            if(box.hasClass("soon"))return@mapNotNull null
            val title=TextKey.title(HtmlData.text(box,site.title).ifBlank { box.attr("data-title") }.ifBlank { a?.attr("title")?:"" }.ifBlank { HtmlData.text(a) })
            if(title.isBlank())return@mapNotNull null
            val kind=if(site.id=="lovefilmizle"&&SeriesIdentity.season(title)!=null)ContentKind.SERIES else site.kind(url)
            Card(ContentRef(site.id,site.canonical(url),kind),title,HtmlData.image(box,page),
                year(if(site.id=="hdfilmdelisi")box.text()else HtmlData.text(box,site.year)),
                if(site.id=="hdfilmdelisi")Regex("★\\s*(\\d+(?:\\.\\d+)?)").find(box.text())?.groupValues?.get(1)?.toDoubleOrNull()else num(HtmlData.text(box,site.imdb)),language(box),sourceSeason=if(kind==ContentKind.SERIES)SeriesIdentity.season(title)else null)
        }
        val dedup=linkedMapOf<String,Card>()
        parsed.forEach{c->val old=dedup[c.ref.id];dedup[c.ref.id]=if(old==null)c else old.copy(poster=old.poster.ifBlank{c.poster},year=old.year?:c.year,imdb=old.imdb?:c.imdb,language=old.language.ifBlank{c.language})}
        val cards=dedup.values.take(60)
        if(cards.isEmpty() && site.id=="dizibox") {
            val index=d.select(".alphabetical-category-list a[href]").mapNotNull { a ->
                val u=site.catalogUrl(a.attr("href"),page)?:return@mapNotNull null
                if(!site.contentPattern.matches(u.toHttpUrl().encodedPath))null else Card(ContentRef(site.id,u,ContentKind.SERIES),HtmlData.text(a))
            }.distinctBy { it.ref.id }
            if(index.isNotEmpty())return CatalogPage(index)
        }
        if(cards.isEmpty() && nodes.isEmpty() && site.id=="hdfilmdelisi") {
            val data=findJson(HtmlData.flight(body)){it.has("initialFilms")}
            if(data!=null)return CatalogPage(data.optJSONArray("initialFilms")?.values()?.filterIsInstance<JSONObject>()?.mapNotNull{dto(it,ContentKind.MOVIE)}?:emptyList())
        }
        if(nodes.isEmpty() && cards.isEmpty() && !Regex("sonuç bulunamadı|bulunamadi|no results|arama sonuç|arşivimizde bulunmamaktadır|kriterlerinize uygun film yok",RegexOption.IGNORE_CASE).containsMatchIn(d.text()))
            throw DataFailure(FailureKind.PARSE,"${site.name} katalog biçimi tanınamadı","catalog")
        return CatalogPage(cards,next(d,page))
    }
    private fun dto(o:JSONObject,kind:ContentKind):Card? {
        if(o.string("baslik").isBlank() || !Regex("^[a-z0-9-]+$").matches(o.string("slug")) || o.string("accessLevel").let{it.isNotBlank() && it!="public"})return null
        val page=site.base+(if(kind==ContentKind.SERIES)"/dizi/" else "/film/")+o.string("slug")
        val lang=mapOf("dublaj" to "Türkçe Dublaj","altyazi" to "Türkçe Altyazılı","dublaj-altyazi" to "Dublaj & Altyazılı")[o.string("dublajDurumu")]?:""
        return Card(ContentRef(site.id,page,kind),o.string("baslik"),publicUrl(o.string("afis"),site.base)?:"",
            o.opt("yayinYili")?.toString()?.toIntOrNull(),o.opt("imdbPuani")?.toString()?.toDoubleOrNull(),lang,o.string("orijinalBaslik"),o.string("imdbId"))
    }
    private fun apiList(data:JSONObject,page:String):CatalogPage {
        if(data.has("error")||listOf("results","films","series").none{data.opt(it) is JSONArray})throw DataFailure(FailureKind.PARSE,"Katalog API yanıtı tanınamadı","catalog")
        val films=data.optJSONArray("results")?:data.optJSONArray("films")?:JSONArray()
        val shows=data.optJSONArray("series")?:JSONArray()
        val cards=films.values().filterIsInstance<JSONObject>().mapNotNull{dto(it,ContentKind.MOVIE)}+
            shows.values().filterIsInstance<JSONObject>().mapNotNull{dto(it,ContentKind.SERIES)}
        val url=page.toHttpUrl();val p=url.queryParameter("page")?.toIntOrNull()?:1
        val next=if(data.optBoolean("hasMore")||data.optInt("totalPages")>p)PageCursor(site.id,url.newBuilder().setQueryParameter("page",(p+1).toString()).build().toString(),p+1)else null
        return CatalogPage(cards.distinctBy{it.ref.id}.take(60),next)
    }
    fun next(d:Document,page:String):PageCursor? {
        fun pageNumber(value:String):Int {
            val url=value.toHttpUrl()
            return Regex("(?:/page/|/sayfa/|/sayfa-|/yeni-filmler/)(\\d+)").find(url.encodedPath)?.groupValues?.get(1)?.toInt()
                ?:url.queryParameter("page")?.toIntOrNull()
                ?:if(site.id=="ddizi" && url.encodedPath.startsWith("/diziler/"))0 else 1
        }
        if(site.id=="ddizi" && (page.toHttpUrl().encodedPath=="/" || page.toHttpUrl().encodedPath=="/l.php")){
            val max=d.select("#listele a[rel]").mapNotNull{it.attr("rel").toIntOrNull()}.maxOrNull()?:1
            val current=page.toHttpUrl().queryParameter("sayfa")?.toIntOrNull()?:1
            if(current<max)return PageCursor(site.id,site.base+"/l.php?sayfa="+(current+1),current+1,mapOf("max" to max.toString()))
        }
        val u=d.select("a[href]").firstNotNullOfOrNull { a ->
            val value=site.catalogUrl(a.attr("href"),page)?:return@firstNotNullOfOrNull null
            val current=page.toHttpUrl();val target=value.toHttpUrl()
            val old=pageNumber(page)
            val n=pageNumber(value)
            if(value!=page && (a.attr("rel")=="next" || a.hasClass("next") || a.hasClass("nextpostslink") || n==old+1))value else null
        }
        if(u!=null)return PageCursor(site.id,u,pageNumber(u))
        if(site.id=="sinemacc"){
            val body=d.outerHtml()
            fun number(name:String)=(runCatching{Literals.after(body,"\\b(?:let|var|const)\\s+"+name+"\\s*=\\s*")}.getOrNull() as? Number)?.toInt()
            val current=number("currentPage");val total=number("totalpages")
            val base=runCatching{Literals.after(body,"\\b(?:let|var|const)\\s+load_adres\\s*=\\s*") as? String}.getOrNull()
            val path=page.toHttpUrl().encodedPath.replace(Regex("page/\\d+/$"),"")
            if(current!=null&&total!=null&&current in 1 until total&&total<=100000&&base!=null&&site.catalogUrl(base)?.toHttpUrl()?.encodedPath==path)
                return PageCursor(site.id,page.toHttpUrl().newBuilder().encodedPath(path+"page/"+(current+1)+"/").build().toString(),current+1)
        }
        return null
    }
    fun detail(body:String,card:Card):Detail {
        if(site.id in TrackedCatalog.sites)return TrackedCatalog(site).detail(body,card)
        if(card.ref.kind==ContentKind.COLLECTION||CollectionParser.recognizes(body,card))return CollectionParser.detail(body,card)
        if(site.id=="diziboxum")return DiziboxumParser.detail(site,body,card)
        HtmlData.verify(body)
        if(site.id=="dizipal")return DizipalParser.detail(site,body,card)
        if(site.id=="filmcibaba"&&(card.ref.kind==ContentKind.COLLECTION||card.ref.pageUrl.toHttpUrl().encodedPath.startsWith("/haber/")))return CollectionParser.detail(body,card)
        if(site.id in setOf("filmcibaba","hdfilmcehennemi")) {
            val o=legacy("detail",body,card.ref.pageUrl,card)
            val result=fromJson(o,card)
            val eps=o.optJSONArray("episodes")?.values()?.filterIsInstance<JSONObject>()?.mapNotNull { e ->
                val n=e.optJSONObject("episode")?:return@mapNotNull null
                val s=n.optInt("season");val v=n.optInt("number")
                if(s<1||v<1)return@mapNotNull null
                Episode(fromJson(e).copy(ref=ContentRef(site.id,e.string("pageUrl"),ContentKind.EPISODE),seriesIdentity=ContentIdentity.key(result),episodeNumber=EpisodeNumber(s,v)),result.ref,EpisodeNumber(s,v),e.string("description"))
            }?:emptyList()
            return Detail(result,o.string("description"),o.optJSONArray("genres")?.values()?.map{it.toString()}?:emptyList(),
                o.optJSONArray("cast")?.values()?.map{it.toString()}?:emptyList(),o.string("duration"),eps,
                options=if(result.ref.kind==ContentKind.COLLECTION)emptyList()else SourceMenus.parse(site,body,result.ref,card.ref.pageUrl),collectionItems=if(result.ref.kind==ContentKind.COLLECTION)CollectionParser.entries(body,card.ref.pageUrl)else emptyList(),seasons=o.optJSONArray("seasons")?.values()?.mapNotNull{it.toString().toIntOrNull()}?:eps.map{it.number.season}.distinct())
        }
        val d=HtmlData.document(body,card.ref.pageUrl)
        if(site.id=="lovefilmizle"&&SeriesIdentity.archive(card.ref)){
            val title=TextKey.title(HtmlData.text(d,"h1.archive-title"));if(title.isBlank())throw DataFailure(FailureKind.PARSE,"Dizi arşivi okunamadı","detail")
            val seasons=list(body,card.ref.pageUrl).cards.filter{it.ref.kind==ContentKind.SERIES}
            val first=seasons.firstOrNull{it.sourceSeason==1}
            return Detail(card.copy(ref=card.ref.copy(kind=ContentKind.SERIES),title=title,year=first?.year,poster=first?.poster?:card.poster),episodePages=seasons.map{it.ref.pageUrl},seasons=seasons.mapNotNull{it.sourceSeason}.distinct().sorted())
        }
        val schemas=HtmlData.schemas(d)
        val episodeId=EpisodePages.ddiziEpisodeId(site,card.ref.pageUrl)
        fun matchingEpisode(value:String)=EpisodePages.ddiziEpisodeId(site,value,card.ref.pageUrl,legacyMetadata=true)==episodeId
        val schema=if(episodeId!=null)schemas.firstOrNull{it.string("@type")=="TVEpisode"&&matchingEpisode(it.string("url"))}
            else schemas.firstOrNull{it.string("@type") in setOf("Movie","TVSeries","TVEpisode")}
        fun canonical(value:String)=site.catalogUrl(value,card.ref.pageUrl)?.let{site.canonical(it).toHttpUrl().newBuilder().query(null).build().toString()}
        val requested=canonical(card.ref.pageUrl)
        val declaredAddress=d.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf(String::isNotBlank)
        val declared=declaredAddress?.let(::canonical)
        val schemaUrl=schema?.string("url")?.takeIf{it.isNotBlank()}?.let(::canonical)
        if(if(episodeId!=null)declaredAddress!=null&&!matchingEpisode(declaredAddress)
            else (declared!=null&&declared!=requested)||(schemaUrl!=null&&schemaUrl!=requested))
            throw DataFailure(FailureKind.PARSE,"İçerik kimliği yanıtla eşleşmiyor","detail")
        val heading=if(site.id=="hdfilmdelisi")d.select("h1").firstOrNull{TextKey.fold(it.text())!="film izle"}else d.selectFirst(site.detailTitle)
        val rawTitle=if(site.id=="fixfullfilmizle")FixFullSearch.archiveTitle(site,d,card.ref)?:HtmlData.text(heading)
            else if(site.id in setOf("filmizlebabam","filmifullizle","filmizlehell","lovefilmizle","fullhdfilmizlesene","filmmakinesi"))schema?.string("name").orEmpty().ifBlank{HtmlData.text(heading)} else HtmlData.text(heading).ifBlank{schema?.string("name")?:""}
        val title=TextKey.title(rawTitle.replace(Regex("\\s+izle\\s*\\(\\d{4}\\)$",RegexOption.IGNORE_CASE),"").replace(Regex("\\s+son bölüm(?: izle)?$",RegexOption.IGNORE_CASE),""))
        if(title.isBlank())throw DataFailure(FailureKind.PARSE,"${site.name} detay biçimi tanınamadı","detail")
        val path=card.ref.pageUrl.toHttpUrl().encodedPath
        val seriesAddress=when(site.id){
            "dizibox","ddizi","fixfullfilmizle"->path.startsWith("/diziler/")
            "filmizlebabam","hdfilmdelisi"->Regex("^/dizi/[^/]+/?$").matches(path)
            else->false
        }
        // Repair previously persisted Movie/TVEpisode labels when the page itself
        // is a verified series archive, rather than depending on that stale label.
        val seriesLayout=SeriesIdentity.archive(card.ref)||(seriesAddress||card.ref.kind==ContentKind.SERIES)&&d.selectFirst("#seasons-list, .seasons, .dizi-boxpost-cat, .moviefilm")!=null||site.id=="lovefilmizle"&&SeriesIdentity.season(title)!=null
        val kind=when{
            episodeId!=null||card.ref.kind==ContentKind.EPISODE&&card.episodeNumber!=null->ContentKind.EPISODE
            seriesLayout->ContentKind.SERIES
            schema?.string("@type")=="TVSeries"->ContentKind.SERIES
            schema?.string("@type")=="TVEpisode"->ContentKind.EPISODE
            else->card.ref.kind
        }
        val imdbId=card.imdbId // PageIdentity reads only primary-page metadata, never recommendation links.
        val specialInfo=when(site.id){"turkcefilmler"->d.selectFirst("#video_infos");"fixfullfilmizle"->d.selectFirst(".filmaltiaciklama");else->null}
        val fieldYear=when(site.id){"turkcefilmler"->HtmlData.text(specialInfo,".year");"fixfullfilmizle"->Regex("Yapım\\s*:?\\s*((?:19|20)\\d{2})").find(specialInfo?.text()?:"")?.groupValues?.get(1).orEmpty();"sinemacc"->schema?.string("dateCreated").orEmpty().ifBlank{schema?.string("datePublished").orEmpty()};"filmmakinesi"->HtmlData.text(d,".date");"filmifullizle"->HtmlData.text(d,"#filmbilgileri a[href*=\"/yil/\"]");else->HtmlData.text(d,".film-yil")}
        val fieldImdb=when(site.id){"turkcefilmler"->num(HtmlData.text(specialInfo,".imdb"));"fixfullfilmizle"->Regex("IMDB Puanı\\s*:?\\s*(\\d+(?:[.,]\\d+)?)").find(specialInfo?.text()?:"")?.groupValues?.get(1)?.let(::num);"filmifullizle"->num(HtmlData.text(d,"#listelements .elements:nth-child(2)"));else->null}
        val image=if(site.id=="turkcefilmler")HtmlData.image(specialInfo,card.ref.pageUrl) else when(val v=schema?.opt("image")){is String->publicUrl(v,card.ref.pageUrl);is JSONObject->publicUrl(v.string("url"),card.ref.pageUrl);else->null}
        val poster=image?.ifBlank{null}?:publicUrl(d.selectFirst("meta[property=og:image]")?.attr("content")?:"",card.ref.pageUrl)
        val enriched=card.copy(ref=card.ref.copy(kind=kind),title=title,poster=if(site.id=="fixfullfilmizle"&&SeriesIdentity.archive(card.ref)&&card.poster.isNotBlank())card.poster
            else poster?:HtmlData.image(d.selectFirst(".film-afis, #main-cover, .filmaltiimg, .poster, [itemprop=image], .content_ .dizi-boxpost-cat"),card.ref.pageUrl).ifBlank{card.poster},
            year=card.year?:year(fieldYear)?:year(schema?.string("startDate")?:schema?.string("releaseDate")?:"")?:if(site.id=="hdfilmdelisi")year(schema?.string("datePublished")?:"")else null,
            imdb=fieldImdb?:schema?.optJSONObject("aggregateRating")?.opt("ratingValue")?.toString()?.toDoubleOrNull()?:card.imdb?:num(HtmlData.text(d,".imdb")),
            originalTitle=schema?.string("alternateName")?.ifBlank{card.originalTitle}?:card.originalTitle,
            imdbId=imdbId,language=if(site.id in setOf("hdfilmdelisi","filmifullizle")){
                val meta=HtmlData.text(d,if(site.id=="hdfilmdelisi")".meta-x-scroll"else ".filmplus_sources .languages")
                val dub=meta.contains("Dublaj",true);val sub=meta.contains("Altyaz",true)
                if(dub&&sub)"Dublaj & Altyazılı"else if(dub)"Türkçe Dublaj"else if(sub)"Türkçe Altyazılı"else card.language
            }else card.language.ifBlank{if(site.id=="turkcefilmler")language(specialInfo?:d)else ""})
        val description=HtmlData.text(d,site.description).ifBlank { schema?.string("description")?.let{Jsoup.parse(it).text()}?:"" }
        val schemaGenres=when(val g=schema?.opt("genre")){is JSONArray->g.values().filterIsInstance<String>();is String->listOf(g);else->emptyList()}
        val genres=schemaGenres.ifEmpty{d.select(site.genres).filter{n->n.parents().none{it.normalName() in setOf("nav","aside","footer","header")||it.normalName()=="form"}}.map{HtmlData.text(it)}.filter{it.isNotBlank()}}.distinct().take(30)
        val cast=schema?.optJSONArray("actor")?.values()?.filterIsInstance<JSONObject>()?.map{it.string("name")}?:d.select("[itemprop=actor], a[href*=\"/oyuncu/\"]").map{it.text()}
        val hdfSeries=if(site.id=="hdfilmdelisi"&&kind==ContentKind.SERIES)hdfSeriesData(d,enriched)else null
        val episodes=if(kind==ContentKind.SERIES)episodes(d,enriched,hdfSeries)else emptyList()
        val seasonPages=if(kind==ContentKind.SERIES)EpisodePages.links(site,d,enriched,card.ref.pageUrl)else emptyList()
        val declaredSeasonNumbers=episodes.map{it.number.season}+d.select("#seasons-list a, #seasons [data-bs-target]").mapNotNull{SeriesIdentity.season(it.text())}+listOfNotNull(enriched.sourceSeason)+
            (hdfSeries?.optJSONArray("seasons")?:hdfSeries?.optJSONArray("sezonlar"))?.values()?.filterIsInstance<JSONObject>().orEmpty().mapNotNull{it.optInt("sezonNo").takeIf{n->n in 1..1000}}
        return Detail(enriched,description,genres,cast.take(60),schema?.string("duration")?:"",episodes,seasonPages,
            SourceMenus.parse(site,body,enriched.ref),seasonPages.firstOrNull()?.let{PageCursor(site.id,it)},seasons=declaredSeasonNumbers.distinct().sorted())
    }
    private fun hdfSeriesData(d:Document,parent:Card):JSONObject?{
        val slug=parent.ref.pageUrl.toHttpUrl().encodedPath.trim('/').split('/').getOrNull(1)
        val expected=TextKey.fold(ContentIdentity.cleanTitle(parent.title))
        val data=findJson(HtmlData.flight(d.outerHtml())){
            val localized=it.optJSONArray("sezonlar")!=null&&it.string("diziId").isNotBlank()&&it.string("slug")==slug
            val legacy=it.has("seriesTitle")&&it.optJSONArray("seasons")!=null&&
                (if(it.string("slug").isNotBlank())it.string("slug")==slug else TextKey.fold(ContentIdentity.cleanTitle(it.string("seriesTitle")))==expected)
            localized||legacy
        }
        if(data==null&&HtmlData.schemas(d).any{it.string("@type")=="TVSeries"&&it.optInt("numberOfEpisodes")>0})throw DataFailure(FailureKind.PARSE,"Dizinin bölüm verisi bu sayfayla eşleştirilemedi","episodes")
        return data
    }
    fun episodes(d:Document,parent:Card,seriesData:JSONObject?=null):List<Episode> {
        if(site.id in TrackedCatalog.sites)return TrackedCatalog(site).episodes(d,parent)
        if(site.id=="diziboxum")return DiziboxumParser.episodes(site,d,parent)
        if(site.id=="dizipal")return DizipalParser.episodes(site,d,parent)
        val base=d.baseUri().takeIf{site.catalogUrl(it)!=null}?:parent.ref.pageUrl
        val defaultSeason=base.toHttpUrl().queryParameter("sezon")?.toIntOrNull()?.takeIf{it in 1..1000}?:parent.sourceSeason?:
            Regex("(\\d+)\\s*\\.?\\s*Sezon",RegexOption.IGNORE_CASE).find(parent.title)?.groupValues?.get(1)?.toIntOrNull()?:1
        val selector=when(site.id){"ddizi"->EpisodePages.DDIZI_EPISODE_LINKS;"dizibox"->"a.season-episode";"fixfullfilmizle"->".leftC .moviefilm .movief a";"filmmakinesi"->site.episodeSelector;else->site.episodeSelector+", .seasons a[href], .p-v12-ep-list a[href]"}
        val out=d.select(selector).filter{a->a.parents().none{it.normalName() in setOf("aside","footer")||Regex("recommend|related|benzer|oneri").containsMatchIn(it.className().lowercase()+" "+it.id().lowercase())}}.mapNotNull { a ->
            val page=site.catalogUrl(a.attr("href"),base)?:return@mapNotNull null
            if(site.id=="hdfilmdelisi"&&!page.toHttpUrl().encodedPath.startsWith(parent.ref.pageUrl.toHttpUrl().encodedPath.trimEnd('/')+"/sezon-"))
                return@mapNotNull null
            val label=HtmlData.text(a)
            val pair=episodeNumber(label,page,defaultSeason)?:return@mapNotNull null
            val c=Card(ContentRef(site.id,page,ContentKind.EPISODE),label.ifBlank{"${parent.title} · ${pair.season}×${pair.number}"},HtmlData.image(a,page).ifBlank{parent.poster},parent.year,language=parent.language,seriesIdentity=ContentIdentity.key(parent),episodeNumber=pair,seriesRef=parent.ref.takeIf{site.id=="ddizi"})
            Episode(c,parent.ref,pair)
        }.toMutableList()
        if(site.id=="hdfilmdelisi") {
            val data=seriesData?:hdfSeriesData(d,parent)
            (data?.optJSONArray("seasons")?:data?.optJSONArray("sezonlar"))?.values()?.filterIsInstance<JSONObject>()?.forEach { season ->
                (season.optJSONArray("episodes")?:season.optJSONArray("bolumler"))?.values()?.filterIsInstance<JSONObject>()?.forEach { e ->
                    val s=e.optInt("sezonNo",season.optInt("sezonNo"));val n=e.optInt("bolumNo")
                    if(season.optInt("sezonNo")>0&&s!=season.optInt("sezonNo"))throw DataFailure(FailureKind.PARSE,"Bölüm kendi sezonuyla eşleşmiyor","episodes")
                    if(s in 1..1000&&n in 1..100000) { val p=parent.ref.pageUrl.trimEnd('/')+"/sezon-$s/bolum-$n"
                        out.add(Episode(Card(ContentRef(site.id,p,ContentKind.EPISODE),e.string("ad").ifBlank{"$s. Sezon $n. Bölüm"},parent.poster,parent.year,seriesIdentity=ContentIdentity.key(parent),episodeNumber=EpisodeNumber(s,n)),parent.ref,EpisodeNumber(s,n),e.string("aciklama"))) }
                }
            }
        }
        return out.distinctBy{"${it.number.season}:${it.number.number}"}.sortedWith(compareBy({it.number.season},{it.number.number}))
    }
    companion object {
        fun episodeNumber(label:String,url:String,defaultSeason:Int=1):EpisodeNumber? {
            val m=Regex("(\\d+)\\s*\\.?\\s*Sezon\\s*(\\d+)\\s*\\.?\\s*Bölüm",RegexOption.IGNORE_CASE).find(label)
                ?:Regex("(?:-|/)sezon-(\\d+)/(?:bolum-)(\\d+)|-(\\d+)-sezon-(\\d+)-bolum").find(url)
            if(m!=null) {
                val nums=m.groupValues.drop(1).filter{it.isNotBlank()}.mapNotNull{it.toIntOrNull()}
                if(nums.size>=2&&nums[0]>0&&nums[1]>0)return EpisodeNumber(nums[0],nums[1])
            }
            val n=Regex("(\\d+)\\s*\\.?\\s*Bölüm",RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
            return n?.takeIf{it>0}?.let{EpisodeNumber(defaultSeason,it)}
        }
    }
}
