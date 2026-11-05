package com.aiyu.switchboard

import com.aiyu.switchboard.net.AppUpdate
import com.aiyu.switchboard.net.UpdateChannel
import com.aiyu.switchboard.net.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String, vararg names: String) =
        """{"tag_name":"v$tag","assets":[${names.joinToString(",") { """{"browser_download_url":"u/$it"}""" }}]}"""

    private val releases = "[" + listOf(
        release("2.0.1-alpha.1", "a.apk"),
        release("2.0.0", "b.apk", "b.AppImage"),
        release("2.0.0-beta.2", "c.apk"),
        release("2.0.2", "d.AppImage"),
    ).joinToString(",") + "]"

    @Test fun picksNewestOnChannel() {
        assertEquals(AppUpdate("2.0.1-alpha.1", "u/a.apk"), Updates.pick(releases, "2.0.0-alpha.1", UpdateChannel.Alpha))
        // Beta takes the stable release too; 2.0.2 has no APK.
        assertEquals(AppUpdate("2.0.0", "u/b.apk"), Updates.pick(releases, "2.0.0-beta.1", UpdateChannel.Beta))
        assertNull(Updates.pick(releases, "2.0.0", UpdateChannel.Stable))
    }

    @Test fun devBuildsNeverUpdate() {
        assertNull(Updates.pick(releases, "2.0.0-alpha.1-dev", UpdateChannel.Alpha))
    }

    @Test fun channelFollowsTheBuild() {
        assertEquals(UpdateChannel.Alpha, Updates.channelOf("2.0.0-alpha.2"))
        assertEquals(UpdateChannel.Beta, Updates.channelOf("2.0.0-beta.1"))
        assertEquals(UpdateChannel.Stable, Updates.channelOf("2.0.0"))
    }
}
