package dev.avijit.orbittuner.net

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: AuthStore
    private lateinit var api: Api

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        auth = AuthStore(InMemoryKv())
        auth.save("h", "t", UserDto("u1", "Sam", "owner"))
        api = Api(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            token = { auth.token() },
            client = OkHttpClient(),
            onTokenRevoked = { t -> auth.markRevoked(t) },
            urlForHost = { "http://$it" },
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    private val playlistJson = """{"id":"p1","name":"Set","deleted":false,"updatedAt":5,"items":[{"songId":"s1","position":"a","settings":{"transpose":2}}]}"""

    @Test fun createAnonymousPostsDeviceNameWithoutTokenAndParses201() = runTest {
        server.enqueue(json(201, """{"deviceToken":"tok","user":{"id":"u","role":"user","plan":"free"},"extra":1}"""))
        val r = api.createAnonymous("${server.hostName}:${server.port}", "Pixel 8")
        assertEquals("tok", r.deviceToken); assertEquals("user", r.user.role); assertEquals("free", r.user.plan)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/v1/accounts/anonymous", req.path)
        assertNull(req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("deviceName"), body.keys)
        assertEquals("Pixel 8", body["deviceName"]!!.jsonPrimitive.content)
    }

    @Test fun createAnonymousRateLimitedIsHttp429() = runTest {
        server.enqueue(json(429, """{"error":"rate_limited"}"""))
        try { api.createAnonymous("${server.hostName}:${server.port}", "X"); fail() } catch (e: ApiError.Http) {
            assertEquals(429, e.code); assertEquals("rate_limited", e.error)
        }
    }

    @Test fun accountResponseToStringHidesToken() {
        assertFalse(AccountResponse("secret", UserDto("u", role = "user")).toString().contains("secret"))
    }

    @Test fun meParsesPlanAndAllowance() = runTest {
        server.enqueue(json(200, """{"id":"u1","role":"user","plan":"plus","planExpiresAt":null,"google":{"email":"a@b.c"},"aiLeftToday":48,"aiDailyLimit":50}"""))
        val me = api.me()
        assertEquals("plus", me.plan); assertEquals(48, me.aiLeftToday); assertEquals(50, me.aiDailyLimit); assertEquals("a@b.c", me.google?.email)
        server.enqueue(json(200, """{"id":"u1","role":"user","plan":"free","planExpiresAt":null,"google":null,"aiLeftToday":3,"aiDailyLimit":3}"""))
        assertNull(api.me().google)
        val req = server.takeRequest()
        assertEquals("/v1/me", req.path); assertEquals("Bearer t", req.getHeader("Authorization"))
    }

    @Test fun deleteThisDeviceSendsDeleteWithToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteThisDevice()
        val req = server.takeRequest()
        assertEquals("DELETE", req.method); assertEquals("/v1/devices/me", req.path); assertEquals("Bearer t", req.getHeader("Authorization"))
    }

    @Test fun accountDisabledThrowsAndReportsTheToken() = runTest {
        val seen = ArrayList<String>()
        val a = Api({ server.url("/").toString().trimEnd('/') }, { auth.token() }, OkHttpClient(), onAccountDisabled = { seen += it })
        server.enqueue(json(403, """{"error":"account_disabled"}"""))
        try { a.facets(); fail() } catch (e: ApiError) { assertSame(ApiError.AccountDisabled, e) }
        assertEquals(listOf("t"), seen)
        assertFalse(auth.needsNewAccount.value)
        server.enqueue(json(403, """{"error":"forbidden"}"""))
        try { a.facets(); fail() } catch (e: ApiError.Http) { assertEquals(403, e.code) }
        assertEquals(1, seen.size)
    }

    @Test fun linkGooglePostsTheIdTokenWithTheDeviceTokenAndParsesARebind() = runTest {
        server.enqueue(json(200, """{"deviceToken":"t","user":{"id":"x9","role":"user","plan":"plus","google":{"email":"a@b.c"}}}"""))
        val r = api.linkGoogle("id-tok")
        assertEquals("t", r.deviceToken); assertEquals("x9", r.user.id); assertEquals("plus", r.user.plan)
        assertEquals("a@b.c", r.user.google?.email)
        assertFalse(GoogleAccountResponse("secret", GoogleUser("u", "user")).toString().contains("secret"))
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/v1/accounts/google", req.path); assertEquals("Bearer t", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("id-tok", body["idToken"]!!.jsonPrimitive.content)

        server.enqueue(json(200, """{"user":{"id":"u1","role":"user","plan":"free","google":{"email":"a@b.c"}}}"""))
        assertNull(api.linkGoogle("id-tok").deviceToken)
    }

    @Test fun linkGoogleInvalidTokenIsHttp401NotUnauthorized() = runTest {
        server.enqueue(json(401, """{"error":"invalid_google_token"}"""))
        try { api.linkGoogle("bad"); fail() } catch (e: ApiError.Http) { assertEquals(401, e.code); assertEquals("invalid_google_token", e.error) }
        assertFalse(auth.needsNewAccount.value)
    }

    @Test fun linkGoogleDisabledTargetDoesNotDisableThisDevice() = runTest {
        val seen = ArrayList<String>()
        val a = Api({ server.url("/").toString().trimEnd('/') }, { auth.token() }, OkHttpClient(), onAccountDisabled = { seen += it })
        server.enqueue(json(403, """{"error":"account_disabled"}"""))
        try { a.linkGoogle("id"); fail() } catch (e: ApiError) { assertSame(ApiError.AccountDisabled, e) }
        assertTrue(seen.isEmpty())
    }

    @Test fun searchSendsBearerAndEncodesQuery() = runTest {
        server.enqueue(json(200, """{"items":[{"id":"1","title":"T","artist":null,"language":"en","categories":[],"originalKey":null,"version":1}],"nextPage":null}"""))
        val p = api.search("AC/DC", null, "rock", 2)
        assertEquals(1, p.items.size)
        val req = server.takeRequest()
        assertEquals("Bearer t", req.getHeader("Authorization"))
        assertTrue(req.path!!, req.path!!.contains("q=AC%2FDC"))
        assertTrue(req.path!!.contains("cat=rock")); assertTrue(req.path!!.contains("page=2")); assertFalse(req.path!!.contains("lang="))
    }

    @Test fun tokenRevokedThrowsAndMarksRepair() = runTest {
        server.enqueue(json(401, """{"error":"token_revoked"}"""))
        try { api.facets(); fail() } catch (e: ApiError) { assertSame(ApiError.TokenRevoked, e) }
        assertTrue(auth.needsNewAccount.value)
        assertNull(auth.token())
    }

    @Test fun otherUnauthorizedDoesNotMarkRepair() = runTest {
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        try { api.facets(); fail() } catch (e: ApiError) { assertSame(ApiError.Unauthorized, e) }
        assertFalse(auth.needsNewAccount.value)
    }

    @Test fun stalePlaylistCarriesCurrent() = runTest {
        server.enqueue(json(409, """{"error":"stale","current":$playlistJson}"""))
        try {
            api.putPlaylist(Playlist("p1", "Set", false, 3, emptyList())); fail()
        } catch (e: ApiError.Stale) {
            assertEquals("p1", e.current.id); assertEquals(2, e.current.items[0].settings.transpose); assertEquals(5L, e.current.updatedAt)
        }
        val req = server.takeRequest()
        assertEquals("PUT", req.method); assertEquals("/v1/me/playlists/p1", req.path)
    }

    @Test fun connectionRefusedIsOffline() = runTest {
        server.shutdown()
        try { api.facets(); fail() } catch (e: ApiError) { assertSame(ApiError.Offline, e) }
    }

    @Test fun otherErrorsAreHttp() = runTest {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try { api.song("x"); fail() } catch (e: ApiError.Http) { assertEquals(404, e.code); assertEquals("not_found", e.error) }
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>"))
        try { api.song("x"); fail() } catch (e: ApiError.Http) { assertEquals(502, e.code); assertNull(e.error) }
    }

    @Test fun duplicateAddSong() = runTest {
        server.enqueue(json(409, """{"error":"duplicate","songId":"s9"}"""))
        try { api.addSong("{title: T}", null, null, null); fail() } catch (e: ApiError.Duplicate) { assertEquals("s9", e.songId) }
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("chordpro"), body.keys)
    }

    @Test fun changesChunksIds() = runTest {
        server.enqueue(json(200, """{"items":[]}""")); server.enqueue(json(200, """{"items":[]}"""))
        api.changes(7, (1..201).map { "id$it" })
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().path!!.startsWith("/v1/songs/changes?since=7&ids=id1%2Cid2"))
    }

    @Test fun favouritesAndPlaylistsRoundTrip() = runTest {
        server.enqueue(json(200, """{"items":[{"songId":"s","favourite":true,"updatedAt":9}]}"""))
        val f = api.putFavourites(listOf(FavouriteItem("s", true, 9)))
        assertEquals(true, f[0].favourite)
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["items"]!!.toString()
        assertEquals("""[{"songId":"s","favourite":true,"updatedAt":9}]""", sent)
        server.enqueue(json(200, """{"items":[$playlistJson],"cursor":1700000000123}"""))
        val page = api.getPlaylists(42)
        assertEquals(1, page.items.size)
        assertEquals(1_700_000_000_123L, page.cursor) // server-stamped, returned verbatim
        assertEquals("/v1/me/playlists?since=42", server.takeRequest().path)
    }

    @Test fun exportZipReturnsBytes() = runTest {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(80, 75, 3, 4))))
        assertArrayEquals(byteArrayOf(80, 75, 3, 4), api.exportZip(listOf("a", "b")))
        assertEquals("/v1/export?ids=a%2Cb", server.takeRequest().path)
    }

    @Test fun unpairedThrowsUnauthorizedWithoutNetwork() = runTest {
        auth.clear()
        try { api.facets(); fail() } catch (e: ApiError) { assertSame(ApiError.Unauthorized, e) }
        assertEquals(0, server.requestCount)
    }

    // ---- contributions: add song, find on the web, flag ----

    private fun bodyOf(req: okhttp3.mockwebserver.RecordedRequest) = Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    private val songJson = """{"id":"n1","title":"New Song","chordpro":"{title: New Song}","status":"review"}"""

    @Test fun createSongSendsEachInputShape() = runTest {
        repeat(3) { server.enqueue(json(201, songJson)) }
        val s = api.createSong(NewSong.ChordPro("{title: X}\n[G]la"))
        assertEquals("review", s.status)
        var req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/v1/songs", req.path); assertEquals("Bearer t", req.getHeader("Authorization"))
        assertEquals(setOf("chordpro"), bodyOf(req).keys)

        api.createSong(NewSong.Text("G  C\nla la", title = "T", artist = null))
        req = server.takeRequest()
        val text = bodyOf(req)
        assertEquals(setOf("text", "title", "ai"), text.keys)
        assertEquals("auto", text["ai"]!!.jsonPrimitive.content)

        api.createSong(NewSong.Link("https://example.com/song"))
        val link = bodyOf(server.takeRequest())
        assertEquals(setOf("url"), link.keys)
        assertEquals("https://example.com/song", link["url"]!!.jsonPrimitive.content)
    }

    @Test fun planLimitCarriesLimitAndPlan() = runTest {
        server.enqueue(json(429, """{"error":"plan_limit_reached","limit":3,"plan":"free"}"""))
        try { api.createSong(NewSong.Link("https://e.com/x")); fail() } catch (e: ApiError.Limit) {
            assertEquals("plan_limit_reached", e.error); assertEquals(3, e.limit); assertEquals("free", e.plan)
        }
        server.enqueue(json(429, """{"error":"search_limit_reached","limit":5,"plan":"plus"}"""))
        try { api.discover("grace", null); fail() } catch (e: ApiError.Limit) {
            assertEquals("search_limit_reached", e.error); assertEquals(5, e.limit); assertEquals("plus", e.plan)
        }
        // Other 429s stay Http (e.g. the per-minute rate limit).
        server.enqueue(json(429, """{"error":"rate_limited"}"""))
        try { api.createSong(NewSong.Link("https://e.com/x")); fail() } catch (e: ApiError.Http) { assertEquals("rate_limited", e.error) }
    }

    @Test fun discoverStartsAJobOrReportsNoResults() = runTest {
        server.enqueue(json(202, """{"jobId":"j1","candidates":3}"""))
        val started = api.discover("amazing grace", "hi")
        assertEquals("j1", started.jobId); assertEquals(3, started.candidates)
        val body = bodyOf(server.takeRequest().also { assertEquals("/v1/discover", it.path) })
        assertEquals("amazing grace", body["q"]!!.jsonPrimitive.content)
        assertEquals("hi", body["lang"]!!.jsonPrimitive.content)

        server.enqueue(json(200, """{"jobId":null,"results":0}"""))
        val none = api.discover("zzzz", null)
        assertNull(none.jobId)
        assertEquals(setOf("q"), bodyOf(server.takeRequest()).keys)
    }

    @Test fun importJobDecodes() = runTest {
        server.enqueue(json(200, """{"id":"j1","status":"running","kind":"discover","query":"grace","total":3,"done":1,
            "items":[{"url":"https://a","status":"review","songId":"s1"},{"url":"https://b","status":"queued"},{"url":"https://c","status":"failed","error":"fetch_failed"}]}"""))
        val j = api.importJob("j1")
        assertEquals("/v1/import/jobs/j1", server.takeRequest().path)
        assertEquals("running", j.status); assertEquals(3, j.total); assertEquals(1, j.done)
        assertEquals("s1", j.items[0].songId); assertEquals("review", j.items[0].status); assertEquals("fetch_failed", j.items[2].error)
    }

    @Test fun flagSendsReasonAndNote() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.flag("s1", FlagReason.WrongChords, "  bridge is off ")
        val req = server.takeRequest()
        assertEquals("/v1/songs/s1/report", req.path)
        val b = bodyOf(req)
        assertEquals("wrong_chords", b["reason"]!!.jsonPrimitive.content)
        assertEquals("bridge is off", b["note"]!!.jsonPrimitive.content)

        server.enqueue(MockResponse().setResponseCode(204))
        api.flag("s1", FlagReason.NotASong, "  ")
        assertEquals(setOf("reason"), bodyOf(server.takeRequest()).keys)

        server.enqueue(json(400, """{"error":"own_song"}"""))
        try { api.flag("s1", FlagReason.Other, null); fail() } catch (e: ApiError.Http) { assertEquals("own_song", e.error) }
    }

    // ---- voice ----

    private val voiceJson = """{"intent":"lyrics","transcript":"dil se gao","language":"hi","guesses":[{"title":"Dil Se Gao","artist":"Meera Sen"}],
        "webQuery":"dil se gao chords","results":[{"id":"s1","title":"Dil Se Gao","artist":"Meera Sen","language":"hi","categories":["bollywood"],
        "originalKey":"Am","version":2,"match":"lyrics","extra":1}]}"""

    @Test fun voicePostsRawWavWithHintsAndParses() = runTest {
        server.enqueue(json(200, voiceJson))
        val wav = byteArrayOf(1, 2, 3, 4)
        val r = api.voice(wav, listOf("hi", "bn"), listOf("Meera Sen", "Rabindranath Tagore"))
        assertEquals("lyrics", r.intent); assertEquals("dil se gao", r.transcript); assertEquals("hi", r.language)
        assertEquals(listOf(VoiceGuess("Dil Se Gao", "Meera Sen")), r.guesses)
        assertEquals("dil se gao chords", r.webQuery)
        val s = r.results.single()
        assertEquals("s1", s.id); assertEquals("lyrics", s.match); assertEquals(listOf("bollywood"), s.categories); assertEquals("Am", s.originalKey)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/voice", req.requestUrl!!.encodedPath)
        assertEquals("hi,bn", req.requestUrl!!.queryParameter("langs"))
        assertEquals("Meera Sen,Rabindranath Tagore", req.requestUrl!!.queryParameter("artists"))
        assertEquals("Bearer t", req.getHeader("Authorization"))
        assertEquals("audio/wav", req.getHeader("Content-Type"))
        assertArrayEquals(wav, req.body.readByteArray())
    }

    @Test fun voiceHintsAreCleanedToTheServiceLimits() = runTest {
        server.enqueue(json(200, voiceJson))
        api.voice(
            ByteArray(4),
            listOf("HI", "xx", "bn", "hi", "ta", "te"),
            listOf("A, B", " ", "C".repeat(80), "D", "E", "F", "G", "H"),
        )
        val url = server.takeRequest().requestUrl!!
        assertEquals("hi,bn,ta", url.queryParameter("langs"))
        val artists = url.queryParameter("artists")!!.split(",")
        assertEquals(listOf("A B", "C".repeat(60), "D", "E", "F"), artists)
    }

    @Test fun voiceArtistHintsLoseControlCharacters() = runTest {
        server.enqueue(json(200, voiceJson))
        api.voice(ByteArray(4), emptyList(), listOf("A\u0000B\u0007", "\u0001\u001f", "C\tD\nE", "F\u007f"))
        val artists = server.takeRequest().requestUrl!!.queryParameter("artists")!!.split(",")
        assertEquals(listOf("A B", "C D E", "F"), artists)
        assertTrue(artists.none { a -> a.any { it.isISOControl() } })
    }

    @Test fun voiceWithoutHintsSendsNoQuery() = runTest {
        server.enqueue(json(200, """{"intent":"none","transcript":"","language":null,"guesses":[],"webQuery":null,"results":[]}"""))
        val r = api.voice(ByteArray(4), emptyList(), emptyList())
        assertEquals("none", r.intent); assertNull(r.webQuery); assertTrue(r.results.isEmpty())
        assertNull(server.takeRequest().requestUrl!!.query)
    }

    @Test fun voiceErrors() = runTest {
        server.enqueue(json(429, """{"error":"voice_limit_reached","limit":3,"plan":"anonymous"}"""))
        try { api.voice(ByteArray(4), emptyList(), emptyList()); fail() } catch (e: ApiError.Limit) {
            assertEquals("voice_limit_reached", e.error); assertEquals(3, e.limit); assertEquals("anonymous", e.plan)
        }
        server.enqueue(json(400, """{"error":"bad_audio"}"""))
        try { api.voice(ByteArray(4), emptyList(), emptyList()); fail() } catch (e: ApiError.Http) {
            assertEquals(400, e.code); assertEquals("bad_audio", e.error)
        }
        server.enqueue(json(503, """{"error":"voice_unavailable"}"""))
        try { api.voice(ByteArray(4), emptyList(), emptyList()); fail() } catch (e: ApiError.Http) {
            assertEquals(503, e.code); assertEquals("voice_unavailable", e.error)
        }
    }

    @Test fun voiceParsesRomanized() = runTest {
        server.enqueue(json(200, """{"intent":"lyrics","transcript":"আমি তোমাকে","romanized":"ami tomake","results":[]}"""))
        assertEquals("ami tomake", api.voice(ByteArray(4), emptyList(), emptyList()).romanized)
        server.enqueue(json(200, voiceJson))
        assertEquals(null, api.voice(ByteArray(4), emptyList(), emptyList()).romanized)
    }

    /** A slow service (read timeout after the request went out) is not "offline": it must not read as no connection. */
    @Test fun aVoiceReadTimeoutIsATimeoutNotOffline() = runTest {
        val slow = Api(
            baseUrl = { server.url("/").toString().trimEnd('/') }, token = { auth.token() },
            client = OkHttpClient(), voiceReadTimeoutMs = 300,
        )
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        try { slow.voice(ByteArray(4), emptyList(), emptyList()); fail() } catch (e: ApiError.Http) {
            assertEquals(504, e.code); assertEquals("timeout", e.error)
        }
    }

    @Test fun otherCallsStillReadATimeoutAsOffline() = runTest {
        val slow = Api(
            baseUrl = { server.url("/").toString().trimEnd('/') }, token = { auth.token() },
            client = OkHttpClient.Builder().readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        try { slow.facets(); fail() } catch (e: ApiError) { assertEquals(ApiError.Offline, e) }
    }

}

