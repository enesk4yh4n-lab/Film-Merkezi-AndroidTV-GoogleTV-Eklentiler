package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/** WordPress searches return episode posts. Only the site's declared series archives become cards. */
object FixFullSearch {
    private fun archives(site:SiteSpec,d:Document,page:String):List<Card> = d.select("select option[value]").mapNotNull{n->
        val url=site.catalogUrl(n.attr("value"),page)?:return@mapNotNull null
        if(!Regex("^/diziler/[a-z0-9-]+/$").matches(url.toHttpUrl().encodedPath))return@mapNotNull null
        val title=TextKey.title(n.text());if(title.isBlank())return@mapNotNull null
        Card(ContentRef(site.id,url,ContentKind.SERIES),title)
    }.distinctBy{it.ref.id}
    fun archiveTitle(site:SiteSpec,d:Document,ref:ContentRef):String? =
        archives(site,d,ref.pageUrl).singleOrNull{it.ref.id==ref.copy(kind=ContentKind.SERIES).id}?.title
    private fun stem(text:String)=Regex("^(.+?)\\s+\\d+\\s+sezon\\s+\\d+\\s+bolum(?:\\s|$)").find(TextKey.fold(text))?.groupValues?.get(1)
    fun project(site:SiteSpec,body:String,page:String,query:String,raw:CatalogPage):CatalogPage {
        val roots=archives(site,HtmlData.document(body,page),page)
        if(roots.isEmpty()&&raw.cards.any{it.ref.kind==ContentKind.EPISODE})
            throw DataFailure(FailureKind.PARSE,"FixFullFilm dizi arşiv bağlantıları bulunamadı","search")
        val out=linkedMapOf<String,Card>()
        for(c in raw.cards){
            if(c.ref.kind!=ContentKind.EPISODE){out.putIfAbsent(c.ref.id,c);continue}
            val title=stem(c.title);val slug=stem(c.ref.pageUrl.toHttpUrl().encodedPath.trim('/'))
            val byTitle=roots.filter{title!=null&&TextKey.fold(it.title)==title}
            val bySlug=roots.filter{slug!=null&&TextKey.fold(it.ref.pageUrl.toHttpUrl().encodedPath.trim('/').substringAfterLast('/'))==slug}
            // Contradictory or ambiguous names cannot place an episode under another series.
            val parent=(if(byTitle.isNotEmpty()&&bySlug.isNotEmpty())byTitle.intersect(bySlug.toSet()).toList()else byTitle.ifEmpty{bySlug}).singleOrNull()?:continue
            val old=out[parent.ref.id]
            out[parent.ref.id]=parent.copy(poster=old?.poster?.ifBlank{c.poster}?:c.poster,language=old?.language?.ifBlank{c.language}?:c.language)
            // An episode's year/rating/number are deliberately not series metadata.
        }
        val key=stem(query)?:TextKey.fold(query)
        if(key.isNotBlank())for(parent in roots)if(TextKey.fold(parent.title).contains(key))out.putIfAbsent(parent.ref.id,parent)
        return raw.copy(cards=out.values.toList()) // Keep real search pagination, including movie results on later pages.
    }
}
