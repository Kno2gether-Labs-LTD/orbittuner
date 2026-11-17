package dev.avijit.orbittuner.songs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avijit.orbittuner.add.ContributionService
import dev.avijit.orbittuner.add.DiscoverQuery
import dev.avijit.orbittuner.add.discoverLang
import dev.avijit.orbittuner.add.discoverQuery
import dev.avijit.orbittuner.add.WebFinder
import dev.avijit.orbittuner.add.WebState
import dev.avijit.orbittuner.add.WebTier
import dev.avijit.orbittuner.data.LocalResults
import dev.avijit.orbittuner.data.categories
import dev.avijit.orbittuner.data.db.CachedSong
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.CategoryFacet
import dev.avijit.orbittuner.net.Facets
import dev.avijit.orbittuner.net.LanguageFacet
import dev.avijit.orbittuner.net.Page
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.net.SongSummary
import dev.avijit.orbittuner.voice.HistorySignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** One song in any tier: what a list row needs. */
data class SongRow(
    val id: String,
    val title: String,
    val artist: String?,
    val key: String?,
    val language: String,
    val categories: List<String>,
)

fun CachedSong.toRow() = SongRow(id, title, artist, originalKey, language, categories())
fun SongSummary.toRow() = SongRow(id, title, artist, originalKey, language, categories)
fun Song.toRow() = SongRow(id, title, artist, originalKey, language, categories)

/**
 * Search results in tier order: "Your songs", "On this device", "Library", then "From the web" ([web], for this query
 * only, without songs already listed). [webAvailable]: this build can find songs on the web.
 */
data class SearchUi(
    val query: String, val yours: List<SongRow>, val cached: List<SongRow>, val library: LibraryTier,
    val web: WebTier = WebTier.Idle, val webAvailable: Boolean = false,
)

/** State of a service-backed list (the Library tier, and the Explore browse list). */
sealed interface LibraryTier {
    data object Idle : LibraryTier
    data object Loading : LibraryTier
    /** [moreFailed]: the last "load more" failed (rows kept; the UI offers a retry). */
    data class Results(val rows: List<SongRow>, val hasMore: Boolean, val moreFailed: Boolean = false) : LibraryTier
    data object Offline : LibraryTier
    data object NoAccount : LibraryTier
    /** An admin disabled this account: the service refuses every request. */
    data object AccountDisabled : LibraryTier
    /** The service answered with an error (not offline, not without an account). */
    data object Unavailable : LibraryTier
}

/** Explore (empty query): facets, the current filter, songs on this device, and the browse list. */
data class ExploreUi(
    val facets: FacetsState,
    val lang: String?,
    val cat: String?,
    val list: LibraryTier,
    val recent: List<SongRow> = emptyList(),
    /** At most [TOP_CATEGORIES] categories "for you" (see [topCategories]); the rest are found through the picker. */
    val topCategories: List<CategoryFacet> = emptyList(),
) {
    val languages: List<LanguageFacet> get() = (facets as? FacetsState.Loaded)?.languages.orEmpty()
    val categories: List<CategoryFacet> get() = (facets as? FacetsState.Loaded)?.categories.orEmpty()
}

/** Languages and categories for the chips and tiles. */
sealed interface FacetsState {
    data object Loading : FacetsState
    data object Error : FacetsState
    data class Loaded(val languages: List<LanguageFacet>, val categories: List<CategoryFacet>) : FacetsState
}

const val TOP_CATEGORIES = 3

/**
 * The categories to show "for you": highest history affinity first ([HistorySignals.categoryCounts], matched to facet
 * names ignoring case), ties and new users falling back to the largest category, then name. At most [TOP_CATEGORIES].
 */
fun topCategories(facets: List<CategoryFacet>, signals: HistorySignals): List<CategoryFacet> {
    val affinity = HashMap<String, Int>()
    for ((name, n) in signals.categoryCounts) affinity.merge(name.trim().lowercase(), n, Int::plus)
    return facets.sortedWith(
        compareByDescending<CategoryFacet> { affinity[it.name.trim().lowercase()] ?: 0 }
            .thenByDescending { it.count }.thenBy { it.name.lowercase() },
    ).take(TOP_CATEGORIES)
}

/** Language and category filter, shared by Explore and the Library tier of search. */
data class Filter(val lang: String? = null, val cat: String? = null)

