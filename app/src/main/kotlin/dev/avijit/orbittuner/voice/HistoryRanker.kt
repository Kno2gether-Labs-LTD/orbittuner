package dev.avijit.orbittuner.voice

/**
 * One candidate song for a sung line or spoken request. [match]: "lyrics" or "title" from the service, or [LIBRARY]
 * for a match found in the user's own favourites/playlists on the device.
 */
data class VoiceHit(
    val id: String,
    val title: String,
    val artist: String?,
    val language: String,
    val categories: List<String>,
    val originalKey: String?,
    val match: String,
) {
    companion object { const val LIBRARY = "library" }
}

/** A ranked candidate. [library]: in the user's favourites or playlists (listed first). [reason]: why it ranks high. */
data class RankedHit(val hit: VoiceHit, val score: Double, val reason: String?, val library: Boolean)

/**
 * Re-ranks voice results with on-device history (spec "Decisions → App → Ranking"). Every hit starts from the same base,
 * so the service's own order (lyrics/title relevance) decides ties; boosts are added on top. Library matches always
 * list first. Pure.
 */
object HistoryRanker {
    const val FAVOURITE = 3.0
    const val PLAYLIST = 2.0
    const val OPENED_30_DAYS = 1.5
    const val OPENED_3_DAYS = 0.5
    const val TOP_ARTIST = 1.0
    const val TOP_LANGUAGE = 0.5
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val RECENT_MS = 30 * DAY_MS
    const val VERY_RECENT_MS = 3 * DAY_MS

    fun rank(results: List<VoiceHit>, signals: HistorySignals, now: Long): List<RankedHit> {
        val artists = signals.topArtists.map(::norm).toSet()
        val langs = signals.topLanguages.map(::norm).toSet()
        val ranked = results.map { h ->
            val fav = h.id in signals.favourites
            val inPlaylist = h.id in signals.playlists
            val opened = signals.openedAt[h.id]?.let { now - it }?.takeIf { it in 0..RECENT_MS }
            val artist = h.artist?.let(::norm)?.takeIf { it.isNotEmpty() && it in artists }
            var score = 0.0
            if (fav) score += FAVOURITE
            if (inPlaylist) score += PLAYLIST
            if (opened != null) {
                score += OPENED_30_DAYS
                if (opened <= VERY_RECENT_MS) score += OPENED_3_DAYS
            }
            if (artist != null) score += TOP_ARTIST
            if (norm(h.language) in langs) score += TOP_LANGUAGE
            val reason = when {
                fav -> "In your favourites"
                inPlaylist -> "In your playlists"
                opened != null -> "You opened this recently"
                artist != null -> "You often play ${h.artist!!.trim()}"
                else -> null
            }
            RankedHit(h, score, reason, library = fav || inPlaylist || h.match == VoiceHit.LIBRARY)
        }
        // sortedWith is stable: equal scores keep the incoming (service) order.
        return ranked.sortedWith(compareByDescending<RankedHit> { it.library }.thenByDescending { it.score })
    }

    private fun norm(s: String) = s.trim().lowercase()
}
