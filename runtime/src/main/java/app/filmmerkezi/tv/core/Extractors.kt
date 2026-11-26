package app.filmmerkezi.tv.core

import app.filmmerkezi.tv.SiteHttpPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder

/** Shared native extractors. Only the selected option is resolved; no media preflight. */
class AddonSourceResolver(private val network:WebTransport,private val youtube:YoutubeResolver=NewPipeYoutube(network)) {
    suspend fun resolve(option:SourceOption):ResolvedSource = withTimeout(35000) {
        if(option.state!=OptionState.AVAILABLE)throw DataFailure(if(option.state==OptionState.ACCESS_REQUIRED)FailureKind.ACCESS else FailureKind.UNSUPPORTED,option.reason,"resolve")
        val current=run{
            val site=Sites.get(option.content.siteId)
            option.copy(content=option.content.copy(pageUrl=site.catalogUrl(option.content.pageUrl)?:option.content.pageUrl),url=site.catalogUrl(option.url)?:option.url)
        }
        val site=Sites.get(current.content.siteId)
        val result=follow(current,current.url,current.content.pageUrl,mutableSetOf(),0)
        result.copy(url=SiteAddresses.rebase(result.url,site),referer=SiteAddresses.rebase(result.referer,site),
            subtitles=result.subtitles.map{it.copy(url=SiteAddresses.rebase(it.url,site))})
    }
    private fun source(option:SourceOption,url:String,referer:String,subtitles:List<Subtitle> = emptyList(),type:StreamType?=null,requirements:Set<String> = emptySet()):ResolvedSource {
        val safe=publicUrl(url,referer)?:throw DataFailure(FailureKind.PARSE,"Medya adresi geçersiz","decode")
        val u=safe.toHttpUrl();val kind=type?:when {u.encodedPath.endsWith(".mp4",true)->StreamType.MP4;u.encodedPath.endsWith(".mpd",true)->StreamType.DASH;else->StreamType.HLS}
        val ref=if(option.content.siteId=="hdfilmcehennemi" && u.host.contains("cdnimages"))"https://hdfilmcehennemi.mobi/" else referer
        return ResolvedSource(option.id,option.label,safe,kind,ref,SiteAddresses.userAgent(referer.toHttpUrl()),
            subtitles=subtitles.distinctBy{it.url},playbackRequirements=requirements,
            expiresAt=MediaExpiry.fromUrl(u))
    }
    private suspend fun follow(option:SourceOption,url:String,referer:String,visited:MutableSet<String>,depth:Int):ResolvedSource {
        val site=Sites.get(option.content.siteId)
        return followCurrent(option,SiteAddresses.rebase(url,site),SiteAddresses.rebase(referer,site),visited,depth)
    }
    private suspend fun followCurrent(option:SourceOption,url:String,referer:String,visited:MutableSet<String>,depth:Int):ResolvedSource {
        if(depth>6 || !visited.add(url))throw DataFailure(FailureKind.LIMIT,"Oynatıcı yönlendirme sınırı","resolve")
        val u=(publicUrl(url,referer)?:throw DataFailure(FailureKind.PARSE,"Kaynak adresi geçersiz","resolve")).toHttpUrl()
        if(YoutubeLinks.isHost(u.host))return youtube.resolve(option,u.toString())
        if(u.host=="p.playturka.space") {
            val id=u.fragment?:u.queryParameter("id")?:throw DataFailure(FailureKind.PARSE,"PlayTurka kimliği yok","decode")
            val r=network.request(WebRequest("https://p.playturka.space/api/video-url?id=$id",Sites.get("filmizlehell").base+"/"))
            val json=runCatching{JSONObject(r.body)}.getOrElse {
                val atbash=r.body.trim().map { c->when(c){in 'A'..'Z'->(155-c.code).toChar();in 'a'..'z'->(219-c.code).toChar();in '0'..'9'->('0'.code+(c-'0'+5)%10).toChar();'-'->'+';'_'->'/';else->c}}.joinToString("")
                JSONObject(Literals.b64(atbash))
            }
            if(json.string("status")!="success"||json.string("videoId")!=id)throw DataFailure(FailureKind.PARSE,"PlayTurka video kimliği eşleşmiyor","decode")
            return source(option,json.getJSONObject("files").getString("masterUrl"),url,requirements=setOf("header-context"))
        }
        val response=network.request(WebRequest(u.toString(),referer,headers=mapOf("User-Agent" to SiteAddresses.userAgent(u))))
        var body=unwrap(response.body)
        if(KatrePlayer.accepts(u.toString()))return KatrePlayer.resolve(option,body,response.url)
        if(u.host.removePrefix("www.") in setOf("vidmoly.biz","vidmoly.net","vidmoly.me","vidmoly.to")&&Regex("<title>\\s*Access Blocked\\s*</title>",RegexOption.IGNORE_CASE).containsMatchIn(body))
            throw DataFailure(FailureKind.ACCESS,"Kaynak bu siteden oynatmaya izin vermiyor","access")
        if(body.trimStart().startsWith("#EXTM3U"))return source(option,response.url,referer)
        if(response.headers["Content-Type"]?.startsWith("video/mp4")==true)return source(option,response.url,referer,type=StreamType.MP4)
        if(u.host=="streamcorecdn.com") {
            val config=StreamCore.parse(body,response.url)
            return source(option,config.url,response.url,config.subtitles,StreamType.HLS,setOf("referer"))
        }
        if(u.host=="videoplays.cfd") {
            val id=u.queryParameter("video")?.takeIf{it.matches(Regex("[1-9]\\d{0,9}"))}
                ?:throw DataFailure(FailureKind.PARSE,"VideoPlays video kimliği yok","decode")
            if(u.encodedPath!="/player.html"||HtmlData.document(body,url).getElementById("videojsMedia")==null||!body.contains("API.getVideo(videoId)"))throw DataFailure(FailureKind.PARSE,"VideoPlays ana oynatıcı bulunamadı","decode")
            val api=u.newBuilder().encodedPath("/api/videos/$id").query(null).build()
            val data=JSONObject(network.request(WebRequest(api.toString(),url)).body)
            if(data.optLong("id").toString()!=id)throw DataFailure(FailureKind.PARSE,"VideoPlays video kimliği eşleşmiyor","decode")
            if(data.string("status")!="completed")throw DataFailure(FailureKind.UNSUPPORTED,"Video henüz hazır değil","decode")
            // The public player sends its actual parent page as embed_referrer.
            // No admin session, settings, ad endpoints or external metadata are used.
            val endpoint=api.newBuilder().addPathSegment("token").addQueryParameter("embed_referrer",referer).build()
            val token=JSONObject(network.request(WebRequest(endpoint.toString(),url)).body)
            if(token.optLong("video_id").toString()!=id||token.string("token").isBlank())throw DataFailure(FailureKind.PARSE,"VideoPlays token kimliği eşleşmiyor","decode")
            val media=publicUrl(token.string("playlist_url"))?.toHttpUrl()?:throw DataFailure(FailureKind.PARSE,"VideoPlays playlist adresi yok","decode")
            val storage=publicUrl(token.string("storage_base_url"))?.toHttpUrl()
            if(media.scheme!="https"||media.encodedPath!="/$id/master.m3u8"||storage==null||storage.host!=media.host||media.queryParameter("token")!=token.string("token"))throw DataFailure(FailureKind.PARSE,"VideoPlays playlist kimliği eşleşmiyor","decode")
            val subs=data.optJSONArray("subtitles")?.values()?.filterIsInstance<JSONObject>().orEmpty().mapNotNull{s->
                val sub=publicUrl(s.string("url"),url)?:return@mapNotNull null
                val label=s.string("lang");val language=when(TextKey.fold(label)){"turkce","turkish","tur"->"tr";"english","ingilizce","eng"->"en";else->label}
                Subtitle(sub,label,language,if(sub.substringBefore('?').endsWith(".srt"))"srt"else"vtt")
            }
            return source(option,media.toString(),url,subs,StreamType.HLS,setOf("referer")).copy(expiresAt=token.optLong("expires_in").takeIf{it in 1..86400}?.let{System.currentTimeMillis()+it*1000})
        }
        if(u.host in setOf("vidara.to","gasobni.com") && u.encodedPath.startsWith("/e/")) {
            val id=u.encodedPath.substringAfterLast('/')
            if(!body.contains("/api/stream")||!body.contains("streaming_url"))throw DataFailure(FailureKind.PARSE,"Vidara aktif oynatıcı ayarı yok","decode")
            val api=u.newBuilder().encodedPath("/api/stream").query(null).build().toString()
            val payload=JSONObject().put("filecode",id).put("device","web").put("codecs",JSONArray()).toString()
            val data=JSONObject(network.request(WebRequest(api,url,"POST",body=payload,headers=mapOf("Content-Type" to "application/json"))).body)
            if(data.string("filecode").let{it.isNotBlank()&&it!=id})throw DataFailure(FailureKind.PARSE,"Vidara video kimliği eşleşmiyor","decode")
            val subs=data.optJSONArray("subtitles")?.values()?.filterIsInstance<JSONObject>()?.filter{it.optInt("type",-1)==0}?.mapNotNull {
                publicUrl(it.string("file_path"))?.let{f->Subtitle(f,it.string("language").ifBlank{"Altyazı"},it.string("language"),if(f.contains(".srt"))"srt" else "vtt")}
            }?:emptyList()
            return source(option,data.string("streaming_url"),u.newBuilder().encodedPath("/").query(null).build().toString(),subs)
        }
        if(u.host=="hotstream.club") {
            val media=HotstreamData.media(body,url)
            return source(option,media,url,requirements=setOf("accept-any","referer"))
        }
        if(u.host in setOf("ok.ru","odnoklassniki.ru"))return ok(option,body,url)
        if(u.host=="my.mail.ru") {
            val id=u.encodedPath.substringAfterLast('/').ifBlank{u.encodedPath.trimEnd('/').substringAfterLast('/')}
            val meta=Literals.after(body,"[\"']metadataUrl[\"']\\s*:\\s*") as? String ?:throw DataFailure(FailureKind.PARSE,"Mail metadata adresi yok","decode")
            val endpoint=publicUrl(meta,url)?:throw DataFailure(FailureKind.PARSE,"Mail metadata adresi geçersiz","decode")
            if(endpoint.toHttpUrl().host!=u.host||!endpoint.toHttpUrl().encodedPath.endsWith("/video/meta/$id"))throw DataFailure(FailureKind.PARSE,"Mail kimliği eşleşmiyor","decode")
            val data=JSONObject(network.request(WebRequest(endpoint,url)).body)
            val videos=data.optJSONArray("videos")?.values()?.filterIsInstance<JSONObject>()?:emptyList()
            val video=videos.sortedByDescending { it.string("key").filter(Char::isDigit).toIntOrNull()?:0 }.firstOrNull { publicUrl(it.string("url"),url)!=null }
                ?:throw DataFailure(FailureKind.UNSUPPORTED,"Mail video kaynağı yok","decode")
            return source(option,video.string("url"),url,type=StreamType.MP4).copy(variants=videos.mapNotNull{v->
                val media=publicUrl(v.string("url"),url)?:return@mapNotNull null
                val height=v.string("key").filter(Char::isDigit).toIntOrNull()?.takeIf{it>0}?:return@mapNotNull null
                MediaVariant("mail-${TextKey.hash(media)}",media,height,v.optInt("width"))
            }.distinctBy{it.url})
        }
        if(u.host=="videoseyred.in") {
            val endpoint=Literals.after(body,"\\bvar\\s+playlistUrl\\s*=\\s*") as? String?:throw DataFailure(FailureKind.PARSE,"Videoseyred playlist adresi yok","decode")
            val safe=publicUrl(endpoint,url)?:throw DataFailure(FailureKind.PARSE,"Videoseyred playlist adresi geçersiz","decode")
            if(safe.toHttpUrl().host!=u.host)throw DataFailure(FailureKind.PARSE,"Videoseyred kökeni farklı","decode")
            val item=JSONArray(network.request(WebRequest(safe,url)).body).optJSONObject(0)
            val media=item?.optJSONArray("sources")?.values()?.filterIsInstance<JSONObject>()?.firstOrNull { publicUrl(it.string("file"),url)!=null }
                ?:throw DataFailure(FailureKind.UNSUPPORTED,"Videoseyred kaynak yok","decode")
            return source(option,media.string("file"),url)
        }
        if(u.host in setOf("film.popcornvakti.net","dbx.molystream.org")) {
            val marker=Regex("""\bCryptoJS\.AES\.decrypt\(\s*""").find(body)
            if(marker!=null) {
                val cipher=Literals.read(body,marker.range.last+1);val comma=body.indexOf(',',cipher.end)
                val password=Literals.read(body,comma+1)
                body=Literals.aesPassword(cipher.value.toString(),password.value.toString())
            }
        }
        if(u.host=="dbx.molystream.org"){
            val id=Regex("^/embed/([A-Za-z0-9_-]+)/?$").find(u.encodedPath)?.groupValues?.get(1)
                ?:throw DataFailure(FailureKind.PARSE,"MolySTREAM video kimliği yok","decode")
            val setup=Regex("""\bjwplayer\(\s*['"]sheplayer['"]\s*\)\.setup\(\s*\{""").find(body)
                ?:throw DataFailure(FailureKind.UNSUPPORTED,"MolySTREAM aktif oynatıcı yok","decode")
            val end=SeededDecoder.closingBrace(body,setup.range.last)
            if(end<0)throw DataFailure(FailureKind.PARSE,"MolySTREAM ayarı eksik","decode")
            val config=body.substring(setup.range.last,end+1)
            val file=Literals.after(config,"\\bfile\\s*:\\s*") as? String
            val target=file?.toHttpUrlOrNull()
            if(target==null||target.scheme!="https"||target.host!=u.host||target.port!=443||target.encodedPath!="/embed/sheila/$id"||target.query!=null)
                throw DataFailure(FailureKind.PARSE,"MolySTREAM medya kimliği eşleşmiyor","decode")
            val subs=runCatching{Literals.after(config,"[\"']?tracks[\"']?\\s*:\\s*") as? JSONArray}.getOrNull()
            return source(option,target.toString(),url,tracks(subs,url),StreamType.HLS,setOf("referer","accept-any"))
        }
        if(u.host in setOf("rapidvid.org","rapidvid.net")) {
            val encoded=Literals.after(body,"\\bwindow\\._p8\\s*=\\s*") as? String
            if(encoded!=null) {
                val data=JSONObject(atom(encoded));val mount=data.string("m")
                if(mount.isBlank() || HtmlData.document(body,url).getElementById(mount)==null)throw DataFailure(FailureKind.PARSE,"Atom oynatıcı kimliği eşleşmiyor","decode")
                return source(option,data.string("cm"),url,tracks(data.optJSONArray("ct"),url))
            }
            val legacy=Regex("""\bjwSetup\.sources\s*=\s*\[[\s\S]{0,2000}?['"]file['"]\s*:\s*av\(\s*""").find(body)
            if(legacy!=null)return source(option,atom(Literals.read(body,legacy.range.last+1).value.toString()),url)
        }
        if(u.host=="oynat.fixfullfilmizle.net") {
            val target=Literals.after(body,"\\biframe\\.src\\s*=\\s*") as? String
                ?:throw DataFailure(FailureKind.PARSE,"FixFull iframe yönlendirmesi yok","decode")
            if(!target.startsWith("https://myplayersvideo.xyz/fireplayer/video/"))throw DataFailure(FailureKind.PARSE,"FixFull yönlendirme kökeni farklı","decode")
            return follow(option,target,url,visited,depth+1)
        }
        if(u.host in setOf("player.filmizle.in","myplayersvideo.xyz","matrudas.com")) {
            val packed=Literals.unpack(body)
            val bronze=jw(body,url)
            if(bronze!=null && bronze.first.toHttpUrl().encodedPath.endsWith(".mp4",true))return source(option,bronze.first,url,bronze.second,type=StreamType.MP4)
            val marker=Regex("""\bFirePlayer\(\s*""").find(packed)?:throw DataFailure(FailureKind.PARSE,"FirePlayer verisi yok","decode")
            val id=Literals.read(packed,marker.range.last+1);val comma=packed.indexOf(',',id.end)
            val config=Literals.read(packed,comma+1).value as? JSONObject?:throw DataFailure(FailureKind.PARSE,"FirePlayer ayarı geçersiz","decode")
            if(!u.encodedPath.substringAfterLast('/').equals(id.value.toString(),true))throw DataFailure(FailureKind.PARSE,"FirePlayer video kimliği farklı","decode")
            val api=if(u.host=="myplayersvideo.xyz")url+"?do=getVideo"else u.newBuilder().encodedPath("/player/index.php").query("data=${id.value}&do=getVideo").build().toString()
            val data=JSONObject(network.request(WebRequest(api,url,"POST",mapOf("hash" to id.value.toString(),"r" to Sites.get(option.content.siteId).base+"/"),
                headers=mapOf("X-Requested-With" to "XMLHttpRequest"))).body)
            if(u.host=="myplayersvideo.xyz") {
                val next=data.string("videoSrc")
                val target=publicUrl(next,url)?.toHttpUrl()
                if(target?.host=="vevocloud.com"&&Regex("^/play/index/[a-f0-9]{24}/?$").matches(target.encodedPath)){
                    // Recognized redirect: native transport preserves a real 521/other HTTP code.
                    network.request(WebRequest(target.toString(),url))
                    throw DataFailure(FailureKind.UNSUPPORTED,"Vevocloud oynatıcı biçimi henüz desteklenmiyor","decode")
                }
                if(!next.startsWith("https://matrudas.com/video/"))throw DataFailure(FailureKind.PARSE,"FixFull video yönlendirmesi farklı","decode")
                return follow(option,next,url,visited,depth+1)
            }
            if(!data.optBoolean("hls"))throw DataFailure(FailureKind.UNSUPPORTED,"FirePlayer HLS kaynağı yok","decode")
            val plain=publicUrl(data.string("videoSource"),url)
            val signed=publicUrl(data.string("securedLink"),url)
            if(plain!=null && signed!=null) {
                val a=plain.toHttpUrl();val b=signed.toHttpUrl()
                if(a.host!=b.host || a.encodedPath.removeSuffix("master.txt")!=b.encodedPath.removeSuffix("master.m3u8"))
                    throw DataFailure(FailureKind.PARSE,"FirePlayer imzalı medya kimliği farklı","decode")
            }
            val selected=if(option.content.siteId in setOf("sinemacc","fixfullfilmizle"))signed?:plain else plain?:signed
            return source(option,selected?:"",url,tracks(config.optJSONArray("tracks"),url),requirements=setOf("extensionless-hls","referer","fireplayer-signed-fallback"))
        }
        if(u.host.endsWith("pilavyerplay.top")||u.host.endsWith("pilavyerplay.live")) {
            val data=runCatching{Literals.after(body,"\\bwindow\\.__PLAYER__\\s*=\\s*") as? JSONObject}.getOrNull()
            if(data!=null)return source(option,data.string("stream"),url,
                data.optJSONArray("subs")?.values()?.filterIsInstance<JSONObject>()?.mapNotNull { s->publicUrl(s.string("src"),url)?.let{Subtitle(it,s.string("label"),s.string("lang"))} }?:emptyList(),
                requirements=setOf("segment-byte-limit:12582912","hls-key-headers"))
        }
        val decodedVix=vixolity(body)
        if(decodedVix!=null)return follow(option,decodedVix,url,visited,depth+1)
        val jw=jw(body,url)
        if(jw!=null)return source(option,jw.first,url,jw.second,requirements=if(jw.first.contains(".txt"))setOf("extensionless-hls")else emptySet())
        val seeded=SeededDecoder.media(Literals.unpack(body),url).firstOrNull { mediaUrl(it) }
        if(seeded!=null)return source(option,seeded,url,requirements=setOf("seeded-data","extensionless-hls"))
        val menu=if(u.host==Sites.get(option.content.siteId).base.toHttpUrl().host)SourceMenus.parse(Sites.get(option.content.siteId),body,option.content,url)else emptyList()
        val direct=menu.firstOrNull{it.url!=url && it.extractor!="page"}
        if(direct!=null)return follow(option,direct.url,url,visited,depth+1)
        val frames=SourceMenus.frames(body,url)
        if(frames.isNotEmpty())return follow(option,frames.first(),url,visited,depth+1)
        if(body.contains("Lütfen bekleyiniz",true) && depth<3){delay(1100);visited.remove(url);return follow(option,url,referer,visited,depth+1)}
        throw DataFailure(FailureKind.UNSUPPORTED,"Aktif kaynak biçimi tanınamadı","decode")
    }
    private fun unwrap(input:String):String {
        var body=input
        repeat(3) {
            val m=Regex("""^\s*(?:<script[^>]*>\s*)?document\.write\s*\(\s*atob\s*\(\s*unescape\s*\(\s*""").find(body)?:return body
            val encoded=Literals.read(body,m.range.last+1).value.toString()
            body=Literals.b64(URLDecoder.decode(encoded.replace("+","%2B"),"UTF-8"))
        }
        return body
    }
    private fun atom(value:String):String {
        val outer=Literals.base64(value.reversed()).toString(Charsets.ISO_8859_1);val salt="K9L"
        val inner=outer.mapIndexed{i,c->(c.code-(salt[i%3].code%5+1)).toChar()}.joinToString("")
        return Literals.b64(inner)
    }
    private fun tracks(array:JSONArray?,page:String):List<Subtitle> = array?.values()?.filterIsInstance<JSONObject>()?.filter {
        it.string("kind") in listOf("captions","subtitles") || it.has("src")
    }?.mapNotNull{t->publicUrl(t.string("file").ifBlank{t.string("src")},page)?.let { Subtitle(it,t.string("label").ifBlank{"Altyazı"},t.string("language").ifBlank{t.string("srclang")},if(it.contains(".srt"))"srt" else "vtt") }}?:emptyList()
    private fun ok(option:SourceOption,body:String,page:String):ResolvedSource {
        if(Regex("""['"]copyrightsRestricted['"]\s*:\s*true|yandexError\(\s*['"]copyrightsRestricted""").containsMatchIn(body))throw DataFailure(FailureKind.ACCESS,"OK.ru kaynağı kaldırılmış","decode")
        val id=Regex("/videoembed/(\\d+)").find(page)?.groupValues?.get(1)
        for(node in HtmlData.document(body,page).select("[data-options]")) {
            val o=runCatching{JSONObject(node.attr("data-options"))}.getOrNull()?:continue
            val raw=o.optJSONObject("flashvars")?.opt("metadata")
            val data=when(raw){is JSONObject->raw;is String->runCatching{JSONObject(raw)}.getOrNull();else->null}?:continue
            val movie=data.optJSONObject("movie")?:continue
            if(id!=null && movie.string("movieId").ifBlank{movie.string("id")}!=id)continue
            if(movie.optBoolean("notPublished") || Regex("blocked|deleted|restricted|copyright",RegexOption.IGNORE_CASE).containsMatchIn(movie.string("status")))throw DataFailure(FailureKind.ACCESS,"OK.ru video kullanılamıyor","decode")
            val media=data.string("hlsManifestUrl").ifBlank{data.string("ondemandHls")}.ifBlank{data.string("liveHlsManifestUrl")}
            if(media.isNotBlank())return source(option,media,page)
            val videos=data.optJSONArray("videos")?.values()?.filterIsInstance<JSONObject>()?:emptyList()
            val direct=videos.sortedBy{mapOf("ultra" to 0,"quad" to 1,"full" to 2,"hd" to 3,"sd" to 4,"low" to 5,"mobile" to 6)[it.string("name")]?:10}.firstOrNull{publicUrl(it.string("url"))!=null}
            if(direct!=null)return source(option,direct.string("url"),page,type=StreamType.MP4).copy(variants=videos.mapNotNull{v->
                val media=publicUrl(v.string("url"),page)?:return@mapNotNull null
                val height=v.optInt("height").takeIf{it>0}?:mapOf("ultra" to 2160,"quad" to 1440,"full" to 1080,"hd" to 720,"sd" to 480,"low" to 360,"mobile" to 240)[v.string("name")]?:return@mapNotNull null
                MediaVariant("ok-${TextKey.hash(media)}",media,height,v.optInt("width"))
            }.distinctBy{it.url})
        }
        throw DataFailure(FailureKind.PARSE,"OK.ru etkin video verisi yok","decode")
    }
    private fun vixolity(body:String):String? {
        val calls=Regex("""\}\)\('([0-9a-f]+)',\s*(\d{1,3})\);""").findAll(body)
        for(c in calls) {
            val ascii=runCatching{Literals.hex(c.groupValues[1]).toString(Charsets.ISO_8859_1).reversed()}.getOrNull()?:continue
            val shift=c.groupValues[2].toInt();if(shift>255)continue
            val decoded=runCatching{Literals.base64(ascii).map{(((it.toInt() and 255)-shift+256) and 255).toChar()}.joinToString("")}.getOrNull()?:continue
            val m=Regex("""setupJWPlayer\(\s*['"]playerArea['"]\s*,\s*\{\s*file\s*:\s*""").find(decoded)?:continue
            val u=runCatching{Literals.read(decoded,m.range.last+1).value as? String}.getOrNull()
            if(u!=null && u.startsWith("https://ha.vixolity.com/vs/"))return u
        }
        return null
    }
    private fun jw(input:String,page:String):Pair<String,List<Subtitle>>? {
        val body=runCatching{Literals.unpack(input)}.getOrDefault(input)
        val doc=HtmlData.document(body,page)
        val active=Regex("""(?:jwplayer\(\s*['"]([^'"]+)['"]\s*\)|\bplayer)\s*\.setup\s*\(\s*""")
        for(m in active.findAll(body)) {
            if(m.groupValues[1].isNotBlank() && doc.getElementById(m.groupValues[1])==null)continue
            val after=m.range.last+1
            val inline=Regex("^\\s*\\{").find(body.substring(after))
            val start=if(inline!=null)after+inline.range.last else {
                // setup(configs): follow only a prior literal object initializer, never execute JS.
                val name=Regex("^\\s*([\\w$]+)\\s*\\)").find(body.substring(after))?.groupValues?.get(1)?:continue
                val declaration=Regex("\\b(?:var|let|const)\\s+"+Regex.escape(name)+"\\s*=\\s*\\{")
                    .findAll(body.substring(0,after)).lastOrNull()?:continue
                declaration.range.last
            }
            val end=SeededDecoder.closingBrace(body,start);if(end<0)continue
            val config=body.substring(start,end+1)
            val files=Regex("""(?:^|[,{])\s*(?:file|['"]file['"])\s*:\s*""").findAll(config).mapNotNull { f->
                val literal=runCatching{Literals.read(config,f.range.last+1).value as? String}.getOrNull()
                literal?:Regex("""^\s*([\w$]+)\s*[,}]""").find(config.substring(f.range.last+1))?.groupValues?.get(1)?.let{SeededDecoder.bindings(body,page)[it]}
            }.toList()
            val captions=runCatching{Literals.after(config,"\\btracks\\s*:\\s*") as? JSONArray}.getOrNull()
                ?:Regex("""\btracks\s*:\s*([\w$]+)""").find(config)?.groupValues?.get(1)?.let{runCatching{Literals.after(body,"\\b(?:var|let|const)\\s+"+Regex.escape(it)+"\\s*=\\s*") as? JSONArray}.getOrNull()}
            val chosen=files.firstOrNull{publicUrl(it,page)!=null && !it.endsWith(".vtt") && !it.endsWith(".srt")}
            if(chosen!=null)return (publicUrl(chosen,page)?:continue) to tracks(captions,page)
        }
        val media=doc.select("video source[src], video[src]").firstOrNull()?.attr("src")?.let{publicUrl(it,page)}
        if(media!=null)return media to doc.select("video track[src]").mapNotNull { t->publicUrl(t.attr("src"),page)?.let{Subtitle(it,t.attr("label"),t.attr("srclang"))} }
        return null
    }
    private fun mediaUrl(url:String):Boolean = Regex("\\.(m3u8|mp4|mpd)(?:$|[?#])|/(?:hls|list)/|master\\.(?:txt|json)",RegexOption.IGNORE_CASE).containsMatchIn(url)
}
