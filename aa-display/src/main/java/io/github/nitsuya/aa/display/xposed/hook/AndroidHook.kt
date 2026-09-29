package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.view.Display
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.IsSystemEnv
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.ManagedHookHandle
import io.github.nitsuya.aa.display.xposed.RemoteConfigProvider
import io.github.nitsuya.aa.display.xposed.XposedRuntimeContext
import io.github.nitsuya.aa.display.xposed.getObject
import io.github.nitsuya.aa.display.xposed.getObjectAs
import io.github.nitsuya.aa.display.xposed.invokeMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.qauxv.util.Initiator
import java.util.Collections

object AndroidHook : BaseHook() {
    override val tagName: String = "AAD_AndroidHook"

    private var runtimeContext: XposedRuntimeContext? = null

    override fun init(ctx: XposedRuntimeContext) {
        runtimeContext = ctx
        Initiator.init(ctx.classLoader)
        CoreManagerService.setConfigProvider(RemoteConfigProvider(ctx, AADisplayConfig.ConfigName, CoreManagerService.TAG))
        log(tagName, "xposed init")

        hookPackageService(ctx)
        hookActivityManagerService(ctx)
        hookDisplayLaunchPermission(ctx)
        hookDisplayGroupIsolation(ctx)
    }

    private fun hookPackageService(ctx: XposedRuntimeContext) {
        runCatching {
            var serviceManagerHooks: List<ManagedHookHandle> = emptyList()
            val addServiceMethods = ctx.findAllMethods("android.os.ServiceManager") {
                name == "addService"
                    && parameterCount >= 2
                    && parameterTypes[0] == String::class.java
                    && IBinder::class.java.isAssignableFrom(parameterTypes[1])
            }
            serviceManagerHooks = addServiceMethods.map { method ->
                ctx.hookBefore(method) { param ->
                    if (param.args.getOrNull(0) == "package") {
                        val binder = param.args.getOrNull(1) as? IBinder ?: return@hookBefore
                        val pms = (binder as? IPackageManager)
                            ?: IPackageManager.Stub.asInterface(binder)
                            ?: return@hookBefore
                        log(tagName, "Got pms: $pms")
                        runCatching {
                            BridgeService.register(ctx, pms)
                            serviceManagerHooks.forEach { it.unhook() }
                            log(tagName, "Bridge service injected")
                        }.onFailure {
                            log(tagName, "System service crashed", it)
                        }
                    }
                }
            }
            if (serviceManagerHooks.isEmpty()) {
                log(tagName, "no ServiceManager.addService overload found")
            }
        }.onFailure {
            log(tagName, "ServiceManager.addService", it)
        }
    }

    private fun hookActivityManagerService(ctx: XposedRuntimeContext) {
        runCatching {
            var constructorHooks: List<ManagedHookHandle> = emptyList()
            val constructors = ctx.findAllConstructors("com.android.server.am.ActivityManagerService") {
                parameterTypes.isNotEmpty() && parameterTypes[0] == Context::class.java
            }
            constructorHooks = constructors.map { constructor ->
                ctx.hookAfter(constructor) { param ->
                    constructorHooks.forEach { it.unhook() }
                    CoreManagerService.systemContext = param.thisObject!!.getObjectAs("mUiContext")
                    log(tagName, "get systemUiContext")
                }
            }
            if (constructorHooks.isEmpty()) {
                log(tagName, "no constructor with parameterTypes[0] == Context found")
            }
        }.onFailure {
            log(tagName, "ActivityManagerService constructor", it)
        }

        runCatching {
            var systemReadyHooks: List<ManagedHookHandle> = emptyList()
            val methods = ctx.findAllMethods("com.android.server.am.ActivityManagerService") {
                name == "systemReady"
            }
            systemReadyHooks = methods.map { method ->
                ctx.hookAfter(method) {
                    systemReadyHooks.forEach { it.unhook() }
                    runCatching {
                        CoreManagerService.systemReady()
                        log(tagName, "system ready")
                    }.onFailure { error ->
                        log(tagName, "systemReady callback", error)
                    }
                }
            }
            if (systemReadyHooks.isEmpty()) {
                log(tagName, "no ActivityManagerService.systemReady overload found")
            }
        }.onFailure {
            log(tagName, "ActivityManagerService.systemReady", it)
        }
    }

