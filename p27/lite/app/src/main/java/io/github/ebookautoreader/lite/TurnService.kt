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
    private enum class Phase { IDLE, WAITING, SWIPING, HOLD }
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
    private var prefs:TurnPrefs?=null
    private var timing=Timing(800,1500)
    private var wake:PowerManager.WakeLock?=null
    private var turns=0
    private var cancelled=0
    private var gestureId=0
    private val evaluation=Runnable { evaluate() }
    private val refresh=Runnable { reconcile() }
    private val renewWake=object:Runnable { override fun run(){
        val lock=wake?:return
        if(phase==Phase.IDLE)return
        if(runCatching{ lock.acquire(2*60*60*1000L) }.isFailure){halt("wake_renew_failed",true);return}
        h.postDelayed(this,30*60*1000L)
    }}
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
            if(phase==Phase.WAITING) { log("audio active=$new phase=$phase"); timing.audio(now(),new); schedule() } else if(phase==Phase.SWIPING) log("audio active=$new phase=$phase")
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
    private fun readerWindow():Pair<Target,Boolean>? {
        if(!power.isInteractive||keyguard.isKeyguardLocked||shade())return null
        val ws=windowsHere()
        val w=ws.firstOrNull{it.isFocused&&it.type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}
            ?:ws.firstOrNull{it.isActive&&it.type==AccessibilityWindowInfo.TYPE_APPLICATION} ?:return null
        if(w.type!=AccessibilityWindowInfo.TYPE_APPLICATION || w.id!=readerId)return null
        val root=w.root?:return null
        if(root.packageName?.toString()!=DMM)return null
        val b=Rect().also(w::getBoundsInScreen)
        if(b.width()<200||b.height()<300)return null
        return Target(w.id,b) to menu(root,b)
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
        if(event.eventType!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.packageName?.toString()==DMM){
            if(!h.hasCallbacks(refresh))h.post(refresh)
        }
    }
    private fun reconcile(){
        if(!talkbackOn()){ if(phase!=Phase.IDLE)halt("talkback_off",false); blocked=false; return }
        if(!registered||blocked)return
        if(!power.isInteractive||keyguard.isKeyguardLocked){if(phase!=Phase.IDLE)halt("screen_locked",true);return}
        if(shade()){if(phase!=Phase.IDLE)hold("notification");return}
        val reader=readerWindow()
        if(reader==null){if(phase!=Phase.IDLE)halt("reader_window_lost",true);return}
        val (next,menuOpen)=reader
        if(phase!=Phase.IDLE && next!=target){halt("reader_window_changed",true);return}
        if(menuOpen){if(phase!=Phase.IDLE)hold("reader_menu");return}
        if(phase==Phase.HOLD){log("resume");resetWait();return}
        if(phase==Phase.IDLE && TurnPrefs.read(this).enabled)start(next)
    }
    private fun hold(reason:String){
        if(phase==Phase.HOLD)return
        gestureId++;phase=Phase.HOLD;h.removeCallbacks(evaluation);log("hold reason=$reason")
    }
    @Suppress("DEPRECATION")
    private fun start(t:Target){
        val p=TurnPrefs.read(this)
        prefs=p;target=t;generation++;turns=0;cancelled=0;phase=Phase.HOLD
        timing=Timing(p.gap,p.noSpeech)
        if(p.awake){
            val lock=power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,"EbookAutoTurn:reading").apply { setReferenceCounted(false) }
            if(runCatching { lock.acquire(2*60*60*1000L) }.isFailure){halt("wake_failed",true);return}
            wake=lock;h.postDelayed(renewWake,30*60*1000L)
        }
        showNotification("自動ページ送り動作中")
        log("start gap=${p.gap} noSpeechWait=${p.noSpeech}")
        resetWait()
    }
    private fun resetWait(){
        val state=syncAudio()?:return halt("audio_unavailable",true)
        playing=state;timing.reset(now(),state);phase=Phase.WAITING
        log("wait_reset audio=$state");schedule()
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
        generation++;gestureId++;h.removeCallbacks(evaluation);h.removeCallbacks(renewWake)
        phase=Phase.IDLE;target=null
        wake?.let { if(it.isHeld)runCatching{it.release()} };wake=null
        notifications.cancel(1);log("stop reason=$reason gestures=$turns blocked=$blocked")
    }
    private fun schedule(){
        h.removeCallbacks(evaluation)
        if(phase!=Phase.WAITING)return
        timing.next(now())?.let { h.postDelayed(evaluation,it.coerceAtLeast(1L)) }
    }
    private fun evaluate(){
        if(phase!=Phase.WAITING)return
        reconcile()
        if(phase!=Phase.WAITING)return
        val state=syncAudio()?:return halt("audio_unavailable",true)
        if(state!=playing){playing=state;timing.audio(now(),state)}
        val due=timing.due(now())
        if(due==Due.NONE){schedule();return}
        log("turn_due reason=$due")
        turn()
    }
    private fun turn(){
        val t=target?:return halt("no_target",true)
        val p=prefs?:return halt("no_prefs",true)
        // Last check, on the same main looper as all callbacks.
        if(!talkbackOn()){halt("talkback_off",false);return}
        val state=syncAudio()?:return halt("audio_unavailable",true)
        if(state){playing=true;timing.audio(now(),true);schedule();return}
        phase=Phase.SWIPING;h.removeCallbacks(evaluation)
        val b=t.bounds
        fun x(f:Float)=b.left+b.width()*f
        fun y(f:Float)=b.top+b.height()*f
        // Identical start time, duration and displacement for both contacts.
        val builder=GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
        for(f in listOf(.43f,.57f)){
            val path=Path().apply{moveTo(x(if(p.left).22f else .78f),y(f));lineTo(x(if(p.left).78f else .22f),y(f))}
            builder.addStroke(GestureDescription.StrokeDescription(path,0,120))
        }
        val g=generation;val id=++gestureId;val started=now()
        log("gesture_start swipe=${if(p.left)"right" else "left"} duration=120")
        val accepted=runCatching { dispatchGesture(builder.build(),object:GestureResultCallback(){
            override fun onCompleted(gestureDescription:GestureDescription?){
                if(g!=generation||id!=gestureId||phase!=Phase.SWIPING)return
                turns++;cancelled=0
                log("gesture_completed count=$turns elapsedMs=${now()-started}")
                // No page inspection. Start a fresh silence interval after
                // every completed gesture, including consecutive silent pages.
                resetWait();reconcile()
            }
            override fun onCancelled(gestureDescription:GestureDescription?){
                if(g!=generation||id!=gestureId||phase!=Phase.SWIPING)return
                log("gesture_cancelled elapsedMs=${now()-started}")
                // User touch can cancel injection. Do not immediately inject again.
                if(++cancelled>=3){halt("gesture_cancelled_repeated",true);return}
                resetWait();reconcile()
            }
        },h) }.getOrDefault(false)
        if(!accepted){halt("gesture_rejected",true);return}
        h.postDelayed({if(g==generation&&id==gestureId&&phase==Phase.SWIPING)halt("gesture_timeout",true)},2500)
    }
    override fun onInterrupt(){halt("interrupt",true)}
    override fun onDestroy(){
        halt("service_destroy",true);h.removeCallbacksAndMessages(null)
        if(registered)runCatching{audio.unregisterAudioPlaybackCallback(callback)}
        runCatching{contentResolver.unregisterContentObserver(settingsObserver)};runCatching{unregisterReceiver(screen)}
        instance=null;super.onDestroy()
    }
}
