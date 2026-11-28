package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray

/** Public catalog only. Login, comments, ads and remote scripts are never executed. */
object DiziboxumParser {
    private fun year(s:String)=Regex("\\b(?:19|20)\\d{2}\\b").find(s)?.value?.toIntOrNull()
    private fun rating(s:String)=Regex("\\d+(?:[.,]\\d+)?").find(s)?.value?.replace(',','.')?.toDoubleOrNull()?.takeIf{it in 0.0..10.0}
    private fun language(node:Element):String {
        val text=TextKey.fold(node.select(".r-lang, .r-dil-ik").joinToString(" "){it.text()+" "+it.attr("title")})
        val dub="dublaj" in text;val sub="altyaz" in text
        return if(dub&&sub)"Dublaj & Altyazılı"else if(dub)"Türkçe Dublaj"else if(sub)"Türkçe Altyazılı"else ""
    }
    fun list(site:SiteSpec,body:String,page:String):CatalogPage {
        val d=HtmlData.document(body,page);val scope=d.selectFirst("main")?:d
        val nodes=scope.select("a.r-card, .single-item")
        val cards=nodes.mapNotNull{n->
            val a=if(n.normalName()=="a")n else n.selectFirst(".categorytitle a[href]")?:return@mapNotNull null
            val url=site.catalogUrl(a.attr("href"),page)?:return@mapNotNull null
            if(!site.contentPattern.matches(url.toHttpUrl().encodedPath))return@mapNotNull null
            val title=TextKey.title(HtmlData.text(n,".r-cap-t, .categorytitle")).ifBlank{a.attr("title")}
            if(title.isBlank())return@mapNotNull null
            val ownYear=HtmlData.text(n,".r-cap-s").ifBlank{Regex("yapim yili\\s*:?\\s*((?:19|20)\\d{2})").find(TextKey.fold(n.text()))?.groupValues?.get(1).orEmpty()}
            Card(ContentRef(site.id,url,site.kind(url)),title,HtmlData.image(n,page),year(ownYear),rating(HtmlData.text(n,".r-imdb, .imdbp")),language(n),episodeNumber=CatalogParser.episodeNumber("",url))
        }.distinctBy{it.ref.id}
        if(nodes.isEmpty()&&!Regex("sonuc bulunamadi|icerik bulunamadi|hicbir sonuc|bulunamadi").containsMatchIn(TextKey.fold(d.text())))
            throw DataFailure(FailureKind.PARSE,"Diziboxum katalog biçimi tanınamadı","catalog")
        val current=page.toHttpUrl().queryParameter("sayfa")?.toIntOrNull()?:Regex("/page/(\\d+)/").find(page)?.groupValues?.get(1)?.toIntOrNull()?:1
        val next=d.select("a.next.page-numbers[href]").firstNotNullOfOrNull{a->
            val u=site.catalogUrl(a.attr("href"),page)?:return@firstNotNullOfOrNull null
            val n=u.toHttpUrl().queryParameter("sayfa")?.toIntOrNull()?:Regex("/page/(\\d+)/").find(u)?.groupValues?.get(1)?.toIntOrNull()
            n?.takeIf{it==current+1&&it<=100000}?.let{PageCursor(site.id,u,it)}
        }
        return CatalogPage(cards.take(60),next)
    }
    fun detail(site:SiteSpec,body:String,seed:Card):Detail {
        val d=HtmlData.document(body,seed.ref.pageUrl)
        fun page(s:String)=site.catalogUrl(s,seed.ref.pageUrl)?.trimEnd('/')
        val expected=page(seed.ref.pageUrl)
        val canonical=d.selectFirst("link[rel=canonical]")?.attr("href")?.let(::page)
        if(canonical!=null&&canonical!=expected)throw DataFailure(FailureKind.PARSE,"Diziboxum yanıtı başka içeriğe ait","detail")
        val schema=HtmlData.schemas(d).firstOrNull{it.string("@type") in setOf("TVSeries","TVEpisode")&&page(it.string("url"))==expected}
            ?:throw DataFailure(FailureKind.PARSE,"Diziboxum ana içerik kimliği bulunamadı","detail")
        val episode=schema.string("@type")=="TVEpisode"
        val parent=schema.optJSONObject("partOfSeries")
        val parentRef=if(episode)parent?.string("url")?.let{site.catalogUrl(it,seed.ref.pageUrl)}?.takeIf{SeriesIdentity.archive(ContentRef(site.id,it,ContentKind.SERIES))}?.let{ContentRef(site.id,it,ContentKind.SERIES)}else null
        val number=if(episode){
            val s=schema.optJSONObject("partOfSeason")?.optInt("seasonNumber")?:0;val n=schema.optInt("episodeNumber")
            if(s !in 1..1000||n !in 1..100000||parentRef==null)throw DataFailure(FailureKind.PARSE,"Diziboxum bölüm kimliği eksik","detail")
            val pair=EpisodeNumber(s,n);val fromUrl=CatalogParser.episodeNumber("",seed.ref.pageUrl)
            if(fromUrl!=null&&fromUrl!=pair||seed.episodeNumber!=null&&seed.episodeNumber!=pair)throw DataFailure(FailureKind.PARSE,"Diziboxum sezon/bölüm kimliği çelişiyor","detail")
            pair
        }else null
        val title=TextKey.title(if(episode)parent!!.string("name")else schema.string("name"))
        if(title.isBlank())throw DataFailure(FailureKind.PARSE,"Diziboxum başlık eksik","detail")
        val card=seed.copy(ref=seed.ref.copy(kind=if(episode)ContentKind.EPISODE else ContentKind.SERIES),title=title,
            poster=seed.poster.ifBlank{publicUrl(schema.string("image"),seed.ref.pageUrl).orEmpty()},
            year=if(episode)seed.year else year(schema.string("startDate")),
            imdb=if(episode)seed.imdb else rating(HtmlData.text(d,".r-dinfo .r-meta .r-imdb")),
            language=seed.language.ifBlank{language(d.selectFirst(if(episode)".r-ep-bas"else".r-dinfo")?:d)},
            seriesRef=parentRef?:seed.seriesRef,episodeNumber=number?:seed.episodeNumber)
        val genres=(schema.opt("genre")as? JSONArray)?.values()?.filterIsInstance<String>().orEmpty()
        val cast=schema.optJSONArray("actor")?.values()?.filterIsInstance<org.json.JSONObject>()?.map{it.string("name")}.orEmpty()
        val episodes=if(episode)emptyList()else episodes(site,d,card)
        return Detail(card.copy(genres=genres),schema.string("description"),genres,cast.take(60),episodes=episodes,
            options=if(episode)sources(site,d,card.ref)else emptyList(),seasons=episodes.map{it.number.season}.distinct().sorted())
    }
    fun episodes(site:SiteSpec,d:Document,parent:Card):List<Episode> = d.select(".r-bolum a[href], a.r-epk[href]").mapNotNull{a->
        if(a.parents().any{it.normalName() in setOf("aside","footer")})return@mapNotNull null
        val url=site.catalogUrl(a.attr("href"),parent.ref.pageUrl)?:return@mapNotNull null
        val pair=CatalogParser.episodeNumber("",url)?:return@mapNotNull null
        val card=Card(ContentRef(site.id,url,ContentKind.EPISODE),parent.title,parent.poster,parent.year,parent.imdb,language(a).ifBlank{parent.language},
            seriesIdentity=ContentIdentity.key(parent),episodeNumber=pair,seriesRef=parent.ref)
        Episode(card,parent.ref,pair)
    }.distinctBy{it.number}.sortedWith(compareBy({it.number.season},{it.number.number}))
    fun sources(site:SiteSpec,d:Document,ref:ContentRef):List<SourceOption> {
        if(ref.kind!=ContentKind.EPISODE)return emptyList()
        val out=mutableListOf<SourceOption>();val current=ref.pageUrl.toHttpUrl();val base=current.encodedPath.replace(Regex("/[1-9]\\d*/$"),"/")
        d.select(".video-container iframe").forEachIndexed{i,node->
            val raw=listOf("data-frame-src","data-src","src").firstNotNullOfOrNull{node.attr(it).takeIf(String::isNotBlank)}
                ?:return@forEachIndexed
            val url=publicUrl(raw,ref.pageUrl)?:return@forEachIndexed
            val known=SourceMenus.knownFrame(url);val label=KatrePlayer.label(url)?:HtmlData.text(d,".r-ep-kaynak").ifBlank{"Kaynak ${i+1}"}
            out.add(SourceOption("diziboxum-frame-"+TextKey.hash(url),ref,label,url,state=if(known)OptionState.AVAILABLE else OptionState.UNSUPPORTED,reason=if(known)""else"Bu oynatıcı henüz desteklenmiyor."))
        }
        d.select("a.post-page-numbers[href], .page-links a[href], .video-parts a[href]").forEach{a->
            val u=site.catalogUrl(a.attr("href"),ref.pageUrl)?:return@forEach
            if(u.toHttpUrl().encodedPath.replace(Regex("/[1-9]\\d*/$"),"/")!=base||u==ref.pageUrl)return@forEach
            out.add(SourceOption("diziboxum-page-"+TextKey.hash(u),ref,a.text().ifBlank{"Diğer kaynak"},u,"page"))
        }
        return out.distinctBy{it.url}
    }
}