    private fun hookDisplayLaunchPermission(ctx: XposedRuntimeContext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val className = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "com.android.server.wm.ActivityTaskSupervisor"
        } else {
            "com.android.server.wm.ActivityStackSupervisor"
        }

        // Hook isCallerAllowedToLaunchOnDisplay (pre-Android 17 path)
        runCatching {
            ctx.hookAfter(ctx.findMethod(className) {
                name == "isCallerAllowedToLaunchOnDisplay"
                    && parameterCount == 4
                    && parameterTypes[0] == Int::class.javaPrimitiveType
                    && parameterTypes[1] == Int::class.javaPrimitiveType
                    && parameterTypes[2] == Int::class.javaPrimitiveType
                    && parameterTypes[3] == ActivityInfo::class.java
            }) { param ->
                val targetDisplayId = CoreManagerService.getDisplayId()
                if (targetDisplayId == Display.INVALID_DISPLAY) return@hookAfter
                if (param.result == false && param.args.getOrNull(2) == targetDisplayId) {
                    param.result = true
                    log(tagName, "hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        }.onFailure {
            log(tagName, "$className.isCallerAllowedToLaunchOnDisplay", it)
        }

        // Hook isCallerAllowedToLaunchOnTaskDisplayArea (Android 17+ path)
        runCatching {
            ctx.findAllMethods(className) {
                name == "isCallerAllowedToLaunchOnTaskDisplayArea"
            }.forEach { m ->
                ctx.hookAfter(m) { param ->
                    val targetDisplayId = CoreManagerService.getDisplayId()
                    if (targetDisplayId == Display.INVALID_DISPLAY) return@hookAfter
                    // arg[2] is TaskDisplayArea - extract displayId via getDisplayId()
                    val taskDisplayArea = param.args.getOrNull(2) ?: return@hookAfter
                    val displayId = runCatching {
                        taskDisplayArea.invokeMethod("getDisplayId") as? Int
                    }.getOrNull() ?: return@hookAfter
                    if (displayId == targetDisplayId && param.result == false) {
                        param.result = true
                        log(tagName, "hook isCallerAllowedToLaunchOnTaskDisplayArea success (displayId=$displayId)")
                    }
                }
            }
        }.onFailure {
            log(tagName, "$className.isCallerAllowedToLaunchOnTaskDisplayArea", it)
        }
    }

    private fun hookDisplayGroupIsolation(ctx: XposedRuntimeContext) {
        runCatching {
            val method = ctx.findAllMethods("com.android.server.display.LogicalDisplayMapper") {
                name.startsWith("assignDisplayGroup") && parameterCount == 1
            }.firstOrNull() ?: throw NoSuchMethodException("LogicalDisplayMapper.assignDisplayGroup*(LogicalDisplay)")

            ctx.hookBefore(method) { param ->
                DisplayGroupIsolation.claimIfExpected(param.args.getOrNull(0))
            }
            log(tagName, "display group isolation hook installed: ${method.name}")
        }.onFailure {
            log(tagName, "display group isolation hook failed", it)
        }
    }

    object Power {
        private var hookPower: ManagedHookHandle? = null

        fun hook() {
            unHook()
            val ctx = runtimeContext
            if (!IsSystemEnv || ctx == null) return
            hookPower = runCatching {
                val methods = ctx.findAllMethods("com.android.server.policy.PhoneWindowManager") {
                    name == "powerPress" && parameterCount >= 2
                }
                log(tagName, "Found ${methods.size} powerPress methods")
                val method = methods.firstOrNull()
                    ?: throw NoSuchMethodException("No powerPress method found in PhoneWindowManager")
                log(tagName, "Hooking powerPress: params=${method.parameterTypes.map { it.simpleName }}")
                ctx.hookBefore(method) { param ->
                    val count = if (param.args.size > 1) param.args[1] else "?"
                    val beganFromNonInteractive = if (param.args.size > 2) param.args[2] as? Boolean ?: false else false
                    log(tagName, "powerPress intercepted: count=$count, beganFromNonInteractive=$beganFromNonInteractive")
                    if (!beganFromNonInteractive) {
                        val success = CoreManagerService.toggleDisplayPowerSync()
                        if (success) {
                            param.returnEarly(null)
                        } else {
                            log(tagName, "SurfaceControl failed, falling through to system default")
                        }
                    } else {
                        CoreManagerService.toggleDisplayPowerSync()
                    }
                }
            }.onFailure {
                log(tagName, "Power PhoneWindowManager.powerPress", it)
            }.getOrNull()
        }

        fun unHook() {
            hookPower?.unhook()
            hookPower = null
        }
    }

    object DisplayGroupIsolation {
        private const val DISPLAY_NAME_PREFIX = "AADisplay-"
        private const val GROUP_NAME_PREFIX = "io.github.nitsuya.aadisplay.projection."

        private val expectedDisplays = Collections.synchronizedSet(mutableSetOf<String>())

        fun expect(displayName: String) {
            expectedDisplays += displayName
        }

        fun release(displayName: String) {
            expectedDisplays -= displayName
        }

        fun claimIfExpected(logicalDisplay: Any?) {
            if (logicalDisplay == null) return
            runCatching {
                val groupName = logicalDisplay.invokeMethod("getLayoutGroupNameLocked") as? String
                if (!groupName.isNullOrEmpty()) return

                val displayDevice = logicalDisplay.invokeMethod("getPrimaryDisplayDeviceLocked") ?: return
                val displayDeviceInfo = displayDevice.invokeMethod("getDisplayDeviceInfoLocked") ?: return
                val displayName = displayDeviceInfo.getObject("name") as? String ?: return
                if (!displayName.startsWith(DISPLAY_NAME_PREFIX) || !expectedDisplays.contains(displayName)) return

                val newGroupName = GROUP_NAME_PREFIX + displayName.removePrefix(DISPLAY_NAME_PREFIX)
                logicalDisplay.invokeMethod("setDisplayGroupNameLocked", newGroupName)
                expectedDisplays -= displayName
                log(tagName, "display group isolated: $displayName -> $newGroupName")
            }.onFailure {
                log(tagName, "display group isolation failed", it)
            }
        }
    }

    object FuckAppUseApplicationContext {
        private val appInitUseDisplay = Collections.synchronizedMap(HashMap<String, Int>())
        private var activityTaskManagerServiceStartProcessAsyncHook: ManagedHookHandle? = null
        private var applicationThreadBindApplicationHook: ManagedHookHandle? = null

        fun hook() {
            unHook()
            val ctx = runtimeContext
            if (!IsSystemEnv || ctx == null) return

            activityTaskManagerServiceStartProcessAsyncHook = runCatching {
                ctx.hookBefore(ctx.findMethod("com.android.server.wm.ActivityTaskManagerService") {
                    name == "startProcessAsync"
                }) { param ->
                    try {
                        val activityRecord = param.args[0] ?: return@hookBefore
                        val displayId = activityRecord.invokeMethod("getDisplayId") as Int
                        val packageName = activityRecord.getObject("packageName") as String
                        if (displayId == 0) {
                            appInitUseDisplay.remove(packageName)
                            return@hookBefore
                        }
                        appInitUseDisplay[packageName] = displayId
                    } catch (e: Exception) {
                        log(tagName, "activityTaskManagerService_startProcessAsync Hook Exception", e)
                    }
                }
            }.onFailure {
                log(tagName, "FuckAppUseAppContext ActivityTaskManagerService.startProcessAsync method", it)
            }.getOrNull()

            applicationThreadBindApplicationHook = runCatching {
                ctx.hookBefore(ctx.findMethod("android.app.IApplicationThread\$Stub\$Proxy") {
                    name == "bindApplication"
                }) { param ->
                    try {
                        val configuration = param.args.getOrNull(15)
                        if (configuration !is Configuration) {
                            return@hookBefore
                        }
                        val packageName = (param.args[0] as String).substringBeforeLast(":")
                        if (appInitUseDisplay.containsKey(packageName)) {
                            val densityDpi = CoreManagerService.getDensityDpi()
                            if (densityDpi != 0) {
                                configuration.densityDpi = densityDpi
                            }
                        }
                    } catch (e: Exception) {
                        log(tagName, "applicationThread_bindApplication Hook Exception", e)
                    }
                }
            }.onFailure {
                log(tagName, "FuckAppUseAppContext IApplicationThread.bindApplication method", it)
            }.getOrNull()
        }

        fun unHook() {
            appInitUseDisplay.clear()
            activityTaskManagerServiceStartProcessAsyncHook?.unhook()
            activityTaskManagerServiceStartProcessAsyncHook = null

            applicationThreadBindApplicationHook?.unhook()
            applicationThreadBindApplicationHook = null
        }
    }
}
