package com.example.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.example.BuildConfig
import com.example.R
import com.example.data.model.AppText
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A GitHub release as the API returns it (only the fields updating needs). */
internal data class GitHubRelease(
    @Json(name = "tag_name") val tagName: String = "",
    val body: String? = null,
    @Json(name = "html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList()
)

internal data class GitHubAsset(
    val name: String = "",
    @Json(name = "browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    val digest: String? = null // "sha256:<hex>"
)

/** A release this app can update to. */
data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?
)

/** Where Settings → About → Updates is. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val release: AppRelease) : UpdateState

    /** [progress] 0..1, or null while the size is unknown. */
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateState

    /** Android needs "Install unknown apps" allowed for this app first. */
    data class NeedsPermission(val release: AppRelease) : UpdateState

    /** Handed to the system installer, which asks the user to confirm. */
    data class Installing(val release: AppRelease) : UpdateState

    /** [release] is set when the failure came after one was found (so it can be retried or opened). */
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/** The versionCode a "vX.Y.Z" tag stands for (X*10000+Y*100+Z, as in app/build.gradle.kts), or null if it isn't one. */
internal fun versionCodeOf(tag: String): Int? {
    val match = Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(tag.trim()) ?: return null
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    if (minor > 99 || patch > 99) return null
    return major * 10000 + minor * 100 + patch
}

/** The release as an update, or null when it isn't one (draft, pre-release, no version tag or no APK). */
internal fun GitHubRelease.toAppRelease(): AppRelease? {
    if (draft || prerelease) return null
    val code = versionCodeOf(tagName) ?: return null
    val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() } ?: return null
    return AppRelease(
        versionName = tagName.trim().removePrefix("v"),
        versionCode = code,
        notes = body.orEmpty().trim(),
        pageUrl = htmlUrl ?: RELEASES_PAGE,
        apkUrl = apk.downloadUrl,
        apkSize = apk.size,
        sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
    )
}

private const val RELEASES_PAGE = "https://github.com/liorbass/baby-buddy-modern-android/releases"

/**
 * Updates from this app's GitHub releases (Settings → About): [check] looks up the latest release, [install]
 * downloads its APK, checks it (SHA-256 from GitHub, same package, newer, signed with this app's key) and hands
 * it to the system installer, which asks the user to confirm; [UpdateInstallReceiver] gets the result.
 * Off in builds made with `-PselfUpdate=false` (F-Droid, which updates and signs its builds itself).
 */
object AppUpdater {
    val isEnabled: Boolean get() = BuildConfig.SELF_UPDATE

    internal var latestReleaseUrl = "https://api.github.com/repos/liorbass/baby-buddy-modern-android/releases/latest"

    private var appContext: Context? = null

    /** Called once from the Application. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private val adapter by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(GitHubRelease::class.java) }

    /** Looks up the latest release; Available if it is newer than [currentVersionCode]. */
    suspend fun check(currentVersionCode: Int = BuildConfig.VERSION_CODE) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = withContext(Dispatchers.IO) {
            try {
                val release = fetchLatest()
                if (release != null && release.versionCode > currentVersionCode) {
                    UpdateState.Available(release)
                } else {
                    appContext?.let { downloadDir(it).deleteRecursively() } // an installed update's APK
                    UpdateState.UpToDate
                }
            } catch (e: Exception) {
                UpdateState.Failed(AppText.get(R.string.update_failed_check, reason(e)))
            }
        }
    }

    internal fun fetchLatest(): AppRelease? {
        val request = Request.Builder()
            .url(latestReleaseUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BabyBuddyCompanion/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null // no release yet
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return adapter.fromJson(response.body.string())?.toAppRelease()
        }
    }

    /** Downloads (unless already downloaded), checks and installs the release found by [check]. */
    suspend fun install() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        } ?: return
        val app = appContext ?: return
        if (!canInstall(app)) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        _state.value = UpdateState.Downloading(release, null)
        _state.value = withContext(Dispatchers.IO) {
            val apk = try {
                download(app, release)
            } catch (e: Exception) {
                return@withContext UpdateState.Failed(AppText.get(R.string.update_failed_download, reason(e)), release)
            }
            verify(app, apk)?.let { problem ->
                apk.delete()
                return@withContext UpdateState.Failed(problem, release)
            }
            try {
                commit(app, apk)
                UpdateState.Installing(release)
            } catch (e: Exception) {
                UpdateState.Failed(AppText.get(R.string.update_failed_install, reason(e)), release)
            }
        }
    }

    /** From [UpdateInstallReceiver]: the user cancelled, or the install failed. */
    internal fun onInstallFinished(cancelled: Boolean, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: return
        _state.value = if (cancelled) {
            UpdateState.Available(release)
        } else {
            UpdateState.Failed(AppText.get(R.string.update_failed_install, message ?: "?"), release)
        }
    }

    /** Whether Android lets this app install packages (Settings → Apps → Install unknown apps, Android 8+). */
    fun canInstall(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun downloadDir(context: Context) = File(context.cacheDir, "updates")

    /** The release's APK in the cache, downloaded if it isn't complete there yet; checked against GitHub's SHA-256. */
    private fun download(context: Context, release: AppRelease): File {
        val dir = downloadDir(context).apply { mkdirs() }
        val apk = File(dir, "baby-buddy-${release.versionCode}.apk")
        if (apk.exists() && apk.length() == release.apkSize && (release.sha256 == null || sha256(apk) == release.sha256)) return apk
        dir.listFiles()?.forEach { it.delete() } // older or partial downloads
        val part = File(dir, apk.name + ".part")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "BabyBuddyCompanion/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = -1
            response.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hex != release.sha256) {
                part.delete()
                throw IOException(AppText.get(R.string.update_corrupt))
            }
        }
        if (!part.renameTo(apk)) throw IOException("rename failed")
        return apk
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Why [apk] can't update this app, or null if it can: it must be this package, newer, and signed with the
     * installed app's key (Android refuses anything else; a copy from F-Droid is signed with F-Droid's key).
     */
    private fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, signingFlags()) ?: return AppText.get(R.string.update_corrupt)
        if (archive.packageName != context.packageName) return AppText.get(R.string.update_corrupt)
        val installed = pm.getPackageInfo(context.packageName, signingFlags())
        if (versionCode(archive) <= versionCode(installed)) return AppText.get(R.string.update_up_to_date)
        val theirs = signers(archive)
        if (theirs.isEmpty() || theirs != signers(installed)) return AppText.get(R.string.update_other_signer)
        return null
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /** SHA-256s of the certificates [info] is signed with. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return certs.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Writes [apk] into a package installer session and commits it; the system asks the user to confirm. */
    @SuppressLint("RequestInstallPackagesPolicy")
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION_STATUS)
                // Mutable: the installer adds the status extras
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun reason(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
