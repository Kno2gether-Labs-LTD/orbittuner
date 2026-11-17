package dev.avijit.orbittuner.songs

import dev.avijit.orbittuner.data.LocalResults
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.CategoryFacet
import dev.avijit.orbittuner.net.Facets
import dev.avijit.orbittuner.net.LanguageFacet
import dev.avijit.orbittuner.net.Page
import dev.avijit.orbittuner.net.SongSummary
import dev.avijit.orbittuner.voice.HistorySignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

fun cached(id: String, title: String, key: String? = "G", artist: String? = "Traditional", category: String = "Hymn"): CachedSong = CachedSong(
    id = id, title = title, artist = artist, chordpro = "", language = "en", categoriesJson = "[\"$category\"]",
    originalKey = key, version = 1, updatedAt = 1, lastOpenedAt = 0, pinnedOffline = false,
)

fun summary(
    id: String, title: String, key: String? = "D", language: String = "en", artist: String? = "Traditional", category: String = "Hymn",
): SongSummary = SongSummary(id = id, title = title, artist = artist, language = language, categories = listOf(category), originalKey = key)

/** Scriptable stand-in for the repository and the service's discovery endpoints. */
class FakeDiscovery {
    val remoteCalls = ArrayList<String>()
    val browseCalls = ArrayList<Triple<String?, String?, Int>>()
    var facetsCalls = 0
    val remote = HashMap<String, List<SongSummary>>()
    /** Per-query response time; the response is non-cancellable, so a slow one really does arrive late. */
    val latency = HashMap<String, Long>()
    /** When set, every call throws this (an [ApiError] or anything unexpected). */
    var fail: Exception? = null
    /** The next N facets calls throw [facetsError]. */
    var facetsFailures = 0
    var facetsError: Exception = ApiError.Offline
    /** Page 1 of a search says there is a page 2; page 2 answers [remotePage2] or throws [failMore]. */
    var remoteNext: Int? = null
    val remotePage2 = HashMap<String, List<SongSummary>>()
    var failMore: Exception? = null
    /** Browse answer; by default one song named after the filter. */
    var browseRows: (String?, String?) -> List<SongSummary> = { lang, _ -> listOf(summary("b-${lang ?: "any"}", "Browse ${lang ?: "any"}", language = lang ?: "en")) }
    var local: (String) -> LocalResults = { LocalResults(emptyList(), emptyList()) }
    var facets = Facets(
        languages = listOf(LanguageFacet("en", 40), LanguageFacet("hi", 12), LanguageFacet("bn", 7)),
        categories = listOf(CategoryFacet("Hymns", 30), CategoryFacet("Worship", 20)),
    )
    val paired = MutableStateFlow(true)
    /** What the device knows of the user's taste (A2's signals). */
    var signals = dev.avijit.orbittuner.voice.HistorySignals()
    val recorded = ArrayList<String>()
    /** Find on the web; null leaves it out (as before it existed). */
    var contributions: dev.avijit.orbittuner.add.FakeContributions? = null
    /** Account identity (host|userId) the view model watches. */
    val account = MutableStateFlow<String?>("host|u1")
    /** Songs the web tier fetches by id. */
    val webSongs = HashMap<String, SongRow>()

    fun localSearch(q: String): Flow<LocalResults> = flowOf(local(q))

    suspend fun remoteSearch(q: String, lang: String?, cat: String?, page: Int): Page<SongSummary> {
        remoteCalls += q
        withContext(NonCancellable) { delay(latency[q] ?: 50L) }
        fail?.let { throw it }
        if (page > 0) { failMore?.let { throw it }; return Page(remotePage2[q].orEmpty()) }
        return Page(remote[q].orEmpty(), remoteNext)
    }

    suspend fun browse(lang: String?, cat: String?, page: Int): Page<SongSummary> {
        browseCalls += Triple(lang, cat, page)
        fail?.let { throw it }
        // Like the service: pages are 0-based, so a short list lives entirely on page 0.
        return Page(if (page == 0) browseRows(lang, cat) else emptyList())
    }

