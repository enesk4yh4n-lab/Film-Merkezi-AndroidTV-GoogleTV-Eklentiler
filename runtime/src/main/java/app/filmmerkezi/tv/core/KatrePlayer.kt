package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Reads the public player's data object; executes no player, advertising or tracking code. */
object KatrePlayer {
    data class Stream(val key:String,val title:String,val url:String)
    data class Config(val streams:List<Stream>,val subtitles:List<Subtitle>,val noReferer:Boolean=false)
    private val uuid=Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private val psourcePath=Regex("^/psource/(${uuid.pattern})/?$")
    private fun psourceId(url:okhttp3.HttpUrl)=if(url.host=="yabancidizim.com"&&url.query==null)
        psourcePath.matchEntire(url.encodedPath)?.groupValues?.get(1) else null
    fun accepts(url:String)=url.toHttpUrlOrNull()?.let{
        it.scheme=="https"&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&
            (it.host=="ksdpictures.site"&&it.encodedPath=="/dzembed.php"||psourceId(it)!=null)
    }==true
    fun label(url:String)=url.toHttpUrlOrNull()?.takeIf{psourceId(it)!=null}?.let{"Psource"}
    fun parse(body:String,url:String):Config {
        val u=url.toHttpUrl()
        val psource=psourceId(u)
        val id=psource?:u.queryParameter("id")
        if(!accepts(url)||id==null||!id.matches(uuid))throw DataFailure(FailureKind.PARSE,"Katre video kimliği geçersiz","decode")
        val config=runCatching{Literals.after(body,"\\bwindow\\.NP_CONFIG\\s*=\\s*")as? JSONObject}.getOrNull()
            ?:throw DataFailure(FailureKind.PARSE,"Katre kaynak yapılandırması bulunamadı","decode")
        val playerId=if(psource!=null)"psource-$id" else "embed-$id"
        if(config.string("id")!=playerId)throw DataFailure(FailureKind.PARSE,"Katre video kimliği eşleşmiyor","decode")
        if(psource!=null)return parsePsource(body,url,playerId,config)
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
    private fun parsePsource(body:String,url:String,playerId:String,config:JSONObject):Config {
        val doc=HtmlData.document(body,url)
        val data=runCatching{Literals.after(body,"""\bwindow\.PSOURCE_CONFIG\s*=\s*""") as? JSONObject}.getOrNull()
        if(doc.getElementById("np")==null||!config.optBoolean("deferSource")||
            data?.string("id")!=playerId||data.string("parentOrigin")!="https://diziboxum.com")
            throw DataFailure(FailureKind.PARSE,"Psource oynatıcı kimliği veya site kökeni eşleşmiyor","decode")
        val parts=data.optJSONArray("parts")?.values()?.filterIsInstance<JSONObject>().orEmpty()
        if(parts.size>64)throw DataFailure(FailureKind.LIMIT,"Psource kaynak listesi sınırını aştı","decode")
        val streams=parts.mapNotNull{part->
            val rawId=part.opt("id")
            val value=if(rawId is Number&&rawId.toDouble()==rawId.toLong().toDouble())rawId.toLong().toString()
                else rawId as? String
            val key=value?.takeIf{it.matches(Regex("[0-9]{1,15}"))}?:return@mapNotNull null
            val title=part.string("label")
            if(title.equals("message",true))return@mapNotNull null
            val media=publicUrl(part.string("url"),url)?.toHttpUrlOrNull()?.takeIf{
                it.scheme=="https"&&it.port==443&&it.host in setOf("video.twimg.com","ksdpictures.site","yabancidizim.com",
                    "pictures.spordunyasi.site","pictures.izmirgezisi.site")&&it.encodedPath.endsWith(".m3u8",true)
            }?:return@mapNotNull null
            Stream(TextKey.hash(key),title.ifBlank{"Kaynak"},media.toString())
        }.distinctBy{it.key}
        if(streams.isEmpty())throw DataFailure(FailureKind.UNSUPPORTED,"Psource oynatılabilir HLS kaynağı bildirmiyor","decode")
        return Config(streams,emptyList(),doc.selectFirst("meta[name=referrer][content=no-referrer]")!=null)
    }
    fun options(option:SourceOption,config:Config)=config.streams.map{stream->option.copy(id=option.id+"-"+stream.key,
        label=option.label+" · "+stream.title,fields=option.fields+("katreStream" to stream.key))}
    fun resolve(option:SourceOption,body:String,url:String):ResolvedSource {
        val config=parse(body,url);val key=option.fields["katreStream"]
        val stream=if(key==null)config.streams.first()else config.streams.firstOrNull{it.key==key}
            ?:throw DataFailure(FailureKind.PARSE,"Katre kaynak listesi değişmiş; listeyi yenile","decode")
        return ResolvedSource(option.id,option.label,stream.url,StreamType.HLS,if(config.noReferer)""else url,
            app.filmmerkezi.tv.SiteHttpPolicy.defaultUserAgent(url.toHttpUrl()),subtitles=config.subtitles,
            playbackRequirements=if(config.noReferer)emptySet()else setOf("referer"))
    }
}
