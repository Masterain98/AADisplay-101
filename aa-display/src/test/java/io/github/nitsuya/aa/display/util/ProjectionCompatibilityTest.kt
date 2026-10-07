package io.github.nitsuya.aa.display.util

import org.junit.Assert.*
import org.junit.Test

class ProjectionCompatibilityTest {
    class Activity
    class Area
    class Logical
    class Members {
        fun getter(): String = ""
        fun getter(ignored: Int): String = ignored.toString()
        fun setter(value: String) { value.length }
        fun setter(value: Int): Boolean = value > 0
        fun assignDisplayGroupLocked(display: Logical) { display.hashCode() }
        fun assignDisplayGroupLocked(display: Any): Boolean = display.hashCode() != 0
        companion object { @JvmStatic fun staticSetter(value: String) { value.length } }
    }
    class PermissionMembers {
        fun isCallerAllowedToLaunchOnDisplay(pid: Int, uid: Int, display: Int, activity: Activity): Boolean = false
        fun isCallerAllowedToLaunchOnDisplay(pid: Int, uid: Int, display: Long, activity: Activity): Boolean = false
        fun isCallerAllowedToLaunchOnTaskDisplayArea(pid: Int, uid: Int, area: Area, activity: Activity): Boolean = false
    }
    class WindowMethods {
        fun setInTouchMode(inTouch: Boolean) { inTouch.hashCode() }
        fun setInTouchMode(inTouch: Boolean, display: Int) { display.hashCode() }
        fun setInTouchMode(inTouch: Boolean, display: Long) { display.hashCode() }
        fun setShouldShowSystemDecors(display: Int, show: Boolean) { show.hashCode() }
        fun setShouldShowSystemDecors(display: Long, show: Boolean): Boolean = show
    }

    @Test fun exactSignaturesRejectWrongReturnParametersStaticAndDuplicates() {
        val methods = Members::class.java.declaredMethods.asIterable()
        assertNotNull(ProjectionCompatibility.select(methods, "getter", String::class.java).method)
        assertNull(ProjectionCompatibility.select(methods, "getter", Integer.TYPE).method)
        assertNotNull(ProjectionCompatibility.select(methods, "setter", Void.TYPE, String::class.java).method)
        assertNull(ProjectionCompatibility.select(methods, "setter", Void.TYPE, Integer.TYPE).method)
        assertNull(ProjectionCompatibility.select(methods, "staticSetter", Void.TYPE, String::class.java).method)
        assertNotNull(ProjectionCompatibility.select(methods, "assignDisplayGroupLocked", Void.TYPE, Logical::class.java).method)
        assertNull(ProjectionCompatibility.select(methods, "assignDisplayGroupLocked", Void.TYPE, Any::class.java).method)
        val getter = methods.single { it.name == "getter" && it.parameterCount == 0 }
        val duplicate = ProjectionCompatibility.select(listOf(getter, getter), "getter", String::class.java)
        assertNull(duplicate.method)
        assertFalse(duplicate.absent)
        assertEquals(2, duplicate.matches)
    }

    @Test fun permissionPrefersDisplayAndFallsBackOnlyWhenAbsent() {
        val methods = PermissionMembers::class.java.declaredMethods.asIterable()
        val preferred = ProjectionCompatibility.selectLaunchPermission(methods, Activity::class.java) {
            fail("preferred match must not load TaskDisplayArea"); Area::class.java
        }
        assertEquals("isCallerAllowedToLaunchOnDisplay", preferred?.name)
        val fallback = methods.filter { it.name == "isCallerAllowedToLaunchOnTaskDisplayArea" }
        assertEquals("isCallerAllowedToLaunchOnTaskDisplayArea",
            ProjectionCompatibility.selectLaunchPermission(fallback, Activity::class.java) { Area::class.java }?.name)
        assertNull(ProjectionCompatibility.selectLaunchPermission(listOf(preferred!!, preferred) + fallback, Activity::class.java) {
            fail("ambiguous preferred match must not attempt fallback"); Area::class.java
        })
        assertNull(ProjectionCompatibility.selectLaunchPermission(fallback + fallback, Activity::class.java) { Area::class.java })
    }

