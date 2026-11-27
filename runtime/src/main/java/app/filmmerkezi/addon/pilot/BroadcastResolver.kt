package app.filmmerkezi.addon.pilot

import app.filmmerkezi.tv.core.*
import org.json.JSONObject
import org.jsoup.Jsoup

internal class BroadcastResolver(private val site: SiteSpec, private val io: PilotIO) {
    fun resolve(option: SourceOption): ResolvedSource {
        require(option.content.siteId==site.id) {"Kaynak başka siteye ait"}
        val address=io.page(option.url).ifBlank{fail("Yayıncı kaynak adresi geçersiz")}
        return when(site.id) {"p002"->puhu(option,address); "p003"->now(option,address); else->star(option,address)}
    }
    private fun expected(option: SourceOption, season: Int, number: Int) {
        if(option.fields["season"]?.toIntOrNull()?.let{it!=season}==true || option.fields["episode"]?.toIntOrNull()?.let{it!=number}==true) fail("Kaynak seçilen sezon/bölümle eşleşmiyor")
    }
    private fun drm(data: JSONObject) {
        fun flag(key: String): Boolean {val v=data.opt(key); return v!=null&&v!=JSONObject.NULL&&v!=false&&v!=0&&v!=""&&v!="false"&&v!="0"}
        if(listOf("drm","is_drm","drm_license_url").any(::flag)) throw DataFailure(FailureKind.UNSUPPORTED,"Bu yayın DRM gerektiriyor","broadcast")
    }
    private fun source(option: SourceOption, value: String, referer: String, data: JSONObject?=null): ResolvedSource {
        val media=io.safeMedia(value); val playlist=io.body(media,referer=referer)
        if(!playlist.trimStart().startsWith("#EXTM3U")) fail("Yayıncı HLS yerine başka yanıt döndürdü")
        if(Regex("METHOD\\s*=\\s*SAMPLE-AES|KEYFORMAT\\s*=\\s*\"[^\"]*(?:widevine|playready)",RegexOption.IGNORE_CASE).containsMatchIn(playlist)) throw DataFailure(FailureKind.UNSUPPORTED,"Bu yayın DRM gerektiriyor","broadcast")
        val subtitles=objects(data?.optJSONArray("tracks")).mapNotNull { t ->
            val url=listOf("file","url","src").map{t.text(it)}.firstOrNull{it.isNotBlank()}.orEmpty()
            if(t.text("kind").lowercase() !in setOf("subtitles","captions") || !url.startsWith("https://")) null
            else Subtitle(io.safeMedia(url),t.text("label").ifBlank{t.text("language")},t.text("srclang").ifBlank{t.text("language")},if(url.substringBefore('?').endsWith(".srt")) "srt" else "vtt")
        }
        // Keep the master, every rendition and audio/subtitle track. No Tizen-specific 480p cap.
        return ResolvedSource(option.id,site.name+" · Tam yayın",media,StreamType.HLS,referer,userAgent="Mozilla/5.0",subtitles=subtitles)
    }
    private fun puhu(option: SourceOption, address: String): ResolvedSource {
        var p=io.nextDataAt(address)
        if(p.optJSONObject("movieAssets")?.optJSONObject("data")==null) {
            val watch=io.page(p.optJSONObject("details")?.optJSONObject("data")?.text("to_watch_asset_slug").orEmpty())
            if(watch.isBlank()||watch==address) fail("PuhuTV oynatılabilir video kaydı yok")
            p=io.nextDataAt(watch)
        }
        val asset=p.optJSONObject("movieAssets")?.optJSONObject("data")?:fail("PuhuTV video kaydı yok")
        if(asset.text("type") !in setOf("asset_movie","asset_episode") || !Regex("PUHU_[A-Za-z0-9_-]+").matches(asset.text("video_id"))) fail("PuhuTV tam yayın video kimliği bulunamadı")
        if(option.content.kind==ContentKind.EPISODE) {
            if(asset.text("type")!="asset_episode") fail("PuhuTV kaynak bir bölüm değil")
            val m=asset.optJSONObject("meta")?:fail("PuhuTV bölüm kimliği yok"); expected(option,m.optInt("season_number"),m.optInt("episode_number"))
            val series=option.fields["seriesExternalId"]
            if(!series.isNullOrBlank()&&p.optJSONObject("watchDetails")?.optJSONObject("data")?.text("id")!=series) fail("PuhuTV kaynak farklı bir diziye ait")
        } else if(asset.text("type")!="asset_movie") fail("PuhuTV kaynak bir film değil")
        // Public parameter from the official web player; no private app credential.
        val j=io.json("https://dygvideo.dygdigital.com/api/video_info?akamai=true&PublisherId=29&ReferenceId=${encode(asset.text("video_id"))}&SecretKey=${encode("NtvApiSecret2014*")}")
        val data=j.optJSONObject("data")?:fail("PuhuTV yayın bilgisi yok")
        if(!j.optBoolean("success")) fail("PuhuTV yayın bu bağlantı/bölgede kullanılamıyor")
        drm(data)
        return source(option,data.optJSONObject("flavors")?.text("hls").orEmpty(),site.base+"/",data)
    }
    private fun now(option: SourceOption, address: String): ResolvedSource {
        val number=Regex("^${Regex.escape(site.base)}/[^/]+/bolum/(\\d+)$").matchEntire(address)?.groupValues?.get(1)?.toIntOrNull()?:fail("NOW yalnız tam bölüm bağlantısını oynatır")
        option.fields["episode"]?.toIntOrNull()?.let{if(it!=number) fail("NOW seçilen bölüm farklı")}
        val doc=Jsoup.parse(io.body(address)); val el=doc.selectFirst(".vod-player[data-video-id][data-program-id]")?:fail("NOW tam bölüm oynatıcısı yok")
        val id=el.attr("data-video-id"); if(!Regex("\\d+").matches(id)) fail("NOW video kimliği geçersiz")
        val j=io.json(site.base+"/ajax/stream",mapOf("video_id" to id))
        if(j.optInt("code")!=200) fail("NOW bu yayına erişim vermedi")
        return source(option,j.text("video_url"),site.base+"/")
    }
    private fun star(option: SourceOption, address: String): ResolvedSource {
        if(!Regex("^${Regex.escape(site.base)}/dizi/[^/]+/bolumler/[^/]+$").matches(address)) fail("Star TV yalnız tam bölüm bağlantısını oynatır")
        val d=FlightData(io.body(address)); val item=d.selected("Episode",address,io::page)
        val ref=d.text(d.field(d.field(item,"video"),"referenceId")); val n=d.field(item,"episodeNo").toString().toIntOrNull()?:0; val s=d.field(item,"seasonNo").toString().toIntOrNull()?:0
        if(!Regex("(?:[a-fA-F0-9]{24}|[0-9]{1,12})").matches(ref)||n<1) fail("Star TV tam bölüm kimliği bulunamadı")
        expected(option,s,n)
        val bundle=io.body("https://player-assets.dygdigital.com/prod/js/bundle.js",referer=address)
        val parameter=Regex("this\\.secret\\s*=\\s*this\\.options\\.secretKey\\s*\\|\\|\\s*[\"']([A-Za-z0-9_*.-]{1,128})[\"']").find(bundle)?.groupValues?.get(1)?:fail("Star TV herkese açık oynatıcı ayarı tanınamadı")
        val j=io.json("https://dygvideo.dygdigital.com/api/video_info?akamai=true&PublisherId=1&ReferenceId=StarTv_${encode(ref)}&SecretKey=${encode(parameter)}")
        val data=j.optJSONObject("data")?:fail("Star TV yayın bilgisi yok")
        if(!j.optBoolean("success")||!data.text("content_type").contains("episode",true)) fail("Star TV bölüm bu bağlantı/bölgede kullanılamıyor")
        if(data.optInt("episode_number")>0&&data.optInt("episode_number")!=n) fail("Star TV medya yanıtı başka bölüme ait")
        drm(data)
        return source(option,data.optJSONObject("flavors")?.text("hls").orEmpty(),address,data)
    }
}
