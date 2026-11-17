package dev.avijit.orbittuner.net

import org.junit.Assert.*
import org.junit.Test

class AuthStoreTest {
    private val user = UserDto("u", "N", "owner")

    @Test fun persistsAcrossInstances() {
        val kv = InMemoryKv()
        AuthStore(kv).save("h.example", "tok", user)
        val b = AuthStore(kv)
        assertEquals("h.example", b.host.value); assertEquals(user, b.user.value); assertEquals("tok", b.token())
    }

    @Test fun revokedFlagSurvivesReinstantiationAndSaveClearsIt() {
        val kv = InMemoryKv()
        val a = AuthStore(kv); a.save("h", "T1", user); a.markRevoked("T1")
        val b = AuthStore(kv)
        assertTrue(b.needsNewAccount.value); assertNull(b.token()); assertEquals("h", b.host.value)
        b.save("h", "T2", user)
        assertFalse(b.needsNewAccount.value); assertEquals("T2", b.token())
        assertFalse(AuthStore(kv).needsNewAccount.value)
    }

    @Test fun markRevokedIgnoresStaleToken() {
        val a = AuthStore(InMemoryKv()); a.save("h", "T2", user)
        a.markRevoked("T1")
        assertFalse(a.needsNewAccount.value); assertEquals("T2", a.token())
    }

    @Test fun clearWipesEverything() {
        val a = AuthStore(InMemoryKv()); a.save("h", "T", user); a.clear()
        assertNull(a.host.value); assertNull(a.token()); assertNull(a.user.value)
    }

    @Test fun anonymousUserWithoutANamePersists() {
        val kv = InMemoryKv()
        AuthStore(kv).save("h", "T", UserDto("u9", role = "user", plan = "free"))
        assertEquals(UserDto("u9", role = "user", plan = "free"), AuthStore(kv).user.value)
    }
}
