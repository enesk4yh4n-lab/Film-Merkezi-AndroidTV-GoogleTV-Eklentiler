package app.filmmerkezi.tv.core
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
/** Wire formats copied from inspected provider contracts, with real option values from each site. */
object SiteFilters {
    private val fields=mapOf(
        "hdfilmcehennemi" to mapOf("genre" to "genres[]","year" to "years","language" to "lang[]","rating" to "imdb"),
        "dizibox" to mapOf("genre" to "tur[]","year" to "yil","rating" to "imdb"),
        "lovefilmizle" to mapOf("genre" to "filter_category","year" to "filter_year","language" to "filter_lang"),
        "filmifullizle" to mapOf("genre" to "tur","year" to "yil","language" to "dil","rating" to "imdb"),
        "filmizlehell" to mapOf("genre" to "category","rating" to "imdb"),
        "sinemacc" to mapOf("genre" to "genres","year" to "years","language" to "lang","rating" to "imdb"))
    private val genreLinks=setOf("filmcibaba","fixfullfilmizle","turkcefilmler","hdfilmdelisi")
    fun hasOptions(site:SiteSpec)=site.id in fields||site.id in genreLinks
    fun optionsPath(site:SiteSpec)=when(site.id){"hdfilmcehennemi"->"/film-robotu-1/";"dizibox"->"/dizi-arsivi/";"sinemacc"->"/film-arsivi/";"filmizlehell"->"/filmler";else->"/"}
    fun options(site:SiteSpec,body:String):FilterOptions{
        val d=HtmlData.document(body,site.base+optionsPath(site));val result=linkedMapOf<String,List<FilterValue>>()
        fields[site.id].orEmpty().forEach{(key,name)->
            val alternatives=when(key){"genre"->listOf(name,"genres","genres[]","genre","category","tur","tur[]","filter_category","rcat");"year"->listOf(name,"years","year","yil","filter_year","rdate");"language"->listOf(name,"lang","lang[]","dil","filter_lang","rdil");else->listOf(name,"imdb","rimdb")}
            val select=alternatives.firstNotNullOfOrNull{d.selectFirst("select[name=\"$it\"]")}
            result[key]=select?.select("option[value]")?.filter{it.attr("value").isNotBlank()&&it.attr("value")!="0"}?.map{FilterValue(it.attr("value"),it.text())}.orEmpty()
        }
        if(site.id=="hdfilmcehennemi")mapOf("genre" to "genres","year" to "years","language" to "lang","rating" to "imdb").forEach{(key,name)->
            result[key]=d.select("[data-dropdown-key=$name][data-dropdown-value]").map{FilterValue(it.attr("data-dropdown-value"),it.text())}.filter{it.value.isNotBlank()}
        }
        if(site.id in genreLinks)result["genre"]=d.select("a[href]").mapNotNull{a->
            val u=site.catalogUrl(a.attr("href"),site.base)?:return@mapNotNull null
            if(!Regex("(?i)/(?:kategori|category|tur|genre|film-turleri)/[^/?]+/?$").containsMatchIn(u.toHttpUrl().encodedPath))return@mapNotNull null
            a.text().takeIf{it.isNotBlank()&&it.length<80}?.let{FilterValue(u,it)}
        }.distinctBy{it.value}.take(80)
        return FilterOptions(result)
    }
    data class Plan(val request:WebRequest?,val coverage:FilterCoverage)
    fun plan(site:SiteSpec,q:CatalogQuery,options:FilterOptions,next:PageCursor?=null):Plan{
        val requested=buildSet{if(q.genre.isNotBlank())add("genre");if(q.year!=null)add("year");if(q.language.isNotBlank())add("language");if(q.minRating>0)add("rating")}
        // Other sites' filter endpoints are film archives, even when their menus
        // now advertise series. Keep their series index and label local coverage.
        val localSeries=q.kind==ContentKind.SERIES&&site.id !in setOf("hdfilmcehennemi","dizibox")
        val selected=linkedMapOf<String,String>()
        requested.forEach{key->val wanted=when(key){"genre"->q.genre;"year"->q.year.toString();"language"->q.language;else->q.minRating.toString()}
            options.values[key]?.firstOrNull{v->when(key){"genre"->GenreKey.key(v.label)==GenreKey.key(wanted);"year"->v.value==wanted||v.label==wanted;"language"->TextKey.fold(v.label).contains(wanted);else->v.value.substringBefore('-').toDoubleOrNull()==q.minRating}}?.let{selected[key]=it.value}
        }
        if(q.kind==ContentKind.SERIES&&site.id in genreLinks)selected.clear()
        val remote=selected.keys.toSet();val coverage=FilterCoverage(site.id,remote,requested-remote)
        if(next!=null&&next.fields["_query"]?.let{it!=q.signature}==true)throw DataFailure(FailureKind.PARSE,"Devam sayfası başka filtreye ait","pagination")
        if(localSeries)return Plan(null,FilterCoverage(site.id,emptySet(),requested))
        val page=next?.page?:1
        if(selected.isEmpty()&&q.order!=CatalogOrder.RATING)return Plan(null,coverage)
        fun get(path:String,params:Map<String,String> = emptyMap()):WebRequest {
            val base=if(next!=null&&site.id !in setOf("hdfilmcehennemi","sinemacc"))next.url else site.base+path
            if(next!=null&&base==next.url)return WebRequest(base,site.base+"/")
            val u=base.toHttpUrl().newBuilder();params.forEach{(k,v)->u.addQueryParameter(k,v)};return WebRequest(u.build().toString(),site.base+"/")
        }
        val request=when(site.id){
            "hdfilmcehennemi"->WebRequest(site.base+"/film-robotu-1/",site.base+"/","POST",mapOf("type" to if(q.kind==ContentKind.SERIES)"2"else"1","genres" to JSONArray(listOfNotNull(selected["genre"])).toString(),"years" to selected["year"].orEmpty(),"lang" to JSONArray(listOfNotNull(selected["language"])).toString(),"imdb" to selected["rating"].orEmpty(),"order" to "posts.imdb desc","page" to "$page"),headers=mapOf("X-Mofycore-Action" to "discover"))
            "sinemacc"->WebRequest(site.base+"/film-arsivi/",site.base+"/","POST",mapOf("action" to "ajax_archive","rdil" to selected["language"].orEmpty(),"rdate" to selected["year"].orEmpty(),"rtip" to "","rcat" to selected["genre"].orEmpty(),"rimdb" to selected["rating"].orEmpty().substringBefore('-'),"sayfano" to "$page"))
            "dizibox"->get("/dizi-arsivi/",selected.mapKeys{(k,_)->fields.getValue(site.id).getValue(k)}+if(q.order==CatalogOrder.RATING)mapOf("orderby" to "imdb")else emptyMap())
            "lovefilmizle"->get("/arama-robotu/",selected.mapKeys{(k,_)->fields.getValue(site.id).getValue(k)}+mapOf("filter_order" to "date","filter_orderby" to "DESC"))
            "filmifullizle"->get("/film",selected.mapKeys{(k,_)->fields.getValue(site.id).getValue(k)}+mapOf("filtrele" to "date","sirala" to "DESC"))
            "filmizlehell"->get("/filmler",selected.mapKeys{(k,_)->fields.getValue(site.id).getValue(k)})
            "filmcibaba"->selected["genre"]?.let{genre->get(genre.removePrefix(site.base),if(q.order==CatalogOrder.RATING)mapOf("sort" to "imdb")else emptyMap())}
            "fixfullfilmizle"->selected["genre"]?.let{get(it.removePrefix(site.base))}
            "turkcefilmler"->selected["genre"]?.let{get(it.removePrefix(site.base),if(q.order==CatalogOrder.RATING)mapOf("ord" to "4")else emptyMap())}
            "hdfilmdelisi"->selected["genre"]?.let{val slug=it.toHttpUrl().pathSegments.getOrNull(1)?:return@let null;get("/api/films/category/$slug",mapOf("page" to "$page","sort" to if(q.order==CatalogOrder.RATING)"imdb"else"newest","limit" to "20"))}
            else->null
        }
        return Plan(request,coverage)
    }
}
