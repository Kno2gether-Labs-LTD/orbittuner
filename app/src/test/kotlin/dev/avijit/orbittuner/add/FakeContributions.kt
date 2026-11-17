package dev.avijit.orbittuner.add

import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.DiscoverStart
import dev.avijit.orbittuner.net.ImportJob
import dev.avijit.orbittuner.net.JobItem
import dev.avijit.orbittuner.net.NewSong
import dev.avijit.orbittuner.net.Song
import kotlinx.coroutines.delay

/** Scriptable contributions endpoints: POST /v1/songs, POST /v1/discover and the job poll. */
class FakeContributions : ContributionService {
    val created = ArrayList<NewSong>()
    /** Answer for POST /v1/songs (throw an [ApiError] to refuse). */
    var create: suspend (NewSong) -> Song = { input ->
        Song(id = "n1", title = (input as? NewSong.Text)?.title ?: "New song", chordpro = "{title: New song}", status = "published")
    }
    val discovers = ArrayList<Pair<String, String?>>()
    var discoverLatency = 0L
    var discover: suspend (String, String?) -> DiscoverStart = { _, _ -> DiscoverStart("j1", 3) }
    /** Successive poll answers; the last one repeats. An [Exception] entry is thrown. */
    val polls = ArrayList<Any>()
    var jobCalls = 0

    override suspend fun createSong(input: NewSong): Song { created += input; return create(input) }

    override suspend fun discover(q: String, lang: String?): DiscoverStart {
        discovers += q to lang
        if (discoverLatency > 0) delay(discoverLatency)
        return discover.invoke(q, lang)
    }

    override suspend fun importJob(id: String): ImportJob {
        val next = polls[minOf(jobCalls, polls.size - 1)]
        jobCalls++
        if (next is Exception) throw next
        return next as ImportJob
    }

    companion object {
        fun job(status: String, vararg items: JobItem) =
            ImportJob(id = "j1", status = status, kind = "discover", total = items.size, done = items.count { it.status != "queued" }, items = items.toList())
        fun item(n: Int, status: String, songId: String? = null, error: String? = null) = JobItem("https://chords.example/$n", status, songId, error)
        val Offline: Exception get() = ApiError.Offline
    }
}