    @Test fun optionalWindowSettersRequireExactPerDisplayContracts() {
        val methods = WindowMethods::class.java.declaredMethods.asIterable()
        val perDisplay = ProjectionCompatibility.select(methods, "setInTouchMode", Void.TYPE, java.lang.Boolean.TYPE, Integer.TYPE)
        assertNotNull(perDisplay.method)
        assertEquals(Integer.TYPE, perDisplay.method!!.parameterTypes[1])
        val globalOnly = methods.filter { it.name == "setInTouchMode" && it.parameterCount == 1 }
        assertNull(ProjectionCompatibility.select(globalOnly, "setInTouchMode", Void.TYPE, java.lang.Boolean.TYPE, Integer.TYPE).method)
        assertNotNull(ProjectionCompatibility.select(methods, "setShouldShowSystemDecors", Void.TYPE, Integer.TYPE, java.lang.Boolean.TYPE).method)
        assertNull(ProjectionCompatibility.select(emptyList(), "setShouldShowSystemDecors", Void.TYPE, Integer.TYPE, java.lang.Boolean.TYPE).method)
    }

    @Test fun capabilityBranchesAndNavigationSetAreNarrow() {
        for (sdk in 31..33) assertNull(ProjectionCompatibility.groupGetter(sdk))
        for (sdk in 34..36) assertEquals("getDisplayGroupNameLocked", ProjectionCompatibility.groupGetter(sdk))
        assertEquals("getLayoutGroupNameLocked", ProjectionCompatibility.groupGetter(37))
        assertNull(ProjectionCompatibility.groupGetter(38))
        val navigation = setOf(19, 20, 21, 22, 23, 61, 66)
        for (key in 0..300) for (sdk in 31..37) {
            assertEquals(sdk >= 34 && key in navigation, ProjectionCompatibility.needsNavigationTouchMode(sdk, key, 8))
            assertFalse(ProjectionCompatibility.needsNavigationTouchMode(sdk, key, -1))
            assertFalse(ProjectionCompatibility.needsNavigationTouchMode(sdk, key, 0))
        }
    }

    @Test fun onlyFalseResultForActiveProjectionCanChange() {
        assertTrue(ProjectionCompatibility.allowProjectionLaunch(false, 8, 8))
        for (result in listOf(null, true, 0, "false")) assertFalse(ProjectionCompatibility.allowProjectionLaunch(result, 8, 8))
        for (active in listOf(-1, 0)) assertFalse(ProjectionCompatibility.allowProjectionLaunch(false, active, active))
        for (requested in listOf(null, -1, 0, 9)) assertFalse(ProjectionCompatibility.allowProjectionLaunch(false, 8, requested))
    }

    @Test fun realGroupIdsDetermineRelationship() {
        assertEquals("shared", ProjectionCompatibility.groupRelationship(0, 0))
        assertEquals("independent", ProjectionCompatibility.groupRelationship(4, 0))
        assertEquals("unknown", ProjectionCompatibility.groupRelationship(null, 0))
        assertEquals("unknown", ProjectionCompatibility.groupRelationship(4, -1))
    }

    @Test fun displayNameIsClaimedOnlyDuringCreationAndPreservesExistingGroup() {
        val names = DisplayCreationNames()
        assertFalse(names.canName("", "AADisplay-test"))
        names.duringCreation("AADisplay-test") {
            assertTrue(names.canName(null, "AADisplay-test"))
            assertTrue(names.canName("", "AADisplay-test"))
            assertFalse(names.canName("existing", "AADisplay-test"))
            assertFalse(names.canName("", "AADisplay-other"))
        }
        assertFalse(names.canName("", "AADisplay-test"))
        runCatching { names.duringCreation("AADisplay-test") { error("creation failed") } }
        assertFalse(names.canName("", "AADisplay-test"))
        names.duringCreation("other-app") { assertFalse(names.canName("", "other-app")) }
    }

    @Test fun policyFailureAndMissingSetterDoNotSkipIndependentRequestsOrReadbacks() {
        val calls = mutableListOf<String>()
        val ime = DisplayPolicyRequests.apply(1, { calls += "ime-set"; error("dead binder") }) {
            calls += "ime-read"; 0
        }
        val keyguard = DisplayPolicyRequests.apply(true, { calls += "keyguard-set" }) {
            calls += "keyguard-read"; true
        }
        val decors = DisplayPolicyRequests.apply(false, null) { calls += "decors-read"; true }
        assertEquals(listOf("ime-set", "ime-read", "keyguard-set", "keyguard-read", "decors-read"), calls)
        assertFalse(ime.set)
        assertTrue(ime.setterAvailable)
        assertEquals(0, ime.actual)
        assertTrue(keyguard.set)
        assertEquals(true, keyguard.actual)
        assertFalse(decors.setterAvailable)
        assertFalse(decors.set)
        assertEquals(true, decors.actual)
        assertNull(DisplayPolicyRequests.apply(false, {}) { error("read failed") }.actual)
    }

