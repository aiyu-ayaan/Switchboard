package com.aiyu.switchboard.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Release streams. A channel takes its own builds and every steadier one: alpha < beta < stable. */
enum class UpdateChannel(val label: String, val stage: Int) {
    Stable("Stable", 2),
    Beta("Beta", 1),
    Alpha("Alpha", 0),
}

data class AppUpdate(val version: String, val url: String)

/** Finds a newer APK among the GitHub releases (.github/workflows/release.yml names them `Switchboard-<version>.apk`). */
object Updates {
    private const val RELEASES = "https://api.github.com/repos/aiyu-ayaan/Switchboard/releases?per_page=30"
    private val VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta)\.(\d+))?$""")

    // Its own client: the desktop one accepts any certificate, which GitHub must not get.
    private val http by lazy { OkHttpClient() }

    /** `1.2.3-beta.4` as [major, minor, patch, stage, number]; null for any other shape (a `-dev` build among them). */
    fun versionKey(text: String): List<Int>? {
        val (major, minor, patch, label, number) = VERSION.matchEntire(text.trim())?.destructured ?: return null
        val stage = when (label) { "alpha" -> 0; "beta" -> 1; else -> 2 }
        // A stable release has no pre-release number and still sorts above every one of its pre-releases.
        return listOf(major.toInt(), minor.toInt(), patch.toInt(), stage, number.toIntOrNull() ?: Int.MAX_VALUE)
    }

    private fun newer(a: List<Int>, b: List<Int>): Boolean =
        a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false

    /** The channel a build belongs to, the default: whoever installed an alpha wants alphas. */
    fun channelOf(version: String): UpdateChannel =
        UpdateChannel.entries.firstOrNull { it.stage == versionKey(version)?.get(3) } ?: UpdateChannel.Stable

    /** The newest release on [channel] newer than [installed] that carries an APK. */
    fun pick(releasesJson: String, installed: String, channel: UpdateChannel): AppUpdate? {
        val current = versionKey(installed) ?: return null
        var best: Pair<List<Int>, AppUpdate>? = null
        for (release in Json.parseToJsonElement(releasesJson).jsonArray) {
            val tag = release.jsonObject["tag_name"]?.jsonPrimitive?.content ?: continue
            val key = versionKey(tag) ?: continue
            if (key[3] < channel.stage || !newer(key, current) || best?.let { !newer(key, it.first) } == true) continue
            val apk = release.jsonObject["assets"]?.jsonArray
                ?.mapNotNull { it.jsonObject["browser_download_url"]?.jsonPrimitive?.content }
                ?.firstOrNull { it.endsWith(".apk") } ?: continue
            best = key to AppUpdate(tag.removePrefix("v"), apk)
        }
        return best?.second
    }

    /** Asks GitHub. Throws when it can't be reached. */
    suspend fun check(installed: String, channel: UpdateChannel): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}")
            pick(r.body!!.string(), installed, channel)
        }
    }

    /** Downloads [update]'s APK into [dir], replacing any earlier download; [onProgress] hears 0..1. Throws when it fails. */
    suspend fun download(update: AppUpdate, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "Switchboard-${update.version}.apk")
        http.newCall(Request.Builder().url(update.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The download answered ${r.code}")
            val body = r.body!!
            // -1 when the server doesn't say; then the bar stays at 0.
            val total = body.contentLength()
            var done = 0L
            val buffer = ByteArray(64 * 1024)
            file.outputStream().use { out ->
                val input = body.byteStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        file
    }
}
