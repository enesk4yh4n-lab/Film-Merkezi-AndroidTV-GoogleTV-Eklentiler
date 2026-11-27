package app.filmmerkezi.addon.pilot

import org.json.*

/** Inert, bounded Next Flight reader. Does not evaluate page or player JavaScript. */
internal class FlightData(body: String) {
    private val records=linkedMapOf<String,Any?>()
    init {
        if(body.length>2_097_152) fail("Star TV sayfası çok büyük")
        val chunks=Regex("self\\.__next_f\\.push\\((\\[[\\s\\S]*?\\])\\)\\s*;?\\s*</script>").findAll(body).map {
            val a=JSONArray(it.groupValues[1]); if(a.optInt(0)==1) a.optString(1) else ""
        }.take(8193).toList()
        if(chunks.isEmpty() || chunks.size>8192) fail("Star TV sayfa verisi bulunamadı veya sınırı aştı")
        val stream=chunks.joinToString(""); var i=0; var count=0
        while(i<stream.length) {
            if(stream[i]=='\n') {i++; continue}
            val h=Regex("^([0-9a-fA-F]{1,12}):").find(stream.substring(i,minOf(i+30,stream.length))) ?: fail("Flight kaydı bozuk")
            if(++count>20000) fail("Flight kayıt sınırı aşıldı")
            val id=h.groupValues[1]; i+=h.value.length
            if(i<stream.length && stream[i]=='T') {
                val t=Regex("^T([0-9a-fA-F]{1,8}),").find(stream.substring(i,minOf(i+30,stream.length))) ?: fail("Flight metin kaydı bozuk")
                val length=t.groupValues[1].toLong(16); if(length>2_097_152) fail("Flight metni çok büyük")
                i+=t.value.length; val start=i; var bytes=0L
                while(i<stream.length && bytes<length) {
                    val c=stream[i++].code
                    bytes+=when {
                        c in 0xd800..0xdbff -> { if(i>=stream.length || stream[i].code !in 0xdc00..0xdfff) fail("Flight UTF-8 metni bozuk"); i++; 4 }
                        c in 0xdc00..0xdfff -> fail("Flight UTF-8 metni bozuk")
                        c<128 -> 1; c<2048 -> 2; else -> 3
                    }
                }
                if(bytes!=length) fail("Flight metni eksik")
                records[id]=stream.substring(start,i)
            } else {
                val end=stream.indexOf('\n',i).let{if(it<0) stream.length else it}
                val raw=stream.substring(i,end)
                if(raw.startsWith("{") || raw.startsWith("[") || raw.startsWith("\"")) records[id]=JSONTokener(raw).nextValue()
                i=end+1
            }
        }
    }
    fun value(input: Any?): Any? {
        var v=input; var n=0
        while(v is String) {
            val ref=Regex("^\\$(?:L|@)?([0-9a-fA-F]+)$").matchEntire(v) ?: break
            if(++n>12 || !records.containsKey(ref.groupValues[1])) return null
            v=records[ref.groupValues[1]]
        }
        return if(v==JSONObject.NULL || v=="\$undefined") null else v
    }
    fun field(input: Any?, key: String): Any? = value((value(input) as? JSONObject)?.opt(key))
    fun text(input: Any?): String = (value(input) as? String)?.let(::clean) ?: ""
    fun image(input: Any?): String = (field(input,"fullPath") as? String)?.takeIf{it.startsWith("/images/")&&!it.contains('\r')&&!it.contains('\n')}?.let{"https://media.startv.com.tr/star-tv$it?width=340&height=470&format=webp&quality=85"} ?: ""
    fun names(input: Any?): List<String> = (value(input) as? JSONArray)?.let{a->(0 until a.length()).map{ text(field(a.opt(it),"name")?:field(a.opt(it),"title")) }.filter{it.isNotBlank()}} ?: emptyList()
    fun find(stop: Boolean=false, test: (Any)->Boolean): List<Any> {
        val pending=java.util.ArrayDeque<Any>(); records.values.filterNotNull().forEach{if(it is JSONObject || it is JSONArray) pending.add(it)}
        val found=mutableListOf<Any>(); var visits=0
        while(pending.isNotEmpty()) {
            val v=pending.removeLast(); if(++visits>100000) fail("Flight dolaşım sınırı aşıldı")
            if(test(v)) {found.add(v); if(stop) continue}
            val children=when(v) {is JSONObject->v.keys().asSequence().map{v.opt(it)}.toList(); is JSONArray->(0 until v.length()).map{v.opt(it)}; else->emptyList()}
            children.filterNotNull().forEach{if(it is JSONObject || it is JSONArray) pending.add(it)}
        }
        return found
    }
    fun selected(type: String, address: String, page: (String)->String): Any = find(true) {
        field(it,"resourceType")==type && page(text(field(it,"url")))==address && Regex("[a-fA-F0-9]{24}").matches(text(field(it,"_id")))
    }.firstOrNull() ?: fail("Star TV'de istenen içeriğin verisi bulunamadı")
}
