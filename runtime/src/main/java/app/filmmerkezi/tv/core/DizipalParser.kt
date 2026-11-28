package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.nodes.Document

/** Native parsing of the public catalog. Site scripts, ads and analytics never execute. */
object DizipalParser {
    private fun year(v:Any?)=v?.toString()?.toIntOrNull()?.takeIf{it in 1888..2100}
    private fun lang(v:String)=when(v){"dublaj_altyazi"->"Dublaj & Altyazılı";"dublaj","yerli"->"Türkçe Dublaj";"altyazi"->"Türkçe Altyazılı";else->""}
    private fun data(body:String,ref:ContentRef):JSONObject? {
        val slug=ref.pageUrl.toHttpUrl().encodedPath.trim('/').substringAfterLast('/')
        val series=ref.kind==ContentKind.SERIES
        return findJson(HtmlData.flight(body)){it.string("slug")==slug&&it.has("title")&&
            (if(series)it.has("first_air_year")&&it.has("seasons")else it.has("release_year"))}
    }
    fun primaryYear(body:String,ref:ContentRef):Int? {
        data(body,ref)?.let{year(it.opt(if(ref.kind==ContentKind.SERIES)"first_air_year"else"release_year"))}?.let{return it}
        if(ref.kind!=ContentKind.MOVIE)return null
        val d=HtmlData.document(body,ref.pageUrl)
        if(HtmlData.schemas(d).none{it.string("@type")=="Movie"&&it.string("url").trimEnd('/')==ref.pageUrl.trimEnd('/')})return null
        return d.select("tr").filter{tr->tr.selectFirst("td")?.text()=="Yıl"&&tr.parents().none{it.normalName() in setOf("aside","footer")||Regex("recommend|related|benzer").containsMatchIn(it.className())}}
            .mapNotNull{year(it.select("td").getOrNull(1)?.text())}.distinct().singleOrNull()
    }
    fun list(site:SiteSpec,body:String,page:String):CatalogPage {
        if(body.trimStart().startsWith("{")) {
            val json=JSONObject(body)
            val rows=json.optJSONArray("data")
            if(!json.optBoolean("success")||rows==null)throw DataFailure(FailureKind.PARSE,"Dizipal arama yanıtı tanınamadı","catalog")
            val cards=rows.values().filterIsInstance<JSONObject>().mapNotNull{o->
                val kind=when(o.string("type")){"movie"->ContentKind.MOVIE;"series"->ContentKind.SERIES;else->return@mapNotNull null}
                val slug=o.string("slug");val title=TextKey.title(o.string("title"))
                if(!slug.matches(Regex("[a-z0-9-]+"))||title.isBlank())return@mapNotNull null
                Card(ContentRef(site.id,site.base+(if(kind==ContentKind.SERIES)"/diziler/"else"/filmler/")+slug,kind),title,
                    publicUrl(o.string("poster_url"),page).orEmpty(),year(o.opt("year")),o.opt("imdb_rating")?.toString()?.toDoubleOrNull(),o.string("language_label"))
            }
            // This is the same unpaginated endpoint used by the site's full search.
            return CatalogPage(cards.distinctBy{it.ref.id}.take(120))
        }
        val d=HtmlData.document(body,page)
        val cards=d.select(site.cards).mapNotNull{a->
            val url=site.catalogUrl(a.attr("href"),page)?:return@mapNotNull null
            if(!site.contentPattern.matches(url.toHttpUrl().encodedPath)||site.kind(url)==ContentKind.EPISODE)return@mapNotNull null
            val title=TextKey.title(HtmlData.text(a,"h3"));if(title.isBlank())return@mapNotNull null
            val spans=a.select("span");val y=spans.firstNotNullOfOrNull{year(it.text())}
            val rating=spans.firstOrNull{it.selectFirst("svg.lucide-star")!=null}?.text()?.toDoubleOrNull()
            val label=spans.map{it.text()}.firstOrNull{it.contains("Dublaj",true)||it.contains("Altyaz",true)}.orEmpty()
            val cc=spans.any{it.text()=="CC"};val dub=a.select("img[alt=TR]").isNotEmpty()
            Card(ContentRef(site.id,url,site.kind(url)),title,CatalogPosters.image(a,page),y,rating,
                label.ifBlank{if(dub&&cc)"Dublaj & Altyazılı"else if(dub)"Türkçe Dublaj"else if(cc)"Türkçe Altyazılı"else""})
        }.distinctBy{it.ref.id}
        if(cards.isEmpty())throw DataFailure(FailureKind.PARSE,"Dizipal katalog kartları bulunamadı","catalog")
        return CatalogPage(cards.take(60),CatalogParser(site).next(d,page))
    }
    fun detail(site:SiteSpec,body:String,seed:Card):Detail {
        val d=HtmlData.document(body,seed.ref.pageUrl)
        val expected=seed.ref.pageUrl.trimEnd('/')
        val canonical=d.selectFirst("link[rel=canonical]")?.attr("href")?.let{site.catalogUrl(it)}
        if(canonical!=null&&canonical.trimEnd('/')!=expected)throw DataFailure(FailureKind.PARSE,"Dizipal içerik adresi eşleşmiyor","detail")
        val schema=HtmlData.schemas(d).firstOrNull{site.catalogUrl(it.string("url"))?.trimEnd('/')==expected&&it.string("@type") in setOf("Movie","TVSeries","TVEpisode")}
            ?:throw DataFailure(FailureKind.PARSE,"Dizipal ana içerik kimliği bulunamadı","detail")
        val kind=when(schema.string("@type")){"TVSeries"->ContentKind.SERIES;"TVEpisode"->ContentKind.EPISODE;else->ContentKind.MOVIE}
        val ref=seed.ref.copy(kind=kind);val primary=if(kind!=ContentKind.EPISODE)data(body,ref)else null
        val parent=schema.optJSONObject("partOfSeries")
        val seriesRef=parent?.string("url")?.let{site.catalogUrl(it)}?.takeIf{SeriesIdentity.archive(ContentRef(site.id,it,ContentKind.SERIES))}?.let{ContentRef(site.id,it,ContentKind.SERIES)}
        val number=if(kind==ContentKind.EPISODE){val s=schema.optInt("seasonNumber");val n=schema.optInt("episodeNumber");if(s in 1..1000&&n in 1..100000)EpisodeNumber(s,n)else null}else null
        if(kind==ContentKind.EPISODE&&(number==null||seriesRef==null))throw DataFailure(FailureKind.PARSE,"Dizipal bölüm kimliği eksik","detail")
        val title=TextKey.title(primary?.string("title").orEmpty().ifBlank{schema.string("name")})
        if(title.isBlank())throw DataFailure(FailureKind.PARSE,"Dizipal başlık eksik","detail")
        val poster=publicUrl(primary?.string("poster_url").orEmpty().ifBlank{schema.string("image")},seed.ref.pageUrl).orEmpty().ifBlank{seed.poster}
        val card=seed.copy(ref=ref,title=title,poster=poster,year=primary?.let{year(it.opt(if(kind==ContentKind.SERIES)"first_air_year"else"release_year"))}?:if(kind==ContentKind.EPISODE)null else seed.year,
            imdb=primary?.opt("imdb_rating")?.toString()?.toDoubleOrNull()?:schema.optJSONObject("aggregateRating")?.opt("ratingValue")?.toString()?.toDoubleOrNull()?:seed.imdb,
            originalTitle=primary?.string("original_title").orEmpty().ifBlank{schema.string("alternateName")},language=primary?.let{lang(it.string("language_type"))}.orEmpty().ifBlank{seed.language},episodeNumber=number,seriesRef=seriesRef)
        val episodes=if(kind==ContentKind.SERIES)episodes(site,d,card)else emptyList()
        val seasons=primary?.optJSONArray("seasons")?.values()?.filterIsInstance<JSONObject>().orEmpty().mapNotNull{it.optInt("season_number").takeIf{n->n in 1..1000}}
        val genres=when(val value=schema.opt("genre")){is JSONArray->value.values().filterIsInstance<String>();is String->listOf(value);else->emptyList()}
        return Detail(card,HtmlData.document(schema.string("description"),seed.ref.pageUrl).text(),genres,
            schema.optJSONArray("actor")?.values()?.filterIsInstance<JSONObject>().orEmpty().map{it.string("name")}.take(60),schema.string("duration"),episodes,
            options=if(kind==ContentKind.SERIES)emptyList()else sources(site,body,ref,ref.pageUrl),seasons=(seasons+episodes.map{it.number.season}).distinct().sorted())
    }
    fun episodes(site:SiteSpec,d:Document,parent:Card):List<Episode> {
        val slug=parent.ref.pageUrl.toHttpUrl().encodedPath.trim('/').substringAfterLast('/')
        val pattern=Regex("^/bolumler/"+Regex.escape(slug)+"-s([1-9]\\d{0,2})b([1-9]\\d{0,4})/?$")
        val anchors=d.select("a[href*=\"/bolumler/\"]")
        val out=anchors.mapNotNull{a->
            val page=site.catalogUrl(a.attr("href"),parent.ref.pageUrl)?:return@mapNotNull null
            val match=pattern.matchEntire(page.toHttpUrl().encodedPath)?:return@mapNotNull null
            val number=EpisodeNumber(match.groupValues[1].toInt(),match.groupValues[2].toInt())
            val c=Card(ContentRef(site.id,page,ContentKind.EPISODE),"${parent.title} · ${number.season}×${number.number}",parent.poster,parent.year,language=parent.language,
                seriesIdentity=ContentIdentity.key(parent),episodeNumber=number,seriesRef=parent.ref)
            Episode(c,parent.ref,number)
        }.toMutableList()
        // Some real episodes use unrelated slugs (e.g. /bolumler/s3b2-2-bolum).
        // Accept them only when the structured row explicitly identifies this
        // parent and season, and the page independently links the actual URL.
        val linked=anchors.mapNotNull{site.catalogUrl(it.attr("href"),parent.ref.pageUrl)}.toSet()
        val groups=findJson(HtmlData.flight(d.outerHtml())){it.opt("initialEpisodesBySeason") is JSONObject}?.optJSONObject("initialEpisodesBySeason")
        groups?.keys()?.forEach{key->
            val season=key.toIntOrNull()?:return@forEach
            groups.optJSONArray(key)?.values()?.filterIsInstance<JSONObject>().orEmpty().forEach entry@{e->
                val s=e.optInt("season_number");val n=e.optInt("episode_number");val episodeSlug=e.string("slug")
                if(s!=season||s !in 1..1000||n !in 1..100000||e.optJSONObject("series")?.string("slug")!=slug||!episodeSlug.matches(Regex("[a-z0-9-]+")))return@entry
                val page=site.base+"/bolumler/"+episodeSlug;if(page !in linked)return@entry
                val number=EpisodeNumber(s,n);val card=Card(ContentRef(site.id,page,ContentKind.EPISODE),"${parent.title} · $s×$n",publicUrl(e.string("still_url"),page).orEmpty().ifBlank{parent.poster},parent.year,
                    language=parent.language,seriesIdentity=ContentIdentity.key(parent),episodeNumber=number,seriesRef=parent.ref)
                if(out.none{it.number==number})out.add(Episode(card,parent.ref,number,e.string("overview")))
            }
        }
        return out.distinctBy{it.number}.sortedWith(compareBy({it.number.season},{it.number.number}))
    }
    fun sources(site:SiteSpec,body:String,content:ContentRef,page:String):List<SourceOption> {
        val array=findJson(HtmlData.flight(body)){it.opt("videoSources") is JSONArray}?.optJSONArray("videoSources")
        if(array!=null)return array.values().filterIsInstance<JSONObject>().filter{it.optBoolean("is_active")}.mapNotNull{o->
            val url=publicUrl(o.string("embed_url"),page)?:return@mapNotNull null
            val host=url.toHttpUrl().host;val label=when(host){"streamcorecdn.com"->"StreamCore";"videoplays.cfd"->"VideoPlays";else->host}+lang(o.string("language_type")).takeIf{it.isNotBlank()}.let{it?.let{" · $it"}.orEmpty()}
            SourceOption(site.id+"-"+o.optLong("id")+"-"+TextKey.hash(url),content,label,url,state=if(SourceMenus.knownFrame(url))OptionState.AVAILABLE else OptionState.UNSUPPORTED,
                reason=if(SourceMenus.knownFrame(url))""else"Bu oynatıcı henüz desteklenmiyor.")
        }.distinctBy{it.url}.take(16)
        return SourceMenus.frames(body,page).map{SourceOption(site.id+"-"+TextKey.hash(it),content,when(it.toHttpUrl().host){"streamcorecdn.com"->"StreamCore";"videoplays.cfd"->"VideoPlays";else->"Kaynak"},it)}
    }
}