class ApiHardeningTest {
    private lateinit var server: MockWebServer
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun api(auth: AuthStore, client: OkHttpClient = OkHttpClient()) = Api(
        baseUrl = { server.url("/").toString().trimEnd('/') }, token = { auth.token() }, client = client,
        onTokenRevoked = { t -> auth.markRevoked(t) }, urlForHost = { "http://$it" },
    )

    private fun pairedAuth(token: String = "t") = AuthStore(InMemoryKv()).also { it.save("h", token, UserDto("u", "n", "owner")) }

    @Test fun boundClientKeepsItsTokenWhenAuthChanges() = runTest {
        val auth = pairedAuth("tokA")
        val snap = auth.snapshot()!!
        assertEquals(AuthSnapshot("h", "u", "tokA"), snap)
        val bound = api(auth).bound(server.url("/").toString().trimEnd('/'), snap.token)
        auth.save("h2", "tokB", UserDto("u2", "n", "member"))
        server.enqueue(json(200, """{"items":[]}"""))
        bound.getFavourites()
        assertEquals("Bearer tokA", server.takeRequest().getHeader("Authorization"))
        auth.markRevoked()
        assertNull(auth.snapshot())
    }

    @Test fun cancellationCancelsCallAndIsNotOffline() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val canceled = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun canceled(call: okhttp3.Call) { canceled.countDown() }
        }).build()
        val a = api(pairedAuth(), client)
        var outcome: Throwable? = null
        val elapsed = kotlin.system.measureTimeMillis {
            kotlinx.coroutines.runBlocking {
                val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                    try { a.facets() } catch (e: Throwable) { outcome = e; throw e }
                }
                server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
                job.cancel()
                job.join()
            }
        }
        assertTrue("took $elapsed ms", elapsed < 1000)
        assertTrue(outcome is kotlinx.coroutines.CancellationException)
        assertTrue("call not cancelled", canceled.await(1, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun lateRevokeForOldTokenLeavesNewPairingIntact() = runTest {
        val auth = pairedAuth("T1")
        val a = Api(
            baseUrl = { server.url("/").toString().trimEnd('/') }, token = { auth.token() }, client = OkHttpClient(),
            onTokenRevoked = { t -> auth.markRevoked(t) }, urlForHost = { "http://$it" },
        )
        // The response arrives only after the user re-paired with T2.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                auth.save("h", "T2", UserDto("u", "n", "owner")) // re-paired while the request was in flight
                return json(401, """{"error":"token_revoked"}""")
            }
        }
        try { a.facets(); fail() } catch (e: ApiError) { assertSame(ApiError.TokenRevoked, e) }
        assertFalse(auth.needsNewAccount.value)
        assertEquals("T2", auth.token())
    }

    @Test fun redirectsAreNotFollowedByAppClient() {
        // Contract check on the client built by AppGraph is covered by construction; here: api surfaces 302 as Http.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example/"))
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        kotlinx.coroutines.runBlocking {
            try { api(pairedAuth(), client).facets(); fail() } catch (e: ApiError.Http) { assertEquals(302, e.code) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun dotSegmentsRejected() = runTest {
        val a = api(pairedAuth())
        for (id in listOf(".", "..", "")) {
            try { a.song(id); fail("id '$id'") } catch (e: ApiError.Http) { assertEquals(400, e.code) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun oversizedErrorBodyIsCapped() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(500_000)))
        try { api(pairedAuth()).facets(); fail() } catch (e: ApiError.Http) { assertEquals(500, e.code); assertNull(e.error) }
    }

    @Test fun putPlaylistOmitsNullSettings() = runTest {
        server.enqueue(json(200, """{"id":"p1","name":"S","deleted":false,"updatedAt":5,"items":[]}"""))
        api(pairedAuth()).putPlaylist(Playlist("p1", "S", false, 5, listOf(PlaylistItem("s1", "a", ItemSettings(transpose = 2)))))
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("null"))
        assertTrue(body, body.contains(""""settings":{"transpose":2}"""))
    }

    @Test fun nonJsonUnauthorizedIsUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("<html>nope</html>"))
        try { api(pairedAuth()).facets(); fail() } catch (e: ApiError) { assertSame(ApiError.Unauthorized, e) }
    }

    @Test fun staleWithUnparseableCurrentIsHttp409() = runTest {
        server.enqueue(json(409, """{"error":"stale","current":{"bogus":1}}"""))
        try { api(pairedAuth()).putPlaylist(Playlist("p1", "S", false, 5, emptyList())); fail() } catch (e: ApiError.Http) {
            assertEquals(409, e.code); assertEquals("stale", e.error)
        }
    }

}
