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
        if(kind==ContentKind.MOVIE && site.id in setOf("dizibox","ddizi","diziboxum"))return CatalogPage(emptyList())
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
        if(q.kind==ContentKind.MOVIE&&site.id in setOf("dizibox","ddizi","diziboxum")||q.kind==ContentKind.SERIES&&site.seriesPath==null)return CatalogPage(emptyList())
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
    override suspend fun detail(card:Card):Detail = detailMutex.withLock {
        addressChanged()
        lastDetail?.takeIf { it.second.card.ref.id==card.ref.id && System.currentTimeMillis()-it.first<60000 }?.let{return@withLock it.second}
        val generation=synchronized(detailCacheLock){trimGeneration}
        val response=fetch(WebRequest(card.ref.pageUrl,site.base+"/"))
        val resolved=if(response.url!=card.ref.pageUrl&&site.id=="ddizi"&&card.ref.kind==ContentKind.SERIES){
            if(!EpisodePages.sameArchive(site,card.ref.pageUrl,response.url))throw DataFailure(FailureKind.PARSE,"Yanıt başka bir diziye yönlendi","detail")
            card.copy(ref=card.ref.copy(pageUrl=response.url))
        }else SiteAddresses.card(card)
        var value=withContext(parsing){AdultContent.detail(response.body,PageIdentity.attach(response.body,parser.detail(response.body,resolved),card))}
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