    fun vm() = SearchViewModel(
        localSearch = ::localSearch,
        remoteSearch = ::remoteSearch,
        facets = {
            facetsCalls++
            fail?.let { throw it }
            if (facetsFailures > 0) { facetsFailures--; throw facetsError }
            facets
        },
        browse = ::browse,
        hasAccount = paired,
        contributions = contributions,
        fetchSong = { id -> webSongs[id] ?: SongRow(id, "Web $id", "Someone", "G", "en", emptyList()) },
        accountId = account,
        history = { signals },
        onCategoryBrowsed = { recorded += it },
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeDiscovery()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun LibraryTier.rows(): List<SongRow> = (this as? LibraryTier.Results)?.rows ?: error("expected Results, was $this")

    @Test fun typingWithin300msSearchesOnce() = runTest(dispatcher) {
        fake.remote["grace"] = listOf(summary("g1", "Saving Grace"))
        val vm = fake.vm()
        vm.setQuery("gr"); advanceTimeBy(100)
        vm.setQuery("gra"); advanceTimeBy(100)
        vm.setQuery("grace")
        advanceUntilIdle()
        assertEquals(listOf("grace"), fake.remoteCalls)
        assertEquals(listOf("g1"), vm.ui.value.library.rows().map { it.id })
    }

    /** Review Focus 5: a slow "gra" response that lands after the "grace" one must not replace it. */
    @Test fun lateResponseForOlderQueryIsIgnored() = runTest(dispatcher) {
        fake.remote["gra"] = listOf(summary("x", "Gravity"))
        fake.remote["grace"] = listOf(summary("g1", "Saving Grace"))
        fake.latency["gra"] = 2_000
        fake.latency["grace"] = 10
        val vm = fake.vm()
        vm.setQuery("gra")
        advanceTimeBy(400) // debounce passed, "gra" in flight
        vm.setQuery("grace")
        advanceUntilIdle() // "grace" answers at ~710 ms, "gra" only at ~2300 ms
        assertEquals(listOf("gra", "grace"), fake.remoteCalls)
        assertEquals("grace", vm.ui.value.query)
        assertEquals(listOf("g1"), vm.ui.value.library.rows().map { it.id })
    }

    @Test fun offlineKeepsLocalTiers() = runTest(dispatcher) {
        fake.local = { LocalResults(listOf(cached("ag", "Amazing Grace")), listOf(cached("gg", "Grace Greater Than Our Sin"))) }
        fake.fail = ApiError.Offline
        val vm = fake.vm()
        vm.setQuery("grace")
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals(listOf("ag"), ui.yours.map { it.id })
        assertEquals(listOf("gg"), ui.cached.map { it.id })
        assertEquals(LibraryTier.Offline, ui.library)
    }

    @Test fun libraryTierSkipsSongsAlreadyShown() = runTest(dispatcher) {
        fake.local = { LocalResults(listOf(cached("ag", "Amazing Grace")), listOf(cached("gg", "Grace Greater"))) }
        fake.remote["grace"] = listOf(summary("ag", "Amazing Grace"), summary("gg", "Grace Greater"), summary("sg", "Saving Grace"))
        val vm = fake.vm()
        vm.setQuery("grace")
        advanceUntilIdle()
        assertEquals(listOf("sg"), vm.ui.value.library.rows().map { it.id })
    }

    @Test fun exploreLoadsFacetsAndBrowsesByLanguage() = runTest(dispatcher) {
        val vm = fake.vm()
        advanceUntilIdle()
        assertEquals(1, fake.facetsCalls)
        assertEquals(listOf("en", "hi", "bn"), vm.explore.value.languages.map { it.code })
        assertEquals(listOf("Hymns", "Worship"), vm.explore.value.categories.map { it.name })

        vm.selectLanguage("hi")
        advanceUntilIdle()
        assertEquals(Triple<String?, String?, Int>("hi", null, 0), fake.browseCalls.last())
        assertEquals("hi", vm.explore.value.lang)
        assertEquals(listOf("b-hi"), vm.explore.value.list.rows().map { it.id })
    }

    @Test fun noAccountShowsTheLibraryTierAsNoAccount() = runTest(dispatcher) {
        fake.paired.value = false
        fake.local = { LocalResults(emptyList(), listOf(cached("gg", "Grace Greater"))) }
        val vm = fake.vm()
        vm.setQuery("grace")
        advanceUntilIdle()
        assertEquals(LibraryTier.NoAccount, vm.ui.value.library)
        assertEquals(LibraryTier.NoAccount, vm.explore.value.list)
        assertTrue(fake.remoteCalls.isEmpty())
        assertEquals(listOf("gg"), vm.ui.value.cached.map { it.id })
    }

    @Test fun failedFacetsShowErrorAndRetryLoadsThem() = runTest(dispatcher) {
        fake.facetsFailures = 1
        fake.facetsError = ApiError.Http(503, null) // a server error, not offline: only Retry reloads
        val vm = fake.vm()
        advanceUntilIdle()
        assertEquals(FacetsState.Error, vm.explore.value.facets)
        assertTrue(vm.explore.value.languages.isEmpty())
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("en", "hi", "bn"), vm.explore.value.languages.map { it.code })
    }

