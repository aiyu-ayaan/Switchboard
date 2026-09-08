package com.switchboard.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.switchboard.app.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * The app keeping itself up to date, from the GitHub releases it was built by.
 *
 * There is no Play Store in the loop: Switchboard's Android builds are APKs
 * attached to a GitHub Release by `.github/workflows/release.yml`, so nothing
 * tells a phone that a newer one exists unless the app does. What it does is
 * deliberately small — ask GitHub what exists, work out whether any of it is
 * newer than what is running, download the APK, and hand the file to Android's
 * own package installer.
 *
 * It never installs anything on its own. Android has no silent install for an
 * app that is not the device owner, and it should not: the last screen is
 * always the system's, showing what is about to replace what.
 *
 * SharedPreferences and one StateFlow, the way the endpoint and the theme are
 * stored. Five values do not need a database.
 */
object Updates {

    private const val FILE = "switchboard.updates"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_SNOOZE_DAYS = "snoozeDays"
    private const val KEY_SNOOZED_UNTIL = "snoozedUntil"
    private const val KEY_LAST_CHECKED = "lastChecked"

    /** One day, which is the point of a snooze: later today is not later. */
    const val DEFAULT_SNOOZE_DAYS = 1

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    private lateinit var prefs: SharedPreferences
    private lateinit var downloads: File

    /**
     * Its own client rather than the paired host's: that one has no read
     * timeout because its socket is long-lived, which is exactly wrong for a
     * GitHub call that should give up rather than hang a screen.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * What this build is, or null for a checkout whose version could not be
     * parsed. A null installed version offers everything, which is right: an
     * unrecognisable version cannot be compared against, and a developer
     * running one is not going to be surprised by the offer.
     */
    val installed: Version? by lazy { Version.parse(BuildConfig.VERSION_NAME) }

