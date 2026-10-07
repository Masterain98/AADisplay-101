package io.github.nitsuya.aa.display.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProjectionSessionTest {
    @Test fun failedOrThrowingBindStillRequiresExactlyOneCleanup() {
        for (throws in listOf(false, true)) {
            val binding = ServiceBinding()
            val calls = mutableListOf<String>()
            val result = runCatching {
                binding.bind {
                    calls += "bind"
                    if (throws) throw SecurityException("denied")
                    false
                }
            }
            assertEquals(throws, result.isFailure)
            if (!throws) assertEquals(false, result.getOrNull())
            assertTrue(binding.attempted)
            assertNull(binding.unbind { calls += "unbind" })
            assertNull(binding.unbind { calls += "duplicate" })
            assertEquals(listOf("bind", "unbind"), calls)
        }
    }

    @Test fun bindingCleanupFailureIsTerminalAndUnattemptedBindingNeedsNoCleanup() {
        val untouched = ServiceBinding()
        untouched.unbind { fail("binding was never attempted") }
        assertFalse(untouched.attempted)
        val binding = ServiceBinding()
        assertTrue(binding.bind { true })
        var cleanups = 0
        assertNotNull(binding.unbind { cleanups++; throw IllegalArgumentException("not registered") })
        assertNull(binding.unbind { cleanups++ })
        assertEquals(1, cleanups)
        assertTrue(runCatching { binding.bind { fail("must not rebind"); true } }.isFailure)
    }

    @Test fun destroyDoesNotWaitForBlockedInjectionAndCancelsRemainingKeyEvent() {
        val lifecycle = ProjectionLifecycle().apply { coreCreated() }
        val input = ProjectionInput(lifecycle)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val calls = mutableListOf<Int>()
        val accepted = mutableListOf<Boolean>()
        val worker = Thread {
            try {
                input.sequence {
                    accepted += input.event(8) { id ->
                        calls += id
                        entered.countDown()
                        release.await()
                    }
                    accepted += input.event(8) { calls += it }
                }
            } finally { completed.countDown() }
        }
        val teardown = Thread { lifecycle.destroy(); destroyed.countDown() }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            teardown.start()
            assertTrue("retirement must not acquire the input order lock", destroyed.await(2, TimeUnit.SECONDS))
            assertEquals(1L, completed.count)
        } finally {
            release.countDown()
            worker.join(2000)
            teardown.join(2000)
        }
        assertFalse(worker.isAlive)
        assertEquals(listOf(8), calls)
        assertEquals(listOf(true, false), accepted)
        assertFalse(input.event(8) { fail("retired session must reject new input") })
    }

    @Test fun inputRemainsSynchronousOrderedAndUsesCapturedSessionDisplay() {
        val lifecycle = ProjectionLifecycle().apply { coreCreated() }
        val input = ProjectionInput(lifecycle)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val attempts = AtomicInteger()
        val calls = mutableListOf<String>()
        var currentDisplayId = 8
        val capturedDisplayId = currentDisplayId
        val first = Thread {
            input.sequence {
                input.event(capturedDisplayId) {
                    calls += "down:$it"
                    entered.countDown()
                    release.await()
                }
                input.event(capturedDisplayId) { calls += "up:$it" }
            }
        }
        val next = Thread {
            nextStarted.countDown()
            input.event(capturedDisplayId) { attempts.incrementAndGet() }
        }
        first.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            currentDisplayId = 9
            next.start()
            assertTrue(nextStarted.await(2, TimeUnit.SECONDS))
            assertEquals(0, attempts.get())
            lifecycle.destroy()
            val replacement = ProjectionInput(ProjectionLifecycle().apply { coreCreated() })
            assertTrue(replacement.event(currentDisplayId) { assertEquals(9, it) })
        } finally {
            release.countDown()
            first.join(2000)
            next.join(2000)
        }
        assertFalse(first.isAlive)
        assertFalse(next.isAlive)
        assertEquals(listOf("down:8"), calls)
        assertEquals(0, attempts.get())
    }

    @Test fun activeSequencePreservesDownUpOrderAndInvalidDisplaysNeverStart() {
        val lifecycle = ProjectionLifecycle()
        val input = ProjectionInput(lifecycle)
        assertFalse(input.event(8) { fail("core is not ready") })
        lifecycle.coreCreated()
        for (id in listOf(-1, 0)) assertFalse(input.event(id) { fail("invalid display") })
        val calls = mutableListOf<String>()
        input.sequence {
            assertTrue(input.event(8) { calls += "down:$it" })
            assertTrue(input.event(8) { calls += "up:$it" })
        }
        assertEquals(listOf("down:8", "up:8"), calls)
    }
}
