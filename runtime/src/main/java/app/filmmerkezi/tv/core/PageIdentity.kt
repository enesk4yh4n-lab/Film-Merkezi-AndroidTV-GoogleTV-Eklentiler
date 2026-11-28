package app.filmmerkezi.tv.core

import org.json.*

object PageIdentity {
    fun attach(body:String,detail:Detail,seed:Card?=null):Detail {
        if(detail.card.ref.kind==ContentKind.COLLECTION)return detail
        val c=detail.card;val d=HtmlData.document(body,c.ref.pageUrl)
        val heading=d.selectFirst(Sites.get(c.ref.siteId).detailTitle)?.text().orEmpty()
        fun types(o:JSONObject)=when(val t=o.opt("@type")){is JSONArray->t.values().map{it.toString()};is String->listOf(t);else->emptyList()}
        val schemas=HtmlData.schemas(d).filter{types(it).any{t->t in setOf("Movie","TVSeries","TVEpisode")}}
        fun page(s:String)=(Sites.get(c.ref.siteId).catalogUrl(s,c.ref.pageUrl)?:publicUrl(s,c.ref.pageUrl))?.substringBefore('#')?.trimEnd('/')
        val matching=schemas.filter{page(it.optString("url"))==page(c.ref.pageUrl)}.ifEmpty{schemas.filter{
            val name=TextKey.fold(ContentIdentity.cleanTitle(it.optString("name"),c.year))
            it.optString("url").isBlank()&&name.isNotBlank()&&(name==TextKey.fold(ContentIdentity.cleanTitle(heading,c.year))||name==TextKey.fold(ContentIdentity.cleanTitle(c.title,c.year)))
        }}
        val schema=matching.firstOrNull()
        fun ids(o:Any?):List<String> = when(o){is JSONArray->o.values().flatMap(::ids);is JSONObject->listOf("@id","url","sameAs").flatMap{ids(o.opt(it))};is String->runCatching{val u=java.net.URI(if(o.startsWith("//"))"https:$o"else o);if(u.host?.matches(Regex("(?i)(?:www\\.)?imdb\\.com"))==true)Regex("/title/(tt\\d{7,10})(?:/|$)").find(u.path)?.groupValues?.get(1)?.let(::listOf)?:emptyList()else emptyList()}.getOrDefault(emptyList());else->emptyList()}
        // Exclude recommendations even when a page wraps the whole layout in a metadata container.
        fun primary(n:org.jsoup.nodes.Element)=n.parents().none{it.normalName() in setOf("aside","footer")||Regex("recommend|related|benzer|oneri").containsMatchIn(it.className().lowercase()+" "+it.id().lowercase())}
        val links=d.select("#video_infos a[href], .film-bilgileri a[href], .film-bilgisi a[href], .custom-fields-container a[href], .filmaltiaciklama a[href], #filmbilgileri a[href], #info--box a[href], .p-v12-info a[href], .movie-info a[href], .post-info-imdb a[href], a.imdb_link[href]").filter(::primary)
        val imdb=(matching.flatMap{ids(it.opt("sameAs"))+ids(it.opt("url"))}+links.flatMap{ids(it.attr("href"))}).distinct()
        // Filmci Baba emits Movie JSON-LD for season pages as well as films. The
        // provider has already verified a series heading/category AND its episode list.
        // Likewise an episode's shared parent schema must not erase its linked number.
        val linkedEpisode=c.ref.kind==ContentKind.EPISODE&&c.episodeNumber!=null&&c.seriesRef!=null
        val sourceSeason=c.sourceSeason?:SeriesIdentity.season(c.title)
        val listedSeries=c.ref.kind==ContentKind.SERIES&&(detail.episodes.isNotEmpty()||sourceSeason!=null||SeriesIdentity.archive(c.ref))
        val kind=when{linkedEpisode->ContentKind.EPISODE;listedSeries->ContentKind.SERIES;schema==null->c.ref.kind;"TVEpisode" in types(schema)->ContentKind.EPISODE;"TVSeries" in types(schema)->ContentKind.SERIES;else->ContentKind.MOVIE}
        fun year(s:String)=Regex("^(?:18|19|20)\\d{2}(?:$|[-/])").find(s.trim())?.value?.take(4)?.toIntOrNull()
        // dateCreated/datePublished usually describe the web page, not the work's release.
        // These two site contracts were verified against saved HTML and their primary year fields.
        val dateFields=listOf("startDate","releaseDate")+when(c.ref.siteId){"sinemacc"->listOf("dateCreated");"filmizlehell","hdfilmdelisi"->listOf("datePublished");else->emptyList()}
        val schemaYears=matching.flatMap{s->dateFields.mapNotNull{k->year(s.optString(k))}}.distinct()
        val categoryYears=if(c.ref.siteId=="filmcibaba")d.select(".p-v12-meta a[rel~=category], .p-v12-info a[rel~=category], .p-v12-detail a[rel~=category], .meta-item a[rel~=category]").filter(::primary).mapNotNull{Regex("^((?:18|19|20)\\d{2}) Filmleri(?: izle)?$",RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.get(1)?.toIntOrNull()}.distinct()else emptyList()
        val yearSelector=when(c.ref.siteId){"filmmakinesi"->"h1 .date";"fullhdfilmizlesene"->".film-info a.category[href*=yil/]";else->""}
        val domYears=when {
            c.ref.siteId=="dizibox"&&kind==ContentKind.SERIES->d.select(".tv-overview > .title-terms > .terms a[href]").filter(::primary).mapNotNull{a->
                // The site's JSON-LD describes a TVEpisode and omits its release.
                // Read the show's own year term, not WebPage.datePublished or the alphabet/sidebar.
                val url=Sites.get("dizibox").catalogUrl(a.attr("href"),c.ref.pageUrl)?:return@mapNotNull null
                val value=Regex("^/yil/((?:18|19|20)\\d{2})/?$").find(java.net.URI(url).path)?.groupValues?.get(1)
                value?.takeIf{it==a.text().trim()}?.toIntOrNull()
            }.distinct()
            yearSelector.isNotBlank()->d.select(yearSelector).filter(::primary).mapNotNull{Regex("\\b((?:18|19|20)\\d{2})\\b").find(it.text())?.groupValues?.get(1)?.toIntOrNull()}.distinct()
            else->emptyList()
        }
        val headingYear=if(kind==ContentKind.MOVIE)Regex(".+\\s+\\(((?:18|19|20)\\d{2})\\)(?:\\s+izle)?$",RegexOption.IGNORE_CASE).find(c.title)?.groupValues?.get(1)?.toIntOrNull()else null
        val story=if(c.ref.siteId=="filmcibaba")TextKey.fold(d.selectFirst(".p-v12-story-content")?.text().orEmpty().take(3000))else ""
        val statedYears=Regex("\\b((?:18|19|20)\\d{2}) (?:tarihinde )?(?:sinemalarda gosterime girmis|yapimi)\\b").findAll(story).map{it.groupValues[1].toInt()}.distinct().toList()
        val seasonYearOnly=kind==ContentKind.SERIES&&sourceSeason!=null&&schema?.let{"TVSeries" !in types(it)}!=false
        val providerYear=if(c.ref.siteId=="dizipal"&&kind!=ContentKind.EPISODE)DizipalParser.primaryYear(body,c.ref)else null
        val release=if(seasonYearOnly)null else providerYear?:domYears.singleOrNull()?:schemaYears.singleOrNull()?:categoryYears.singleOrNull()?:headingYear?:statedYears.singleOrNull()
        val resolvedYear=if(seasonYearOnly)null else release?:c.year
        fun names(o:Any?):List<String> = when(o){is String->listOf(o);is JSONArray->o.values().filterIsInstance<String>();else->emptyList()}
        val originalSelector=when(c.ref.siteId){"filmmakinesi"->"h2.original-title";"lovefilmizle"->".block-poster-right > h2";"hdfilmcehennemi"->".post-original-title";else->""}
        val domOriginals=if(originalSelector.isBlank())emptyList()else d.select(originalSelector).filter(::primary).map{ContentIdentity.cleanTitle(it.text(),release?:c.year)}
        val originals=matching.flatMap{names(it.opt("alternateName"))}+domOriginals
        val lead=if(c.ref.siteId=="filmcibaba")d.selectFirst(".p-v12-story-content strong")?.text()?.takeIf{
            val prefix=TextKey.fold(it);prefix.length>=8&&prefix.split(' ').size>=2&&TextKey.fold(ContentIdentity.cleanTitle(c.title)).startsWith(prefix)
        }else null
        val prior=seed?.takeIf{page(it.ref.pageUrl)==page(c.ref.pageUrl)&&(it.imdbId.isBlank()||imdb.singleOrNull().isNullOrBlank()||it.imdbId==imdb.singleOrNull())}
        val oldNames=prior?.let{it.aliases+listOf(it.originalTitle)+it.title.split(Regex("\\s+[–—|]\\s+|\\s+-\\s+"))}.orEmpty()
        val aliases=(originals+c.aliases+oldNames+listOfNotNull(lead)).filter{it.isNotBlank()}.distinct().take(16)
        val inherited=prior?.identityEvidence?.takeIf{release==null&&it.referencePage.isNotBlank()}
        val e=IdentityEvidence(c.ref.pageUrl,kind,resolvedYear,imdb.singleOrNull().orEmpty(),originals.firstOrNull().orEmpty().ifBlank{c.originalTitle},aliases,
            c.title,schema!=null||listedSeries||linkedEpisode,release!=null,true,imdb.size>1,reason=if(release!=null)"primary-release-year"else inherited?.reason.orEmpty(),referencePage=inherited?.referencePage.orEmpty(),season=sourceSeason)
        return detail.copy(card=c.copy(year=resolvedYear,yearHint=c.yearHint?:if(seasonYearOnly)c.year else null,sourceSeason=sourceSeason,imdbId=if(imdb.size>1)""else imdb.singleOrNull()?:seed?.imdbId.orEmpty(),identityEvidence=e,genres=detail.genres,aliases=aliases))
    }
}
