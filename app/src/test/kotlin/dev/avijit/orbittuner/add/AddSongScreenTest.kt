package dev.avijit.orbittuner.add

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.avijit.orbittuner.net.ApiError
import dev.avijit.orbittuner.net.Me
import dev.avijit.orbittuner.net.NewSong
import dev.avijit.orbittuner.net.Song
import dev.avijit.orbittuner.ui.theme.OrbitTunerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class AddSongScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeContributions()
    private val me = MutableStateFlow<Me?>(Me("u1", "user", plan = "free", aiLeftToday = 2, aiDailyLimit = 3))
    private var opened: String? = null
    private var backs = 0
    private var signIns = 0

    private fun show(dark: Boolean = true, tab: AddTab = AddTab.Write): AddSongViewModel {
        val vm = AddSongViewModel(fake, me, onAiUsed = {})
        rule.setContent {
            OrbitTunerTheme(darkTheme = dark) {
                AddSongScreen(vm, onBack = { backs++ }, onOpenSong = { opened = it }, onSignIn = { signIns++ }, initialTab = tab)
            }
        }
        settle()
        return vm
    }

    private fun settle() {
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); rule.waitForIdle() }
    }

    private fun submit() {
        rule.onNodeWithTag("submit").performScrollTo().performClick()
        settle()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun writeTabInsertsChordsPreviewsAndPublishes() {
        show()
        rule.onNodeWithTag("write-title").performTextInput("Amazing Grace")
        rule.onNodeWithTag("write-artist").performTextInput("John Newton")
        rule.onNodeWithTag("key-G").performScrollTo().performClick()
        rule.onNodeWithTag("write-body").performTextInput("Amazing grace")
        rule.onNodeWithTag("chord-D7").performScrollTo().performClick() // at the cursor (the end)
        settle()
        // The live preview renders the sheet with the inserted chord.
        rule.onNodeWithTag("write-preview").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("D7")).fetchSemanticsNodes().size >= 2)
        submit()
        val sent = fake.created.single() as NewSong.ChordPro
        assertEquals("{title: Amazing Grace}\n{artist: John Newton}\n{key: G}\n\nAmazing grace[D7]\n", sent.chordpro)
        rule.onNodeWithText("Published").assertIsDisplayed()
        rule.onNodeWithText("Open song").performClick()
        assertEquals("n1", opened)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun pasteTabSubmitsTextAndShowsInReview() {
        fake.create = { Song(id = "r1", title = "Pasted", chordpro = "", status = "review") }
        show(tab = AddTab.Paste)
        rule.onNodeWithTag("paste-text").performTextInput("G      C\nAmazing grace")
        rule.onNodeWithText("2 of 3 AI imports left today").assertIsDisplayed()
        submit()
        assertTrue(fake.created.single() is NewSong.Text)
        rule.onNodeWithText("In review").assertIsDisplayed()
        rule.onNodeWithText("Thanks — a moderator will check it").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun linkDuplicateOpensTheExistingSong() {
        fake.create = { throw ApiError.Duplicate("s9") }
        show()
        rule.onNodeWithTag("tab-link").performClick()
        rule.onNodeWithTag("link-url").performTextReplacement("https://chords.example/amazing-grace")
        submit()
        assertEquals(NewSong.Link("https://chords.example/amazing-grace"), fake.created.single())
        rule.onNodeWithText("Already in the library").assertIsDisplayed()
        rule.onNodeWithText("Open existing").performClick()
        assertEquals("s9", opened)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun limitReachedShowsPlanMessageAndSignInHint() {
        fake.create = { throw ApiError.Limit(429, "plan_limit_reached", 3, "free") }
        show(tab = AddTab.Link)
        rule.onNodeWithTag("link-url").performTextInput("https://chords.example/x")
        submit()
        rule.onNodeWithText("You've used today's AI imports (Free plan: 3/day)").assertIsDisplayed()
        rule.onNodeWithText("Sign in with Google to get more").performClick()
        assertEquals(1, signIns)
    }

    // ---- design renders: only with ORBIT_SCREENSHOTS=1 (written to docs/design/app) ----
    // Skipped otherwise: after ShellTest in the same JVM, Robolectric's native Skia crashes (SIGSEGV in a float parse)
    // drawing these screens; run on their own (`--tests '*AddSongScreenTest.render*'`) they render fine.

    private fun shoot(name: String) {
        settle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bmp)) }
        if (System.getenv("ORBIT_SCREENSHOTS") == "1") {
            val dir = File("../docs/design/app").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * Sets a field's text without focusing it: a focused Material text field in a NATIVE-graphics render crashes
     * Robolectric's native Skia after ShellTest has run in the same JVM.
     */
    private fun type(tag: String, text: String) {
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
    }

    private fun assumeRendering() = Assume.assumeTrue(System.getenv("ORBIT_SCREENSHOTS") == "1")

    private fun write(name: String, dark: Boolean) {
        assumeRendering()
        show(dark)
        type("write-title", "Amazing Grace")
        type("write-artist", "John Newton")
        rule.onNodeWithTag("key-G").performSemanticsAction(SemanticsActions.OnClick) // keeps the key row unscrolled
        type("write-body", 
            "{start_of_verse: Verse 1}\nA[G]mazing grace, how [G7]sweet the [C]sound\nThat [G]saved a wretch like [D]me\n" +
                "I [G]once was lost, but [G7]now am [C]found\nWas [G]blind, but [D]now I [G]see\n{end_of_verse}",
        )
        rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("write-preview")).fetchSemanticsNodes().isNotEmpty() }
        shoot(name)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderWritePhoneDark() = write("add-write-phone-dark", true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderWritePhoneLight() = write("add-write-phone-light", false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w884dp-h1104dp-port-hdpi")
    fun renderWriteTabletDark() = write("add-write-tablet-dark", true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w884dp-h1104dp-port-hdpi")
    fun renderWriteTabletLight() = write("add-write-tablet-light", false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderPasteReviewPhoneDark() {
        assumeRendering()
        fake.create = { Song(id = "r1", title = "Saving Grace", artist = "Traditional", chordpro = "", status = "review") }
        show(true, AddTab.Paste)
        type("paste-text", "G          C        G\nAmazing grace, how sweet the sound\n       G           D\nThat saved a wretch like me")
        submit()
        shoot("add-paste-review-phone-dark")
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    fun renderLinkLimitPhoneLight() {
        assumeRendering()
        me.value = Me("u1", "user", plan = "free", aiLeftToday = 0, aiDailyLimit = 3)
        fake.create = { throw ApiError.Limit(429, "plan_limit_reached", 3, "free") }
        show(false, AddTab.Link)
        type("link-url", "https://chords.example/amazing-grace")
        submit()
        shoot("add-link-limit-phone-light")
    }
}
