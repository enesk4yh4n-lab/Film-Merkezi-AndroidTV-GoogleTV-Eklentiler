package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Reads only the public player's inert config; site scripts/advertising never execute. */
object HotstreamData {
    fun media(body:String,page:String):String {
        val active=Regex("""\bbePlayer\s*\(\s*config\.videoId\s*,\s*config\.settings\s*\)""").containsMatchIn(body)
        val args=if(active){
            val nodes=HtmlData.document(body,page).select("#be-player-config[data-config]")
            if(nodes.size!=1)throw DataFailure(FailureKind.PARSE,"Hotstream yapılandırması belirsiz","decode")
            val data=JSONObject(nodes.single().attr("data-config"))
            val password=data.opt("videoId") as? String
            val settings=data.opt("settings") as? String
            if(password.isNullOrBlank()||settings.isNullOrBlank())throw DataFailure(FailureKind.PARSE,"Hotstream yapılandırması eksik","decode")
            password to settings
        }else legacy(body)?:throw DataFailure(FailureKind.PARSE,"Hotstream ayarı yok","decode")
        val decoded=JSONObject(Literals.aesPassword(args.second,args.first))
        val media=decoded.string("video_location").toHttpUrlOrNull()
        if(media==null||media.scheme!="https"||media.host!="hotstream.club"||media.port!=443||media.username.isNotEmpty()||media.password.isNotEmpty()||!media.encodedPath.startsWith("/list/")||media.encodedPath=="/list/")
            throw DataFailure(FailureKind.PARSE,"Hotstream medya kökeni eşleşmiyor","decode")
        return media.toString()
    }
    private fun legacy(body:String):Pair<String,String>? {
        for(call in Regex("""\bbePlayer\s*\(\s*""").findAll(body).take(32)){
            try {
                val password=Literals.read(body,call.range.last+1);if(password.value !is String)continue
                var comma=password.end;while(body.getOrNull(comma)?.isWhitespace()==true)comma++
                if(body.getOrNull(comma)!=',')continue
                val encoded=Literals.read(body,comma+1);if(encoded.value !is String)continue
                var end=encoded.end;while(body.getOrNull(end)?.isWhitespace()==true)end++
                if(body.getOrNull(end)!=')')continue
                return (password.value as String) to (encoded.value as String)
            }catch(e:DataFailure){if(e.kind==FailureKind.LIMIT)throw e}
        }
        return null
    }
}
