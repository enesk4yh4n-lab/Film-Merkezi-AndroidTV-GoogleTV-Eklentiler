package app.filmmerkezi.addon.pilot

import app.filmmerkezi.addon.BuiltinEntry
import app.filmmerkezi.tv.core.*
import app.filmmerkezi.tv.pluginapi.*
import org.json.*
import java.net.URI
import java.net.URLEncoder

/** P002–P005 pilot. Remote HTML/Flight/player configuration is data, never executable code. */
class PilotEntry : Plugin {
    private val filmci = BuiltinEntry()
    private val caches = linkedMapOf<String, Pair<Long, List<Card>>>()
    override fun call(operation: String, payload: String, host: PluginHost): String {
        val input = JSONObject(payload)
        val site = PluginWire.site(input.getJSONObject("site"))
        if (site.id == "filmcibaba") {
            val result=JSONObject(filmci.call(operation,payload,host))
            if(operation=="search") {
                // WordPress also matches plot text. Tizen P004 keeps title/alias matches only.
                val terms=TextKey.fold(input.getString("query")).split(' ').filter{it.isNotBlank()}
                val cards=objects(result.optJSONArray("cards")).filter { c ->
                    val aliases=c.optJSONArray("aliases")?.let{a->(0 until a.length()).map{a.optString(it)}}?:emptyList()
                    val title=TextKey.fold((listOf(c.text("title"),c.text("originalTitle"))+aliases).joinToString(" "))
                    terms.all{title.contains(it)}
                }
                result.put("cards",JSONArray(cards))
            }
            if(operation=="resolve") {
                val io=PilotIO(site,host)
                val body=io.body(io.safeMedia(result.getString("url")),referer=result.optString("referer",site.base+"/"))
                if(!body.trimStart().startsWith("#EXTM3U")) fail("Filmci Baba HLS yerine başka yanıt döndürdü")
                if(Regex("METHOD\\s*=\\s*SAMPLE-AES|KEYFORMAT\\s*=\\s*\"[^\"]*(?:widevine|playready)",RegexOption.IGNORE_CASE).containsMatchIn(body)) throw DataFailure(FailureKind.UNSUPPORTED,"Bu yayın DRM gerektiriyor","broadcast")
            }
            return result.toString()
        }
        require(site.id in setOf("p002", "p003", "p005")) { "Pilot yalnız P002–P005 paketlerini destekler" }
        val io = PilotIO(site, host)
        val provider = Broadcaster(site, io, caches)
        val next = input.optJSONObject("next")?.let(PluginWire::cursor)
        val result = when (operation) {
            "describe" -> JSONObject().put("sdk", 1).put("pilot", "p002-p005").put("siteId", site.id)
            "search" -> PluginWire.page(provider.search(input.getString("query"), next))
            "browse" -> PluginWire.page(provider.browse(ContentKind.valueOf(input.getString("kind")), next))
            "parseList" -> PluginWire.page(provider.parseList(input.getString("body"), input.getString("url")))
            "detail" -> PluginWire.detail(provider.detail(Contracts.readCard(input.getJSONObject("card"))))
            "episodes" -> JSONObject().put("episodes", JSONArray(provider.detail(Contracts.readCard(input.getJSONObject("card"))).episodes.map(PluginWire::episode)))
            "resolve" -> Contracts.source(BroadcastResolver(site, io).resolve(PluginWire.option(input.getJSONObject("option"))))
            "options" -> PluginWire.options(provider.options())
            "filtered" -> {
                val query = PluginWire.query(input.getJSONObject("query"))
                val page = provider.browse(query.kind, next)
                val fields = buildSet { if(query.genre.isNotBlank()) add("genre"); if(query.year!=null) add("year"); if(query.language.isNotBlank()) add("language"); if(query.minRating>0) add("rating") }
                PluginWire.page(page.copy(coverage=FilterCoverage(site.id, emptySet(), fields)))
            }
            "collections" -> JSONObject().put("cards", JSONArray())
            "trim" -> { caches.clear(); JSONObject() }
            else -> throw DataFailure(FailureKind.UNSUPPORTED, "Eklenti işlemi desteklenmiyor: $operation", "plugin")
        }
        return result.toString()
    }
}

internal fun fail(message: String): Nothing = throw DataFailure(FailureKind.PARSE, message, "broadcast")
internal fun objects(a: JSONArray?) = if(a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
internal fun clean(value: String) = org.jsoup.Jsoup.parse(value).text().replace(Regex("\\s+"), " ").trim()
internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
internal fun JSONObject.text(key: String): String = if(isNull(key)) "" else optString(key)

internal class PilotIO(val site: SiteSpec, private val host: PluginHost) {
    private val end = System.nanoTime() + 30_000_000_000L
    fun check() {
        if(host.cancelled()) throw java.util.concurrent.CancellationException("İşlem iptal edildi")
        if(System.nanoTime()>=end) throw DataFailure(FailureKind.TIMEOUT,"Yayıncı işleminin süresi doldu","broadcast")
    }
    fun page(value: String): String {
        val u = runCatching { URI(site.base+"/").resolve(value.trim()) }.getOrNull() ?: return ""
        val b = URI(site.base)
        if(value.isBlank() || u.scheme!=b.scheme || u.rawAuthority!=b.rawAuthority || u.userInfo!=null) return ""
        return site.base+u.rawPath.trimEnd('/')
    }
    fun body(url: String, fields: Map<String,String>?=null, referer: String=site.base+"/", limit: Int=2_097_152): String {
        check()
        val r = JSONObject(host.request(JSONObject().put("url",url).put("referer",referer)
            .put("method",if(fields==null) "GET" else "POST").put("fields",JSONObject(fields?:emptyMap<String,String>()))
            .put("headers",JSONObject().put("User-Agent","Mozilla/5.0").apply { if(fields!=null) { put("Content-Type","application/x-www-form-urlencoded"); put("X-Requested-With","XMLHttpRequest") } })
            .put("timeoutMs",minOf(15000L, maxOf(1L,(end-System.nanoTime())/1_000_000)))
            .put("byteLimit",limit).toString()))
        check()
        val code=r.optInt("code",200)
        if(code !in 200..299) throw DataFailure(FailureKind.HTTP,"Yayıncı HTTP $code","broadcast",code)
        val body=r.getString("body")
        if(body.length>limit) throw DataFailure(FailureKind.LIMIT,"Yayıncı yanıtı çok büyük","broadcast")
        return body
    }
    fun json(url: String, fields: Map<String,String>?=null) = JSONObject(body(url, fields))
    fun nextData(body: String): JSONObject {
        if(body.length>2_097_152) fail("PuhuTV sayfası çok büyük")
        val text=org.jsoup.Jsoup.parse(body).selectFirst("script#__NEXT_DATA__")?.data() ?: fail("PuhuTV sayfa verisi bulunamadı")
        return JSONObject(text).optJSONObject("props")?.optJSONObject("pageProps") ?: fail("PuhuTV sayfa verisi bozuk")
    }
    fun nextDataAt(url: String) = nextData(body(url))
    fun safeMedia(value: String): String {
        val u=runCatching{URI(value)}.getOrNull() ?: fail("Geçersiz medya adresi")
        if(u.scheme!="https" || u.host.isNullOrBlank() || u.userInfo!=null) fail("Güvenli medya adresi verilmedi")
        return u.toASCIIString()
    }
}
