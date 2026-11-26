package app.filmmerkezi.tv.core

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Bounded arithmetic data decoding; remote functions are never executed. */
object SeededDecoder {
    private data class Profile(
        val metadata:Boolean,val salt:String?,val operations:String?,val hostname:Boolean,
        val hashMultiplier:Int,val hashModulus:Int,val hashShift:Int,val seedMultiplier:Int,
        val incrementModulus:Int,val incrementOffset:Int,val shuffleSeedMultiplier:Int,val shuffleSeedModulus:Int,
        val shuffleSeedOffset:Int,val shuffleMultiplier:Int,val shuffleOffset:Int,val shuffleModulus:Int,
        val base64Code:Char,val reverseCode:Char,val rotationBase:Int,val xorMultiplier:Int,
        val removed:Int=0,val saltModulus:Int=0,val operationOffset:Int=0,val operationModulus:Int=0
    )
    fun closingBrace(s:String,start:Int):Int {
        var i=start;var depth=0;var previous=' '
        while(i<s.length) {
            val c=s[i]
            if(c=='\''||c=='"'||c=='\u0060') {
                val q=c;i++
                while(i<s.length){if(s[i]=='\\')i+=2 else if(s[i++]==q)break}
                previous='s';continue
            }
            if(c=='/'&&s.getOrNull(i+1)=='/'){i=s.indexOf('\n',i+2).let{if(it<0)s.length else it+1};continue}
            if(c=='/'&&s.getOrNull(i+1)=='*'){i=s.indexOf("*/",i+2).let{if(it<0)s.length else it+2};continue}
            if(c=='/' && previous in "=(:,![{;?&|") {
                i++;var bracket=false
                while(i<s.length){val r=s[i++];if(r=='\\')i++ else if(r=='[')bracket=true else if(r==']')bracket=false else if(r=='/'&&!bracket)break}
                previous='r';continue
            }
            if(c=='{')depth++
            if(c=='}'){depth--;if(depth==0)return i}
            if(!c.isWhitespace())previous=c
            i++
        }
        return -1
    }
    private fun profile(body:String,parameter:String):Profile? = runCatching {
        val c=body.replace(Regex("""\b(?:var|let|const)\s+"""),";").replace(Regex("""\s+"""),"")
        fun m(pattern:String)=Regex(pattern).find(c)
        fun esc(value:String)=Regex.escape(value)
        val hash=m("""([\w$]+)=\(\1\*(\d+)\+([\w$]+)\)%(\d+)""")?:return null
        val h1=esc(hash.groupValues[1]);val byte=esc(hash.groupValues[3])
        val saltRead=m("""$byte=([\w$]+)\.charCodeAt\(([\w$]+)\)""")?:return null
        val saltName=saltRead.groupValues[1];val index=esc(saltRead.groupValues[2])
        val metadata=c.contains("$parameter.splice(")
        val hostname=m("""${esc(saltName)}\+=(?:(?:window|self|document)\.)?location(\.hostname|\[([^\]]{1,128})\])(?:;|$)""")?.let {
            it.groupValues[1]==".hostname" || Regex("""['"]([^'"]+)['"]""").findAll(it.groupValues[2]).joinToString(""){v->v.groupValues[1]}=="hostname"
        }?:false
        var removed=0;var saltMod=0;var opOffset=0;var opMod=0;var salt:String?=null;var ops:String?=null;val opName:String
        val second:MatchResult;val seed:MatchResult;val inc:MatchResult;val shuffleSeed:MatchResult;var hashShift=0
        if(metadata) {
            val meta=m("""([\w$]+)=${esc(parameter)}\.length-(\d+),([\w$]+)=\1%(\d+),([\w$]+)=(\d+)\+\(\1%(\d+)\)""")?:return null
            val splice=m("""([\w$]+)=${esc(parameter)}\.splice\(${esc(meta.groupValues[5])},1\)\[0\],([\w$]+)=${esc(parameter)}\.splice\(${esc(meta.groupValues[3])},1\)\[0\]""")?:return null
            if(splice.groupValues[2]!=saltName)return null
            opName=splice.groupValues[1];removed=meta.groupValues[2].toInt();saltMod=meta.groupValues[4].toInt();opOffset=meta.groupValues[6].toInt();opMod=meta.groupValues[7].toInt()
            second=m("""([\w$]+)=\(\1\+\(\($byte<<(\d+)\)\^$index\)\)&255""")?:return null
            hashShift=second.groupValues[2].toInt();val h2=esc(second.groupValues[1])
            seed=m("""([\w$]+)=\($h1\*(\d+)\+$h2\)%256""")?:return null
            inc=m("""([\w$]+)=\($h2%(\d+)\)\+(\d+)""")?:return null
            shuffleSeed=m("""([\w$]+)=\(\($h2\*(\d+)\+$h1\)%(\d+)\)\+(\d+)""")?:return null
        } else {
            second=m("""([\w$]+)=\(\1\^\($byte\+$index\)\)&255""")?:return null
            val h2=esc(second.groupValues[1])
            seed=m("""([\w$]+)=\($h1\+$h2\)%256""")?:return null
            inc=m("""([\w$]+)=\($h1%(\d+)\)\+(\d+)""")?:return null
            shuffleSeed=m("""([\w$]+)=\(\($h1\*(\d+)\+$h2\)%(\d+)\)\+(\d+)""")?:return null
            val declarations=mutableMapOf<String,String>()
            Regex("""(?:var|let|const)\s+([\w$]+)\s*=\s*(['"])""").findAll(body).forEach { d->
                runCatching{Literals.read(body,d.range.last).value as? String}.getOrNull()?.let{declarations[d.groupValues[1]]=it}
            }
            val loop=m("""for\(([\w$]+)=([\w$]+)\.length-1;\1>=0;\1--\)""")?:return null
            opName=loop.groupValues[2];salt=declarations[saltName];ops=declarations[opName]
        }
        val sh=esc(shuffleSeed.groupValues[1])
        val shuffle=m("""$sh=\($sh\*(\d+)\+(\d+)\)%(\d+);([\w$]+)\[$index\]=$sh%\($index\+1\)""")?:return null
        val op=m("""([\w$]+)=${esc(opName)}\.charAt\($index\);if\(\1===['"]([^'"])['"]\)\{([\w$]+)=atob\(\3\);?\}elseif\(\1===['"]([^'"])['"]\)""")?:return null
        val rotation=m("""\(26-\(\(${esc(op.groupValues[1])}\.charCodeAt\(0\)-(\d+)\)%26\)\)%26""")?:return null
        val xor=m("""([\w$]+)=${esc(seed.groupValues[1])},[\w$]+=['"]["'];[\s\S]*?\1=\(\1(?:\*(\d+))?\+${esc(inc.groupValues[1])}\)%256;[\w$]+\+=String\.fromCharCode\($byte\^\1\);\1=\(\1\+$byte\)%256""")?:return null
        val p=Profile(metadata,salt,ops,hostname,hash.groupValues[2].toInt(),hash.groupValues[4].toInt(),hashShift,
            if(metadata)seed.groupValues[2].toInt()else 1,inc.groupValues[2].toInt(),inc.groupValues[3].toInt(),
            shuffleSeed.groupValues[2].toInt(),shuffleSeed.groupValues[3].toInt(),shuffleSeed.groupValues[4].toInt(),
            shuffle.groupValues[1].toInt(),shuffle.groupValues[2].toInt(),shuffle.groupValues[3].toInt(),
            op.groupValues[2].single(),op.groupValues[4].single(),rotation.groupValues[1].toInt(),xor.groupValues[2].toIntOrNull()?:1,removed,saltMod,opOffset,opMod)
        if(p.hashMultiplier !in 1..1000 || p.hashModulus !in 1..65537 || p.shuffleModulus !in 1..65537 || p.shuffleMultiplier !in 1..1000 || p.hashShift !in 0..8 || p.xorMultiplier !in 1..1000)return null
        p
    }.getOrNull()
    private fun decode(parts:List<String>,p:Profile,hostname:String):String {
        val values=parts.toMutableList();var salt=p.salt;var operations=p.operations
        if(p.metadata) {
            val count=values.size-p.removed
            require(p.removed==2 && p.saltModulus>0 && p.operationModulus>0 && count>0)
            operations=values.removeAt(p.operationOffset+count%p.operationModulus);salt=values.removeAt(count%p.saltModulus)
        }
        require(!salt.isNullOrBlank() && !operations.isNullOrBlank() && salt.length<=4096 && operations.length<=16 && values.size<=10000)
        if(p.hostname)salt+=hostname.lowercase()
        var h1=0L;var h2=0
        salt.forEachIndexed { i,v->val b=v.code;h1=(h1*p.hashMultiplier+b)%p.hashModulus;h2=if(p.metadata)(h2+((b shl p.hashShift) xor i)) and 255 else(h2 xor(b+i))and 255 }
        var seed=((h1*p.seedMultiplier+h2)%256).toInt()
        val increment=((if(p.metadata)h2.toLong()else h1)%p.incrementModulus+p.incrementOffset).toInt()
        var shuffleSeed=(((if(p.metadata)h2.toLong()else h1)*p.shuffleSeedMultiplier+(if(p.metadata)h1 else h2.toLong()))%p.shuffleSeedModulus)+p.shuffleSeedOffset
        var value=values.joinToString("");require(value.length<=2*1024*1024)
        for(op in operations.reversed()) value=when(op) {
            p.base64Code->Literals.base64(value).toString(Charsets.ISO_8859_1)
            p.reverseCode->value.reversed()
            else->{require(op.isLetter());val shift=(26-((op.code-p.rotationBase)%26))%26
                value.map{c->when(c){in 'a'..'z'->('a'.code+(c-'a'+shift)%26).toChar();in 'A'..'Z'->('A'.code+(c-'A'+shift)%26).toChar();else->c}}.joinToString("")}
        }
        val swaps=IntArray(value.length)
        for(i in value.length-1 downTo 1){shuffleSeed=(shuffleSeed*p.shuffleMultiplier+p.shuffleOffset)%p.shuffleModulus;swaps[i]=(shuffleSeed%(i+1)).toInt()}
        val chars=value.toCharArray()
        for(i in 1 until chars.size){val swap=chars[i];chars[i]=chars[swaps[i]];chars[swaps[i]]=swap}
        return buildString { for(c in chars){val b=c.code;seed=(seed*p.xorMultiplier+increment)%256;append((b xor seed).toChar());seed=(seed+b)%256} }
    }
    fun bindings(body:String,page:String):Map<String,String> {
        if(body.length>2*1024*1024)throw DataFailure(FailureKind.LIMIT,"Şifreli oynatıcı veri sınırı","decode")
        val output=linkedMapOf<String,String>()
        val functions=Regex("""(?:function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)|([\w$]+)\s*=\s*function\s*\(\s*([\w$]+)\s*\))\s*\{""")
        for(m in functions.findAll(body).take(32)) {
            val name=m.groupValues[1].ifBlank{m.groupValues[3]};val parameter=m.groupValues[2].ifBlank{m.groupValues[4]}
            val end=closingBrace(body,m.range.last);if(end<0)continue
            val p=profile(body.substring(m.range.last+1,end),parameter)?:continue
            for(call in Regex("""(?:var|let|const)\s+([\w$]+)\s*=\s*${Regex.escape(name)}\s*\(""").findAll(body).take(32)) {
                val literal=runCatching{Literals.read(body,call.range.last+1)}.getOrNull()?:continue
                val parts=when(val value=literal.value){
                    is org.json.JSONArray->value.values().map{it as? String?:""}
                    is String->{
                        val split=Regex("""^\s*\.split\s*\(\s*""").find(body.substring(literal.end))?:continue
                        val delimiter=runCatching{Literals.read(body,literal.end+split.value.length).value as? String}.getOrNull()?:continue
                        if(delimiter.length !in 1..8)continue
                        value.split(delimiter)
                    }
                    else->continue
                }
                if(parts.any{it.isEmpty()})continue
                val decoded=runCatching{decode(parts,p,page.toHttpUrl().host)}.getOrNull()?:continue
                publicUrl(decoded,page)?.let{output[call.groupValues[1]]=it}
            }
        }
        return output
    }
    fun media(body:String,page:String):List<String> {
        val values=bindings(body,page)
        val referenced=Regex("""\b(?:file|src)\s*:\s*([\w$]+)\s*[,}]""").findAll(body).map{it.groupValues[1]}.toSet()
        return values.filterKeys{it in referenced}.values.distinct()
    }
}
