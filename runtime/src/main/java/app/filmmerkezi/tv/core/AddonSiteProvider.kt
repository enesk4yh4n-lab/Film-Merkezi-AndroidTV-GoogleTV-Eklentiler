package app.filmmerkezi.tv.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.LinkedHashMap

/** Single native parse queue. Parsed data, rather than DOM documents, crosses it. */
class AddonSiteProvider(private val template:SiteSpec,private val network:WebTransport,private val parsing:CoroutineDispatcher) : SiteProvider {
    override val site get()=Sites.current(template)
    private val parser get()=CatalogParser(site)
    private var addressRevision=Sites.revision
    private fun addressChanged(){if(addressRevision!=Sites.revision){trim();filterOptions=null;addressRevision=Sites.revision}}
    private val detailMutex=Mutex()
    @Volatile private var lastDetail:Pair<Long,Detail>?=null
    private val detailCacheLock=Any()
    private var trimGeneration=0L
    private suspend fun fetch(request:WebRequest):WebResponse {
        val url=site.catalogUrl(request.url)?:throw DataFailure(FailureKind.PARSE,"Site dışı katalog adresi","request")
        val headers=if(site.id=="hdfilmcehennemi")mapOf("Accept" to "application/json, text/html","X-Requested-With" to "fetch")+request.headers else request.headers
        return network.request(request.copy(url=url,referer=site.catalogUrl(request.referer)?:request.referer,headers=headers))
    }
    private suspend fun listRequest(request:WebRequest):CatalogPage {
        val response=fetch(request)
        return withContext(parsing){AdultContent.listing(response.body,parser.list(response.body,request.url),site)}
    }
    override suspend fun search(query:String,next:PageCursor?):CatalogPage {
        addressChanged()
        val q=TextKey.clean(query)
        if(q.length !in 1..100)throw DataFailure(FailureKind.PARSE,"Arama 1–100 karakter olmalı","search")
        val url=next?.also{validateCursor(it)}?.url?:when(site.id) {
            "dizibox"->site.base+"/"
            "fullhdfilmizlesene"->site.base+site.search+java.net.URLEncoder.encode(q,"UTF-8").replace("+","%20")
            "ddizi"->site.base+"/arama/"
            else->site.base+site.search+java.net.URLEncoder.encode(q,"UTF-8")
        }
        val request=if(site.id=="ddizi")WebRequest(url,site.base+"/","POST",mapOf("arama" to q)) else WebRequest(url,site.base+"/")
        return if(site.id=="dizibox") {
            val response=fetch(WebRequest(site.base+"/"))
            withContext(parsing){
                val d=HtmlData.document(response.body,url)
                val index=d.select(".alphabetical-category-list a[href]").mapNotNull { a->
                    val u=site.catalogUrl(a.attr("href"),url)?:return@mapNotNull null
                    if(site.contentPattern.matches(u.toHttpUrl().encodedPath))Card(ContentRef(site.id,u,ContentKind.SERIES),TextKey.title(a.text()))else null
                }.filter{TextKey.fold(it.title).contains(TextKey.fold(q))}.distinctBy{it.ref.id}
                CatalogPage(index)
            }
        }else if(site.id=="fixfullfilmizle"){
            val response=fetch(request)
            withContext(parsing){FixFullSearch.project(site,response.body,request.url,q,AdultContent.listing(response.body,parser.list(response.body,request.url),site))}
        }else listRequest(request)
    }
    override suspend fun browse(kind:ContentKind,next:PageCursor?):CatalogPage {
        addressChanged()
        if(kind==ContentKind.EPISODE)throw DataFailure(FailureKind.PARSE,"Bölümleri dizi detayından alın","browse")
        if(kind==ContentKind.SERIES && site.seriesPath==null)return CatalogPage(emptyList())
        if(kind==ContentKind.MOVIE && !site.supports(kind))return CatalogPage(emptyList())
        val url=next?.also{validateCursor(it)}?.url?:site.base+(if(kind==ContentKind.SERIES)site.seriesPath?:site.list else site.list)
        if(kind==ContentKind.SERIES && site.id in setOf("ddizi","fixfullfilmizle","lovefilmizle")){
            val response=fetch(WebRequest(url,site.base+"/"))
            return withContext(parsing){parser.seriesIndex(response.body,url,next?.fields?.get("indexOffset")?.toIntOrNull()?:0)}
        }
        val fields=next?.fields?.filterKeys{it!="max"}?:emptyMap()
        val headers=if(site.id=="hdfilmcehennemi")mapOf("Accept" to "application/json, text/html","X-Requested-With" to "fetch")+(if(fields.isNotEmpty())mapOf("X-Mofycore-Action" to "discover")else emptyMap())else emptyMap()
        var response=listRequest(WebRequest(url,site.base+"/",if(fields.isNotEmpty())"POST"else"GET",fields,headers=headers))
        if(site.id=="ddizi" && next?.fields?.containsKey("max")==true && response.next==null && response.cards.isNotEmpty()){
            val max=next.fields["max"]?.toIntOrNull()?:1
            if(next.page<max)response=response.copy(next=PageCursor(site.id,site.base+"/l.php?sayfa="+(next.page+1),next.page+1,next.fields))
        }
        return response.copy(cards=response.cards.filter{it.ref.kind==kind || (kind==ContentKind.MOVIE&&it.ref.kind==ContentKind.COLLECTION) || (kind==ContentKind.SERIES && it.ref.kind==ContentKind.EPISODE)})
    }
    private var filterOptions:Pair<Long,FilterOptions>?=null
    suspend fun options():FilterOptions {
        addressChanged()
        if(!SiteFilters.hasOptions(site))return FilterOptions()
        filterOptions?.takeIf{System.currentTimeMillis()-it.first<3600000}?.let{return it.second}
        val response=fetch(WebRequest(site.base+SiteFilters.optionsPath(site),site.base+"/"))
        return withContext(parsing){SiteFilters.options(site,response.body)}.also{filterOptions=System.currentTimeMillis() to it}
    }
    suspend fun filtered(q:CatalogQuery,next:PageCursor?,check:()->Boolean,unverifiedCandidates:Boolean=false):CatalogPage {
        if(!site.supports(q.kind))return CatalogPage(emptyList())
        if(next!=null){validateCursor(next);if(next.fields["_query"]!=q.signature)throw DataFailure(FailureKind.PARSE,"Devam sayfası başka filtreye ait","pagination")}
        val options=if(q.filtered)options()else FilterOptions()
        val plan=SiteFilters.plan(site,q,options,next)
        val filterResponse=plan.request?.let{fetch(it)}
        val page=if(plan.request==null)browse(q.kind,next?.copy(fields=next.fields.filterKeys{!it.startsWith("_")}))else withContext(parsing){parser.list(checkNotNull(filterResponse).body,plan.request.url)}
        var inspected=0;var omitted=0
        val cards=mutableListOf<Card>()
        for(raw in page.cards){
            if(raw.ref.kind!=q.kind&&!(raw.ref.kind==ContentKind.COLLECTION&&q.kind==ContentKind.MOVIE&&!q.filtered))continue
            var c=raw
            if(!q.accepts(c,plan.coverage.remote)&&plan.coverage.local.isNotEmpty()&&check()){
                inspected++;try{c=detail(c).card}catch(e:CancellationException){throw e}catch(_:Exception){}
            }
            if(unverifiedCandidates||q.accepts(c,plan.coverage.remote))cards.add(c)else omitted++
        }
        val ordered=when(q.order){CatalogOrder.DEFAULT->cards;CatalogOrder.YEAR->cards.sortedByDescending{it.year?:0};CatalogOrder.RATING->cards.sortedByDescending{it.imdb?:0.0};CatalogOrder.TITLE->cards.sortedBy{TextKey.fold(it.title)}}
        val archiveNext=if(site.id=="sinemacc"&&plan.request?.method=="POST"){
            val responseDoc=HtmlData.document(checkNotNull(filterResponse).body,plan.request.url)
            responseDoc.selectFirst(".pagination a.next[rel]")?.attr("rel")?.toIntOrNull()?.takeIf{it==(next?.page?:1)+1}?.let{PageCursor(site.id,plan.request.url,it)}
        }else null
        val nextPage=archiveNext?:page.next
        val cursor=nextPage?.copy(fields=nextPage.fields+("_query" to q.signature))
        return page.copy(cards=ordered,next=cursor,coverage=plan.coverage.copy(checked=inspected,omitted=omitted))
    }
    private fun validateCursor(cursor:PageCursor) {
        if(cursor.siteId!=site.id || site.catalogUrl(cursor.url)==null || cursor.page !in 1..100000)throw DataFailure(FailureKind.PARSE,"Geçersiz devam bağlantısı","pagination")
    }
    private suspend fun recoverDdiziEpisode(card:Card,parent:ContentRef):String? {
        val key=EpisodePages.ddiziNamedEpisodeKey(site,card.ref.pageUrl)?:return null
        val number=card.episodeNumber?:return null
        if(parent.siteId!=site.id||parent.kind!=ContentKind.SERIES)return null
        val archive=site.catalogUrl(parent.pageUrl)?:return null
        if(!EpisodePages.sameArchive(site,archive,archive))return null
        val series=Card(parent,card.title)
        val queue=ArrayDeque(listOf(archive))
        val visited=mutableSetOf<String>()
        repeat(3){
            val next=queue.removeFirstOrNull()?:return null
            visited.add(EpisodePages.key(site,next))
            val response=fetch(WebRequest(next,site.base+"/",timeoutMs=5000))
            if(!EpisodePages.sameArchive(site,archive,response.url))return null
            val (candidates,pages)=withContext(parsing){
                val doc=HtmlData.document(response.body,response.url)
                val canonical=doc.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf(String::isNotBlank)
                if(canonical!=null&&!EpisodePages.sameArchive(site,archive,canonical))
                    return@withContext emptyList<String>() to emptyList<String>()
                val matches=doc.select(EpisodePages.DDIZI_EPISODE_LINKS).mapNotNull{link->
                    val address=site.catalogUrl(link.attr("href"),response.url)?:return@mapNotNull null
                    if(address==card.ref.pageUrl||EpisodePages.ddiziNamedEpisodeKey(site,address)!=key)
                        return@mapNotNull null
                    val label=HtmlData.text(link).ifBlank{link.attr("title")}
                    address.takeIf{
                        CatalogParser.episodeNumber(label,address,number.season)==number
                    }
                }.distinct()
                matches to EpisodePages.links(site,doc,series,response.url)
            }
            if(candidates.isNotEmpty())return candidates.singleOrNull()
            pages.filter{EpisodePages.key(site,it) !in visited&&it !in queue}.forEach(queue::addLast)
        }
        return null
    }

