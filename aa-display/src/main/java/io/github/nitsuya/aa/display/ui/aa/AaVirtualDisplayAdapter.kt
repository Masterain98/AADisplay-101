package io.github.nitsuya.aa.display.ui.aa

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ITaskStackListener
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.*
import android.view.*
import android.window.TaskSnapshot
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.service.ShellManagerService
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.ProjectionCompatibility
import io.github.nitsuya.aa.display.util.ProjectionLifecycle
import io.github.nitsuya.aa.display.util.DisplayPolicyRequests
import io.github.nitsuya.aa.display.util.InjectionDiagnostics
import io.github.nitsuya.aa.display.util.argTypes
import io.github.nitsuya.aa.display.util.args
import io.github.nitsuya.aa.display.util.getObjectAs
import io.github.nitsuya.aa.display.util.invokeMethod
import io.github.nitsuya.aa.display.util.newInstance
import io.github.nitsuya.aa.display.util.tryOrNull
import io.github.nitsuya.aa.display.service.IShellManager
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.TipUtil
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.template.bases.runMain


class AaVirtualDisplayAdapter(
      private val context: Context
    , private val config: SharedPreferences?
    , private val onReady: (suspend AaVirtualDisplayAdapter.(it:AaVirtualDisplayAdapter) -> Unit)
    , private val onFailure: (AaVirtualDisplayAdapter) -> Unit
) {
    companion object {
        const val TAG = "AADisplay_AaVirtualDisplayAdapter"

        /** Package names to ignore in recent task list */
        private val IGNORE_RECENT_PACKAGE = setOf(
            BuildConfig.APPLICATION_ID,
            "com.android.launcher3"
        )

        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
    }

    /** Default launch package name: the app package to launch when virtual display is created, can be null */
    private var mLauncherPackage = AADisplayConfig.LauncherPackage.get(CoreManagerService.config)
    
    /** Home package name: the app package to launch when Home key is pressed */
    private var mHomePackage = AADisplayConfig.HomePackage.get(CoreManagerService.config)
    
    /** Task ID of the Home package */
    private var mHomeTaskId: Int? = null
    
    /** Task ID of the default launch package */
    private var mLauncherPackageTaskId: Int? = null
    private val mTaskStackListener = TaskStackListener()
    @Volatile var mDisplayId = Display.INVALID_DISPLAY
        private set
    var mDensityDpi: Int = 0

    private val mTransaction = SurfaceControl.Transaction()
    private var mSurfaceControls = mutableMapOf<SurfaceControl, SurfaceControl>()
    public lateinit var mVirtualDisplay: VirtualDisplay
    private var mDisplayWindowManager: WindowManager? = null
    private var forceViewAdded = false
    private var serviceBound = false
    private var listenerRegistered = false
    private var shellPrepared = false
    private val lifecycle = ProjectionLifecycle()
    private val inputLock = Any()
    private val injectionDiagnostics = InjectionDiagnostics(runCatching {
        AADisplayConfig.DebugInputInjectionLog.get(config)
    }.getOrDefault(false))
    private val navigationDiagnostics = InjectionDiagnostics(false)
    private val navigationTouchMode by lazy {
        runCatching {
            ProjectionCompatibility.select(IWindowManager::class.java.methods.asIterable(), "setInTouchMode",
                Void.TYPE, java.lang.Boolean.TYPE, Integer.TYPE).method
        }.getOrNull()
    }
    val isReady: Boolean get() = lifecycle.coreReady && !lifecycle.destroyed
    private var mForceView: View? = null

    private var mShellManager: IShellManager? = null
    private var mServiceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            runMain {
                if (lifecycle.destroyed) return@runMain
                runCatching {
                    // A service restart refreshes the cleanup endpoint without repeating preparation.
                    mShellManager = IShellManager.Stub.asInterface(service)
                    if (!lifecycle.beginPreparation()) return@runCatching
                    shellPrepared = mShellManager != null
                    mShellManager?.createVirtualDisplayBefore()
                    if (!lifecycle.destroyed) onReady(this@AaVirtualDisplayAdapter)
                }.onFailure {
                    log(TAG, "display preparation failed", it)
                    if (!isReady) {
                        onFailure(this@AaVirtualDisplayAdapter)
                        onDestroy()
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            runMain { if (!lifecycle.destroyed) mShellManager = null }
        }
    }

    fun initialize() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (lifecycle.destroyed || serviceBound) return
        serviceBound = runCatching {
            context.bindService(Intent(ShellManagerService::class.java.name).apply {
                setPackage(BuildConfig.APPLICATION_ID)
            }, mServiceConnection, AppCompatActivity.BIND_AUTO_CREATE)
        }.onFailure { log(TAG, "shell binding failed", it) }.getOrDefault(false)
        if (!serviceBound) {
            onFailure(this)
            onDestroy()
        }
    }

    fun setSurface(surface: Surface?){
        if (isReady) mVirtualDisplay.surface = surface
    }

    @SuppressLint("WrongConstant")
    fun onConnected(width: Int, height: Int, densityDpi: Int, onVirtualDisplayCreated: ((Int) -> Unit)) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (lifecycle.destroyed || lifecycle.coreReady) return
        val indent = Binder.clearCallingIdentity()
        val displayName = "AADisplay-${System.currentTimeMillis()}"
        try {
            val flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                or VIRTUAL_DISPLAY_FLAG_TRUSTED
                or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
                or VIRTUAL_DISPLAY_FLAG_OWN_FOCUS)
            mVirtualDisplay = AndroidHook.DisplayGroupIsolation.duringCreation(displayName) {
                createVirtualDisplay(displayName, width, height, densityDpi, flags)
            }
        } catch (error: Throwable) {
            log(TAG, "core virtual display creation failed; session stopped", error)
            onFailure(this)
            onDestroy()
            return
        } finally {
            Binder.restoreCallingIdentity(indent)
        }
        mDisplayId = mVirtualDisplay.display.displayId
        if (mDisplayId <= Display.DEFAULT_DISPLAY) {
            log(TAG, "core virtual display returned invalid projection display; session stopped")
            onFailure(this)
            onDestroy()
            return
        }
        mDensityDpi = densityDpi
        lifecycle.coreCreated()
        reportDisplayGroups()
        applyDisplayPolicies()
        runCatching {
            mDisplayWindowManager = context.createDisplayContext(mVirtualDisplay.display)
                .createWindowContext(mVirtualDisplay.display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                .getSystemService(WindowManager::class.java)
            val windowManager = mDisplayWindowManager ?: error("projection WindowManager unavailable")
            val forceView = View(context).also { mForceView = it }
            windowManager.addView(
                forceView,
                WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSPARENT
                ).also {
                    it.gravity = Gravity.START or Gravity.TOP
                    it.screenOrientation = if (width > height) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            )
            forceViewAdded = true
        }.onFailure { log(TAG, "auxiliary projection view unavailable; core display remains usable", it) }
        runCatching {
            Instances.iActivityTaskManager.registerTaskStackListener(mTaskStackListener)
            listenerRegistered = true
        }.onFailure { log(TAG, "task tracking unavailable", it) }
        // When virtual display is created, launch default package first (if configured)
        runCatching { if(mLauncherPackage != null) {
            startDefaultPackage()
        } else {
            // If no default package is configured, launch Home package as fallback
            startHomeLauncher()
        } }.onFailure { log(TAG, "initial launcher unavailable", it) }
        if (lifecycle.takeReadyNotification()) onVirtualDisplayCreated(mDisplayId)
    }

    private fun applyDisplayPolicies() {
        // Resolve optional methods against the runtime framework interface, never the module's stubs.
        val decorsSetter = runCatching {
            ProjectionCompatibility.select(IWindowManager::class.java.methods.asIterable(),
                "setShouldShowSystemDecors", Void.TYPE, Integer.TYPE, java.lang.Boolean.TYPE).method
        }.getOrNull()
        fun report(name: String, outcome: DisplayPolicyRequests.Outcome) {
            log(TAG, "display policy $name: displayId=$mDisplayId, request=${outcome.requested}, " +
                "set=${if (!outcome.setterAvailable) "unavailable" else if (outcome.set) "applied" else "failed"}, " +
                "actual=${outcome.actual ?: "unknown"}")
        }
        val ime = AADisplayConfig.DisplayImePolicy.get(config)
        report("IME", DisplayPolicyRequests.apply(ime, { Instances.iWindowManager.setDisplayImePolicy(mDisplayId, ime) }) {
            Instances.iWindowManager.getDisplayImePolicy(mDisplayId)
        })
        report("insecureKeyguard", DisplayPolicyRequests.apply(true, { Instances.iWindowManager.setShouldShowWithInsecureKeyguard(mDisplayId, true) }) {
            Instances.iWindowManager.shouldShowWithInsecureKeyguard(mDisplayId)
        })
        report("systemDecors", DisplayPolicyRequests.apply(false,
            decorsSetter?.let { method -> { method.invoke(Instances.iWindowManager, mDisplayId, false); Unit } }) {
            Instances.iWindowManager.shouldShowSystemDecors(mDisplayId)
        })
    }

    private fun reportDisplayGroups() {
        fun readGroup(display: Display?): Int? = runCatching {
            if (display == null) return@runCatching null
            val infoClass = Class.forName("android.view.DisplayInfo", false, Display::class.java.classLoader)
            val read = ProjectionCompatibility.select(Display::class.java.methods.asIterable(),
                "getDisplayInfo", java.lang.Boolean.TYPE, infoClass).method ?: return@runCatching null
            val info = infoClass.getConstructor().newInstance()
            if (read.invoke(display, info) != true) return@runCatching null
            val groupId = infoClass.getField("displayGroupId")
            if (groupId.type != Integer.TYPE) return@runCatching null
            groupId.getInt(info)
        }.getOrNull()
        val projectionGroup = runCatching { readGroup(mVirtualDisplay.display) }.getOrNull()
        val defaultGroup = runCatching { readGroup(Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)) }.getOrNull()
        log(TAG, "display groups: projection=${projectionGroup ?: "unknown"}, default=${defaultGroup ?: "unknown"}, " +
            "relationship=${ProjectionCompatibility.groupRelationship(projectionGroup, defaultGroup)}; power isolation unverified")
    }

    private fun createVirtualDisplay(
        displayName: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int,
    ): VirtualDisplay {
        // Resolve the newer config API reflectively so Android 12/13 can keep the original path.
        val configuredCreation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                val frameworkLoader = DisplayManager::class.java.classLoader
                val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig", false, frameworkLoader)
                val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder", false, frameworkLoader)
                val createMethod = DisplayManager::class.java.getMethod("createVirtualDisplay", configClass)
                check(createMethod.returnType == VirtualDisplay::class.java)
                val builder = builderClass.newInstance(
                    args(displayName, width, height, densityDpi),
                    argTypes(String::class.java, Integer.TYPE, Integer.TYPE, Integer.TYPE),
                )
                builder.invokeMethod("setFlags", args(flags), argTypes(Integer.TYPE))
                builder.invokeMethod("setIgnoreActivitySizeRestrictions", args(true), argTypes(java.lang.Boolean.TYPE))
                // Surface stays null until setSurface(), just as in the original creation path.
                val displayConfig = builder.invokeMethod("build")
                    ?: throw IllegalStateException("VirtualDisplayConfig.Builder.build returned null")
                val enabled = displayConfig.invokeMethod("isIgnoreActivitySizeRestrictions") as? Boolean
                    ?: throw IllegalStateException("VirtualDisplayConfig size restriction state is not boolean")
                log(TAG, "VirtualDisplay size policy: sdk=${Build.VERSION.SDK_INT}, " +
                    "ignoreActivitySizeRestrictions=" + (if (enabled) "enabled in config" else "disabled by system feature flag"))
                createMethod to displayConfig
            }.onFailure { error ->
                if (error is ClassNotFoundException || error is NoSuchMethodException) {
                    log(TAG, "VirtualDisplay size policy: sdk=${Build.VERSION.SDK_INT}, " +
                        "API unavailable; using original creation")
                } else {
                    log(TAG, "VirtualDisplay size policy: configuration failed; using original creation", error)
                }
            }.getOrNull()
        } else {
            log(TAG, "VirtualDisplay size policy: sdk=${Build.VERSION.SDK_INT}, " +
                "API unavailable; using original creation")
            null
        }

        // Only preparation may fall back. Never retry a failed display creation with another API.
        return if (configuredCreation != null) {
            configuredCreation.first.invoke(Instances.displayManager, configuredCreation.second) as? VirtualDisplay
                ?: throw IllegalStateException("Configured virtual display creation returned null")
        } else {
            Instances.displayManager.createVirtualDisplay(displayName, width, height, densityDpi, null, flags)
                ?: throw IllegalStateException("Virtual display creation returned null")
        }
    }

    fun onReconnected(width: Int, height: Int, densityDpi: Int){
        if (!isReady) return
        mVirtualDisplay.resize(width, height, densityDpi)
        mDensityDpi = densityDpi
        // Reload configuration
        mLauncherPackage = AADisplayConfig.LauncherPackage.get(CoreManagerService.config)
        mHomePackage = AADisplayConfig.HomePackage.get(CoreManagerService.config)
    }

    fun onDestroy() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val displayId: Int
        synchronized(inputLock) {
            if (!lifecycle.destroy()) return
            displayId = mDisplayId
            mDisplayId = Display.INVALID_DISPLAY
        }
        if (listenerRegistered) {
            listenerRegistered = false
            tryOrNull { Instances.iActivityTaskManager.unregisterTaskStackListener(mTaskStackListener) }
        }
        if (displayId > Display.DEFAULT_DISPLAY) tryOrNull {
            Instances.iActivityTaskManager.apply {
                getAllRootTaskInfosOnDisplay(displayId).forEach{ task ->
                    removeTask(task.taskId)
                    task.topActivity?.packageName.let { pkgName ->
                        Instances.activityManagerHidden.forceStopPackageAsUser(pkgName, task.getObjectAs("userId", Int::class.javaPrimitiveType) as Int)
                    }
                }
            }
        }
        if (serviceBound) {
            serviceBound = false
            tryOrNull { context.unbindService(mServiceConnection) }
        }
        mSurfaceControls.values.forEach { tryOrNull { it.release() } }
        mSurfaceControls.clear()
        if (forceViewAdded) {
            forceViewAdded = false
            tryOrNull { mForceView?.let { mDisplayWindowManager?.removeView(it) } }
        }
        mForceView = null
        mDisplayWindowManager = null
        if (::mVirtualDisplay.isInitialized) tryOrNull { mVirtualDisplay.release() }
        tryOrNull { mTransaction.close() }
        mDensityDpi = 0
        if (shellPrepared) tryOrNull { mShellManager?.destroyVirtualDisplayAfter() }
        shellPrepared = false
        mShellManager = null
        navigationDiagnostics.finish()
        injectionDiagnostics.finish()?.let {
            log(TAG, "input session ended: count=${it.count}, failed=${it.failed}, averageNanos=${it.averageNanos}, maxNanos=${it.maxNanos}")
        }
    }

    fun onTouch(event: MotionEvent) = injectInputEvent(event)

    fun onPressKey(action: Int) {
        synchronized(inputLock) {
            if (!isReady || mDisplayId <= Display.DEFAULT_DISPLAY) return
            if (ProjectionCompatibility.needsNavigationTouchMode(Build.VERSION.SDK_INT, action, mDisplayId)) {
                runCatching {
                    val setter = navigationTouchMode ?: error("per-display setInTouchMode unavailable")
                    setter.invoke(Instances.iWindowManager, false, mDisplayId)
                }.onFailure {
                    if (navigationDiagnostics.shouldWarn(System.nanoTime())) log(TAG, "navigation touch mode unavailable; keys still injected", it)
                }
            }
            val uptimeMillis = SystemClock.uptimeMillis()
            injectInputEvent(KeyEvent(uptimeMillis, uptimeMillis, KeyEvent.ACTION_DOWN, action, 0).apply {
                source = InputDevice.SOURCE_KEYBOARD
            })
            injectInputEvent(KeyEvent(uptimeMillis, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, action, 0).apply {
                source = InputDevice.SOURCE_KEYBOARD
            })
        }
    }

    fun addMirror(surfaceControl: SurfaceControl){
        synchronized(inputLock) {
            if (!isReady || mDisplayId <= Display.DEFAULT_DISPLAY) return
            val sc = SurfaceControl::class.java.newInstance(args(), argTypes()) as SurfaceControl
            try{
                if(!Instances.iWindowManager.mirrorDisplay(mDisplayId, sc)){
                    sc.release()
                    return
                }
            } catch (e: Throwable){
                TipUtil.showToast("addMirror error: ${e.message}")
                log(TAG, "addMirror error:", e)
                sc.release()
                return
            }
            if (!sc.isValid) {
                sc.release()
                TipUtil.showToast("addMirror not Valid")
                return
            }
            try {
                mTransaction
                    .apply {
                        invokeMethod("show", args(sc), argTypes(SurfaceControl::class.java))
                    }
                    .reparent(sc, surfaceControl)
                    .apply()
            } catch (e: Throwable){
                log(TAG, "addMirror show error:", e)
                sc.release()
                return
            }
            mSurfaceControls.put(surfaceControl, sc)?.release()
        }
    }

    fun removeMirror(surfaceControl: SurfaceControl){
        synchronized(inputLock) {
            if (!isReady) return
            mSurfaceControls.remove(surfaceControl)?.also {sc ->
                try {
                    mTransaction.apply {
                        invokeMethod("remove", args(sc), argTypes(SurfaceControl::class.java))
                    }.apply()
                } finally {
                    sc.release()
                }
            }
        }
    }

    fun getRecentTask(): RecentTask {
        return try{
            RecentTask(
                recentTaskInfo(0),
                if(mDisplayId == Display.INVALID_DISPLAY) emptyList() else recentTaskInfo(mDisplayId)
            )
        } catch (e: Throwable){
            log(TAG, "RecentTask Exception", e)
            RecentTask(emptyList(), emptyList())
        }
    }

    /**
     * Launch the app corresponding to Home key (usually the launcher)
     * Called when user presses Home key
     */
    fun startLauncher(){
        startHomeLauncher()
    }

    /**
     * Launch the app corresponding to Home package name
     * If the app is already running, bring it to front; otherwise start a new instance
     */
    private fun startHomeLauncher(){
        if(mHomePackage == null) return
        if(mHomeTaskId != null){
            moveTaskToFront(mHomeTaskId!!)
        } else {
            startActivity(mHomePackage!!, 0)
        }
    }

    /**
     * Launch the app corresponding to default launch package name
     * Called when virtual display is created. If the app is already running, bring it to front; otherwise start a new instance
     */
    private fun startDefaultPackage(){
        if(mLauncherPackage == null) return
        if(mLauncherPackageTaskId != null){
            moveTaskToFront(mLauncherPackageTaskId!!)
        } else {
            startActivity(mLauncherPackage!!, 0)
        }
    }

    fun startActivity(packageName: String, userId: Int): Boolean{
        try {
            if(mDisplayId == Display.INVALID_DISPLAY) return false
            val componentName = if(packageName.contains("/")){
                val packageComponent = packageName.split("/", limit = 2)
                if(packageComponent.size != 2) return false
                ComponentName.createRelative(packageComponent[0], packageComponent[1])
            } else {
                Instances.packageManager.getLaunchIntentForPackage(packageName)?.component ?: return false
            }
            context.invokeMethod(
                "startActivityAsUser",
                args(
                    Intent().apply {
                        //addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        component = componentName
                        `package` = component?.packageName ?: return false
                        action = Intent.ACTION_VIEW
                        putExtra("displayId", mDisplayId)
                        //putExtra("isUcarMode", true)
                        setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    ActivityOptions.makeBasic().apply {
                        launchDisplayId = mDisplayId
                        this.invokeMethod("setCallerDisplayId", args(mDisplayId), argTypes(Integer.TYPE))
                    }.toBundle(),
                    UserHandle::class.java.newInstance(
                        args(userId),
                        argTypes(Integer.TYPE)
                    )
                ), argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
            )
            return true
      } catch (e: Throwable) {
          log(TAG, "startActivity error:", e)
          return false
      }
    }

    fun startTaskId(taskId: Int?, packageName: String, userId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        if(taskId == null){
            return startActivity(packageName, userId)
        }
        return try {
            moveTaskId(taskId, true)
        } catch (e: Throwable){
            log(TAG,"startTaskId error:", e)
            startActivity(packageName, userId)
        }
    }

    fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, if(isVirtualDisplay) mDisplayId else 0)
        } catch (e: Throwable){
            log(TAG,"moveTaskId error:", e)
        }
        return try {
            moveTaskToFront(taskId)
        } catch (e: Throwable){
            log(TAG,"moveTaskId error:", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun moveTaskToFront(taskId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            Instances.activityManager.moveTaskToFront(taskId, 0)
            true
        } catch (e: Throwable){
            log(TAG,"moveTaskToFront error:", e)
            false
        }
    }

    fun removeTask(taskId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            Instances.iActivityTaskManager.removeTask(taskId)
            true
        } catch (e: Throwable){
            log(TAG,"removeTask error:", e)
            false
        }
    }

    /**
     * Move the second task to front
     * If the second task is the Home package app, move the third task to front instead
     */
    fun moveSecondTaskToFront(){
        if(mDisplayId == Display.INVALID_DISPLAY)
            return
        val allRootTaskInfosOnDisplay = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId).filter { i -> i.topActivity != null }
        if(allRootTaskInfosOnDisplay.size < 2){
            return
        }
        moveTaskToFront(
            if(allRootTaskInfosOnDisplay.size == 2 || allRootTaskInfosOnDisplay[1].topActivity!!.packageName != mHomePackage){
                allRootTaskInfosOnDisplay[1].taskId
            } else {
                allRootTaskInfosOnDisplay[2].taskId
            }
        )
    }

    private fun injectInputEvent(event: InputEvent) {
        synchronized(inputLock) {
            if (!isReady || mDisplayId <= Display.DEFAULT_DISPLAY) return
            val started = System.nanoTime()
            var ok = false
            try {
                event.invokeMethod("setDisplayId", args(mDisplayId), argTypes(Integer.TYPE))
                ok = Instances.iInputManager.injectInputEvent(event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)
                if (!ok && injectionDiagnostics.shouldWarn(System.nanoTime())) log(TAG, "input injection rejected: displayId=$mDisplayId")
            } catch (error: Throwable) {
                if (injectionDiagnostics.shouldWarn(System.nanoTime())) log(TAG, "input injection failed: displayId=$mDisplayId", error)
            } finally {
                injectionDiagnostics.record(ok, System.nanoTime() - started)
            }
        }
    }

    /**
     * Get recent task list for the specified display
     * Filter out ignored package names and Home package app
     */
    private fun recentTaskInfo(displayId: Int): List<RecentTaskInfo> {
        val allRootTaskInfosOnDisplay = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        log(TAG, "RecentTask $displayId, ${allRootTaskInfosOnDisplay.size}")
        return allRootTaskInfosOnDisplay
            .map { taskInfo ->
                val topActivity = taskInfo.topActivity ?: return@map null
                // Filter out ignored package names and Home package
                if(IGNORE_RECENT_PACKAGE.contains(topActivity.packageName) || mHomePackage == topActivity.packageName) {
                    return@map null
                }

                var taskDescription = taskInfo.taskDescription
                if(taskDescription == null){
                    taskDescription = Instances.iActivityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@map null
                }

                var icon = runCatching { taskDescription.icon }.getOrNull()
                if (icon == null) {
                    icon = Instances.packageManager.getActivityIcon(topActivity).toBitmap()
                }
                var label = taskDescription.label
                if(label == null){
                    label = Instances.packageManager.getActivityInfo(topActivity, 0).loadLabel(Instances.packageManager).toString()
                }

                val packageName = topActivity.packageName

                var snapshot: Bitmap? = runCatching {
                    try {
                        if (Build.VERSION.SDK_INT >= 34) {//14+
                            Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true, true)
                        } else {
                            Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                        }
                    } catch (e: Throwable){
                        Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                    }?.let { taskSnapshot ->
                        taskSnapshot.hardwareBuffer?.let { buffer ->
                            Bitmap.wrapHardwareBuffer(buffer, taskSnapshot.colorSpace)
                        }
                    }
                }.let { result ->
                    if(result.isFailure){
                        log(TAG,"load snapshot exception", result.exceptionOrNull())
                        null
                    } else {
                        result.getOrNull()
                    }
                }

                log(TAG, "RecentTask: $packageName, ${taskInfo.taskId}, snapshot:${snapshot != null}")

                RecentTaskInfo(
                    icon,
                    taskInfo.taskId,
                    label,
                    snapshot
                )
            }
            .filterNotNull()
    }


    inner class TaskStackListener : ITaskStackListener.Stub() {
        override fun onTaskStackChanged() {}
        override fun onActivityPinned(packageName: String?, userId: Int, taskId: Int, stackId: Int) {}
        override fun onActivityUnpinned() {}
        override fun onActivityRestartAttempt(task: ActivityManager.RunningTaskInfo?, homeTaskVisible: Boolean, clearedTask: Boolean, wasVisible: Boolean) {}
        override fun onActivityForcedResizable(packageName: String?, taskId: Int, reason: Int) {}
        override fun onActivityDismissingDockedTask() {}
        override fun onActivityLaunchOnSecondaryDisplayFailed(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {}
        override fun onActivityLaunchOnSecondaryDisplayRerouted(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {}
        /**
         * Called when a new task is created
         * Record task IDs for Home package and default launch package
         */
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
            runMain {
                if (!isReady) return@runMain
                val packageName = componentName?.packageName ?: return@runMain
                if(packageName == mHomePackage) {
                    mHomeTaskId = taskId
                } else if(packageName == mLauncherPackage) {
                    mLauncherPackageTaskId = taskId
                }
            }
        }
        
        /**
         * Called when a task is removed
         * If the removed task is the Home package, restart it
         */
        override fun onTaskRemoved(taskId: Int) {
            runMain {
                if (!isReady) return@runMain
                if(mHomeTaskId == taskId) {
                    mHomeTaskId = null
                    startHomeLauncher()
                } else if(mLauncherPackageTaskId == taskId) {
                    mLauncherPackageTaskId = null
                }
            }
        }
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {}
        override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {}
        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?, userId: Int) {}
        override fun onTaskSnapshotChanged(taskId: Int, snapshot: TaskSnapshot?) {}
        override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {}
        override fun onRecentTaskListUpdated() {}
        override fun onRecentTaskListFrozenChanged(frozen: Boolean) {}
        override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {}
        override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onActivityRotation(displayId: Int) {}
        override fun onTaskMovedToBack(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onLockTaskModeChanged(mode: Int) {}
        override fun onTaskSnapshotInvalidated(taskId: Int) {}

        //Samsung OneUi
        override fun onActivityDismissingSplitTask(str: String?) {}
        override fun onOccludeChangeNotice(componentName: ComponentName?, z: Boolean) {}
        override fun onTaskbarIconVisibleChangeRequest(componentName: ComponentName?, z: Boolean) {}
        //Samsung OneUi 7
        override fun onTaskWindowingModeChanged(i: Int) {}
        //Android 16 QPR2
        override fun onRecentTaskRemovedForAddTask(taskId: Int) {}
    }
}
