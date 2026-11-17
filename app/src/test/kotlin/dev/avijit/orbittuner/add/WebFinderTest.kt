package dev.avijit.orbittuner.add

import dev.avijit.orbittuner.add.FakeContributions.Companion.item
import dev.avijit.orbittuner.add.FakeContributions.Companion.job
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.DiscoverStart
import dev.avijit.orbittuner.songs.SongRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebFinderTest {
    private val fake = FakeContributions()
    private val fetched = ArrayList<String>()
    private var aiRefreshes = 0
    private val titles = mapOf("s1" to "Amazing Grace (My Chains Are Gone)", "s2" to "Amazing Grace", "s3" to "Grace Alone")

    private fun TestScope.finder() = WebFinder(
        fake,
        fetchSong = { id -> fetched += id; SongRow(id, titles[id] ?: id, "Chris Tomlin", "G", "en", emptyList()) },
        scope = backgroundScope,
        onAiUsed = { aiRefreshes++ },
    )

    /** Runs the finder's background work (advanceUntilIdle ignores backgroundScope). */
    private fun TestScope.settle() { advanceTimeBy(120_000); runCurrent() }

    private fun WebTier.ids() = when (this) {
        is WebTier.Importing -> rows
        is WebTier.Done -> rows
        else -> error("no rows in $this")
    }.map { it.row.id to it.inReview }

    @Test fun resultsStreamIntoTheTierAsItemsFinish() = runTest {
        fake.polls += job("running", item(1, "queued"), item(2, "queued"), item(3, "queued"))
        fake.polls += job("running", item(1, "done", "s1"), item(2, "queued"), item(3, "queued"))
        fake.polls += job("done", item(1, "done", "s1"), item(2, "review", "s2"), item(3, "failed", error = "fetch_failed"))
        val f = finder()
        f.find("amazing grace", "en")
        runCurrent()
        assertEquals(listOf("amazing grace" to "en"), fake.discovers)
        // First poll right away: nothing finished yet.
        assertEquals(WebTier.Importing(emptyList(), pending = 3), f.state.value.tier)

        advanceTimeBy(2_001)
        assertEquals(listOf("s1" to false), f.state.value.tier.ids())
        assertEquals(2, (f.state.value.tier as WebTier.Importing).pending)

        advanceTimeBy(2_001)
        val done = f.state.value.tier as WebTier.Done
        assertEquals(listOf("s1" to false, "s2" to true), done.ids())
        assertEquals(listOf("s1", "s2"), fetched)
        assertEquals(3, fake.jobCalls)

        // Stopped polling once the job finished; AI-left refreshed.
        advanceTimeBy(20_000)
        assertEquals(3, fake.jobCalls)
        assertTrue(aiRefreshes >= 1)
    }

    @Test fun duplicatesShowAtOnce() = runTest {
        fake.polls += job("done", item(1, "duplicate", "s3"))
        val f = finder()
        f.find("grace alone", null)
        settle()
        assertEquals(listOf("s3" to false), f.state.value.tier.ids())
    }

    @Test fun noResults() = runTest {
        fake.discover = { _, _ -> DiscoverStart(null, 0) }
        val f = finder()
        f.find("zzzz", null)
        settle()
        assertEquals(WebTier.Done(emptyList(), "Nothing found on the web for “zzzz”"), f.state.value.tier)
        assertEquals(0, fake.jobCalls)
    }

    @Test fun planAndSearchLimits() = runTest {
        fake.discover = { _, _ -> throw ApiError.Limit(429, "plan_limit_reached", 3, "free") }
        val f = finder()
        f.find("grace", null)
        settle()
        val t = f.state.value.tier as WebTier.Failed
        assertEquals("You've used today's AI imports (Free plan: 3/day)", t.message)
        assertTrue(t.limit)

        fake.discover = { _, _ -> throw ApiError.Limit(429, "search_limit_reached", 10, "free") }
        f.find("grace", null)
        settle()
        assertEquals("You've used today's web searches (Free plan: 10/day)", (f.state.value.tier as WebTier.Failed).message)
    }

    @Test fun pagesLeftOverByTheLimitAreExplained() = runTest {
        fake.polls += job("done", item(1, "done", "s1"), item(2, "limit_reached", error = "plan_limit_reached"))
        val f = finder()
        f.find("grace", null)
        settle()
        val done = f.state.value.tier as WebTier.Done
        assertEquals(listOf("s1" to false), done.ids())
        assertEquals("1 more page needs tomorrow's AI imports", done.note)
    }

    @Test fun nothingImportable() = runTest {
        fake.polls += job("done", item(1, "failed", error = "fetch_failed"), item(2, "failed", error = "ai_unparseable"))
        val f = finder()
        f.find("grace", null)
        settle()
        assertEquals(WebTier.Done(emptyList(), "Found 2 pages, but none had chords we could read"), f.state.value.tier)
    }

    @Test fun offlineSearchCanBeRetried() = runTest {
        fake.discover = { _, _ -> throw ApiError.Offline }
        val f = finder()
        f.find("grace", null)
        settle()
        val t = f.state.value.tier as WebTier.Failed
        assertEquals("You're offline. Connect and try again.", t.message)
        assertTrue(t.canRetry)
    }

    @Test fun pollErrorsBackOffThenRecover() = runTest {
        fake.polls += FakeContributions.Offline
        fake.polls += FakeContributions.Offline
        fake.polls += job("done", item(1, "done", "s1"))
        val f = finder()
        f.find("grace", null)
        runCurrent()
        assertEquals(1, fake.jobCalls)
        advanceTimeBy(2_001) // first error waits 4 s, not 2 s
        assertEquals(1, fake.jobCalls)
        advanceTimeBy(2_000)
        assertEquals(2, fake.jobCalls)
        advanceTimeBy(8_001) // second error: 8 s
        assertEquals(3, fake.jobCalls)
        assertEquals(listOf("s1" to false), f.state.value.tier.ids())
    }

    @Test fun pausedStopsPollingUntilResumed() = runTest {
        fake.polls += job("running", item(1, "queued"))
        fake.polls += job("done", item(1, "done", "s1"))
        val f = finder()
        f.find("grace", null)
        runCurrent()
        assertEquals(1, fake.jobCalls)
        f.pause()
        advanceTimeBy(30_000)
        assertEquals(1, fake.jobCalls)
        f.resume()
        settle()
        assertEquals(2, fake.jobCalls)
        assertEquals(listOf("s1" to false), f.state.value.tier.ids())
    }

    @Test fun clearCancelsTheJob() = runTest {
        fake.polls += job("running", item(1, "queued"))
        val f = finder()
        f.find("grace", null)
        runCurrent()
        f.clear()
        advanceTimeBy(30_000)
        assertEquals(1, fake.jobCalls)
        assertEquals(WebTier.Idle, f.state.value.tier)
    }

    @Test fun badRequestIsNotRetryable() = runTest {
        fake.discover = { _, _ -> throw ApiError.Http(400, "invalid_input") }
        val f = finder()
        f.find("grace", null)
        settle()
        val t = f.state.value.tier as WebTier.Failed
        assertTrue(!t.canRetry)
    }

    @Test fun queryAndLanguageRulesMatchTheService() {
        assertEquals("hi", discoverLang("hi"))
        assertEquals("en", discoverLang("EN"))
        assertEquals(null, discoverLang("es"))
        assertEquals(null, discoverLang("und"))
        assertEquals(null, discoverLang(null))
        assertEquals(DiscoverQuery.Ok, discoverQuery("amazing grace"))
        assertEquals(DiscoverQuery.TooShort, discoverQuery(" a "))
        assertEquals(DiscoverQuery.TooLong, discoverQuery("x".repeat(121)))
        assertEquals(DiscoverQuery.Link, discoverQuery("https://chords.example/x"))
        assertEquals(DiscoverQuery.Link, discoverQuery("WWW.chords.example"))
    }
}