    @Test fun facetsReloadWhenTheServiceIsReachableAgain() = runTest(dispatcher) {
        fake.fail = ApiError.Offline
        val vm = fake.vm()
        advanceUntilIdle()
        assertEquals(FacetsState.Error, vm.explore.value.facets)
        assertEquals(LibraryTier.Offline, vm.explore.value.list)
        fake.fail = null
        fake.remote["grace"] = listOf(summary("g1", "Saving Grace"))
        vm.setQuery("grace")
        advanceUntilIdle()
        assertTrue(vm.explore.value.facets is FacetsState.Loaded)
        assertEquals(listOf("Hymns", "Worship"), vm.explore.value.categories.map { it.name })
    }

    @Test fun unexpectedErrorsBecomeUnavailable() = runTest(dispatcher) {
        fake.fail = IllegalStateException("bad json")
        val vm = fake.vm()
        vm.setQuery("grace")
        advanceUntilIdle()
        assertEquals(LibraryTier.Unavailable, vm.ui.value.library)
        assertEquals(LibraryTier.Unavailable, vm.explore.value.list)
        assertEquals(FacetsState.Error, vm.explore.value.facets)
    }

    @Test fun oneLetterDoesNotSearchTheLibrary() = runTest(dispatcher) {
        val vm = fake.vm()
        vm.setQuery("g")
        advanceUntilIdle()
        assertTrue(fake.remoteCalls.isEmpty())
        assertEquals(LibraryTier.Idle, vm.ui.value.library)
    }

    @Test fun failedLoadMoreIsReportedAndCanBeRetried() = runTest(dispatcher) {
        fake.remote["grace"] = listOf(summary("g1", "Saving Grace"))
        fake.remoteNext = 1
        fake.remotePage2["grace"] = listOf(summary("g2", "Grace Alone"))
        fake.failMore = IllegalStateException("boom")
        val vm = fake.vm()
        vm.setQuery("grace")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val failed = vm.ui.value.library as LibraryTier.Results
        assertTrue(failed.moreFailed)
        assertEquals(listOf("g1"), failed.rows.map { it.id })
        fake.failMore = null
        vm.loadMore()
        advanceUntilIdle()
        val ok = vm.ui.value.library as LibraryTier.Results
        assertFalse(ok.moreFailed)
        assertEquals(listOf("g1", "g2"), ok.rows.map { it.id })
    }

    @Test fun disabledAccountIsItsOwnState() {
        assertEquals(LibraryTier.AccountDisabled, SearchViewModel.tierFor(ApiError.AccountDisabled))
    }

    // ---- find on the web ----

    @Test fun findOnWebUsesQueryAndLanguageAndClearsOnNewQuery() = runTest(dispatcher) {
        val web = dev.avijit.orbittuner.add.FakeContributions().apply {
            polls += dev.avijit.orbittuner.add.FakeContributions.job("running", dev.avijit.orbittuner.add.FakeContributions.item(1, "queued"))
        }
        fake.contributions = web
        val vm = fake.vm()
        vm.setQuery("grace "); vm.selectLanguage("hi")
        advanceUntilIdle()
        assertEquals(dev.avijit.orbittuner.add.WebTier.Idle, vm.ui.value.web)
        vm.findOnWeb()
        advanceTimeBy(10)
        assertEquals(listOf("grace" to "hi"), web.discovers)
        assertTrue(vm.ui.value.web is dev.avijit.orbittuner.add.WebTier.Importing)
        vm.setQuery("other")
        advanceTimeBy(10_000)
        assertEquals(dev.avijit.orbittuner.add.WebTier.Idle, vm.ui.value.web)
        val calls = web.jobCalls
        advanceTimeBy(10_000)
        assertEquals(calls, web.jobCalls) // polling stopped
    }

