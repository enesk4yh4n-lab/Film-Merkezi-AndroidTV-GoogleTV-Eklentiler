package app.filmmerkezi.addon

import app.filmmerkezi.tv.core.*
import app.filmmerkezi.tv.pluginapi.*
import kotlinx.coroutines.*
import org.json.*

/** Same implementation source builds site and resolver packages; no UI/player/database code. */
class BuiltinEntry:Plugin {
    private lateinit var currentHost:PluginHost
    private val providers=mutableMapOf<String,AddonSiteProvider>()
    override fun call(operation:String,payload:String,host:PluginHost):String=runBlocking {
        currentHost=host;val o=JSONObject(payload);val site=PluginWire.site(o.getJSONObject("site"))
        val network=object:WebTransport{override suspend fun request(request:WebRequest):WebResponse{
            if(currentHost.cancelled())throw CancellationException()
            val raw=JSONObject(currentHost.request(JSONObject().put("url",request.url).put("referer",request.referer).put("method",request.method).put("fields",JSONObject(request.fields)).put("body",request.body?:JSONObject.NULL).put("headers",JSONObject(request.headers)).put("timeoutMs",request.timeoutMs).put("byteLimit",request.byteLimit).toString()))
            val headers=raw.optJSONObject("headers")?:JSONObject()
            return WebResponse(raw.getString("url"),raw.getString("body"),raw.optInt("code",200),headers.keys().asSequence().associateWith{headers.getString(it)})
        }}
        val p=providers.getOrPut(site.id+site.base){AddonSiteProvider(site,network,PluginGateway.parsing)}
        val next=o.optJSONObject("next")?.let(PluginWire::cursor)
        val value:Any=when(operation){
            "describe"->JSONObject().put("sdk",1)
            "search"->PluginWire.page(p.search(o.getString("query"),next))
            "browse"->PluginWire.page(p.browse(ContentKind.valueOf(o.getString("kind")),next))
            "detail"->PluginWire.detail(p.detail(Contracts.readCard(o.getJSONObject("card"))))
            "episodes"->JSONObject().put("episodes",JSONArray(p.episodes(Contracts.readCard(o.getJSONObject("card"))).map(PluginWire::episode)))
            "options"->PluginWire.options(p.options())
            "filtered"->{var budget=o.optInt("verificationBudget",0);PluginWire.page(p.filtered(PluginWire.query(o.getJSONObject("query")),next,{budget-->0},o.optBoolean("unverifiedCandidates")))}
            "parseList"->PluginWire.page(CatalogParser(site).list(o.getString("body"),o.getString("url")))
            "resolve"->Contracts.source(AddonSourceResolver(network,YoutubeResolver{option,url->PluginWire.source(JSONObject(host.resolver("youtube","resolve",JSONObject().put("option",Contracts.option(option)).put("url",url).toString())))}).resolve(PluginWire.option(o.getJSONObject("option"))))
            "collections"->{
                val path=if(site.id=="p016")"/seckiler/" else "/"
                val home=network.request(WebRequest(site.base+path,site.base+"/"));var cards=CatalogParser(site).list(home.body,home.url).cards.filter{it.ref.kind==ContentKind.COLLECTION}
                if(cards.isEmpty())cards=listOf("izleme sırası","top").flatMap{p.search(it).cards.filter{it.ref.kind==ContentKind.COLLECTION}}.distinctBy{it.ref.id}
                JSONObject().put("cards",JSONArray(cards.map(Contracts::card)))
            }
            "trim"->{p.trim();JSONObject()}
            else->throw DataFailure(FailureKind.UNSUPPORTED,"Eklenti işlemi desteklenmiyor: $operation","plugin")
        }
        value.toString()
    }
}
