package dev.jdtech.jellyfin.offline.queue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveDownloadRegistryTest {
    @Test
    fun cancel_if_active_only_cancels_matching_entry() = runBlocking {
        val registry = ActiveDownloadRegistry()
        val deferred = CompletableDeferred<Unit>()
        val job: Job = deferred

        registry.attach("entry-A", job)

        assertFalse(
            "Wrong entryId must not cancel",
            registry.cancelIfActive("entry-B"),
        )
        assertFalse(job.isCancelled)

        assertTrue(registry.cancelIfActive("entry-A"))
        assertTrue("Matching entry should be cancelled", job.isCancelled)
    }

    @Test
    fun detach_clears_active_entry() {
        val registry = ActiveDownloadRegistry()
        val job: Job = CompletableDeferred<Unit>()
        registry.attach("entry-A", job)
        assertSame("entry-A", registry.activeEntryId())

        registry.detach("entry-A")
        assertNull(registry.activeEntryId())
        // After detach, cancel must be a no-op
        assertFalse(registry.cancelIfActive("entry-A"))
    }

    @Test
    fun reattach_swaps_active_entry() {
        val registry = ActiveDownloadRegistry()
        val first: Job = CompletableDeferred<Unit>()
        val second: Job = CompletableDeferred<Unit>()
        registry.attach("entry-A", first)
        registry.attach("entry-B", second)

        assertFalse(
            "After swap, cancelling old entryId must be a no-op",
            registry.cancelIfActive("entry-A"),
        )
        assertFalse(first.isCancelled)

        assertTrue(registry.cancelIfActive("entry-B"))
        assertTrue(second.isCancelled)
    }
}
