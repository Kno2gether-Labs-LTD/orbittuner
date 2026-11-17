package dev.avijit.orbittuner.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.avijit.orbittuner.OrbitTunerApp
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.AccountState
import dev.avijit.orbittuner.net.ApiError
import java.util.concurrent.TimeUnit

/** Runs [LibraryRepository.sync] (then refreshes cached songs) in the background. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as? OrbitTunerApp)?.graph ?: return Result.failure()
        return runSync(runAttemptCount) {
            requireAccount(graph.accounts)
            graph.library.sync()
            graph.songs.refreshCached()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 8

        /**
         * Every install has an account; make sure it exists (first launch offline) before syncing. Throws
         * [ApiError.AccountDisabled] when disabled (no retries) and [NoAccountYetException] when it can't be made yet.
         */
        suspend fun requireAccount(accounts: AccountManager) {
            if (accounts.ensureAccount()) return
            if (accounts.state.value == AccountState.Disabled) throw ApiError.AccountDisabled
            throw NoAccountYetException()
        }

        /**
         * Maps sync errors to WorkManager results. Offline / server trouble / no account yet: retry with backoff (data
         * stays dirty). TokenRevoked / Unauthorized: stop; AccountManager replaces a revoked account, and the new
         * account requests a sync. AccountDisabled: stop (no sync while disabled).
         */
        suspend fun runSync(attempt: Int, sync: suspend () -> Unit): Result = try {
            sync()
            Result.success()
        } catch (e: AccountChangedException) {
            Result.retry() // the account changed mid-sync (new account, sign-out); not a credentials problem
        } catch (e: NoAccountYetException) {
            if (attempt >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        } catch (e: ApiError) {
            when (e) {
                ApiError.TokenRevoked, ApiError.Unauthorized, ApiError.AccountDisabled -> Result.failure()
                else -> if (attempt >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
    }
}

/** The account couldn't be created yet (offline, rate limited, service unavailable); AccountManager keeps trying. */
class NoAccountYetException : Exception("no account yet")

object SyncScheduler {
    const val UNIQUE_ONCE = "orbittuner-sync"
    const val UNIQUE_PERIODIC = "orbittuner-sync-periodic"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * One sync soon after a local write. KEEP leaves a pending/running sync alone; a running sync loops while dirty
     * rows remain (LibraryRepository.sync), so edits made during it are still pushed.
     */
    fun request(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, req)
    }

    /** App start: sync now, and keep a 6-hourly sync scheduled. */
    fun onAppStart(ctx: Context) {
        request(ctx)
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** Like [request], but never throws (WorkManager may be unavailable, e.g. in some tests). */
    fun requestSafely(ctx: Context) {
        try { request(ctx) } catch (e: IllegalStateException) { Log.w("SyncScheduler", "sync not scheduled", e) }
    }
}
