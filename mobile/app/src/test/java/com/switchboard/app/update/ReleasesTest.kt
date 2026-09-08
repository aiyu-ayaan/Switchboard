package com.switchboard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions that install the wrong build silently when they are wrong:
 * how versions order across pre-releases, what each channel accepts, and which
 * asset of a release a phone can actually install. Everything else in the
 * updater is a network call or a file write.
 */
class ReleasesTest {

    private fun v(text: String): Version =
        requireNotNull(Version.parse(text)) { "expected $text to parse" }

    private fun release(tag: String, assets: List<String> = listOf("Switchboard-${tag.removePrefix("v")}.apk")) =
        Release(
            version = v(tag),
            name = tag,
            notes = "",
            publishedAt = "2026-01-01T00:00:00Z",
            assets = assets.map { ReleaseAsset(it, "https://example.com/$it", 1) },
        )

    @Test
    fun `a finished release outranks every pre-release of the same version`() {
        val order = listOf("1.2.3-alpha.1", "1.2.3-alpha.2", "1.2.3-beta.1", "1.2.3", "1.2.4-alpha.1")
        order.zipWithNext().forEach { (lower, higher) ->
            assertTrue("$higher should sort above $lower", v(higher) > v(lower))
        }
    }

    @Test
    fun `only the shapes the release workflow produces are understood`() {
        assertEquals(v("1.0.0"), Version.parse("v1.0.0"))
        assertEquals(Version.BETA, Version.parse("1.0.0-BETA.2")?.stage)
        assertNull(Version.parse("1.0"))
        assertNull(Version.parse("1.0.0-rc.1"))
        assertNull(Version.parse("latest"))
        assertNull(Version.parse(null))
    }

    @Test
    fun `a channel takes its own builds and everything steadier`() {
        assertTrue(UpdateChannel.STABLE.accepts(v("1.0.0")))
        assertTrue(!UpdateChannel.STABLE.accepts(v("1.0.0-beta.1")))
        assertTrue(UpdateChannel.BETA.accepts(v("1.0.0-beta.1")))
        // Beta must still see the stable release that supersedes a beta build.
        assertTrue(UpdateChannel.BETA.accepts(v("1.0.0")))
        assertTrue(!UpdateChannel.BETA.accepts(v("1.0.0-alpha.1")))
        assertTrue(UpdateChannel.ALPHA.accepts(v("1.0.0-alpha.1")))
    }

    @Test
    fun `the default channel is the one this build came from`() {
        assertEquals(UpdateChannel.ALPHA, UpdateChannel.forVersion("1.0.0-alpha.3"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.forVersion("1.0.0-beta.1"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.forVersion("1.0.0"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.forVersion("nonsense"))
    }

    @Test
    fun `newer is by version and never by publish date`() {
        // The stable release was cut first; the alpha that follows it is the
        // newer build, and an alpha install must not be moved backwards onto it.
        val releases = listOf(release("v1.0.0"), release("v1.1.0-alpha.1"))
        assertNull(Releases.pick(releases, v("1.1.0-alpha.1"), UpdateChannel.ALPHA))
        assertEquals("v1.1.0-alpha.1", Releases.pick(releases, v("0.9.0"), UpdateChannel.ALPHA)?.name)
        assertEquals("v1.0.0", Releases.pick(releases, v("0.9.0"), UpdateChannel.STABLE)?.name)
    }

    @Test
    fun `the APK is offered and the Play bundle beside it never is`() {
        val full = release("v1.0.0", listOf("Switchboard-1.0.0.aab", "Switchboard-1.0.0.apk"))
        assertEquals("Switchboard-1.0.0.apk", Releases.apkFor(full)?.name)

        // A release whose Android job failed still exists, and offers nothing.
        val desktopOnly = release("v1.0.0", listOf("Switchboard-Setup-1.0.0.exe"))
        assertNull(Releases.apkFor(desktopOnly))
    }

    @Test
    fun `drafts and unparseable tags are skipped rather than guessed at`() {
        val body = """
            [
              {"tag_name":"v2.0.0","draft":true,"assets":[]},
              {"tag_name":"nightly","assets":[]},
              {"tag_name":"v1.0.0","name":"","body":"notes","assets":[
                {"name":"Switchboard-1.0.0.apk","browser_download_url":"https://e/x.apk","size":12}
              ]}
            ]
        """.trimIndent()

        val releases = Updates.parse(body)
        assertEquals(1, releases.size)
        // A release with no name falls back to its tag rather than showing blank.
        assertEquals("v1.0.0", releases.single().name)
        assertEquals(12L, releases.single().assets.single().size)
    }
}
