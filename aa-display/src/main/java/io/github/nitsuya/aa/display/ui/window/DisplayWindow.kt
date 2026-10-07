package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.PowerManager
import android.os.ServiceManager
import android.provider.Settings
import android.view.*
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.WindowControllerBinding
import io.github.nitsuya.aa.display.databinding.WindowMirrorBinding
import io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.AADisplayLogger
import io.github.nitsuya.aa.display.util.AcquiredResource
import io.github.nitsuya.aa.display.util.tryOrNull
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.TipUtil
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.RomUtil
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


class DisplayWindow(
      private val mContext: Context
    , private val displayAdapter: AaVirtualDisplayAdapter
    , private var mDisplayWidth: Int
    , private var mDisplayHeight: Int
    , private var mDensityDpi: Int
): View.OnTouchListener {
    companion object {
        private const val TAG = "AADisplay_DisplayWindow"
        const val AA_DISCONNECT_DELAY_EXIT_TYPE = 1
        const val AA_DISCONNECT_SUSPEND_TYPE = 2
    }

    private var mControllerBinding: WindowControllerBinding? = null
    private lateinit var mControllerLayoutParams: WindowManager.LayoutParams

    private var mMirrorBinding: WindowMirrorBinding? = null
    private lateinit var mMirrorLayoutParams: WindowManager.LayoutParams

    private var mControllerStatus = false
    private var mMirrorStatus = false

    private var mAaDisconnectType = AA_DISCONNECT_DELAY_EXIT_TYPE

    private var mDisplayRatio = 1f
    private var mDisplayPower = true

    private var mDestroyJob: Job? = null
    private var mChangeAlphaCountDownTimer = object : CountDownTimer(5000,5000){
        override fun onFinish() {
            mControllerBinding?.apply {
                ViewCompat.animate(root).setDuration(500).alpha(0.5F).start()
            }
        }
        override fun onTick(millisUntilFinished: Long) {}
    }

    private var mScreenOffReplaceLockScreen = AADisplayConfig.ScreenOffReplaceLockScreen.get(CoreManagerService.config)
    private var mBlackOverlayFallback = AADisplayConfig.ScreenOffBlackOverlayFallback.get(CoreManagerService.config)
    private val mDelayDestroyTime = AADisplayConfig.DelayDestroyTime.get(CoreManagerService.config).let { value ->
        if (value < 0) 0 else value
    }

    private val isSupportInteractive = RomUtil.isMiui()
    private var interactiveMonitor = object: BroadcastReceiver(){
        private var monitor: PowerManager.WakeLock? = null
        private val wakeLock = AcquiredResource()
        private val receiver = AcquiredResource()
        private val powerHook = AcquiredResource()
        private val contextHooks = AcquiredResource()
        fun addAction(intentFilter: IntentFilter): IntentFilter {
            return intentFilter.apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        }
        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let { action ->
                onReceive(context, action)
            }
        }
        fun onReceive(context: Context, action: String){
            if(!isSupportInteractive) return
            try {
                when(action){
                    Intent.ACTION_SCREEN_ON -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 0)
                    Intent.ACTION_SCREEN_OFF -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1)
                    //miui
                    //Settings.Global.putInt(contentResolver, "ucar_casting_state", 1);
                    //Settings.Secure.putInt(contentResolver, "screen_project_in_screening", 1);
                    //Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1);
                }
            } catch (e : Throwable){}
        }
        fun init(){
            try {
                if(mScreenOffReplaceLockScreen){
                    powerHook.acquire({ AndroidHook.Power.hook() }, { AndroidHook.Power.unHook() })
                } else if(isSupportInteractive){
                    receiver.acquire({ mContext.registerReceiver(this, addAction(IntentFilter())) }, {
                        mContext.unregisterReceiver(this)
                    })
                    onReceive(mContext, if(Instances.powerManager.isInteractive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF)
                } else {
                    wakeLock.acquire({
                        val acquiredMonitor = monitor ?: Instances.powerManagerHidden.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                            "${BuildConfig.APPLICATION_ID}:Monitor", displayAdapter.mDisplayId
                        ).apply { setReferenceCounted(false) }.also { monitor = it }
                        if (!acquiredMonitor.isHeld) acquiredMonitor.acquire()
                    }, { monitor?.takeIf { it.isHeld }?.release() })
                }
                contextHooks.acquire({ AndroidHook.FuckAppUseApplicationContext.hook() }, {
                    AndroidHook.FuckAppUseApplicationContext.unHook()
                })
            } catch (error: Throwable) {
                release()
                throw error
            }
        }
        fun release(){
            fun report(name: String, error: Throwable?) {
                if (error != null) log(TAG, "auxiliary $name cleanup failed", error)
            }
            report("power hook", powerHook.release { AndroidHook.Power.unHook() })
            report("receiver", receiver.release {
                mContext.unregisterReceiver(this)
                onReceive(mContext, Intent.ACTION_SCREEN_ON)
            })
            report("wake lock", wakeLock.release { monitor?.takeIf { it.isHeld }?.release() })
            report("context hooks", contextHooks.release { AndroidHook.FuckAppUseApplicationContext.unHook() })
        }
    }



    init {
        runCatching {
            with(ContextThemeWrapper(mContext, R.style.Theme_AADisplay_Window)){
                mControllerBinding = WindowControllerBinding.inflate(LayoutInflater.from(this))
                mMirrorBinding = WindowMirrorBinding.inflate(LayoutInflater.from(this)).apply {
                    llRecentTask.setPadding(0, getStatusBarHeight(), 0, 0)
                }
            }
            doInit()
            interactiveMonitor.init()
            log(TAG, "DisplayWindow init: ScreenOffReplaceLockScreen=$mScreenOffReplaceLockScreen, " +
                "interactive=${Instances.powerManager.isInteractive}, displayId=${displayAdapter.mDisplayId}")
        }.onFailure {
            interactiveMonitor.release()
            close()
            mControllerBinding = null
            mMirrorBinding = null
            log(TAG, "init: new window failed may you forget reboot", it)
            TipUtil.showToast("new window failed\nmay you forget reboot")
        }
    }

    fun doInit() {
        initLayoutParams()
        mControllerBinding?.apply {
            root.allViews.forEach {
                it.setOnTouchListener(this@DisplayWindow)
            }
            if(mScreenOffReplaceLockScreen){
                ibExtinguish.visibility = View.VISIBLE
                ibExtinguish.setOnClickListener {
                    toggleDisplayPower(false)
                }
            }
            if(mDelayDestroyTime > 0){
                ibDisconnectType.visibility = View.VISIBLE
                ibDisconnectType.setOnClickListener {
                    mAaDisconnectType = if(mAaDisconnectType == AA_DISCONNECT_DELAY_EXIT_TYPE) {
                        ibDisconnectType.setImageResource(R.drawable.ic_motion_photos_pause_24)
                        AA_DISCONNECT_SUSPEND_TYPE
                    } else {
                        ibDisconnectType.setImageResource(R.drawable.ic_motion_photos_auto_24)
                        AA_DISCONNECT_DELAY_EXIT_TYPE
                    }
                }
            }
            tvDestroyTime.setOnClickListener {
                mDestroyJob?.cancel()
                tvDestroyTime.visibility = View.GONE
                ibExitDisplay.visibility = View.VISIBLE
            }
            ibMirrorDisplay.setOnClickListener {
                hideController()
                showMirror()
            }
            ibMirrorDisplay.setOnLongClickListener {
                collapseController()
                true
            }
            ibExpand.setOnClickListener {
                expandController()
            }
            // 默认折叠：投屏时手机上只留一个小展开按钮，减少悬浮窗对手机屏的占用；
            // 用户需要时点 ibExpand 展开。与长按 mirror 折叠走同一机制。
            collapseController()
        }
        mMirrorBinding?.apply {
            arrayOf(vHeightUmbrella1, vHeightUmbrella2).forEach {
                it.setOnClickListener {
                    hideMirror()
                    showController()
                }
            }
            ibBack.setOnClickListener {
                displayAdapter.onPressKey(KeyEvent.KEYCODE_BACK)
            }
            ibHome.setOnClickListener {
                displayAdapter.startLauncher()
            }
            ibRecentTask.setOnClickListener { v ->
                val tapCount = v.getTag(R.id.tap_count) as? Int ?: 0
                v.setTag(R.id.tap_count,   tapCount + 1)
                if(tapCount > 0) return@setOnClickListener
                runIO {
                    delay(300)
                    runMain {
                        when(v.getTag(R.id.tap_count) as? Int ?: 0){
                            1 -> toggleRecentTask()
                            2 -> displayAdapter.moveSecondTaskToFront()
                        }
                        v.setTag(R.id.tap_count,  0)
                    }
                }
            }
            tvVirtualDisplayInfo.text = "$mDisplayWidth*$mDisplayHeight,$mDensityDpi"
            rvRecentTaskLeft.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = DisplayRecyclerViewAdapter(this) {
                    hideRecentTask()
                }
            }
            rvRecentTaskRight.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = DisplayRecyclerViewAdapter(this){
                    hideRecentTask()
                }.apply {
                    otherAdapter = (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter).also {
                        it.otherAdapter = this@apply
                    }
                }
            }
            arrayOf(rvRecentTaskLeft, rvRecentTaskRight).forEach {
                it.setOnTouchListener { v, event ->
                    when(event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.setTag(R.id.drag_last_x, event.x)
                            v.setTag(R.id.drag_last_y, event.y)
                        }
                        MotionEvent.ACTION_UP -> {
                            if (v.id != 0
                                && abs((v.getTag(R.id.drag_last_x) as? Float ?: 0f) - event.x) <= 5
                                && abs((v.getTag(R.id.drag_last_y) as? Float ?: 0f) - event.y) <= 5) {
                                hideRecentTask()
                            }
                        }
                    }
                    return@setOnTouchListener false
                }
            }
        }
        updateDipslaySize()
        mMirrorBinding?.apply {
            svMirror.setOnTouchListener { _, event ->
                val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
                val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
                val oldCoords = MotionEvent.PointerCoords()
                for (i in 0 until event.pointerCount) {
                    val pointerProperty = MotionEvent.PointerProperties()
                    event.getPointerCoords(i, oldCoords)
                    event.getPointerProperties(i, pointerProperty)
                    pointerCoords[i] = MotionEvent.PointerCoords()
                    pointerCoords[i]!!.apply {
                        x = oldCoords.x / mDisplayRatio
                        y = oldCoords.y / mDisplayRatio
                    }
                    pointerProperties[i] = pointerProperty
                }
                val newEvent = MotionEvent.obtain(event.downTime, event.eventTime, event.action, event.pointerCount, pointerProperties, pointerCoords, event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
                displayAdapter.onTouch(newEvent)
                newEvent.recycle()
                true
            }
            svMirror.holder.addCallback(object: SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    displayAdapter.addMirror(svMirror.surfaceControl)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    displayAdapter.removeMirror(svMirror.surfaceControl)
                }
            })
        }
        showController()
    }

    private fun initLayoutParams() {
        mControllerLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        mMirrorLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            ,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun updateDipslaySize(){
        mControllerBinding?.apply {
            ibMirrorDisplay.visibility = View.VISIBLE
        }
        mDisplayRatio = Instances.windowManager.currentWindowMetrics.bounds.let {
            (it.width().toFloat() / mDisplayWidth).coerceAtMost(it.height().toFloat() / mDisplayHeight)
        }
        mMirrorBinding?.apply {
            svMirror.holder.setFixedSize(mDisplayWidth, mDisplayHeight)
            svMirror.updateLayoutParams{
                width = (mDisplayWidth * mDisplayRatio).toInt()
                height = (mDisplayHeight * mDisplayRatio).toInt()
            }
        }
    }

    suspend fun onResume(width: Int, height: Int){
        mDisplayWidth = width
        mDisplayHeight = height
        interactiveMonitor.init()
        mDestroyJob?.cancelAndJoin()
        updateDipslaySize()
        mAaDisconnectType = AA_DISCONNECT_DELAY_EXIT_TYPE
        mControllerBinding?.apply {
            tvDestroyTime.visibility = View.GONE
            ibExitDisplay.visibility = View.GONE
            ibDisconnectType.visibility = View.VISIBLE
        }
        showController()
    }

    suspend fun onDestroyPromptly() {
        interactiveMonitor.release()
        toggleDisplayPower(true)
        mDestroyJob?.cancelAndJoin()
        close()
    }
    suspend fun onDestroy(onDestroySucceed: () -> Unit) {
        interactiveMonitor.release()
        toggleDisplayPower(true)
        mDestroyJob?.cancelAndJoin()

        if(mDelayDestroyTime == 0 || mControllerBinding == null){
            close()
            onDestroySucceed()
            return
        }
        mControllerBinding?.apply {
            ibDisconnectType.visibility = View.GONE
            ibExitDisplay.setOnClickListener {
                close()
                onDestroySucceed()
            }
            if(mAaDisconnectType == AA_DISCONNECT_SUSPEND_TYPE){
                tvDestroyTime.visibility = View.GONE
                ibExitDisplay.visibility = View.VISIBLE
            } else {
                mDestroyJob = flow {
                    for (i in mDelayDestroyTime downTo 0) {
                        emit(i)
                        delay(1000)
                    }
                }.onStart {
                    ibExitDisplay.visibility = View.GONE
                    tvDestroyTime.visibility = View.VISIBLE
                }.onEach {
                    if(mControllerStatus){
                        tvDestroyTime.text = "${it}S"
                    }
                }.onCompletion {
                    if(it == null){
                        close()
                        onDestroySucceed()
                    }
                }.launchIn(CoroutineScope(Dispatchers.Main))
            }
        }
    }

    // --- Display Token Resolution (Android 17 compatible) ---

    private fun resolveLegacySurfaceControlToken(): android.os.IBinder? {
        return runCatching {
            val sc = SurfaceControl::class.java
            sc.methods.firstOrNull {
                it.name == "getInternalDisplayToken" &&
                    it.parameterCount == 0 &&
                    android.os.IBinder::class.java.isAssignableFrom(it.returnType)
            }?.invoke(null) as? android.os.IBinder
        }.onFailure {
            log(TAG, "resolveLegacySurfaceControlToken failed: ${it.message}")
        }.getOrNull()
    }

    private fun resolveDisplayTokenViaISurfaceComposer(): android.os.IBinder? {
        return runCatching {
            val sfBinder = android.os.ServiceManager.getService("SurfaceFlinger")
                ?: return null

            val stubClass = Class.forName("android.gui.ISurfaceComposer\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            val composer = asInterface.invoke(null, sfBinder)

            // Dump all ISurfaceComposer methods for diagnostics
            val methods = composer.javaClass.methods.joinToString("\n") { m ->
                "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}"
            }
            log(TAG, "ISurfaceComposer methods:\n$methods")
            AADisplayLogger.log(TAG, "ISurfaceComposer methods:\n$methods")

            val getPhysicalDisplayIds = composer.javaClass.methods.firstOrNull {
                it.name == "getPhysicalDisplayIds" &&
                    it.parameterCount == 0 &&
                    it.returnType == LongArray::class.java
            }
            log(TAG, "ISurfaceComposer.getPhysicalDisplayIds=${getPhysicalDisplayIds != null}")

            val getPhysicalDisplayToken = composer.javaClass.methods.firstOrNull {
                it.name == "getPhysicalDisplayToken" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE &&
                    android.os.IBinder::class.java.isAssignableFrom(it.returnType)
            }
            log(TAG, "ISurfaceComposer.getPhysicalDisplayToken=${getPhysicalDisplayToken != null}")

            if (getPhysicalDisplayIds == null || getPhysicalDisplayToken == null) {
                log(TAG, "ISurfaceComposer: required methods not found")
                return null
            }

            val ids = getPhysicalDisplayIds.invoke(composer) as LongArray
            log(TAG, "ISurfaceComposer physicalDisplayIds=${ids.joinToString()}")
            AADisplayLogger.log(TAG, "physicalDisplayIds=${ids.joinToString()}")

            val defaultPhysicalId = resolveDefaultPhysicalDisplayId()
            log(TAG, "defaultPhysicalId=$defaultPhysicalId")

            val orderedIds = if (defaultPhysicalId != null && ids.contains(defaultPhysicalId)) {
                longArrayOf(defaultPhysicalId) + ids.filter { it != defaultPhysicalId }
            } else {
                ids
            }

            for (id in orderedIds) {
                val token = getPhysicalDisplayToken.invoke(composer, id) as? android.os.IBinder
                log(TAG, "ISurfaceComposer token for physicalId=$id = $token")
                AADisplayLogger.log(TAG, "token for physicalId=$id = $token")
                if (token != null) return token
            }
            null
        }.onFailure {
            log(TAG, "resolveDisplayTokenViaISurfaceComposer failed: ${it.javaClass.simpleName}: ${it.message}")
            AADisplayLogger.log(TAG, "ISurfaceComposer failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    private fun resolveDefaultPhysicalDisplayId(): Long? {
        return runCatching {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = dmgClass.getDeclaredMethod("getInstance")
            val dmg = getInstance.invoke(null)

            val getDisplayInfo = dmgClass.getDeclaredMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            val info = getDisplayInfo.invoke(dmg, Display.DEFAULT_DISPLAY)

            val uniqueIdField = info.javaClass.getDeclaredField("uniqueId").apply { isAccessible = true }
            val uniqueId = uniqueIdField.get(info) as? String
            log(TAG, "default display uniqueId=$uniqueId")

            uniqueId
                ?.substringAfter("local:", missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
                ?.toULongOrNull()
                ?.toLong()
        }.onFailure {
            log(TAG, "resolveDefaultPhysicalDisplayId failed: ${it.message}")
        }.getOrNull()
    }

    private fun resolveInternalDisplayToken(): android.os.IBinder? {
        return resolveLegacySurfaceControlToken()
            ?: resolveDisplayTokenViaISurfaceComposer()
    }

    private var mCachedDisplayToken: android.os.IBinder? = null

    private fun getDisplayToken(): android.os.IBinder? {
        mCachedDisplayToken?.let { return it }
        val token = resolveInternalDisplayToken()
        if (token != null) {
            mCachedDisplayToken = token
            log(TAG, "getDisplayToken resolved: $token")
        } else {
            log(TAG, "getDisplayToken: all methods failed")
        }
        return token
    }

    // --- Black Overlay Fallback ---

    private var mBlackoutView: View? = null

    private fun showBlackoutOverlay() {
        if (mBlackoutView != null) return
        val view = View(mContext).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            screenBrightness = 0f
            buttonBrightness = 0f
        }
        runCatching {
            Instances.windowManager.addView(view, params)
            mBlackoutView = view
            mDisplayPower = false
            log(TAG, "showBlackoutOverlay: fallback active")
        }.onFailure {
            log(TAG, "showBlackoutOverlay failed", it)
        }
    }

    private fun hideBlackoutOverlay() {
        val view = mBlackoutView ?: return
        runCatching {
            Instances.windowManager.removeView(view)
        }.onFailure {
            log(TAG, "hideBlackoutOverlay failed", it)
        }
        mBlackoutView = null
        mDisplayPower = true
    }

    // --- toggleDisplayPower ---

    fun toggleDisplayPower(displayPower: Boolean = !mDisplayPower): Boolean {
        if (!mScreenOffReplaceLockScreen) {
            runCatching {
                Instances.powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "${BuildConfig.APPLICATION_ID}:wakeup"
                ).apply { acquire(); release() }
            }
            return true
        }

        // Turning ON: always succeed
        if (displayPower) {
            hideBlackoutOverlay()
            val token = getDisplayToken()
            if (token != null) {
                runCatching {
                    SurfaceControlHidden.setDisplayPowerMode(token, SurfaceControlHidden.POWER_MODE_NORMAL)
                    log(TAG, "toggleDisplayPower: NORMAL success")
                    AADisplayLogger.log(TAG, "toggleDisplayPower: NORMAL success")
                }.onFailure {
                    log(TAG, "toggleDisplayPower: setDisplayPowerMode NORMAL failed", it)
                    AADisplayLogger.log(TAG, "toggleDisplayPower: NORMAL failed: ${it.message}")
                }
            }
            mDisplayPower = true
            return true
        }

        // Turning OFF: try SurfaceControl first
        val token = getDisplayToken()
        if (token != null) {
            runCatching {
                SurfaceControlHidden.setDisplayPowerMode(token, SurfaceControlHidden.POWER_MODE_OFF)
                mDisplayPower = false
                log(TAG, "toggleDisplayPower: OFF success via SurfaceControl")
                AADisplayLogger.log(TAG, "toggleDisplayPower: OFF success via SurfaceControl")
                return true
            }.onFailure {
                log(TAG, "toggleDisplayPower: setDisplayPowerMode OFF failed", it)
                AADisplayLogger.log(TAG, "toggleDisplayPower: OFF failed: ${it.message}")
            }
        }

        // Fallback: black overlay (only if enabled in settings)
        if (mBlackOverlayFallback) {
            showBlackoutOverlay()
            return mBlackoutView != null
        }

        log(TAG, "toggleDisplayPower: all methods failed, black overlay disabled")
        AADisplayLogger.log(TAG, "toggleDisplayPower: all methods failed, overlay disabled")
        return false
    }

    private fun showController(){
        if(mMirrorStatus){
            hideMirror()
        }
        if(mControllerStatus) return
        mControllerBinding?.apply {
            runCatching {
                Instances.windowManager.addView(root, mControllerLayoutParams)
                mControllerStatus = true
            }.onFailure { log(TAG, "auxiliary controller view unavailable", it) }
            mChangeAlphaCountDownTimer.start()
        }
    }
    private fun hideController(){
        if(!mControllerStatus) return
        mControllerBinding?.apply {
            tryOrNull { Instances.windowManager.removeView(root) }
            mControllerStatus = false
        }
    }

    private fun showMirror(){
        if(mMirrorStatus) return
        hideRecentTask()
        mMirrorBinding?.apply {
            runCatching {
                Instances.windowManager.addView(root, mMirrorLayoutParams)
                mMirrorStatus = true
            }.onFailure { log(TAG, "auxiliary mirror view unavailable", it) }
        }
    }
    private fun hideMirror(){
        if(!mMirrorStatus) return
        mMirrorBinding?.apply {
            tryOrNull { Instances.windowManager.removeView(root) }
            mMirrorStatus = false
        }
    }

    private fun toggleRecentTask(){
        mMirrorBinding?.apply {
            if(llRecentTask.visibility == View.VISIBLE){
                hideRecentTask()
                return
            }
            llRecentTask.visibility = View.VISIBLE
            vHeightUmbrella1.visibility = View.GONE
            runIO {
                displayAdapter.getRecentTask().also {recentTask ->
                    runMain {
                        (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.virtualDisplay)
                        (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.mainDisplay)
                    }
                }
            }
        }
    }
    private fun hideRecentTask(){
        mMirrorBinding?.apply {
            llRecentTask.visibility = View.GONE
            vHeightUmbrella1.visibility = View.VISIBLE
            (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.clearItem()
            (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.clearItem()
        }
    }

    private fun expandController(){
        mControllerBinding?.apply {
            llPanel.visibility = View.VISIBLE
            ibExpand.visibility = View.GONE
        }
    }
    private fun collapseController(){
        mControllerBinding?.apply {
            llPanel.visibility = View.GONE
            ibExpand.visibility = View.VISIBLE
        }
    }

    private fun close() {
        mChangeAlphaCountDownTimer.cancel()
        hideMirror()
        hideController()
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId: Int = mContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = mContext.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return mControllerBinding?.run {
            var isDrag = false
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.apply {
                        setTag(R.id.is_drag, false)
                        setTag(R.id.drag_last_x, event.rawX)
                        setTag(R.id.drag_last_y, event.rawY)
                        setTag(R.id.drag_distance, 0f)
                    }
                    mChangeAlphaCountDownTimer.cancel()
                    ViewCompat.animate(root).setDuration(200).alpha(0.9f).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    val dragMoveX = event.rawX
                    val dragMoveY = event.rawY
                    val dragLastX = (v.getTag(R.id.drag_last_x) as? Float ?: 0f)
                    val dragLastY = (v.getTag(R.id.drag_last_y) as? Float ?: 0f)
                    if(!isDrag){
                        val dragSumDistance = abs(sqrt((dragMoveX - dragLastX).pow(2) + (dragMoveY - dragLastY).pow(2))) + (v.getTag(R.id.drag_distance) as? Float ?: 0f)
                        if(dragSumDistance > 5f){
                            isDrag = true
                            v.apply {
                                setTag(R.id.is_drag, true)
                                onTouchEvent(MotionEvent.obtain(event).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                })
                            }
                        } else {
                            v.setTag(R.id.drag_distance, dragSumDistance)
                        }
                    }
                    Instances.windowManager.updateViewLayout(root, mControllerLayoutParams.apply {
                        x = (dragMoveX - dragLastX + x).toInt()
                        y = (dragMoveY - dragLastY + y).toInt()
                    })
                    v.apply {
                        setTag(R.id.drag_last_x, dragMoveX)
                        setTag(R.id.drag_last_y, dragMoveY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    Instances.windowManager.updateViewLayout(root, mControllerLayoutParams.apply {
                        val displayMetrics = mContext.resources.displayMetrics
                        x = if(event.rawX > (displayMetrics.widthPixels / 2)) displayMetrics.widthPixels - root.measuredWidth else 0
                        if (y < 0) {
                            y = 0
                        } else {
                            var height = displayMetrics.heightPixels - root.measuredHeight
                            if(y > height) {
                                y = height
                            }
                        }
                    })
                    mChangeAlphaCountDownTimer.start()
                }
            }
            return if(isDrag) true else v.onTouchEvent(event)
        } ?: false
    }



}
