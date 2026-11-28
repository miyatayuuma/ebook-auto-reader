package io.github.ebookautoreader.lite

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.*
import android.content.*
import android.database.ContentObserver
import android.graphics.*
import android.media.*
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.accessibility.*
import android.view.Display

class TurnService : AccessibilityService() {
    companion object { internal var instance:TurnService?=null; private const val DMM="com.dmm.app.dmmbooks_google_play"; private const val TB="com.google.android.marvin.talkback"; private const val READER="com.dmm.app.dmmbooks.viewer.pbr.PublusReaderActivity" }
    private enum class Phase { IDLE, BASELINE, READING, CHECK, TURN, VERIFY }
    private data class Target(val id:Int,val bounds:Rect)
    private val h=Handler(Looper.getMainLooper())
    private val audio by lazy { requireNotNull(getSystemService(AudioManager::class.java)) }
    private val accessibility by lazy { requireNotNull(getSystemService(AccessibilityManager::class.java)) }
    private val power by lazy { requireNotNull(getSystemService(PowerManager::class.java)) }
    private val keyguard by lazy { requireNotNull(getSystemService(KeyguardManager::class.java)) }
    private val notifications by lazy { requireNotNull(getSystemService(NotificationManager::class.java)) }
    private var phase=Phase.IDLE
    private var target:Target?=null
    private var readerId:Int?=null
    private var blocked=false
    private var playing=false
    private var registered=false
    private var generation=0
    private var captureAt=0L
    private var baseline:PageFeatures?=null
    private var prefs:TurnPrefs?=null
    private var timing=Timing(800,1500)
    private var wake:PowerManager.WakeLock?=null
    private var turns=0
    private var verifyDeadline=0L
    private var lastVerify:PageFeatures?=null
    private var shadeHold=false
    private var manualSync=false
    private var lastContentProbe=0L
    private var resyncCandidate:PageFeatures?=null
    private val contentProbe=Runnable { inspectManualChange() }
    private var earliestTurn=0L
    private val renewWake=object:Runnable { override fun run(){
        val lock=wake?:return
        if(phase==Phase.IDLE)return
        if(runCatching{ lock.acquire(2*60*60*1000L) }.isFailure){halt("wake_renew_failed",true);return}
        h.postDelayed(this,30*60*1000L)
    }}
    private val evaluation=Runnable { evaluate() }
    private val refresh=Runnable { reconcile() }
    private fun now()=SystemClock.elapsedRealtime()
    private fun log(s:String)=Log.i("EbookAutoTurn","t=${now()} $s")
    private fun talkbackOn()=accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName==TB }
    private fun candidate(configs:List<AudioPlaybackConfiguration>)=configs.any {
        it.audioAttributes.usage==AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY &&
            it.audioAttributes.contentType in setOf(AudioAttributes.CONTENT_TYPE_UNKNOWN,AudioAttributes.CONTENT_TYPE_SPEECH)
    }
    private fun syncAudio():Boolean?=runCatching { candidate(audio.activePlaybackConfigurations) }.getOrNull()
    private val callback=object:AudioManager.AudioPlaybackCallback(){
        override fun onPlaybackConfigChanged(configs:MutableList<AudioPlaybackConfiguration>){
            val new=candidate(configs)
            if(new==playing)return
            playing=new
            if(phase!=Phase.IDLE) { log("audio active=$new phase=$phase"); timing.audio(now(),new); schedule() }
        }
    }
    private val settingsObserver=object:ContentObserver(h){
        override fun onChange(selfChange:Boolean){ reconcile() }
    }
    private val screen=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){ if(intent.action==Intent.ACTION_SCREEN_OFF) halt("screen_off",true) }
    }
    private fun windowsHere()=windows.filter { it.displayId==Display.DEFAULT_DISPLAY }
    private fun shade()=windowsHere().any { (it.isFocused||it.isActive) && it.root?.packageName?.toString()=="com.android.systemui" }
    private fun current():Target? {
        if(!power.isInteractive||keyguard.isKeyguardLocked||shade())return null
        val ws=windowsHere()
        val w=ws.firstOrNull{it.isFocused&&it.type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}
            ?:ws.firstOrNull{it.isActive&&it.type==AccessibilityWindowInfo.TYPE_APPLICATION} ?:return null
        if(w.type!=AccessibilityWindowInfo.TYPE_APPLICATION || w.id!=readerId)return null
        val root=w.root?:return null
        if(root.packageName?.toString()!=DMM)return null
        val b=Rect().also(w::getBoundsInScreen)
        if(b.width()<200||b.height()<300||menu(root,b))return null
        return Target(w.id,b)
    }
    // Metadata only. Do not read node text, descriptions or book content.
    private fun menu(root:AccessibilityNodeInfo,b:Rect):Boolean {
        val queue=ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val buttons=mutableListOf<Rect>()
        repeat(160){
            val n=queue.removeFirstOrNull()?:return false
            if(!n.isVisibleToUser)return@repeat
            val rect=Rect().also(n::getBoundsInScreen); val kind=n.className?.toString()
            if(rect.top>=b.bottom-b.height()/4 && !rect.isEmpty){
                if(kind=="android.widget.SeekBar" || (n.rangeInfo!=null && rect.width()>=b.width()/3))return true
                if(n.isClickable && kind in listOf("android.widget.Button","android.widget.ImageButton") && rect.width()<=b.width()/3 && rect.height()<=b.height()/8){
                    buttons.add(rect)
                    val row=buttons.filter { kotlin.math.abs(it.centerY()-rect.centerY())<rect.height()/2 }
                    if(row.size>=3 && row.maxOf{it.centerX()}-row.minOf{it.centerX()}>b.width()/3)return true
                }
            }
            for(i in 0 until minOf(n.childCount,160))n.getChild(i)?.let(queue::addLast)
        }
        return false
    }
    override fun onServiceConnected(){
        super.onServiceConnected(); instance=this
        if(!registered){
            registered=runCatching { audio.registerAudioPlaybackCallback(callback,h); true }.getOrDefault(false)
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),false,settingsObserver)
            registerReceiver(screen,IntentFilter(Intent.ACTION_SCREEN_OFF),RECEIVER_NOT_EXPORTED)
        }
        log("ready callback=$registered")
        notifications.createNotificationChannel(NotificationChannel("reading","自動ページ送り",NotificationManager.IMPORTANCE_LOW))
        reconcile()
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        if(event==null)return
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString()==DMM){
            val name=event.className?.toString()
            if(name==READER) readerId=event.windowId
            else if(name?.endsWith("Activity")==true) readerId=null
        }
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.packageName?.toString()==DMM && phase==Phase.READING && now()-lastContentProbe>=650 && !h.hasCallbacks(contentProbe)){
            h.postDelayed(contentProbe,180)
        }
        if(event.eventType!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.packageName?.toString()==DMM){
            if(!h.hasCallbacks(refresh))h.post(refresh)
        }
    }
    private fun reconcile(){
        val on=talkbackOn()
        if(!on){ if(phase!=Phase.IDLE)halt("talkback_off",false); blocked=false; return }
        if(!registered||blocked)return
        if(phase!=Phase.IDLE && shade()){
            if(!shadeHold){shadeHold=true;h.removeCallbacks(evaluation);log("notification_hold")}
            return
        }
        val next=current()
        if(phase!=Phase.IDLE){
            if(next==null || next!=target){halt("reader_window_lost",true);return}
            if(shadeHold){shadeHold=false;halt("notification_return_rearm",true)}
            return
        }
        if(next!=null && TurnPrefs.read(this).enabled)start(next)
    }
    @Suppress("DEPRECATION")
    private fun start(t:Target){
        val p=TurnPrefs.read(this)
        val state=syncAudio()?:return halt("audio_unavailable",true)
        prefs=p; target=t; generation++; phase=Phase.BASELINE; playing=state
        timing=Timing(p.gap,p.noSpeech); timing.reset(now(),state)
        turns=0; earliestTurn=now()+p.noSpeech
        if(p.awake){
            val lock=power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,"EbookAutoTurn:reading").apply { setReferenceCounted(false) }
            if(runCatching { lock.acquire(2*60*60*1000L) }.isFailure){halt("wake_failed",true);return}
            wake=lock; h.postDelayed(renewWake,30*60*1000L)
        }
        showNotification("自動ページ送り動作中")
        log("start gap=${p.gap} noSpeechWait=${p.noSpeech} audio=$state")
        capture { f ->
            baseline=f; phase=Phase.READING
            // Baseline must exist before any timeout can cause a turn.
            schedule()
        }
    }
    private fun showNotification(text:String){
        val stop=PendingIntent.getBroadcast(this,1,Intent(this,StopReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,2,Intent(this,SetupActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=Notification.Builder(this,"reading").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Ebook Auto Turn")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止",stop).build()).build()
        runCatching { notifications.notify(1,n) }
    }
    internal fun emergencyStop(){ halt("emergency_stop",true) }
    private fun halt(reason:String,latch:Boolean){
        if(latch)blocked=true
        generation++; h.removeCallbacks(evaluation); h.removeCallbacks(contentProbe); h.removeCallbacks(renewWake); phase=Phase.IDLE
        target=null; baseline=null; lastVerify=null; shadeHold=false; manualSync=false; resyncCandidate=null
        wake?.let { if(it.isHeld)runCatching{it.release()} }; wake=null
        notifications.cancel(1); log("stop reason=$reason turns=$turns blocked=$blocked")
    }
    private fun schedule(){
        h.removeCallbacks(evaluation)
        if(phase!=Phase.READING||shadeHold)return
        timing.next(now())?.let { h.postDelayed(evaluation,maxOf(it,earliestTurn-now(),20L)) }
    }
    private fun valid():Boolean {
        if(!talkbackOn()){halt("talkback_off",false);return false}
        if(shade()){shadeHold=true;return false}
        if(current()!=target){halt("unsafe_window",true);return false}
        return true
    }
    private fun evaluate(){
        if(phase!=Phase.READING||!valid())return
        val state=syncAudio()?:return halt("audio_unavailable",true)
        if(state!=playing){playing=state;timing.audio(now(),state)}
        if(now()<earliestTurn){schedule();return}
        val due=timing.due(now())
        if(due==Due.NONE){schedule();return}
        phase=Phase.CHECK
        capture { frame ->
            val old=baseline?:return@capture halt("no_baseline",true)
            if(!old.same(frame))return@capture beginManualSync(frame)
            val fresh=syncAudio()?:return@capture halt("audio_unavailable",true)
            if(fresh!=playing){playing=fresh;timing.audio(now(),fresh)}
            if(playing||timing.due(now())!=due){phase=Phase.READING;schedule();return@capture}
            log("turn_due reason=$due")
            turn()
        }
    }
    private fun turn(){
        val t=target?:return halt("no_target",true)
        if(!valid())return
        val state=syncAudio()?:return halt("audio_unavailable",true)
        if(state){playing=true;timing.audio(now(),true);phase=Phase.READING;schedule();return}
        phase=Phase.TURN;timing.reset(now(),false,1200);playing=false;earliestTurn=0;manualSync=false
        val p=prefs?:return halt("no_prefs",true)
        val b=t.bounds
        fun x(f:Float)=b.left+b.width()*f
        fun y(f:Float)=b.top+b.height()*f
        val builder=GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
        for(f in listOf(.43f,.57f)){
            val path=Path().apply{moveTo(x(if(p.left).22f else .78f),y(f));lineTo(x(if(p.left).78f else .22f),y(f))}
            builder.addStroke(GestureDescription.StrokeDescription(path,0,120))
        }
        log("gesture_start swipe=${if(p.left)"right" else "left"} duration=120")
        val g=generation
        val accepted=dispatchGesture(builder.build(),object:GestureResultCallback(){
            override fun onCompleted(gestureDescription:GestureDescription?){
                if(g!=generation)return
                phase=Phase.VERIFY;verifyDeadline=now()+4500;lastVerify=null
                log("gesture_completed"); h.postDelayed({verify(g)},260)
            }
            override fun onCancelled(gestureDescription:GestureDescription?){if(g==generation)halt("gesture_cancelled",true)}
        },h)
        if(!accepted){halt("gesture_rejected",true);return}
        h.postDelayed({if(g==generation&&phase==Phase.TURN)halt("gesture_timeout",true)},2500)
    }
    private fun verify(g:Int){
        if(g!=generation||phase!=Phase.VERIFY)return
        if(!valid())return
        if(now()>verifyDeadline){halt("page_change_unconfirmed",true);return}
        capture { frame ->
            val old=baseline?:return@capture halt("no_baseline",true)
            val prior=lastVerify
            // Require change from prior page and two stable post-turn samples.
            if(old.distance(frame)>=.015 && prior!=null&&prior.same(frame)){
                baseline=frame;turns++;phase=Phase.READING
                // Keep any speech onset/stop that occurred during transition.
                // If none started, start the 1.5s wait from verified stable page.
                if(!timing.seen)timing.reset(now(),playing,1200)
                earliestTurn=now()+100
                log("page_verified count=$turns audio=$playing"); schedule()
            }else{lastVerify=frame;h.postDelayed({verify(g)},260)}
        }
    }
    private fun inspectManualChange(){
        if(phase!=Phase.READING || !valid())return
        lastContentProbe=now()
        phase=Phase.CHECK;h.removeCallbacks(evaluation)
        capture { frame ->
            val old=baseline?:return@capture halt("no_baseline",true)
            if(!old.same(frame))beginManualSync(frame) else {phase=Phase.READING;schedule()}
        }
    }
    private fun beginManualSync(frame:PageFeatures){
        manualSync=true
        phase=Phase.VERIFY
        resyncCandidate=frame
        verifyDeadline=now()+4500
        // Ignore the old page's completed speech. Observe speech on the new
        // page, including any start/stop while its screenshot stabilizes.
        val p=prefs?:return halt("no_prefs",true)
        val state=syncAudio()?:return halt("audio_unavailable",true)
        playing=state;timing=Timing(p.gap,p.noSpeech)
        timing.reset(now(),state,1200)
        log("manual_page_change resync audio=$state")
        val g=generation;h.postDelayed({verifyManual(g)},260)
    }
    private fun verifyManual(g:Int){
        if(g!=generation||phase!=Phase.VERIFY||!manualSync||!valid())return
        if(now()>verifyDeadline)return halt("manual_page_unstable",true)
        capture { frame ->
            val previous=resyncCandidate
            if(previous!=null&&previous.same(frame)){
                baseline=frame;resyncCandidate=null;manualSync=false;phase=Phase.READING
                if(!timing.seen)timing.reset(now(),playing,1200)
                earliestTurn=now()+100
                log("manual_page_resynced audio=$playing");schedule()
            }else{
                resyncCandidate=frame
                timing.reset(now(),playing,1200)
                h.postDelayed({verifyManual(g)},260)
            }
        }
    }
    private fun capture(done:(PageFeatures)->Unit){
        val t=target?:return halt("capture_no_target",true)
        val g=generation
        val delay=(captureAt+430-now()).coerceAtLeast(0)
        h.postDelayed({
            if(g!=generation||!valid())return@postDelayed
            captureAt=now()
            takeScreenshotOfWindow(t.id,mainExecutor,object:TakeScreenshotCallback{
                override fun onSuccess(result:ScreenshotResult){
                    val buffer=result.hardwareBuffer
                    val frame=try{
                        val hw=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
                        val software=hw?.copy(Bitmap.Config.ARGB_8888,false)
                        hw?.recycle()
                        if(software==null)null else{
                            val scaled=Bitmap.createScaledBitmap(software,PageAnalysis.W,PageAnalysis.H,true)
                            val pixels=IntArray(PageAnalysis.W*PageAnalysis.H)
                            scaled.getPixels(pixels,0,PageAnalysis.W,0,0,PageAnalysis.W,PageAnalysis.H)
                            if(scaled!==software)scaled.recycle();software.recycle()
                            PageAnalysis.analyze(pixels)
                        }
                    }catch(e:Exception){null}finally{buffer.close()}
                    if(g!=generation||!valid())return
                    if(frame==null)halt("capture_decode_failed",true) else done(frame)
                }
                override fun onFailure(errorCode:Int){if(g==generation)halt("capture_failed code=$errorCode",true)}
            })
        },delay)
    }
    override fun onInterrupt(){halt("interrupt",true)}
    override fun onDestroy(){
        halt("service_destroy",true);h.removeCallbacksAndMessages(null)
        if(registered)runCatching{audio.unregisterAudioPlaybackCallback(callback)}
        runCatching{contentResolver.unregisterContentObserver(settingsObserver)};runCatching{unregisterReceiver(screen)}
        instance=null;super.onDestroy()
    }
}
