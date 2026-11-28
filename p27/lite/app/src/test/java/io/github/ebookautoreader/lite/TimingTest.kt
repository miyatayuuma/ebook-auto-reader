package io.github.ebookautoreader.lite
import org.junit.Test
import org.junit.Assert.*
class TimingTest {
    @Test fun shortGapDoesNotTurn(){
        val t=Timing(800,1500);t.reset(0,true)
        t.audio(1000,false);assertEquals(Due.NONE,t.due(1799))
        t.audio(1128,true);assertEquals(Due.NONE,t.due(9000))
        t.audio(9000,false);assertEquals(Due.READ_END,t.due(9800))
    }
    @Test fun everyGestureResetsSilentPageTimer(){
        val t=Timing(800,1500)
        repeat(30){ i ->
            val start=i*1620L
            t.reset(start,false)
            assertEquals(1500L,t.next(start))
            assertEquals(Due.NONE,t.due(start+1499))
            assertEquals(Due.NO_SPEECH,t.due(start+1500))
        }
    }
    @Test fun speechAtGestureCompletionBlocksNextTurn(){
        val t=Timing(800,1500);t.reset(0,true)
        assertNull(t.next(10000));assertEquals(Due.NONE,t.due(10000))
        t.audio(10000,false);assertEquals(Due.READ_END,t.due(10800))
    }
    @Test fun oldSpeechNotInheritedAcrossGesture(){
        val t=Timing(800,1500);t.reset(0,true);t.audio(100,false)
        t.reset(1000,false);assertEquals(Due.NONE,t.due(1800))
        assertEquals(Due.NO_SPEECH,t.due(2500))
    }
    @Test fun manualSpeechAndAnnouncementUseSameSilenceRule(){
        val t=Timing(800,1500);t.reset(0,false)
        t.audio(1200,true);t.audio(1300,false)
        assertEquals(Due.NONE,t.due(2099));assertEquals(Due.READ_END,t.due(2100))
        t.audio(2000,true);assertEquals(Due.NONE,t.due(4000))
    }
}