    @Test fun webResultsSkipSongsAlreadyListed() = runTest(dispatcher) {
        fake.remote["grace"] = listOf(summary("s1", "Saving Grace"))
        val web = dev.avijit.orbittuner.add.FakeContributions().apply {
            polls += dev.avijit.orbittuner.add.FakeContributions.job(
                "done", dev.avijit.orbittuner.add.FakeContributions.item(1, "duplicate", "s1"), dev.avijit.orbittuner.add.FakeContributions.item(2, "done", "s2"),
            )
        }
        fake.contributions = web
        val vm = fake.vm()
        vm.setQuery("grace")
        advanceUntilIdle()
        vm.findOnWeb()
        advanceUntilIdle()
        val t = vm.ui.value.web as dev.avijit.orbittuner.add.WebTier.Done
        assertEquals(listOf("s2"), t.rows.map { it.row.id })
    }

    // ---- A3 review fixes ----

    @Test fun findOnWebSendsOnlyLanguagesTheServiceAccepts() = runTest(dispatcher) {
        val web = dev.avijit.orbittuner.add.FakeContributions().apply { discover = { _, _ -> dev.avijit.orbittuner.net.DiscoverStart(null, 0) } }
        fake.contributions = web
        val vm = fake.vm()
        vm.setQuery("despacito"); vm.selectLanguage("es")
        advanceUntilIdle()
        vm.findOnWeb()
        advanceUntilIdle()
        vm.selectLanguage("hi")
        advanceUntilIdle()
        vm.findOnWeb()
        advanceUntilIdle()
        assertEquals(listOf<Pair<String, String?>>("despacito" to null, "despacito" to "hi"), web.discovers)
    }

    @Test fun findOnWebIgnoresLinks() = runTest(dispatcher) {
        val web = dev.avijit.orbittuner.add.FakeContributions()
        fake.contributions = web
        val vm = fake.vm()
        vm.setQuery("https://chords.example/despacito")
        advanceUntilIdle()
        vm.findOnWeb()
        advanceUntilIdle()
        assertTrue(web.discovers.isEmpty())
        assertEquals(dev.avijit.orbittuner.add.WebTier.Idle, vm.ui.value.web)
    }

    @Test fun accountChangeClearsWebResultsAndStopsPolling() = runTest(dispatcher) {
        val web = dev.avijit.orbittuner.add.FakeContributions().apply {
            polls += dev.avijit.orbittuner.add.FakeContributions.job(
                "running", dev.avijit.orbittuner.add.FakeContributions.item(1, "review", "w1"), dev.avijit.orbittuner.add.FakeContributions.item(2, "queued"),
            )
        }
        fake.contributions = web
        val vm = fake.vm()
        vm.setQuery("zion")
        advanceUntilIdle()
        vm.findOnWeb()
        advanceTimeBy(100)
        assertTrue((vm.ui.value.web as dev.avijit.orbittuner.add.WebTier.Importing).rows.isNotEmpty())
        fake.account.value = "host|u2"
        advanceTimeBy(100)
        assertEquals(dev.avijit.orbittuner.add.WebTier.Idle, vm.ui.value.web)
        val calls = web.jobCalls
        advanceTimeBy(20_000)
        assertEquals(calls, web.jobCalls)
    }

    // ---- E1: top 3 categories for you + category search ----

    private fun facetsOf(vararg c: Pair<String, Int>) = c.map { CategoryFacet(it.first, it.second) }

    @Test fun newUserGetsTheThreeLargestCategories() {
        val f = facetsOf("Folk" to 9, "Hymns" to 30, "Worship" to 20, "Christmas" to 14, "90s" to 2)
        assertEquals(listOf("Hymns", "Worship", "Christmas"), topCategories(f, HistorySignals()).map { it.name })
    }

    @Test fun aFrequentCategoryComesFirstEvenWhenSmall() {
        val f = facetsOf("Hymns" to 30, "Worship" to 20, "Christmas" to 14, "rabindra-sangeet" to 3)
        val s = HistorySignals(categoryCounts = mapOf("rabindra-sangeet" to 5, "Missing" to 99))
        assertEquals(listOf("rabindra-sangeet", "Hymns", "Worship"), topCategories(f, s).map { it.name })
    }

