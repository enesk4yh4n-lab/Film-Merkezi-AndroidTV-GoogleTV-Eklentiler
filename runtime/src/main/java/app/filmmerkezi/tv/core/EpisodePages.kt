package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/** Only declared season/archive links of this series; never the general site's next link. */
object EpisodePages {
    internal const val DDIZI_EPISODE_LINKS=".content_ .dizi-boxpost-cat > a[href]"
    fun key(site:SiteSpec,url:String)=if(site.id=="ddizi")url.replace(Regex("/sayfa-0/?$"),"")else url
    private fun archive(path:String)=path.trimEnd('/').replace(Regex("/(?:page/|sayfa-|sayfa/)\\d+$"),"")
    private fun ddiziId(path:String)=Regex("^/diziler/(\\d+)/[^/]+(?:/sayfa-\\d+)?/?$").matchEntire(path)?.groupValues?.get(1)
    private val ddiziEpisode = Regex("""^/izle/(\d+)/[^/]+\.htm/?$""")
    private val ddiziNamedEpisode = Regex("""^/izle/\d+/([a-z0-9-]+-bolum)(?:-izle)?(?:-hd\d{1,3})?\.htm/?$""")
    fun ddiziEpisodeId(site:SiteSpec,value:String,page:String=value,legacyMetadata:Boolean=false):String? {
        if(site.id!="ddizi")return null
        val address=if(legacyMetadata&&value.startsWith("http://",true))"https://"+value.substring(7) else value
        val url=site.catalogUrl(address,page)?.toHttpUrl()?:return null
        return ddiziEpisode.matchEntire(url.encodedPath)?.groupValues?.get(1)
    }
    fun episodePageKey(site:SiteSpec,address:String):String? {
        if(site.id=="ddizi")return ddiziEpisodeId(site,address)
        if(site.id!="p012")return null
        val path=site.catalogUrl(address)?.toHttpUrl()?.encodedPath?:return null
        return Regex("""^/([a-z0-9-]+-\d+-bolum)-izle-(?:full-tek-parca|\d+)/?$""")
            .matchEntire(path)?.groupValues?.get(1)
    }
    fun ddiziNamedEpisodeKey(site:SiteSpec,address:String):String? {
        if(site.id!="ddizi")return null
        val url=site.catalogUrl(address)?.toHttpUrl()?:return null
        if(url.query!=null)return null
        return ddiziNamedEpisode.matchEntire(url.encodedPath)?.groupValues?.get(1)
    }

    fun sameEpisodePage(site:SiteSpec,original:String,target:String):Boolean {
        val id=episodePageKey(site,original)?:return false
        if(id==episodePageKey(site,target))return true
        val named=ddiziNamedEpisodeKey(site,original)?:return false
        return named==ddiziNamedEpisodeKey(site,target)
    }
    fun sameArchive(site:SiteSpec,parent:String,target:String):Boolean {
        val safe=site.catalogUrl(target,parent)?:return false
        val a=parent.toHttpUrl();val b=safe.toHttpUrl()
        return if(site.id=="ddizi")ddiziId(a.encodedPath)?.let{it==ddiziId(b.encodedPath)}==true
            else archive(a.encodedPath)==archive(b.encodedPath)
    }
    fun links(site:SiteSpec,d:Document,parent:Card,page:String):List<String> {
        val urls=linkedSetOf<String>()
        for(a in d.select("#seasons-list a[href], a[href*=\"?sezon=\"], a[href*=\"&sezon=\"], .pagination a[href], .wp-pagenavi a[href], a[rel=next]")){
            val url=site.catalogUrl(a.attr("href"),page)?:continue
            if(url==page)continue
            val u=url.toHttpUrl()
            val season=u.queryParameter("sezon")?.toIntOrNull()
            val archivePage=Regex("/(?:page/|sayfa-|sayfa/)\\d+/?$").containsMatchIn(u.encodedPath)||
                listOf("page","paged").any{u.queryParameter(it)?.toIntOrNull()?.let{n->n>0}==true}
            val declaredDiziboxSeason=site.id=="dizibox"&&a.parents().any{it.id()=="seasons-list"}&&
                Regex("^/dizi/[^/]+/\\d+-sezon-[^/]+/?$").matches(u.encodedPath)
            // Dizibox season URLs use a different slug (visionquest -> vision-quest).
            // Only its primary explicit season selector can declare that relationship.
            if(declaredDiziboxSeason||sameArchive(site,parent.ref.pageUrl,url)&&
                (season!=null&&season in 1..1000||archivePage))urls.add(url)
        }
        return urls.toList()
    }
}
