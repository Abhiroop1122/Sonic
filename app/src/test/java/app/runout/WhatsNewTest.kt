package app.runout

import app.runout.update.WhatsNew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WhatsNewTest {
    private val changelog = """
        # Changelog

        ---

        ## 1.3.0 (2026-10-26)

        Dig finds what's out there.

        - **Dig** hears everyone who answers.
          - Searches carry `flac`.
        - Friends' shelves.

        ---

        ## 1.2.0 (2026-10-25)

        - The neon deck.
    """.trimIndent()

    @Test fun `a version's section is its opening line and bullets, up to the next one`() {
        val s = WhatsNew.section(changelog, "1.3.0")!!
        assertEquals("1.3.0", s.version)
        assertEquals("2026-10-26", s.date)
        assertEquals("Dig finds what's out there.", s.lede)
        assertEquals(listOf(WhatsNew.Item("**Dig** hears everyone who answers.", 0), WhatsNew.Item("Searches carry `flac`.", 1), WhatsNew.Item("Friends' shelves.", 0)), s.items)
        // A development build reads its release's notes; an unknown version has none.
        assertEquals(s, WhatsNew.section(changelog, "1.3.0-dev.2"))
        assertNull(WhatsNew.section(changelog, "9.9.9"))
    }

    @Test fun `the repository's own changelog has a section for every release it names`() {
        val text = listOf(File("../CHANGELOG.md"), File("CHANGELOG.md")).first { it.exists() }.readText()
        val s = WhatsNew.section(text, BuildConfig.VERSION_NAME)
        assertNotNull("CHANGELOG.md needs a section for ${BuildConfig.VERSION_NAME}", s)
        assertTrue(s!!.items.isNotEmpty())
    }

    @Test fun `shown once after an update, never on a fresh install`() {
        val context = Fixtures.context
        context.getSharedPreferences("updates", 0).edit().clear().commit()
        val fresh = WhatsNew(context, "1.3.0", 1030099) { changelog }
        fresh.checkOnLaunch(onboarded = false)
        assertNull("a new install meets onboarding instead", fresh.section.value)

        context.getSharedPreferences("updates", 0).edit().clear().commit()
        val updated = WhatsNew(context, "1.3.0", 1030099) { changelog }
        updated.checkOnLaunch(onboarded = true)
        assertEquals("1.3.0", updated.section.value?.version)
        updated.dismiss()
        val again = WhatsNew(context, "1.3.0", 1030099) { changelog }
        again.checkOnLaunch(onboarded = true)
        assertNull("seen stays seen", again.section.value)
        again.show()
        assertEquals("1.3.0", again.section.value?.version)
    }
}
