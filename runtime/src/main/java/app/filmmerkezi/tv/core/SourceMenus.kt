package app.filmmerkezi.tv.core

import org.json.JSONObject
import org.json.JSONArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/** Only inventories options. No embed, manifest, key or segment is fetched here. */
object SourceMenus {
    private val hosts=Regex("(^|\\.)(videoplays\\.cfd|streamcorecdn\\.com|hotstream\\.club|hdfilmcehennemi\\.(mobi|nl)|dizibox\\.live|molystream\\.org|vidmoly\\.(biz|net|me|to)|ok\\.ru|odnoklassniki\\.ru|my\\.mail\\.ru|videoseyred\\.in|vidara\\.to|gasobni\\.com|pilavyerplay\\.(top|live)|vixolity\\.com|fixfullfilmizle\\.net|myplayersvideo\\.xyz|matrudas\\.com|filmizle\\.in|playturka\\.space|popcornvakti\\.net|rapidvid\\.(org|net)|filmmakinesi\\.to|filmifullizle\\.news|lovefilmizle\\.net|as9u7bgh9\\.com)$")
    fun knownFrame(url:String):Boolean = runCatching { val u=url.toHttpUrl();u.scheme=="https" && (hosts.containsMatchIn(u.host)||YoutubeLinks.videoId(url)!=null||KatrePlayer.accepts(url)||Sites.all.any{it.catalogUrl(url)!=null}) }.getOrDefault(false)
    fun frames(body:String,page:String):List<String> {
        val doc=HtmlData.document(body,page)
        val frames=doc.select("iframe").mapNotNull { node ->
            val raw=listOf("data-litespeed-src","data-src","data-frame-src","data-keremiya-src","src").firstNotNullOfOrNull{node.attr(it).takeIf{v->v.isNotBlank()}}?:return@mapNotNull null
            (YoutubeLinks.frameUrl(raw,page)?:publicUrl(raw,page))?.takeIf(::knownFrame)
        }.distinct()
        if(frames.size>128)throw DataFailure(FailureKind.LIMIT,"128 kaynak sınırı aşıldı; liste kesilmedi","sources")
        return frames
    }
    fun parse(site:SiteSpec,body:String,content:ContentRef,pageUrl:String=content.pageUrl):List<SourceOption> {
        val d=HtmlData.document(body,pageUrl);val result=mutableListOf<SourceOption>()
        fun add(url:String,label:String,id:String="",extractor:String="",fields:Map<String,String> = emptyMap()) {
            val safe=publicUrl(url,pageUrl)?:return
            if(result.none{it.url==safe && it.fields==fields}) result.add(SourceOption(id.ifBlank{site.id+"-"+TextKey.hash(safe+fields)},content,TextKey.clean(label).take(100).ifBlank{"Kaynak ${result.size+1}"},safe,extractor,fields=fields))
        }
        when(site.id) {
            "diziboxum" -> return DiziboxumParser.sources(site,d,content)
            "dizipal" -> return DizipalParser.sources(site,body,content,pageUrl)
            "filmizlebabam" -> {
                if(d.selectFirst("[data-player]")!=null)return TrackedSources.options(site,d,content)
                d.select("[data-pv]").forEach{node->
                    val code=node.attr("data-pv")
                    if(code.matches(Regex("[A-Za-z0-9_-]{8,80}")))add("https://play2.pilavyerplay.top/assets/js/s.php?s="+code,"Pilavyer",extractor="pilavyer")
                }
            }
            "filmcibaba","lovefilmizle" -> {
                val current=pageUrl.toHttpUrl();val basePath=current.encodedPath.replace(Regex("/[1-9]\\d*/$"),"/")
                val number=Regex("/([1-9]\\d*)/$").find(current.encodedPath)?.groupValues?.get(1)?.toInt()?:1
                val currentFrames=frames(body,pageUrl)
                val menu=d.select(if(site.id=="filmcibaba")".p-v12-ep-list a.post-page-numbers" else "a[aria-label=page][href]")
                // The same menu is a film's alternate players OR a series' episode list.
                // Other episode pages must never become fallback sources of this episode.
                if(menu.isNotEmpty() && content.kind==ContentKind.MOVIE) {
                    add(pageUrl,if(site.id=="filmcibaba")if(number==1)"Hotstream" else "Hotstream $number" else d.selectFirst(".selected a[aria-label=page]")?.text()?:"Kaynak $number",
                        site.id+"-page-"+number,"page")
                    menu.forEach { a ->
                        val u=site.catalogUrl(a.attr("href"),pageUrl)?:return@forEach
                        val path=u.toHttpUrl().encodedPath;val n=Regex("/([1-9]\\d*)/$").find(path)?.groupValues?.get(1)?.toInt()?:1
                        if(path.replace(Regex("/[1-9]\\d*/$"),"/")==basePath && u!=pageUrl)
                            add(u,if(site.id=="filmcibaba")if(n==1)"Hotstream" else "Hotstream $n" else a.text(),site.id+"-page-"+n,"page")
                    }
                } else currentFrames.forEachIndexed { i,u->add(u,if(site.id=="filmcibaba")"Hotstream" else "Kaynak ${i+1}") }
            }
            "dizibox" -> {
                d.select(".woca-linkpages-dd option").forEach { o ->
                    val raw=o.attr("value").ifBlank{o.attr("href")}.ifBlank{if(o.hasAttr("selected"))pageUrl else ""}
                    site.catalogUrl(raw,pageUrl)?.let{add(it,o.text(),extractor="page")}
                }
            }
            "hdfilmcehennemi" -> {
                val frame=frames(body,pageUrl).firstOrNull()
                d.select(".alternative-link[data-video]").forEach { a ->
                    val video=a.attr("data-video")
                    if(video.matches(Regex("\\d+")) && frame!=null) {
                        val active=a.attr("data-active")=="1"
                        val target=if(active)frame else frame.toHttpUrl().newBuilder().query(null).apply {
                            if(a.text().contains("Rapidrame",true))addQueryParameter("rapidrame_id",video)
                        }.build().toString()
                        add(target,a.text(),(if(a.text().contains("Rapidrame",true))"rapidrame-" else "hdf-close-")+video)
                    }
                }
                if(result.isEmpty())frame?.let{add(it,"Close / Rapidrame")}
            }
            "filmifullizle" -> {
                d.select(".sources [id^=source]").forEach { n ->
                    val id=n.id();if(!id.matches(Regex("source\\d+")))return@forEach
                    val marker=Regex("\\$\\(\\s*['\"]#"+Regex.escape(id)+"['\"]\\s*\\)\\s*\\.click\\(").find(body)?:return@forEach
                    val tail=body.substring(marker.range.last+1,(marker.range.last+6001).coerceAtMost(body.length))
                    val fragment=tail.substringBefore(".removeClass(")
                    val m=Regex("\\.html\\(\\s*").find(fragment)?:return@forEach
                    val html=runCatching{Literals.read(fragment,m.range.last+1).value as? String}.getOrNull()
                    val normalFrame=html?.let{frames(it,pageUrl).firstOrNull()}
                    // The MailRu handler publishes single-quoted iframe attributes inside a single-quoted literal.
                    val mailFrame=if(normalFrame==null&&TextKey.fold(n.text())=="mailru"){
                        val raw=fragment.substring(m.range.last+1).trimStart()
                        val match=Regex("""^['"]<!--baslik:MailRu,(?:trd|tra)-->(<iframe\b[^>]*>\s*</iframe>)""",RegexOption.IGNORE_CASE).find(raw)
                        match?.groupValues?.get(1)?.let{frames(it,pageUrl).singleOrNull()}?.takeIf{
                            val u=it.toHttpUrl()
                            u.host=="my.mail.ru"&&u.encodedPath.matches(Regex("""/video/embed/\d+/?"""))
                        }
                    }else null
                    (normalFrame?:mailFrame)?.let{
                        val label=n.text()+" · "+if(n.attr("dil")=="trd")"Türkçe Dublaj" else "Türkçe Altyazılı"
                        if(TextKey.fold(n.text()).contains("sinefilm")||it.toHttpUrl().host.endsWith(".popcornvakti.net")||it.toHttpUrl().host=="popcornvakti.net"){
                            result.add(SourceOption(site.id+"-"+id,content,label,it,state=OptionState.UNSUPPORTED,
                                reason="Sinefilm bu paket için doğrulanmış kaynak kapsamı dışında."))
                        }else add(it,label,site.id+"-"+id)
                    }
                }
            }
            "fullhdfilmizlesene" -> {
                val data=runCatching{Literals.after(body,"\\bvar\\s+scx\\s*=\\s*") as? JSONObject}.getOrNull()
                data?.keys()?.forEach { key ->
                    val item=data.optJSONObject(key)?:return@forEach
                    var u=item.optJSONObject("sx")?.optJSONArray("t")?.optString(0)?:""
                    if(!u.startsWith("https://"))u=runCatching{Literals.b64(u.map{ c->when(c){in 'a'..'z'->('a'.code+(c-'a'+13)%26).toChar();in 'A'..'Z'->('A'.code+(c-'A'+13)%26).toChar();else->c}}.joinToString(""))}.getOrDefault("")
                    if(publicUrl(u)?.toHttpUrl()?.host in setOf("rapidvid.org","rapidvid.net"))add(u,runCatching{Literals.b64(item.string("tt"))}.getOrDefault(key),site.id+"-"+key)
                }
            }
            "filmmakinesi" -> d.select("#player-section .video-parts [data-video_url]").forEach{add(it.attr("data-video_url"),it.text())}
            "filmizlehell" -> d.select("[x-data],button").forEach { n ->
                val attrs=listOf("x-data","@click","x-on:click").joinToString(" "){n.attr(it)}
                Regex("https://p\\.playturka\\.space/#[A-Za-z0-9_-]+").findAll(attrs).forEach { add(it.value,if(n.normalName()=="button")n.text().ifBlank{"PlayTurka"}else "PlayTurka",extractor="playturka") }
            }
            "sinemacc" -> {
                d.select("#player_part button.grup_button[rel]").filterNot{it.text().contains("Fragman",true)}.forEach {
                    runCatching{Literals.b64(it.attr("rel"))}.getOrNull()?.let{u->if(knownFrame(u))add(u,it.text())}
                }
                if(result.isEmpty())d.select("iframe#video_playeriframe[data-vsrc]").forEach {
                    runCatching{Literals.b64(it.attr("data-vsrc"))}.getOrNull()?.let{u->if(knownFrame(u))add(u,"SinemaCC")}
                }
            }
            "turkcefilmler" -> {
                Regex("\\bvar\\s+ilkpartkod\\s*=\\s*").findAll(body).forEach { m ->
                    runCatching{Literals.b64(Literals.read(body,m.range.last+1).value.toString())}.getOrNull()?.let{fragment->frames(fragment,pageUrl).forEach{add(it,"DUAL")}}
                }
                val prefix=if(body.contains("BSZtFmcmlGP"))"PGlmcmFtZSB" else ""
                if(prefix.isNotBlank())Regex("\\bpdata\\[['\"]prt_\\d+['\"]\\]\\s*=\\s*").findAll(body).forEach { m ->
                    runCatching{Literals.b64(prefix+Literals.read(body,m.range.last+1).value.toString())}.getOrNull()?.let{fragment->frames(fragment,pageUrl).forEach{add(it,"DUAL")}}
                }
            }
            "hdfilmdelisi" -> {
                val graph=HtmlData.flight(body)
                val guard=findJson(graph){it.has("accessLevel") && it.has("requireAuthForWatch")}
                if(guard!=null && (guard.string("accessLevel")!="public"||guard.optBoolean("requireAuthForWatch"))) {
                    return listOf(SourceOption(site.id+"-restricted",content,"Üye girişi gerekli",pageUrl,state=OptionState.ACCESS_REQUIRED,reason="Bu içerik herkese açık değil."))
                }
                // Current player props carry sources[]; older pages used videoUrl.
                // Stay inside the access guard so a recommendation cannot supply media.
                val player=findJson(guard?:graph){(it.has("isCopyrighted")||it.has("telif"))&&(it.has("sources")||it.has("videoUrl"))}
                    ?:findJson(guard?:graph){it.has("videoUrl")}
                if(player?.optBoolean("isCopyrighted")==true||player?.optBoolean("telif")==true)return listOf(SourceOption(site.id+"-restricted",content,"Kaynak kullanılamıyor",pageUrl,
                    state=OptionState.ACCESS_REQUIRED,reason="Site bu içeriği oynatmaya kapatmış."))
                player?.string("videoUrl")?.takeIf(::knownFrame)?.let{add(it,"Vixolity")}
                player?.optJSONArray("sources")?.values()?.filterIsInstance<JSONObject>()?.forEach { item->
                    item.string("url").takeIf(::knownFrame)?.let{add(it,item.string("title").ifBlank{"Vixolity"})}
                }
            }
            "ddizi" -> {
                val episodeId=EpisodePages.ddiziEpisodeId(site,pageUrl)?:return emptyList()
                d.select(".video-g iframe, #content iframe").forEach { node ->
                    val raw=listOf("data-litespeed-src","data-frame-src","data-src","src")
                        .firstNotNullOfOrNull{node.attr(it).takeIf(String::isNotBlank)}?:return@forEach
                    val player=site.catalogUrl(raw,pageUrl)?.takeIf{
                        val u=it.toHttpUrl()
                        u.query==null&&u.encodedPath.matches(Regex("""/player/oynat/[a-f0-9]{24,64}/?"""))
                    }
                    if(player!=null)add(player,"Kaynak ${result.size+1}")
                    else YoutubeLinks.frameUrl(raw,pageUrl)?.let{add(it,"YouTube",extractor="youtube")}
                }
                d.select(".parts a[href]").forEach { a->
                    val target=site.catalogUrl(a.attr("href"),pageUrl)?:return@forEach
                    if(target!=pageUrl&&EpisodePages.ddiziEpisodeId(site,target)==episodeId)add(target,a.text(),extractor="page")
                }
                if(result.size>128)throw DataFailure(FailureKind.LIMIT,"128 kaynak sınırı aşıldı; liste kesilmedi","sources")
                return result
            }
        }
        if(result.isEmpty()) {
            frames(body,pageUrl).forEachIndexed { i,url->add(url,"Kaynak ${i+1}") }
            d.select("[data-video_url], [data-iframe], iframe[data-src]").forEach { n ->
                listOf("data-video_url","data-iframe","data-src").map{n.attr(it)}.filter{it.isNotBlank()}.forEach { v->publicUrl(v,pageUrl)?.takeIf(::knownFrame)?.let{add(it,n.text())} }
            }
        }
        if(result.isEmpty() && (body.contains("jwplayer(") || body.contains("FirePlayer(") || body.contains("<video")))add(pageUrl,"Ana kaynak","${site.id}-direct","page")
        if(result.size>128)throw DataFailure(FailureKind.LIMIT,"128 kaynak sınırı aşıldı; liste kesilmedi","sources")
        return result
    }
}