    val installedName: String get() = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        downloads = File(app.cacheDir, "updates")
    }

    private val ready: Boolean get() = ::prefs.isInitialized

    /**
     * On by default. An app distributed as an APK has no store to tell anybody
     * a security fix exists, so the useful default is the one that notices.
     */
    var enabled: Boolean
        get() = !ready || prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            if (ready) prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) _state.value = UpdateState.Idle
        }

    /**
     * Defaults to the channel this build came from: somebody running an alpha
     * asked for alphas, and defaulting them to stable would strand them on the
     * build they have until its version is released.
     */
    var channel: UpdateChannel
        get() = if (ready && prefs.contains(KEY_CHANNEL)) {
            UpdateChannel.of(prefs.getString(KEY_CHANNEL, null))
        } else {
            UpdateChannel.forVersion(installedName)
        }
        set(value) {
            if (ready) prefs.edit().putString(KEY_CHANNEL, value.name).apply()
            // What was on offer came from the old channel, and on a narrower
            // one it may not be on offer at all.
            _state.value = UpdateState.Idle
        }

    /** How long "not now" lasts. */
    var snoozeDays: Int
        get() = if (ready) prefs.getInt(KEY_SNOOZE_DAYS, DEFAULT_SNOOZE_DAYS) else DEFAULT_SNOOZE_DAYS
        set(value) {
            if (ready) prefs.edit().putInt(KEY_SNOOZE_DAYS, value.coerceIn(1, 30)).apply()
        }

    val lastChecked: Long get() = if (ready) prefs.getLong(KEY_LAST_CHECKED, 0) else 0

    private val snoozedUntil: Long get() = if (ready) prefs.getLong(KEY_SNOOZED_UNTIL, 0) else 0

    /**
     * Quiet until the snooze runs out. Asking again tomorrow is asking; asking
     * again on the next launch is nagging.
     */
    fun snoozed(now: Long = System.currentTimeMillis()): Boolean = now < snoozedUntil

    fun snooze(now: Long = System.currentTimeMillis()) {
        if (ready) prefs.edit().putLong(KEY_SNOOZED_UNTIL, now + snoozeDays * DAY_MILLIS).apply()
        _state.value = UpdateState.Idle
    }

    /** Dismissing the prompt without deciding. It comes back on the next launch. */
    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /**
     * Ask GitHub what exists.
     *
     * [manual] is the difference between the button on the update screen and
     * the check on launch: the button says so when there is nothing new and
     * ignores a snooze, and the launch check stays silent.
     *
     * Unauthenticated, so it is subject to GitHub's sixty-requests-an-hour-per-
     * address limit — ample for a check on launch and a button, and the reason
     * a refused check is reported rather than retried.
     */
    suspend fun check(manual: Boolean = false): UpdateState = withContext(Dispatchers.IO) {
        if (!manual && (!enabled || snoozed())) return@withContext _state.value
        _state.value = UpdateState.Checking

        val next = runCatching {
            val request = Request.Builder()
                .url(Releases.API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                response.body?.string().orEmpty()
            }
            val pick = Releases.pick(parse(body), installed, channel)
                ?: return@runCatching UpdateState.UpToDate
            // A release with no APK for this device is not an offer. It happens:
            // a release whose Android job failed still exists.
            val apk = Releases.apkFor(pick) ?: return@runCatching UpdateState.UpToDate
            UpdateState.Available(pick, apk)
        }.getOrElse { UpdateState.Failed(it.message ?: "The update check failed") }

        if (ready) prefs.edit().putLong(KEY_LAST_CHECKED, System.currentTimeMillis()).apply()
        _state.value = next
        next
    }

    /**
     * The releases GitHub returned, minus the drafts and anything whose tag is
     * not a version this app understands.
     */
    internal fun parse(body: String): List<Release> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { index ->
            val json = array.getJSONObject(index)
            if (json.optBoolean("draft")) return@mapNotNull null
            val version = Version.parse(json.optString("tag_name")) ?: return@mapNotNull null
            val assets = json.optJSONArray("assets")
            Release(
                version = version,
                name = json.optString("name").ifBlank { json.optString("tag_name") },
                notes = json.optString("body"),
                publishedAt = json.optString("published_at"),
                assets = (0 until (assets?.length() ?: 0)).map { position ->
                    val asset = assets!!.getJSONObject(position)
                    ReleaseAsset(
                        name = asset.optString("name"),
                        url = asset.optString("browser_download_url"),
                        size = asset.optLong("size"),
                    )
                },
            )
        }
    }

    /**
     * Fetch the APK, reporting progress, and leave it in the cache.
     *
     * Streamed to disk rather than read into memory: an APK is tens of
     * megabytes. The cache is the right place — an installed update has no
     * further use for the file, and Android may reclaim one that was never
     * installed.
     */
    suspend fun download(release: Release, asset: ReleaseAsset): File = withContext(Dispatchers.IO) {
        downloads.mkdirs()
        // Everything else in the directory is a previous attempt or a previous
        // version, and neither is worth keeping.
        downloads.listFiles()?.forEach { if (it.name != asset.name) it.delete() }
        val target = File(downloads, asset.name)
        if (asset.size > 0 && target.length() == asset.size) {
            _state.value = UpdateState.Ready(release, target)
            return@withContext target
        }

        _state.value = UpdateState.Downloading(release, 0f)
        val request = Request.Builder().url(asset.url).build()
        http.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) error("The download failed (${response.code})")
            val total = if (asset.size > 0) asset.size else body.contentLength()
            var written = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            _state.value = UpdateState.Downloading(
                                release,
                                (written.toFloat() / total).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
        }
        _state.value = UpdateState.Ready(release, target)
        target
    }

    /**
     * Whether Android will let this app start an install at all.
     *
     * From API 26 "install unknown apps" is a per-app setting rather than a
     * device-wide one, and it is not a runtime permission: it cannot be asked
     * for with a dialog, only opened in settings.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The settings page that grants it, for this app alone. */
    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Hand the APK to Android's package installer, which shows what is about to
     * replace what and asks. That screen is the system's and is not skippable,
     * which is the correct end to this: nothing here installs anything behind
     * anybody's back.
     *
     * A `PackageInstaller` session rather than an `ACTION_VIEW` intent on a
     * `content://` URI. Both end at the same confirmation dialog, and the
     * difference is entirely what happens afterwards: the intent form is fire
     * and forget, so the most likely failure of all — an APK signed with a
     * different key from the installed build, which is what somebody
     * sideloading their first release hits — came back as a dialog that closed
     * and an app that had not changed. A session reports its outcome to
     * [UpdateInstallReceiver], and the reason can be said out loud.
     */
    suspend fun install(context: Context, apk: File): Unit = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }

        val sessionId = installer.createSession(parameters)
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_FILE, 0, apk.length()).use { sink ->
                apk.inputStream().use { it.copyTo(sink) }
                session.fsync(sink)
            }
            // The receiver is where the answer arrives — including the one that
            // matters, which is "user action required": the system dialog is
            // handed back rather than shown, and something has to start it.
            val status = PendingIntent.getBroadcast(
                app,
                sessionId,
                Intent(app, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    private const val SESSION_FILE = "switchboard.apk"

    fun fail(message: String) {
        _state.value = UpdateState.Failed(message)
    }
}

/** Where a check, a download or an install has got to. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val release: Release, val apk: ReleaseAsset) : UpdateState

    data class Downloading(val release: Release, val progress: Float) : UpdateState

    data class Ready(val release: Release, val file: File) : UpdateState

    data class Failed(val message: String) : UpdateState
}
