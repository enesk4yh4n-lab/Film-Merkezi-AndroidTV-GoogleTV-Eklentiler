package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Reads the public player's data object; executes no player, advertising or tracking code. */
object KatrePlayer {
    data class Stream(val key:String,val title:String,val url:String)
    data class Config(val streams:List<Stream>,val subtitles:List<Subtitle>)
    fun accepts(url:String)=url.toHttpUrlOrNull()?.let{it.scheme=="https"&&it.host=="ksdpictures.site"&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&it.encodedPath=="/dzembed.php"}==true
    fun parse(body:String,url:String):Config {
        val u=url.toHttpUrl();val id=u.queryParameter("id")
        if(!accepts(url)||id==null||!id.matches(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))throw DataFailure(FailureKind.PARSE,"Katre video kimliği geçersiz","decode")
        val config=runCatching{Literals.after(body,"\\bwindow\\.NP_CONFIG\\s*=\\s*")as? JSONObject}.getOrNull()
            ?:throw DataFailure(FailureKind.PARSE,"Katre kaynak yapılandırması bulunamadı","decode")
        if(config.string("id")!="embed-$id")throw DataFailure(FailureKind.PARSE,"Katre video kimliği eşleşmiyor","decode")
        val rows=config.optJSONArray("sources")?.values()?.filterIsInstance<JSONObject>().orEmpty()
        if(rows.size>64)throw DataFailure(FailureKind.LIMIT,"Katre kaynak listesi sınırını aştı","decode")
        val streams=rows.mapIndexedNotNull{i,row->
            val master=publicUrl(row.string("master"),url)?.toHttpUrlOrNull()?:return@mapIndexedNotNull null
            if(master.scheme!="https"||master.host!=u.host||!master.encodedPath.startsWith("/dserv/uploads/")||!master.pathSegments.contains(id)||!master.encodedPath.endsWith(".m3u8"))return@mapIndexedNotNull null
            Stream(TextKey.hash(row.string("kind")+"|"+row.string("title")+"|$i"),row.string("title").ifBlank{"Kaynak ${i+1}"},master.toString())
        }.distinctBy{it.url}
        if(streams.isEmpty())throw DataFailure(FailureKind.PARSE,"Katre oynatılabilir kaynak bildirmiyor","decode")
        val subtitles=config.optJSONArray("subtitles")?.values()?.filterIsInstance<JSONObject>().orEmpty().take(64).mapNotNull{row->
            val value=publicUrl(row.string("url"),url)?:return@mapNotNull null
            val sub=value.toHttpUrl()
            if(sub.scheme!="https"||sub.host!=u.host||!sub.pathSegments.contains(id))return@mapNotNull null
            Subtitle(value,row.string("label"),row.string("lang"),if(sub.encodedPath.endsWith(".srt"))"srt"else"vtt")
        }
        return Config(streams,subtitles.distinctBy{it.url})
    }
    fun options(option:SourceOption,config:Config)=config.streams.map{stream->option.copy(id=option.id+"-"+stream.key,
        label=option.label+" · "+stream.title,fields=option.fields+("katreStream" to stream.key))}
    fun resolve(option:SourceOption,body:String,url:String):ResolvedSource {
        val config=parse(body,url);val key=option.fields["katreStream"]
        val stream=if(key==null)config.streams.first()else config.streams.firstOrNull{it.key==key}
            ?:throw DataFailure(FailureKind.PARSE,"Katre kaynak listesi değişmiş; listeyi yenile","decode")
        return ResolvedSource(option.id,option.label,stream.url,StreamType.HLS,url,
            app.filmmerkezi.tv.SiteHttpPolicy.defaultUserAgent(url.toHttpUrl()),subtitles=config.subtitles,playbackRequirements=setOf("referer"))
    }
}
