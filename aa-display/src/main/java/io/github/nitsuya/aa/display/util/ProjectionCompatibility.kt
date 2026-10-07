package io.github.nitsuya.aa.display.util

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/** Exact platform contracts only; an ambiguous structure must never pick its first member. */
internal object ProjectionCompatibility {
    data class Selection(val method: Method?, val matches: Int) {
        val absent: Boolean get() = matches == 0
    }

    fun select(methods: Iterable<Method>, name: String, result: Class<*>, vararg parameters: Class<*>): Selection {
        val matches = methods.filter {
            it.name == name && !Modifier.isStatic(it.modifiers) && it.returnType == result &&
                it.parameterTypes.contentEquals(parameters)
        }
        return Selection(matches.singleOrNull()?.also { it.isAccessible = true }, matches.size)
    }

    fun groupGetter(sdk: Int): String? = when (sdk) {
        in 34..36 -> "getDisplayGroupNameLocked"
        37 -> "getLayoutGroupNameLocked"
        else -> null
    }

    fun selectLaunchPermission(methods: Iterable<Method>, activityInfo: Class<*>, taskArea: () -> Class<*>): Method? {
        val preferred = select(methods, "isCallerAllowedToLaunchOnDisplay", java.lang.Boolean.TYPE,
            Integer.TYPE, Integer.TYPE, Integer.TYPE, activityInfo)
        if (!preferred.absent) return preferred.method
        return select(methods, "isCallerAllowedToLaunchOnTaskDisplayArea", java.lang.Boolean.TYPE,
            Integer.TYPE, Integer.TYPE, taskArea(), activityInfo).method
    }

    fun allowProjectionLaunch(original: Any?, activeDisplay: Int, requestedDisplay: Int?): Boolean =
        original == false && activeDisplay > 0 && requestedDisplay == activeDisplay

    // Android KeyEvent constants, kept here so the capability decision is JVM-testable.
    fun needsNavigationTouchMode(sdk: Int, keyCode: Int, displayId: Int): Boolean =
        sdk >= 34 && displayId > 0 && when (keyCode) { 19, 20, 21, 22, 23, 61, 66 -> true; else -> false }

    fun groupRelationship(projection: Int?, default: Int?): String = when {
        projection == null || default == null || projection < 0 || default < 0 -> "unknown"
        projection == default -> "shared"
        else -> "independent"
    }
}

/** A delayed callback may remove only the session it captured. */
internal class ProjectionOwner<T> {
    @Volatile var current: T? = null
    @Synchronized fun clear(expected: T): Boolean {
        if (current !== expected) return false
        current = null
        return true
    }
}

internal class DisplayCreationNames {
    private val expected = Collections.synchronizedSet(mutableSetOf<String>())

    fun <T> duringCreation(name: String, create: () -> T): T {
        expected.add(name)
        try {
            return create()
        } finally {
            expected.remove(name)
        }
    }

    fun canName(existingGroup: String?, name: String): Boolean =
        existingGroup.isNullOrEmpty() && name.startsWith("AADisplay-") && expected.contains(name)
}

/** Optional resource ownership, with partial-acquisition rollback and exactly-once release. */
internal class AcquiredResource {
    var acquired = false
        private set

    fun acquire(action: () -> Unit, rollback: () -> Unit = {}): Boolean {
        if (acquired) return false
        try {
            action()
            acquired = true
            return true
        } catch (error: Throwable) {
            runCatching(rollback)
            throw error
        }
    }

    fun release(action: () -> Unit): Throwable? {
        if (!acquired) return null
        acquired = false
        return runCatching(action).exceptionOrNull()
    }
}

/** Each optional display policy has separate request and readback failure boundaries. */
internal object DisplayPolicyRequests {
    data class Outcome(val requested: Any, val setterAvailable: Boolean, val set: Boolean, val actual: Any?)

    fun apply(requested: Any, setter: (() -> Unit)?, getter: () -> Any?): Outcome {
        val set = setter != null && runCatching(setter).isSuccess
        val actual = runCatching(getter).getOrNull()
        return Outcome(requested, setter != null, set, actual)
    }
}

/** Main-thread lifecycle transitions; callback/input readers can safely inspect the terminal state. */
internal class ProjectionLifecycle {
    @Volatile var destroyed = false
        private set
    @Volatile var coreReady = false
        private set
    private var preparationStarted = false
    private var readyDelivered = false

    @Synchronized fun beginPreparation(): Boolean {
        if (destroyed || preparationStarted) return false
        preparationStarted = true
        return true
    }

    @Synchronized fun coreCreated(): Boolean {
        if (destroyed || coreReady) return false
        coreReady = true
        return true
    }

    @Synchronized fun takeReadyNotification(): Boolean {
        if (destroyed || !coreReady || readyDelivered) return false
        readyDelivered = true
        return true
    }

    @Synchronized fun destroy(): Boolean {
        if (destroyed) return false
        destroyed = true
        coreReady = false
        return true
    }
}

/** Diagnostics have no event payload and never emit more than once per interval. */
internal class InjectionDiagnostics(private val debug: Boolean) {
    data class Summary(val count: Long, val failed: Long, val averageNanos: Long, val maxNanos: Long)
    private var count = 0L
    private var failed = 0L
    private var totalNanos = 0L
    private var maxNanos = 0L
    private var lastWarningNanos: Long? = null
    private var ended = false

    @Synchronized fun record(ok: Boolean, elapsedNanos: Long) {
        if (!debug || ended) return
        count++
        if (!ok) failed++
        totalNanos += elapsedNanos
        maxNanos = maxOf(maxNanos, elapsedNanos)
    }

    @Synchronized fun shouldWarn(nowNanos: Long): Boolean {
        val previous = lastWarningNanos
        if (ended || previous != null && nowNanos - previous < 5_000_000_000L) return false
        lastWarningNanos = nowNanos
        return true
    }

    @Synchronized fun finish(): Summary? {
        if (ended) return null
        ended = true
        return if (debug) Summary(count, failed, if (count == 0L) 0 else totalNanos / count, maxNanos) else null
    }
}