    @Test fun historyMatchesFacetNamesIgnoringCaseAndTiesFallBackToSize() {
        val f = facetsOf("Hymns" to 30, "Worship" to 20, "Folk" to 9, "Christmas" to 14)
        val s = HistorySignals(categoryCounts = mapOf("folk" to 2, "WORSHIP" to 2))
        assertEquals(listOf("Worship", "Folk", "Hymns"), topCategories(f, s).map { it.name })
    }

    @Test fun fewerThanThreeFacetsAreAllShown() {
        assertEquals(listOf("A"), topCategories(facetsOf("A" to 1), HistorySignals()).map { it.name })
        assertTrue(topCategories(emptyList(), HistorySignals()).isEmpty())
    }

    @Test fun exploreUiCarriesAtMostThreeTopCategoriesFromHistory() = runTest(dispatcher) {
        fake.facets = fake.facets.copy(categories = (1..30).map { CategoryFacet("Cat$it", it) } + CategoryFacet("rabindra-sangeet", 1))
        fake.signals = HistorySignals(categoryCounts = mapOf("rabindra-sangeet" to 5))
        val vm = fake.vm()
        advanceUntilIdle()
        assertEquals(listOf("rabindra-sangeet", "Cat30", "Cat29"), vm.explore.value.topCategories.map { it.name })
    }

    @Test fun categorySearchMatchesPrefixOrWordIgnoringCaseAndDiacritics() {
        val f = facetsOf("90s" to 4, "Rabindra Sangeet" to 3, "Bhajan" to 7, "Café Songs" to 2, "Hymns" to 30)
        assertTrue(findCategories(f, "nine").isEmpty())
        assertEquals(listOf("90s"), findCategories(f, "90").map { it.name })
        assertEquals(listOf("Rabindra Sangeet"), findCategories(f, "SANG").map { it.name })
        assertEquals(listOf("Café Songs"), findCategories(f, "cafe").map { it.name })
        assertTrue(findCategories(f, "  ").isEmpty())
        assertTrue(findCategories(f, "ymn").isEmpty())
    }

    @Test fun choosingACategoryBrowsesItFromPageZeroAndRecordsIt() = runTest(dispatcher) {
        val vm = fake.vm()
        advanceUntilIdle()
        vm.selectCategory("Worship")
        advanceUntilIdle()
        assertEquals(Triple<String?, String?, Int>(null, "Worship", 0), fake.browseCalls.last())
        assertEquals(listOf("Worship"), fake.recorded)
        vm.selectCategory(null)
        advanceUntilIdle()
        assertEquals(listOf("Worship"), fake.recorded)
    }

    @Test fun topCategoriesRefreshWhenExploreIsShownAgainNotWhileBrowsing() = runTest(dispatcher) {
        fake.facets = fake.facets.copy(categories = listOf(CategoryFacet("Hymns", 30), CategoryFacet("Worship", 20), CategoryFacet("Folk", 9), CategoryFacet("Christmas", 14), CategoryFacet("90s", 2)))
        val vm = fake.vm()
        advanceUntilIdle()
        assertEquals(listOf("Hymns", "Worship", "Christmas"), vm.explore.value.topCategories.map { it.name })
        // The user browses 90s five times; its tally lands in history, but the tiles stay put while it is open.
        fake.signals = HistorySignals(categoryCounts = mapOf("90s" to 5))
        vm.selectCategory("90s")
        advanceUntilIdle()
        assertEquals(listOf("Hymns", "Worship", "Christmas"), vm.explore.value.topCategories.map { it.name })
        vm.selectCategory(null)
        advanceUntilIdle()
        assertEquals("90s", vm.explore.value.topCategories.first().name)
    }

    @Test fun topCategoriesRefreshWhenASearchIsCleared() = runTest(dispatcher) {
        fake.facets = fake.facets.copy(categories = listOf(CategoryFacet("Hymns", 30), CategoryFacet("Worship", 20), CategoryFacet("Folk", 9), CategoryFacet("Christmas", 14)))
        val vm = fake.vm()
        advanceUntilIdle()
        vm.setQuery("grace"); advanceUntilIdle()
        fake.signals = HistorySignals(categoryCounts = mapOf("Folk" to 3))
        vm.setQuery(""); advanceUntilIdle()
        assertEquals("Folk", vm.explore.value.topCategories.first().name)
    }
}
