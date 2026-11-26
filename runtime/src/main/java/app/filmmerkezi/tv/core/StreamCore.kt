package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Read the main player's literal configuration, never advertisements or executable JS. */
object StreamCore {
    data class Config(val url:String,val subtitles:List<Subtitle>)
    fun parse(body:String,page:String):Config {
        fun fail():Nothing=throw DataFailure(FailureKind.PARSE,"StreamCore ana oynatıcı ayarı eşleşmiyor","decode")
        val origin=page.toHttpUrl()
        if(origin.host!="streamcorecdn.com"||HtmlData.document(body,page).selectFirst("video#main-video")==null)fail()
        val id=(Literals.after(body,"\\bconst\\s+videoId\\s*=\\s*") as? Number)?.toLong()?.takeIf{it>0}?:fail()
        val raw=Literals.after(body,"\\bconst\\s+src\\s*=\\s*") as? String?:fail()
        val url=publicUrl(raw,page)?.toHttpUrl()?:fail()
        if(url.scheme!="https"||url.host!=origin.host||url.encodedPath!="/play.m3u8"||url.queryParameter("id")!=id.toString()||url.queryParameter("m")!="master"||url.queryParameter("token").isNullOrBlank()||url.queryParameter("expires")?.toLongOrNull()==null)fail()
        val data=runCatching{Literals.after(body,"\\bconst\\s+tracksData\\s*=\\s*") as? JSONObject}.getOrNull()
        val subs=data?.optJSONArray("subtitles")?.values()?.filterIsInstance<JSONObject>().orEmpty().mapNotNull{s->
            val path=s.string("url");if(path.isBlank())return@mapNotNull null
            val target=if(path.startsWith("https://"))publicUrl(path)else {
                // The page's own HLS proxy serves relative subtitle paths, with the
                // same token as the manifest. The endpoint extension is misleading.
                if(!Regex("^tracks/[A-Za-z0-9_/-]+\\.(?:vtt|srt)$").matches(path))return@mapNotNull null
                url.newBuilder().removeAllQueryParameters("m").removeAllQueryParameters("p").addQueryParameter("p",path).build().toString()
            }
            target?.let{Subtitle(it,s.string("name").ifBlank{"Altyazı"},s.string("lang"),if(path.substringBefore('?').endsWith(".srt"))"srt"else"vtt")}
        }
        return Config(url.toString(),subs.distinctBy{it.url})
    }
}
