package io.github.ebookautoreader.lite

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Low-resolution pixels only, no OCR, no content persistence. Unknown is never a skip. */
internal data class PageFeatures(val signature: IntArray, val image: Boolean, val largeInk: Int, val smallInk: Int) {
    fun distance(other: PageFeatures): Double {
        if (signature.size != other.signature.size) return 1.0
        return signature.indices.count { abs(signature[it]-other.signature[it]) >= 28 }.toDouble()/signature.size
    }
    fun same(other: PageFeatures) = distance(other) < 0.008
}
internal object PageAnalysis {
    const val W=240
    const val H=360
    fun analyze(pixels: IntArray): PageFeatures {
        require(pixels.size==W*H)
        val luma=IntArray(pixels.size) { i ->
            val p=pixels[i]; ((p shr 16 and 255)*77+(p shr 8 and 255)*150+(p and 255)*29) shr 8
        }
        val sig=IntArray(96*144) { i ->
            val x=i%96; val y=i/96
            // Avoid status, bottom controls and outside page margins; sample blocks.
            val sx=12+x*216/96; val sy=18+y*324/144
            (luma[sy*W+sx]+luma[sy*W+sx+1]+luma[(sy+1)*W+sx]+luma[(sy+1)*W+sx+1])/4
        }
        val hist=IntArray(16)
        for(y in 0 until H) for(x in 0 until W) if(x<8 || x>=W-8 || y<8 || y>=H-8) hist[luma[y*W+x]/16]++
        val bg=hist.indices.maxByOrNull { hist[it] }!!*16+8
        val mask=BooleanArray(pixels.size) { i -> abs(luma[i]-bg)>48 }
        val seen=BooleanArray(mask.size)
        val queue=IntArray(mask.size)
        var large=0; var glyph=0; var total=0
        for(i in mask.indices) {
            if(!mask[i] || seen[i]) continue
            var head=0; var tail=1; queue[0]=i; seen[i]=true
            var minX=i%W; var maxX=minX; var minY=i/W; var maxY=minY
            while(head<tail) {
                val k=queue[head++]; val x=k%W; val y=k/W
                minX=min(minX,x); maxX=max(maxX,x); minY=min(minY,y); maxY=max(maxY,y)
                fun add(n:Int) { if(mask[n]&&!seen[n]) { seen[n]=true; queue[tail++]=n } }
                if(x>0)add(k-1); if(x<W-1)add(k+1); if(y>0)add(k-W); if(y<H-1)add(k+W)
            }
            total+=tail
            val w=maxX-minX+1; val h=maxY-minY+1
            if(w>=W/3 && h>=H/4 && tail>=pixels.size/12) large=max(large,tail)
            if(w in 2..24 && h in 2..28 && tail>=3) glyph++
        }
        // Conservative positive evidence: broad connected graphic; even a few
        // isolated glyph-like shapes make the page unknown. No claim of semantic classification.
        val image=large>=pixels.size/7 && glyph<=2 && total<=pixels.size*9/10
        return PageFeatures(sig,image,large,glyph)
    }
}