/**
 * Tiered search and Explore. Local tiers follow every keystroke; the Library tier is debounced by [DEBOUNCE_MS] and each
 * new query (or filter) cancels the previous request ([collectLatest]), so a late answer for an older query is dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val localSearch: (String) -> Flow<LocalResults>,
    private val remoteSearch: suspend (q: String, lang: String?, cat: String?, page: Int) -> Page<SongSummary>,
    private val facets: suspend () -> Facets,
    private val browse: suspend (lang: String?, cat: String?, page: Int) -> Page<SongSummary>,
    hasAccount: Flow<Boolean>,
    /** Find on the web; null hides it. */
    contributions: ContributionService? = null,
    /** Fetches (and caches) one song for the web tier. */
    fetchSong: (suspend (String) -> SongRow)? = null,
    /** After an AI action (a web import): refresh the AI allowance. */
    onAiUsed: () -> Unit = {},
    /** The signed-in account (host|userId); a change drops the previous account's web results. */
    accountId: Flow<String?> = flowOf(null),
    /** What the device knows of the user's taste; read once, to pick the categories "for you". */
    history: suspend () -> HistorySignals = { HistorySignals() },
    /** A category was chosen in Explore (tile or picker): counted locally as taste. */
    private val onCategoryBrowsed: (String) -> Unit = {},
) : ViewModel() {
    private val finder: WebFinder? =
        if (contributions != null && fetchSong != null) WebFinder(contributions, fetchSong, viewModelScope, onAiUsed) else null
    private val webState = finder?.state ?: MutableStateFlow(WebState("", null, WebTier.Idle))
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(Filter())
    private val retries = MutableStateFlow(0)
    /** Bumped when the service answers again after facets failed (e.g. offline -> online). */
    private val facetsReload = MutableStateFlow(0)
    /** Some call failed with [ApiError.Offline] and none has succeeded since. */
    @Volatile private var offline = false
    private val hasAccount = hasAccount.distinctUntilChanged()

    /** Raw service rows for one search key; de-duplication against the local tiers happens in [ui]. */
    private data class Remote(val key: Key, val tier: LibraryTier, val nextPage: Int? = null)
    private data class Key(val q: String, val filter: Filter)

    private val remote = MutableStateFlow(Remote(Key("", Filter()), LibraryTier.Idle))
    private var remoteMore: Job? = null

    private val _explore = MutableStateFlow(ExploreUi(FacetsState.Loading, null, null, LibraryTier.Idle))
    val explore: StateFlow<ExploreUi> = _explore.asStateFlow()
    private var browseNext: Int? = null
    private var browseMore: Job? = null

    private val signals = MutableStateFlow(HistorySignals())
    private val readHistory = history
    private var tasteJob: Job? = null

    /** Reads taste again; the "for you" tiles pick it up. Called only when Explore comes back into view, never under the user's thumb. */
    private fun refreshTaste() {
        tasteJob?.cancel()
        tasteJob = viewModelScope.launch {
            try { signals.value = readHistory() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* keep what we had */ }
        }
    }

    private val trimmed = query.map { it.trim() }.distinctUntilChanged()

    private val local: Flow<LocalResults> = trimmed.flatMapLatest { q -> if (q.isEmpty()) flowOf(NO_LOCAL) else localSearch(q) }

    val ui: StateFlow<SearchUi> = combine(query, local, remote, webState) { q, l, r, w ->
        val yours = l.yours.map { it.toRow() }
        val cached = l.cached.map { it.toRow() }
        val shown = (yours + cached).mapTo(HashSet()) { it.id }
        val library = when {
            // A result for an older query is never shown, even for the frame before the new request starts.
            q.trim().length < MIN_REMOTE_CHARS -> LibraryTier.Idle
            r.key.q != q.trim() -> LibraryTier.Loading
            r.tier is LibraryTier.Results -> r.tier.copy(rows = r.tier.rows.filterNot { it.id in shown })
            else -> r.tier
        }
        val listed = shown + (library as? LibraryTier.Results)?.rows.orEmpty().map { it.id }
        val web = if (w.query.isEmpty() || w.query != q.trim()) WebTier.Idle else w.tier.without(listed)
        SearchUi(q, yours, cached, library, web, webAvailable = finder != null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUi("", emptyList(), emptyList(), LibraryTier.Idle, webAvailable = finder != null))

    init {
        // Another account: the old one's web results (some only it can see) and its polling go.
        if (finder != null) viewModelScope.launch {
            accountId.distinctUntilChanged().drop(1).collect { finder.clear() }
        }
        // A new query or language ends the web search for the old one (and its polling).
        if (finder != null) viewModelScope.launch {
            combine(trimmed, filter) { q, f -> q to f.lang }.collect { (q, lang) ->
                val w = finder.state.value
                if (w.tier != WebTier.Idle && (w.query != q || w.lang != discoverLang(lang))) finder.clear()
            }
        }
        viewModelScope.launch {
            combine(trimmed, filter, this@SearchViewModel.hasAccount, retries) { q, f, p, _ -> Triple(q, f, p) }
                .collectLatest { (q, f, signedIn) ->
                    remoteMore?.cancel()
                    val key = Key(q, f)
                    when {
                        q.length < MIN_REMOTE_CHARS -> remote.value = Remote(key, LibraryTier.Idle)
                        !signedIn -> remote.value = Remote(key, LibraryTier.NoAccount)
                        else -> {
                            remote.value = Remote(key, LibraryTier.Loading)
                            delay(DEBOUNCE_MS)
                            val next = load { remoteSearch(q, f.lang, f.cat, FIRST_PAGE) }
                            coroutineContext.ensureActive()
                            remote.value = Remote(key, next.first, next.second)
                        }
                    }
                }
        }
        viewModelScope.launch {
            combine(this@SearchViewModel.hasAccount, retries, facetsReload) { p, _, _ -> p }.collectLatest { signedIn ->
                if (!signedIn) return@collectLatest
                if (_explore.value.facets !is FacetsState.Loaded) _explore.update { it.copy(facets = FacetsState.Loading) }
                val state = try {
                    facets().let { FacetsState.Loaded(it.languages, it.categories) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e === ApiError.Offline) offline = true
                    FacetsState.Error
                }
                coroutineContext.ensureActive()
                // A failed reload keeps facets that were already shown.
                _explore.update { if (state is FacetsState.Error && it.facets is FacetsState.Loaded) it else it.copy(facets = state) }
            }
        }
        viewModelScope.launch {
            combine(filter, this@SearchViewModel.hasAccount, retries) { f, p, _ -> f to p }.collectLatest { (f, signedIn) ->
                browseMore?.cancel()
                _explore.update { it.copy(lang = f.lang, cat = f.cat) }
                if (!signedIn) {
                    browseNext = null
                    _explore.update { it.copy(list = LibraryTier.NoAccount) }
                    return@collectLatest
                }
                _explore.update { it.copy(list = LibraryTier.Loading) }
                val (tier, next) = load { browse(f.lang, f.cat, FIRST_PAGE) }
                coroutineContext.ensureActive()
                browseNext = next
                _explore.update { it.copy(list = tier) }
            }
        }
        viewModelScope.launch {
            // Read on start, then again each time Explore returns (see refreshTaste): tiles never reshuffle while in view.
            refreshTaste()
        }
        viewModelScope.launch {
            combine(_explore.map { it.categories }.distinctUntilChanged(), signals) { c, s -> topCategories(c, s) }
                .collect { top -> _explore.update { it.copy(topCategories = top) } }
        }
        viewModelScope.launch {
            localSearch("").collect { l ->
                _explore.update { e -> e.copy(recent = (l.yours + l.cached).take(RECENT).map { it.toRow() }) }
            }
        }
    }

    fun setQuery(q: String) {
        // Back to the empty-query Explore: taste may have changed since it was last in view.
        if (q.isBlank() && query.value.isNotBlank() && filter.value.cat == null) refreshTaste()
        query.value = q
    }

    /** Null clears the language filter; selecting the current language clears it too. */
    fun selectLanguage(code: String?) = filter.update { it.copy(lang = if (code == it.lang) null else code) }

    /** Null clears the category; choosing the current one clears it too. A newly chosen category is counted as taste. */
    fun selectCategory(name: String?) {
        val chosen = name != null && name != filter.value.cat
        filter.update { it.copy(cat = if (name == it.cat) null else name) }
        if (chosen) onCategoryBrowsed(name!!) else if (filter.value.cat == null && query.value.isBlank()) refreshTaste()
    }

    fun retry() = retries.update { it + 1 }

    /** Searches the web for the current query (and language filter); results stream into [SearchUi.web]. */
    fun findOnWeb() {
        val q = query.value.trim()
        if (discoverQuery(q) != DiscoverQuery.Ok) return
        finder?.find(q, discoverLang(filter.value.lang))
    }

    /**
     * Sing a line's "Search the web for '…'": puts [q] in the search box and starts Find on the web for it straight
     * away (the same discover flow as the button), whatever the library tier shows.
     */
    fun findOnWeb(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        setQuery(t)
        if (discoverQuery(t) != DiscoverQuery.Ok) return
        finder?.find(t, discoverLang(filter.value.lang))
    }

    fun retryWeb() { finder?.retry() }

    /** The search screen left (polling stops) and came back (polling resumes). */
    fun pauseWeb() { finder?.pause() }
    fun resumeWeb() { finder?.resume() }

    /** Next page of the Library tier, appended (ids already listed are skipped). */
    fun loadMore() {
        val r = remote.value
        val page = r.nextPage ?: return
        val rows = (r.tier as? LibraryTier.Results)?.rows ?: return
        if (remoteMore?.isActive == true) return
        remoteMore = viewModelScope.launch {
            val p = try {
                remoteSearch(r.key.q, r.key.filter.lang, r.key.filter.cat, page).also { reachable() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e === ApiError.Offline) offline = true
                remote.update { cur -> if (cur.key != r.key || cur.tier !is LibraryTier.Results) cur else cur.copy(tier = cur.tier.copy(moreFailed = true)) }
                return@launch
            }
            remote.update { cur ->
                if (cur.key != r.key) cur else cur.copy(tier = LibraryTier.Results(rows.appendNew(p.items), p.nextPage != null), nextPage = p.nextPage)
            }
        }
    }

    /** Next page of the Explore browse list. */
    fun loadMoreExplore() {
        val page = browseNext ?: return
        val start = _explore.value
        val rows = (start.list as? LibraryTier.Results)?.rows ?: return
        if (browseMore?.isActive == true) return
        browseMore = viewModelScope.launch {
            val p = try {
                browse(start.lang, start.cat, page).also { reachable() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e === ApiError.Offline) offline = true
                _explore.update { x -> if (x.lang != start.lang || x.cat != start.cat || x.list !is LibraryTier.Results) x else x.copy(list = x.list.copy(moreFailed = true)) }
                return@launch
            }
            if (_explore.value.lang != start.lang || _explore.value.cat != start.cat) return@launch
            browseNext = p.nextPage
            _explore.update { it.copy(list = LibraryTier.Results(rows.appendNew(p.items), p.nextPage != null)) }
        }
    }

    /** One page as a tier; any failure (not just [ApiError]) becomes a state, never a crash. */
    private suspend fun load(call: suspend () -> Page<SongSummary>): Pair<LibraryTier, Int?> = try {
        val p = call()
        reachable()
        LibraryTier.Results(p.items.map { it.toRow() }.distinctBy { it.id }, p.nextPage != null) to p.nextPage
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e === ApiError.Offline) offline = true
        tierFor(e) to null
    }

    /** The service just answered. Back from offline with failed facets: fetch them again. */
    private fun reachable() {
        if (!offline) return
        offline = false
        if (_explore.value.facets is FacetsState.Error) facetsReload.update { it + 1 }
    }

    private fun List<SongRow>.appendNew(items: List<SongSummary>): List<SongRow> {
        val have = mapTo(HashSet()) { it.id }
        return this + items.filter { have.add(it.id) }.map { it.toRow() }
    }

    /** Drops songs already listed in another tier. */
    private fun WebTier.without(listed: Set<String>): WebTier = when (this) {
        is WebTier.Importing -> copy(rows = rows.filterNot { it.row.id in listed })
        is WebTier.Done -> {
            val left = rows.filterNot { it.row.id in listed }
            if (left.isEmpty() && rows.isNotEmpty()) WebTier.Done(left, note ?: "What we found is already listed above") else copy(rows = left)
        }
        else -> this
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        /** Shorter queries search only this device. */
        const val MIN_REMOTE_CHARS = 2
        /** The service numbers pages from 0; asking for 1 first skipped the first 25 songs of every list. */
        const val FIRST_PAGE = 0
        private const val RECENT = 12
        private val NO_LOCAL = LocalResults(emptyList(), emptyList())

        fun tierFor(e: Exception): LibraryTier = when (e) {
            ApiError.Offline -> LibraryTier.Offline
            ApiError.TokenRevoked, ApiError.Unauthorized -> LibraryTier.NoAccount
            ApiError.AccountDisabled -> LibraryTier.AccountDisabled
            else -> LibraryTier.Unavailable
        }
    }
}
