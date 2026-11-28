package app.filmmerkezi.tv.core

import org.jsoup.nodes.Element

/** Read a declared image variant without downloading or changing the site's URL. */
object CatalogPosters {
    private const val TARGET_WIDTH = 320
    private val widths = Regex("""(?:^|,)\s*(\S+?)\s+(\d{1,5})w(?=\s*(?:,|$))""")
    private val attributes = listOf("data-srcset", "data-litespeed-srcset", "data-catalog-srcset", "srcset")
    private data class Candidate(val url:String,val width:Int)

    fun thumbnail(node:Element?,page:String):String {
        val image=node?.takeIf{it.normalName()=="img"}?:node?.selectFirst("img")?:return ""
        val sources=image.parent()?.takeIf{it.normalName()=="picture"}?.select("source:not([media])")
            .orEmpty().filter{it.attr("type") in setOf("","image/jpeg","image/png","image/webp")}
        val candidates=(listOf(image)+sources).asSequence().flatMap{source->
            attributes.asSequence().flatMap{attribute->widths.findAll(source.attr(attribute).take(8192))}
        }.mapNotNull{match->
            val width=match.groupValues[2].toInt()
            val url=publicUrl(match.groupValues[1],page)?.takeIf{it.startsWith("https://")}
            if(url!=null&&width>=80)Candidate(url,width)else null
        }.toList()
        val current=HtmlData.image(image,page)
        val alreadySmall=candidates.any{it.url==current&&it.width<=TARGET_WIDTH}
        if(alreadySmall)return current
        val larger=candidates.filter{it.width>=TARGET_WIDTH}.minByOrNull{it.width}
        val smaller=candidates.filter{it.width<TARGET_WIDTH}.maxByOrNull{it.width}
        return (if(smaller!=null&&smaller.width>=220&&(larger?.width?:Int.MAX_VALUE)>420)smaller
            else larger?:smaller)?.url.orEmpty()
    }

    fun image(node:Element?,page:String)=thumbnail(node,page).ifBlank{HtmlData.image(node,page)}
}
