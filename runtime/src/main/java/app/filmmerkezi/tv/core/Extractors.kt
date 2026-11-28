package app.filmmerkezi.tv.core

import app.filmmerkezi.tv.SiteHttpPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder

private val liderStreamCallback = Regex(
    """\s*\)\s*\.then\(\s*function\s*\(\s*(\w+)\s*\)\s*\{\s*return\s+\1\.json\(\)\s*;?\s*\}\s*\)\s*""" +
        """\.then\(\s*function\s*\(\s*(\w+)\s*\)\s*\{\s*if\s*\(\s*\2\.url\s*\)\s*\{\s*""" +
        """(?:\w+\s*=\s*)?new\s+Playerjs\(\s*\{\s*id\s*:\s*["']([A-Za-z0-9_-]{1,64})["'][^{}]{0,2048}?,\s*file\s*:\s*\2\.url\s*[,}]"""
)

/** Shared native extractors. Only the selected option is resolved; no media preflight. */
class AddonSourceResolver(private val network:WebTransport,private val youtube:YoutubeResolver=NewPipeYoutube(network)) {
    suspend fun resolve(option:SourceOption):ResolvedSource = withTimeout(35000) {
        if(option.state!=OptionState.AVAILABLE)throw DataFailure(if(option.state==OptionState.ACCESS_REQUIRED)FailureKind.ACCESS else FailureKind.UNSUPPORTED,option.reason,"resolve")
        val current=run{
            val site=Sites.get(option.content.siteId)
            option.copy(content=option.content.copy(pageUrl=site.catalogUrl(option.content.pageUrl)?:option.content.pageUrl),url=site.catalogUrl(option.url)?:option.url)
        }
        val site=Sites.get(current.content.siteId)
        val sourcePage=current.fields["sourcePage"]?.takeIf{
            EpisodePages.sameEpisodePage(site,current.content.pageUrl,it)
        }?:current.content.pageUrl
        val result=follow(current,current.url,sourcePage,mutableSetOf(),0)
        val mediaHost=result.url.toHttpUrl().host
        if(site.id=="ddizi"&&(mediaHost=="googlevideo.com"||mediaHost.endsWith(".googlevideo.com"))&&
            result.expiresAt?.let{it<=System.currentTimeMillis()}==true)
            throw DataFailure(FailureKind.ACCESS,"Site, geçerlilik süresi dolmuş bir GoogleVideo adresi verdi","media-expiry")
        val caption=current.fields["caption"]?.takeIf{site.catalogUrl(it)!=null&&it.toHttpUrl().encodedPath.endsWith(".srt",true)}
        result.copy(url=SiteAddresses.rebase(result.url,site),referer=SiteAddresses.rebase(result.referer,site),
            subtitles=(result.subtitles+listOfNotNull(caption?.let{Subtitle(it,"Türkçe","tr","srt")})).distinctBy{it.url}.map{it.copy(url=SiteAddresses.rebase(it.url,site))})
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
        val site=Sites.get(option.content.siteId)
        val rapidvid = u.host == "rapidvid.org" || u.host == "rapidvid.net"
        val trDiziJw = site.id == "p012" && site.catalogUrl(u.toString()) != null && u.query == null &&
            Regex("/player/oynat/[a-f0-9]{24,64}/?").matches(u.encodedPath)
        val userAgent = when {
            rapidvid && site.id == "fullhdfilmizlesene" -> SiteHttpPolicy.forProvider(site.id)
            trDiziJw -> SiteHttpPolicy.BROWSER_USER_AGENT
            else -> SiteAddresses.userAgent(u)
        }
        val response=network.request(WebRequest(u.toString(),referer,headers=mapOf("User-Agent" to userAgent)))
        val episodeId=EpisodePages.episodePageKey(site,u.toString())
        if(episodeId!=null&&EpisodePages.episodePageKey(site,response.url)!=episodeId)
            throw DataFailure(FailureKind.PARSE,"Kaynak sayfası başka bir bölüme yönlendi","resolve")
        var body=unwrap(response.body)
        if(option.extractor=="spidypro"&&site.id=="p008"&&u.host=="spidypro.com"){
            val doc=HtmlData.document(body,response.url)
            val gate=doc.title()=="Redirecting..."&&doc.selectFirst("#loading[role=status]")!=null&&
                doc.select("script").any{script->
                    val code=script.data()
                    code.contains("ad-overlay")&&code.contains("getComputedStyle")&&code.contains("document.referrer")
                }
            if(gate)throw DataFailure(FailureKind.ACCESS,"SpidyPro oynatıcıyı açmak için tarayıcı doğrulaması istiyor","embed")
        }
        if(option.extractor=="hdf2-vip"&&u.host=="vidload.top"){
            val video=option.fields["video"]?.takeIf{it.matches(Regex("[a-zA-Z0-9]{8,64}"))}
                ?:throw DataFailure(FailureKind.PARSE,"VIP video kimliği eksik","decode")
            if(u.encodedPath!="/player/$video"||response.url.toHttpUrl().encodedPath!=u.encodedPath||
                HtmlData.document(body,response.url).getElementById("player")==null)
                throw DataFailure(FailureKind.PARSE,"VIP oynatıcı eşleşmedi","decode")
            val config=TrackedSources.jsonLiteral(body,"""\bjwplayer\(\s*['"]player['"]\s*\)\s*\.setup\(\s*JSON\.parse\(\s*""")
                ?:throw DataFailure(FailureKind.PARSE,"VIP oynatma verisi bulunamadı","decode")
            fun allowed(raw:String)=publicUrl(raw,response.url)?.toHttpUrl()?.takeIf{target->
                target.scheme=="https"&&target.port==443&&setOf("haccodavayup.shop","pictureupfans.site").any{target.host==it||target.host.endsWith(".$it")}
            }
            val media=allowed(config.string("file"))?.takeIf{Regex("/"+Regex.escape(video)+"(?:/|_)").containsMatchIn(it.encodedPath)&&it.encodedPath.endsWith(".m3u8",true)}
                ?:throw DataFailure(FailureKind.PARSE,"VIP medya kimliği eşleşmedi","decode")
            val subtitles=tracks(config.optJSONArray("tracks"),response.url).filter{allowed(it.url)!=null}
            return source(option,media.toString(),response.url,subtitles,StreamType.HLS)
        }
        if(option.extractor=="lider-vod"&&u.host=="play.liderfilm.cc"){
            val raw=Literals.after(body,"""\bvar\s+CFG\s*=\s*\{\s*src\s*:\s*""") as? String
            val media=publicUrl(raw.orEmpty(),response.url)?.toHttpUrl()?.takeIf{
                it.host==u.host&&it.encodedPath=="/player/stream.php"&&publicUrl(it.queryParameter("url").orEmpty())!=null
            }?:throw DataFailure(FailureKind.PARSE,"Lider HLS yayın adresi bulunamadı","decode")
            return source(option,media.toString(),response.url,type=StreamType.HLS,requirements=setOf("extensionless-hls"))
        }
        if(option.extractor=="lider-playerjs"&&site.id=="p018"&&u.host=="play.liderfilm.cc"){
            val frame=response.url.toHttpUrl()
            if(frame.scheme!="https"||frame.host!=u.host||frame.port!=443||frame.encodedPath!=u.encodedPath)
                throw DataFailure(FailureKind.UNSUPPORTED,"PlayerJS farklı bir oynatıcıya yönlendi","embed")
            val doc=HtmlData.document(body,response.url)
            val config=playerJsConfig(doc) ?: return resolveLiderNestedPlayer(option,frame,doc)
            val media=publicUrl(config.string("file"),response.url)?.toHttpUrl()?.takeIf{
                it.scheme=="https"&&it.port==443&&it.host==frame.host
            }?:throw DataFailure(FailureKind.UNSUPPORTED,"PlayerJS doğrulanmış bir medya adresi sunmadı","decode")
            val type=when{
                media.encodedPath.endsWith(".mp4",true)->StreamType.MP4
                media.encodedPath.endsWith(".m3u8",true)->StreamType.HLS
                else->throw DataFailure(FailureKind.UNSUPPORTED,"PlayerJS medya biçimi doğrulanamadı","decode")
            }
            val playable=source(option,media.toString(),response.url,type=type)
            return if(doc.selectFirst("meta[name=referrer][content=no-referrer]")!=null)playable.copy(referer="")else playable
        }
        if(option.content.siteId=="p016"&&u.host=="player.nadirfilm.com"){
            val key=u.queryParameter("tmdb")?.takeIf{it.matches(Regex("""\d{1,12}"""))}
            val raw=HtmlData.document(body,response.url).selectFirst("#player_iframe[data-api]")?.attr("data-api").orEmpty()
            val api=publicUrl(raw,response.url)?.toHttpUrl()?.takeIf{key!=null&&it.host==u.host&&
                it.encodedPath=="/vs_src.php"&&it.queryParameter("type")=="movie"&&it.queryParameter("id")==key}
                ?:throw DataFailure(FailureKind.PARSE,"NadirFilm oynatıcı kimliği eşleşmedi","decode")
            val data=JSONObject(network.request(WebRequest(api.toString(),response.url)).body)
            val next=publicUrl(data.string("src"),response.url)?.toHttpUrl()
            if(next?.host=="stellarconductornexus.com")
                throw DataFailure(FailureKind.ACCESS,"NadirFilm oynatıcıyı açmak için tarayıcı doğrulaması istiyor","access")
            if(next==null||next.host !in setOf("ok.ru","dzen.ru","vidmoly.biz","vidmoly.me","vidmoly.net"))
                throw DataFailure(FailureKind.UNSUPPORTED,"NadirFilm oynatıcısı doğrulanmış bir medya adresi sunmadı","decode")
            return follow(option,next.toString(),response.url,visited,depth+1)
        }
        if(option.content.siteId in setOf("p016","filmizlebabam")&&u.host=="dzen.ru"){
            val id=Regex("""^/embed/([A-Za-z0-9_-]{8,80})$""").matchEntire(u.encodedPath)?.groupValues?.get(1)
            val meta=HtmlData.document(body,response.url).selectFirst("meta[property=og:vh-uuid]")?.attr("content")
            if(id==null||id!=meta)throw DataFailure(FailureKind.PARSE,"Dzen video kimliği eşleşmedi","decode")
            // Separate script blocks use the same local variable for environment, logging and video data.
            val params=Regex("""\bvar\s+_params\s*=\s*\(\s*""")
            val data=params.findAll(body).take(16).firstNotNullOfOrNull{match->
                val value=runCatching{Literals.read(body,match.range.last+1).value as? JSONObject}.getOrNull()
                value?.optJSONObject("ssrData")?.optJSONObject("exportResponse")?.optJSONObject("content")
                    ?.takeIf{it.string("content_id")==id}
            }
            val stream=data?.optJSONArray("streams")?.values()?.filterIsInstance<JSONObject>()
                ?.firstOrNull{it.string("type")=="hls"&&publicUrl(it.string("url"),response.url)!=null}
                ?:throw DataFailure(FailureKind.UNSUPPORTED,"Dzen bu video için HLS kaynağı vermedi","decode")
            return source(option,stream.string("url"),response.url,type=StreamType.HLS)
        }
        if(option.content.siteId=="diziboxum"&&KatrePlayer.accepts(u.toString()))return KatrePlayer.resolve(option,body,u.toString())
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
        if((u.host in setOf("vidara.to","gasobni.com")||option.content.siteId=="filmizlebabam"&&u.host=="mxzhxco8dft.com") && u.encodedPath.startsWith("/e/")) {
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
        if(rapidvid) {
            val encoded=Literals.after(body,"\\bwindow\\._p8\\s*=\\s*") as? String
            if(encoded!=null) {
                val data=JSONObject(atom(encoded));val mount=data.string("m")
                if(mount.isBlank() || HtmlData.document(body,url).getElementById(mount)==null)throw DataFailure(FailureKind.PARSE,"Atom oynatıcı kimliği eşleşmiyor","decode")
                return source(option,data.string("cm"),url,tracks(data.optJSONArray("ct"),url)).copy(userAgent=userAgent)
            }
            val legacy=Regex("""\bjwSetup\.sources\s*=\s*\[[\s\S]{0,2000}?['"]file['"]\s*:\s*av\(\s*""").find(body)
            if(legacy!=null)return source(option,atom(Literals.read(body,legacy.range.last+1).value.toString()),url).copy(userAgent=userAgent)
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
            if(bronze!=null && bronze.url.toHttpUrl().encodedPath.endsWith(".mp4",true))
                return source(option,bronze.url,url,bronze.subtitles,type=StreamType.MP4)
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
        val decodedVix=if(u.host=="pl.vixolity.com")vixolity(body)else null
        if(decodedVix!=null)return follow(option,decodedVix,url,visited,depth+1)
        val jw=jw(body,url)
        if(jw!=null){
            val resolved=source(option,jw.url,response.url,jw.subtitles,jw.type,
                requirements=if(jw.type!=StreamType.MP4&&jw.url.contains(".txt"))setOf("extensionless-hls")else emptySet())
            val headers = if (trDiziJw) resolved.copy(userAgent = userAgent) else resolved
            return if(site.id in setOf("ddizi","p012")&&jw.noReferer)headers.copy(referer="")else headers
        }
        val seeded=SeededDecoder.media(Literals.unpack(body),url).firstOrNull { mediaUrl(it) }
        if(seeded!=null)return source(option,seeded,url,requirements=setOf("seeded-data","extensionless-hls"))
        val menu=if(u.host==site.base.toHttpUrl().host)SourceMenus.parse(site,body,option.content,response.url)else emptyList()
        val direct=menu.firstOrNull{it.url!=url && it.extractor!="page"}
        if(direct!=null)return follow(option,direct.url,response.url,visited,depth+1)
        if(episodeId!=null)throw DataFailure(FailureKind.UNSUPPORTED,"Bu bölümün etkin oynatıcısı bulunamadı","decode")
        val frames=SourceMenus.frames(body,url)
        if(frames.isNotEmpty())return follow(option,frames.first(),url,visited,depth+1)
        if(body.contains("Lütfen bekleyiniz",true) && depth<3){delay(1100);visited.remove(url);return follow(option,url,referer,visited,depth+1)}
        throw DataFailure(FailureKind.UNSUPPORTED,"Aktif kaynak biçimi tanınamadı","decode")
    }

    private fun playerJsConfig(doc: org.jsoup.nodes.Document): JSONObject? {
        val calls=Regex("""\bnew\s+Playerjs\(\s*(?=\{)""")
        return doc.select("script:not([src])").asSequence().flatMap{script->
            calls.findAll(script.data()).mapNotNull{call->
                runCatching{Literals.read(script.data(),call.range.last+1).value as? JSONObject}.getOrNull()
            }
        }.firstOrNull{it.string("id").isNotBlank()&&doc.getElementById(it.string("id"))!=null}
    }

    private suspend fun resolveLiderNestedPlayer(option: SourceOption, frame: HttpUrl, doc: org.jsoup.nodes.Document): ResolvedSource {
        val embeddedHost = "x.ag2m5.cfd"
        val mediaHost = "qp21.uk-traffic-076.com"
        val nested = doc.select("iframe[data-frame-src], iframe[src]").mapNotNull { node ->
            val address = node.attr("data-frame-src").ifBlank { node.attr("src") }
            publicUrl(address,frame.toString())?.toHttpUrl()?.takeIf {
                it.scheme == "https" && it.port == 443 && it.host == embeddedHost && it.fragment == null &&
                    it.encodedPath == frame.encodedPath
            }
        }.distinct().singleOrNull()
            ?: throw DataFailure(FailureKind.UNSUPPORTED,"PlayerJS bölümüne bağlı oynatıcı bulunamadı","embed")
        val player = network.request(WebRequest(nested.toString(),frame.toString()))
        val playerUrl = player.url.toHttpUrlOrNull()
        if (playerUrl != nested) throw DataFailure(FailureKind.PARSE,"PlayerJS oynatıcı kimliği eşleşmedi","embed")
        val playerDoc = HtmlData.document(player.body,player.url)
        val hasLiteralConfig = playerJsConfig(playerDoc) != null
        val fetchCall = Regex("""\bfetch\(\s*(?=['"]/dl\?op=get_stream&)""")
        val endpoint = playerDoc.select("script:not([src])").asSequence().flatMap { script ->
            val code = script.data()
            fetchCall.findAll(code).mapNotNull { call ->
                val value = runCatching { Literals.read(code,call.range.last+1) }.getOrNull() ?: return@mapNotNull null
                if (!hasLiteralConfig) {
                    val id = liderStreamCallback.matchAt(code,value.end)?.groupValues?.get(3) ?: return@mapNotNull null
                    if (playerDoc.getElementById(id) == null) return@mapNotNull null
                }
                value.value as? String
            }
        }.mapNotNull { path -> publicUrl(path,player.url)?.toHttpUrl() }.filter {
            it.scheme == "https" && it.port == 443 && it.host == embeddedHost && it.fragment == null &&
                it.encodedPath == "/dl" && it.queryParameter("op") == "get_stream" &&
                it.queryParameter("view_id")?.matches(Regex("[0-9]{1,12}")) == true && !it.queryParameter("hash").isNullOrBlank()
        }.distinct().toList().singleOrNull()
            ?: throw DataFailure(FailureKind.UNSUPPORTED,"PlayerJS oynatma isteği bulunamadı","decode")
        val reply = network.request(WebRequest(endpoint.toString(),player.url))
        if (reply.url.toHttpUrlOrNull() != endpoint) throw DataFailure(FailureKind.PARSE,"PlayerJS oynatma isteği farklı adrese yönlendi","decode")
        val data = runCatching { JSONObject(reply.body) }.getOrElse {
            throw DataFailure(FailureKind.PARSE,"PlayerJS oynatma yanıtı okunamadı","decode",cause=it)
        }
        if (data.string("error") == "unauthorized") throw DataFailure(FailureKind.ACCESS,"Oynatma sunucusu erişimi reddetti","access")
        val media = publicUrl(data.string("url"))?.toHttpUrl()?.takeIf {
            data.string("error").isBlank() && it.scheme == "https" && it.port == 443 && it.fragment == null &&
                it.encodedPath.endsWith(".m3u8",true) && (it.host == embeddedHost || it.host == mediaHost && it.encodedPath.startsWith("/hls2/"))
        } ?: throw DataFailure(FailureKind.UNSUPPORTED,"PlayerJS doğrulanmış bir HLS adresi sunmadı","decode")
        val referer = if (media.host == mediaHost) "https://$embeddedHost/" else player.url
        return source(option,media.toString(),referer,type=StreamType.HLS)
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
        val calls=Regex("""\}\)\('([0-9a-f]{2,1048576})',\s*(\d{1,3})\);""",RegexOption.IGNORE_CASE).findAll(body)
        val current=body.contains("charCodeAt")&&body.contains("reverse(")&&
            Regex("""<div\s+id=["']oynatici["']""").containsMatchIn(body)
        fun validated(value:String?):String?=value?.takeIf{
            it.matches(Regex("""https://ha\.vixolity\.com/vs/[A-Za-z0-9_-]+(?:/s[1-9]\d*/e0*[1-9]\d*)?"""))
        }
        for(c in calls) {
            val ascii=runCatching{Literals.hex(c.groupValues[1]).toString(Charsets.ISO_8859_1).reversed()}.getOrNull()?:continue
            val shift=c.groupValues[2].toInt();if(shift>255)continue
            val decoded=runCatching{Literals.base64(ascii).map{(((it.toInt() and 255)-shift+256) and 255).toChar()}.joinToString("")}.getOrNull()?:continue
            val old=runCatching{Literals.after(decoded,"""setupJWPlayer\(\s*['"]playerArea['"]\s*,\s*\{\s*file\s*:\s*""") as? String}.getOrNull()
            validated(old)?.let{return it}
            if(current){
                val config=runCatching{Literals.after(Literals.b64(decoded),"""\bvar\s+AYAR\s*=\s*""") as? JSONObject}.getOrNull()
                validated(config?.string("kaynak"))?.let{return it}
            }
        }
        return null
    }
    private data class PlayerMedia(val url:String,val subtitles:List<Subtitle>,val type:StreamType?,val noReferer:Boolean)
    private fun streamType(value:String)=when(value.lowercase()){
        "mp4","video/mp4"->StreamType.MP4
        "hls","m3u8","application/x-mpegurl","application/vnd.apple.mpegurl"->StreamType.HLS
        "dash","mpd","application/dash+xml"->StreamType.DASH
        else->null
    }
    private val playerField=Regex("""\s*,\s*['"]?([a-zA-Z_][a-zA-Z_0-9]*)['"]?\s*:\s*""")
    private fun playerFileType(config:String,start:Int):StreamType? {
        var at=start
        repeat(8){
            val field=playerField.matchAt(config,at)?:return null
            val value=runCatching{Literals.read(config,field.range.last+1)}.getOrNull()?:return null
            if(field.groupValues[1]=="type")return (value.value as? String)?.let(::streamType)
            at=value.end
        }
        return null
    }
    private fun jw(input:String,page:String):PlayerMedia? {
        val body=runCatching{Literals.unpack(input)}.getOrDefault(input)
        val doc=HtmlData.document(body,page)
        val noReferer=doc.selectFirst("meta[name=referrer][content=no-referrer]")!=null
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
            val chosen=Regex("""(?:^|[,{])\s*(?:file|['"]file['"])\s*:\s*""").findAll(config).firstNotNullOfOrNull { f->
                val at=f.range.last+1
                val literal=runCatching{Literals.read(config,at)}.getOrNull()
                val variable=if(literal?.value !is String)Regex("""^\s*([\w$]+)(?=\s*[,}])""").find(config.substring(at))else null
                val file=(literal?.value as? String)?:variable?.groupValues?.get(1)?.let{SeededDecoder.bindings(body,page)[it]}
                val address=file?.let{publicUrl(it,page)}?:return@firstNotNullOfOrNull null
                if(address.toHttpUrl().encodedPath.endsWith(".vtt",true)||address.toHttpUrl().encodedPath.endsWith(".srt",true))
                    return@firstNotNullOfOrNull null
                val end=variable?.range?.last?.plus(at+1)?:literal?.end?:return@firstNotNullOfOrNull null
                address to playerFileType(config,end)
            }?:continue
            val captions=runCatching{Literals.after(config,"\\btracks\\s*:\\s*") as? JSONArray}.getOrNull()
                ?:Regex("""\btracks\s*:\s*([\w$]+)""").find(config)?.groupValues?.get(1)?.let{runCatching{Literals.after(body,"\\b(?:var|let|const)\\s+"+Regex.escape(it)+"\\s*=\\s*") as? JSONArray}.getOrNull()}
            return PlayerMedia(chosen.first,tracks(captions,page),chosen.second,noReferer)
        }
        val media=doc.selectFirst("video source[src], video[src]")
        val mediaUrl=media?.attr("src")?.let{publicUrl(it,page)}
        if(mediaUrl!=null)return PlayerMedia(mediaUrl,doc.select("video track[src]").mapNotNull { t->
            publicUrl(t.attr("src"),page)?.let{Subtitle(it,t.attr("label"),t.attr("srclang"))}
        },streamType(media.attr("type")),noReferer)
        return null
    }
    private fun mediaUrl(url:String):Boolean = Regex("\\.(m3u8|mp4|mpd)(?:$|[?#])|/(?:hls|list)/|master\\.(?:txt|json)",RegexOption.IGNORE_CASE).containsMatchIn(url)
}