    @Test fun failedCoreAndLateCallbacksCannotPublishReady() {
        val session = ProjectionLifecycle()
        assertFalse(session.takeReadyNotification())
        assertTrue(session.beginPreparation())
        assertFalse(session.beginPreparation())
        assertTrue(session.destroy())
        assertFalse(session.destroy())
        assertFalse(session.beginPreparation())
        assertFalse(session.coreCreated())
        assertFalse(session.takeReadyNotification())
        assertFalse(session.coreReady)
    }

    @Test fun successfulCorePublishesOnceAndDestroyIsTerminal() {
        val session = ProjectionLifecycle()
        assertTrue(session.beginPreparation())
        assertTrue(session.coreCreated())
        assertFalse(session.coreCreated())
        assertTrue(session.coreReady)
        assertTrue(session.takeReadyNotification())
        assertFalse(session.takeReadyNotification())
        assertTrue(session.destroy())
        assertFalse(session.coreReady)
        assertFalse(session.takeReadyNotification())
    }

    @Test fun lateFailureOrDelayedCleanupCannotClearNewSession() {
        val owner = ProjectionOwner<ProjectionLifecycle>()
        val pending = ProjectionLifecycle()
        val replacement = ProjectionLifecycle()
        owner.current = pending
        assertTrue(owner.clear(pending))
        pending.destroy()
        owner.current = replacement
        assertFalse(owner.clear(pending))
        assertSame(replacement, owner.current)
        assertFalse(pending.beginPreparation())
        assertFalse(pending.takeReadyNotification())
        assertTrue(owner.clear(replacement))
        assertNull(owner.current)
    }

    @Test fun diagnosticsAggregateAndRateLimitWithoutEventPayload() {
        val diagnostics = InjectionDiagnostics(true)
        diagnostics.record(true, 100)
        diagnostics.record(false, 300)
        assertTrue(diagnostics.shouldWarn(0))
        assertFalse(diagnostics.shouldWarn(4_999_999_999L))
        assertTrue(diagnostics.shouldWarn(5_000_000_000L))
        assertEquals(InjectionDiagnostics.Summary(2, 1, 200, 300), diagnostics.finish())
        assertNull(diagnostics.finish())
        diagnostics.record(false, 10000)
        assertFalse(diagnostics.shouldWarn(10_000_000_000L))
        assertNull(InjectionDiagnostics(false).finish())
    }

    @Test fun acquisitionFailureRollsBackAndDoesNotReleaseAnUnacquiredResource() {
        val resource = AcquiredResource()
        val calls = mutableListOf<String>()
        val failed = runCatching { resource.acquire({ calls += "start"; error("partial acquisition") }, { calls += "rollback" }) }
        assertTrue(failed.isFailure)
        assertFalse(resource.acquired)
        resource.release { calls += "must not run" }
        assertEquals(listOf("start", "rollback"), calls)
        assertTrue(resource.acquire({ calls += "success" }))
        assertFalse(resource.acquire({ calls += "duplicate" }))
        assertNull(resource.release { calls += "release" })
        assertNull(resource.release { calls += "duplicate release" })
        assertEquals(listOf("start", "rollback", "success", "release"), calls)
    }

    @Test fun failingCleanupDoesNotStrandOtherAcquiredResources() {
        val first = AcquiredResource()
        val second = AcquiredResource()
        first.acquire({})
        second.acquire({})
        assertNotNull(first.release { error("receiver teardown failed") })
        var released = false
        assertNull(second.release { released = true })
        assertTrue(released)
        assertFalse(first.acquired)
        assertFalse(second.acquired)
    }

    @Test fun inputCountersRemainCorrectAcrossConcurrentCalls() {
        val diagnostics = InjectionDiagnostics(true)
        val threads = (0 until 4).map { index ->
            Thread { repeat(1000) { diagnostics.record(index != 0, 100) } }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(InjectionDiagnostics.Summary(4000, 1000, 100, 100), diagnostics.finish())
    }
}
