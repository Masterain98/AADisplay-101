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
import io.github.nitsuya.aa.display.util.ProjectionCompatibility
import io.github.nitsuya.aa.display.util.DisplayCreationNames
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
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.lang.reflect.Modifier

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
        val className = "com.android.server.wm.ActivityTaskSupervisor"
        runCatching {
            val methods = ctx.loadClass(className).declaredMethods.asIterable()
            val intType = Integer.TYPE
            val selected = ProjectionCompatibility.selectLaunchPermission(methods, ActivityInfo::class.java) {
                ctx.loadClass("com.android.server.wm.TaskDisplayArea")
            } ?: error("missing or ambiguous launch permission signature")
            var taskAreaDisplayId: Method? = null
            if (selected.name == "isCallerAllowedToLaunchOnTaskDisplayArea") {
                val taskArea = ctx.loadClass("com.android.server.wm.TaskDisplayArea")
                taskAreaDisplayId = ProjectionCompatibility.select(taskArea.methods.asIterable(),
                    "getDisplayId", intType).method ?: error("missing or ambiguous TaskDisplayArea.getDisplayId")
            }
            ctx.hookAfter(selected) { param ->
                if (param.result != false) return@hookAfter
                val requestedDisplay = if (taskAreaDisplayId == null) param.args.getOrNull(2) as? Int else {
                    runCatching { taskAreaDisplayId.invoke(param.args.getOrNull(2)) as? Int }.getOrNull()
                }
                if (ProjectionCompatibility.allowProjectionLaunch(param.result, CoreManagerService.getDisplayId(), requestedDisplay)) {
                    param.result = true
                }
            }
            log(tagName, "display launch permission hook installed: ${selected.name}")
        }.onFailure {
            log(tagName, "display launch permission feature disabled", it)
        }
    }

    private fun hookDisplayGroupIsolation(ctx: XposedRuntimeContext) {
        val getterName = ProjectionCompatibility.groupGetter(Build.VERSION.SDK_INT) ?: return
        runCatching {
            val logical = ctx.loadClass("com.android.server.display.LogicalDisplay")
            val device = ctx.loadClass("com.android.server.display.DisplayDevice")
            val info = ctx.loadClass("com.android.server.display.DisplayDeviceInfo")
            fun required(type: Class<*>, name: String, result: Class<*>, vararg params: Class<*>): Method =
                ProjectionCompatibility.select(type.declaredMethods.asIterable(), name, result, *params).method
                    ?: error("missing or ambiguous ${type.simpleName}.$name")
            val getter = required(logical, getterName, String::class.java)
            val setter = required(logical, "setDisplayGroupNameLocked", Void.TYPE, String::class.java)
            val primary = required(logical, "getPrimaryDisplayDeviceLocked", device)
            val deviceInfo = required(device, "getDisplayDeviceInfoLocked", info)
            val name = info.getDeclaredField("name").also {
                check(it.type == String::class.java && !Modifier.isStatic(it.modifiers))
                it.isAccessible = true
            }
            val method = required(ctx.loadClass("com.android.server.display.LogicalDisplayMapper"),
                "assignDisplayGroupLocked", Void.TYPE, logical)
            val members = DisplayGroupIsolation.Members(getter, setter, primary, deviceInfo, name)
            ctx.hookBefore(method) { param ->
                DisplayGroupIsolation.claimIfExpected(param.args.getOrNull(0), members)
            }
            log(tagName, "auxiliary display group naming installed: sdk=${Build.VERSION.SDK_INT}, getter=$getterName")
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

        private val displayNames = DisplayCreationNames()

        fun <T> duringCreation(displayName: String, create: () -> T): T =
            displayNames.duringCreation(displayName, create)

        data class Members(val getter: Method, val setter: Method, val primary: Method, val info: Method, val name: Field)

        fun claimIfExpected(logicalDisplay: Any?, members: Members) {
            if (logicalDisplay == null) return
            runCatching {
                val groupName = members.getter.invoke(logicalDisplay) as? String
                if (!groupName.isNullOrEmpty()) return
                val displayDevice = members.primary.invoke(logicalDisplay) ?: return
                val displayDeviceInfo = members.info.invoke(displayDevice) ?: return
                val displayName = members.name.get(displayDeviceInfo) as? String ?: return
                if (!displayNames.canName(groupName, displayName)) return

                val newGroupName = GROUP_NAME_PREFIX + displayName.removePrefix(DISPLAY_NAME_PREFIX)
                members.setter.invoke(logicalDisplay, newGroupName)
                log(tagName, "auxiliary display group name requested: $displayName -> $newGroupName")
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