    override suspend fun detail(card:Card):Detail = detailMutex.withLock {
        addressChanged()
        lastDetail?.takeIf { it.second.card.ref.id==card.ref.id && System.currentTimeMillis()-it.first<60000 }?.let{return@withLock it.second}
        val generation=synchronized(detailCacheLock){trimGeneration}
        val current=SiteAddresses.card(card)
        val (requested,response)=try {
            current.ref.pageUrl to fetch(WebRequest(current.ref.pageUrl,site.base+"/"))
        } catch(e:DataFailure) {
            if(e.httpCode!=404||current.ref.kind!=ContentKind.EPISODE)throw e
            val parent=current.seriesRef?:throw e
            val replacement=recoverDdiziEpisode(current,parent)?:throw e
            replacement to fetch(WebRequest(replacement,parent.pageUrl,timeoutMs=5000))
        }
        val episodeId=EpisodePages.episodePageKey(site,requested)
        val resolved=when {
            episodeId!=null -> {
                if(EpisodePages.episodePageKey(site,response.url)!=episodeId)
                    throw DataFailure(FailureKind.PARSE,"Yanıt başka bir bölüme yönlendi","detail")
                current.copy(ref=current.ref.copy(pageUrl=response.url))
            }
            response.url!=card.ref.pageUrl&&site.id=="ddizi"&&card.ref.kind==ContentKind.SERIES -> {
                if(!EpisodePages.sameArchive(site,card.ref.pageUrl,response.url))throw DataFailure(FailureKind.PARSE,"Yanıt başka bir diziye yönlendi","detail")
                current.copy(ref=current.ref.copy(pageUrl=response.url))
            }
            else -> current
        }
        var value=withContext(parsing){
            val parsed=parser.detail(response.body,resolved)
            if(requested!=current.ref.pageUrl) {
                val number=checkNotNull(current.episodeNumber)
                if(CatalogParser.episodeNumber(parsed.card.title,response.url,number.season)!=number)
                    throw DataFailure(FailureKind.PARSE,"Arşivden bulunan sayfa kayıtlı bölümle eşleşmedi","detail")
            }
            val primary=if(site.id in TrackedCatalog.sites)parsed else PageIdentity.attach(response.body,parsed,card)
            AdultContent.detail(response.body,ContentMetadata.enrich(primary,response.body))
        }
        if(episodeId!=null&&resolved.ref.pageUrl!=current.ref.pageUrl){
            val stable=value.card.ref.copy(pageUrl=current.ref.pageUrl)
            value=value.copy(card=value.card.copy(ref=stable),options=value.options.map{
                it.copy(content=stable,fields=it.fields+("sourcePage" to resolved.ref.pageUrl))
            })
        }
        if(site.id=="p018"&&value.card.ref.kind==ContentKind.SERIES){
            value=value.copy(episodePages=withContext(parsing){
                val d=HtmlData.document(response.body,response.url)
                val id=Regex("""\b(?:const|var|let)\s+seriesId\s*=\s*(\d{1,12})\s*;""")
                    .find(response.body)?.groupValues?.get(1)
                val existing=value.episodes.map{it.number.season}.toSet()
                val remaining=d.select(".season-selector [data-season]").mapNotNull{it.attr("data-season").toIntOrNull()}
                    .filter{it in 1..1000&&it !in existing}.distinct().take(20)
                if(id==null&&remaining.isNotEmpty())throw DataFailure(FailureKind.PARSE,"Sezon kimliği eksik","episodes")
                remaining.map{site.base+"/api/get_season_episodes.php?series_id="+id+"&season="+it}
            })
        }
        if(site.id=="diziboxum"){
            val options=mutableListOf<SourceOption>()
            for(option in value.options){
                if(!KatrePlayer.accepts(option.url)){options.add(option);continue}
                try{
                    val embed=network.request(WebRequest(option.url,value.card.ref.pageUrl))
                    val config=withContext(parsing){KatrePlayer.parse(embed.body,embed.url)}
                    options.addAll(KatrePlayer.options(option,config))
                }catch(e:CancellationException){throw e}catch(e:DataFailure){options.add(option.copy(reason=e.message))}
            }
            value=value.copy(options=options)
        }
        synchronized(detailCacheLock){if(generation==trimGeneration)lastDetail=System.currentTimeMillis() to value}
        value
    }
    override suspend fun episodes(card:Card):List<Episode> {
        val first=detail(card)
        if(first.card.ref.kind!=ContentKind.SERIES)return emptyList()
        val out=first.episodes.toMutableList()
        val queue=ArrayDeque(first.episodePages);first.episodesNext?.let{queue.add(it.url)}
        val seen=mutableSetOf(EpisodePages.key(site,card.ref.pageUrl),EpisodePages.key(site,first.card.ref.pageUrl));var requests=0
        try{withTimeout(60000) {
            while(queue.isNotEmpty()) {
                val url=queue.removeFirst()
                if(!seen.add(EpisodePages.key(site,url)))continue
                if(++requests>50)throw DataFailure(FailureKind.LIMIT,"Bölüm listesi 50 sayfa sınırında","episodes")
                val response=fetch(WebRequest(url,card.ref.pageUrl))
                if(site.id=="ddizi"&&!EpisodePages.sameArchive(site,first.card.ref.pageUrl,response.url))throw DataFailure(FailureKind.PARSE,"Bölüm sayfası başka diziye yönlendi","episodes")
                val pages=withContext(parsing) {
                    if(site.id=="p018"&&url.toHttpUrl().encodedPath=="/api/get_season_episodes.php"){
                        val season=url.toHttpUrl().queryParameter("season")?.toIntOrNull()
                        val data=org.json.JSONObject(response.body)
                        val items=data.optJSONArray("episodes")
                        if(season !in 1..1000||data.optInt("season_number")!=season||!data.optBoolean("success")||items==null)
                            throw DataFailure(FailureKind.PARSE,"Sezon yanıtı eşleşmedi","episodes")
                        items.values().filterIsInstance<org.json.JSONObject>()
                            .filter{it.string("status")=="published"}.forEach { row ->
                                val number=row.optInt("episode_number")
                                if(number in 1..100000){
                                    val address=first.card.ref.pageUrl.trimEnd('/')+"/sezon-"+season+"/bolum-"+number
                                    out.add(TrackedCatalog(site).episode(first.card,address,checkNotNull(season),number,row.string("title")))
                                }
                            }
                        return@withContext emptyList()
                    }
                    val doc=HtmlData.document(response.body,response.url)
                    out.addAll(parser.episodes(doc,first.card))
                    EpisodePages.links(site,doc,first.card,response.url)
                }
                pages.forEach{if(EpisodePages.key(site,it) !in seen&&it !in queue)queue.add(it)}
                if(out.size>4000)throw DataFailure(FailureKind.LIMIT,"Bölüm listesi boyut sınırında","episodes")
            }
        }}catch(e:TimeoutCancellationException){throw DataFailure(FailureKind.TIMEOUT,"Bölüm listesinin tamamı zamanında alınamadı","episodes",cause=e)}
        return out.distinctBy{"${it.number.season}:${it.number.number}"}.sortedWith(compareBy({it.number.season},{it.number.number}))
    }
    fun trim(){synchronized(detailCacheLock){trimGeneration++;lastDetail=null}}
}

