package io.github.ebookautoreader.lite
import org.junit.Test
import org.junit.Assert.*
class TimingTest {
    @Test fun readEndAndGapCancellation(){
        val t=Timing(800,1500);t.reset(0,false)
        assertEquals(Due.NONE,t.due(1499));assertEquals(Due.NO_SPEECH,t.due(1500))
        t.audio(1600,true);assertEquals(Due.NONE,t.due(10000))
        t.audio(10000,false);assertEquals(Due.NONE,t.due(10799))
        t.audio(10700,true);assertEquals(Due.NONE,t.due(12000))
        t.audio(12000,false);assertEquals(Due.READ_END,t.due(12800))
    }
    @Test fun transitionAnnouncementIsNotReadEnd(){
        val t=Timing(800,1500)
        t.reset(0,false,1200)
        t.audio(100,true);t.audio(600,false)
        assertEquals(Due.NONE,t.due(1400))
        assertEquals(Due.NO_SPEECH,t.due(2100))
        t.audio(2200,true);t.audio(3500,false)
        assertEquals(Due.READ_END,t.due(4300))
    }
    @Test fun resetDisarmsOldRead(){
        val t=Timing(800,1500);t.reset(0,true);t.audio(100,false)
        t.reset(200,false);assertEquals(Due.NONE,t.due(1000));assertEquals(Due.NO_SPEECH,t.due(1700))
    }
    @Test fun silentPagesNeverNeedContentClassification(){
        val t=Timing(800,1500)
        repeat(20){ i ->
            val start=i*2000L
            t.reset(start,false,1200)
            assertEquals(Due.NONE,t.due(start+1499))
            assertEquals(Due.NO_SPEECH,t.due(start+1500))
        }
    }
    @Test fun signatureOnlyChecksChange(){
        val w=PageAnalysis.W;val h=PageAnalysis.H
        val a=IntArray(w*h){-1}
        val blank=PageAnalysis.analyze(a)
        assertTrue(blank.same(PageAnalysis.analyze(a)))
        for(y in 80..280)for(x in 40..200)a[y*w+x]=0xff202020.toInt()
        val changed=PageAnalysis.analyze(a)
        assertFalse(blank.same(changed))
        assertTrue(blank.distance(changed)>=.015)
    }
}
