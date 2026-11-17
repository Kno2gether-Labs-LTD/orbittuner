package dev.avijit.orbittuner.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.avijit.orbittuner.net.AccountManager
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.AuthStore
import dev.avijit.orbittuner.net.FakeAccounts
import dev.avijit.orbittuner.net.InMemoryKv
import dev.avijit.orbittuner.net.UserDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    @Test fun outcomeMapping() = runTest {
        assertEquals(Result.success(), SyncWorker.runSync(0) {})
        assertEquals(Result.retry(), SyncWorker.runSync(0) { throw ApiError.Offline })
        assertEquals(Result.retry(), SyncWorker.runSync(0) { throw ApiError.Http(503, null) })
        assertEquals(Result.failure(), SyncWorker.runSync(0) { throw ApiError.TokenRevoked })
        assertEquals(Result.failure(), SyncWorker.runSync(0) { throw ApiError.Unauthorized })
        assertEquals(Result.failure(), SyncWorker.runSync(0) { throw ApiError.AccountDisabled })
        // No account yet (offline first launch, or the service can't create one right now): retry later.
        assertEquals(Result.retry(), SyncWorker.runSync(0) { throw NoAccountYetException() })
        assertEquals(Result.failure(), SyncWorker.runSync(SyncWorker.MAX_ATTEMPTS) { throw ApiError.Http(503, null) })
    }

    @Test fun schedulerEnqueuesUniqueNetworkWorkAndPeriodic() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().build())
        SyncScheduler.onAppStart(ctx)
        SyncScheduler.request(ctx)
        val wm = WorkManager.getInstance(ctx)
        val once = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_ONCE).get()
        assertEquals(1, once.size) // KEEP: the second request didn't cancel/replace the first
        assertEquals(1, once.count { !it.state.isFinished })
        assertTrue(once.single { !it.state.isFinished }.constraints.requiredNetworkType != androidx.work.NetworkType.NOT_REQUIRED)
        val periodic = wm.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_PERIODIC).get()
        assertEquals(WorkInfo.State.ENQUEUED, periodic.single().state)
    }

    @Test fun disabledAccountFailsAtOnceAndNoAccountYetRetries() = runTest {
        val auth = AuthStore(InMemoryKv())
        val accounts = FakeAccounts().also { s -> s.tokenSeen = { auth.token() } }
        val m = AccountManager(accounts, auth, backgroundScope, serviceHost = { "h" }, deviceName = "X")
        accounts.failWith = ApiError.Offline
        assertEquals(Result.retry(), SyncWorker.runSync(0) { SyncWorker.requireAccount(m) })
        accounts.failWith = null
        auth.save("h", "tok1", UserDto("u1", role = "user"))
        m.accountDisabled("tok1")
        assertEquals(Result.failure(), SyncWorker.runSync(0) { SyncWorker.requireAccount(m) })
    }
}
