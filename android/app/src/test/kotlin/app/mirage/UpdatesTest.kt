package app.mirage

import app.mirage.data.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "is there a newer version" check: reading a tag, and telling newer from not. */
class UpdatesTest {

    @Test
    fun aVersionIsItsNumbers() {
        assertEquals(listOf(0, 3, 0), Updates.numbers("0.3.0"))
        assertEquals(listOf(0, 3, 1), Updates.numbers("v0.3.1"))
        assertEquals(listOf(0, 3, 1), Updates.numbers("0.3.1-debug"))
        assertEquals(listOf(1, 2), Updates.numbers("mirage-1.2"))
        assertEquals(listOf(7), Updates.numbers("7"))
        assertNull(Updates.numbers(""))
        assertNull(Updates.numbers("latest"))
    }

    @Test
    fun newerIsDecidedNumberByNumber() {
        assertTrue(Updates.isNewer("v0.3.1", "0.3.0"))
        assertTrue(Updates.isNewer("v0.4.0", "0.3.9"))
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        // Ten is more than nine: numbers, not text.
        assertTrue(Updates.isNewer("0.10.0", "0.9.0"))
        assertTrue(Updates.isNewer("0.3.0.1", "0.3.0"))
    }

    @Test
    fun theSameOrOlderIsNotNews() {
        assertFalse(Updates.isNewer("v0.3.0", "0.3.0"))
        assertFalse(Updates.isNewer("0.3", "0.3.0"))
        assertFalse(Updates.isNewer("0.2.9", "0.3.0"))
        assertFalse(Updates.isNewer("v0.3.0", "0.3.0-debug"))
    }

    @Test
    fun aTagThatIsNotAVersionIsNotNews() {
        assertFalse(Updates.isNewer("nightly", "0.3.0"))
        assertFalse(Updates.isNewer("", "0.3.0"))
        assertFalse(Updates.isNewer("v9.9.9", ""))
    }

    @Test
    fun theTagIsReadOutOfGitHubsAnswer() {
        assertEquals("v0.4.1", Updates.tagOf("""{"url":"https://api.github.com/x","tag_name":"v0.4.1","assets":[]}"""))
        assertEquals("", Updates.tagOf("""{"message":"Not Found"}"""))
        assertEquals("", Updates.tagOf("not json"))
        assertEquals("", Updates.tagOf(""))
    }

    @Test
    fun onlyAndroidReleasesAreRead() {
        val list = """[
            {"tag_name":"desktop-v9.0.0"},
            {"tag_name":"android-v0.10.0","draft":true},
            {"tag_name":"android-v0.9.5","prerelease":true},
            {"tag_name":"android-v0.4.1"},
            {"tag_name":"android-v0.4.0"},
            {"tag_name":"android-vnext"}
        ]"""
        assertEquals("android-v0.4.1", Updates.newestAndroidTag(list))
        assertEquals("android-v0.10.0",
            Updates.newestAndroidTag("""[{"tag_name":"android-v0.9.0"},{"tag_name":"android-v0.10.0"}]"""))
        assertEquals("", Updates.newestAndroidTag("""[{"tag_name":"desktop-v1.0"}]"""))
        assertEquals("", Updates.newestAndroidTag("""{"message":"Not Found"}"""))
        assertEquals("", Updates.newestAndroidTag("not json"))
    }

    @Test
    fun whatIsSaidNamesBothVersions() {
        val newer = Updates.Found("0.3.0", "v0.4.0", newer = true).text
        assertTrue(newer.contains("v0.4.0") && newer.contains("0.3.0"))
        assertTrue(Updates.Found("0.3.0", "v0.3.0", newer = false).text.contains("0.3.0"))
    }
}
