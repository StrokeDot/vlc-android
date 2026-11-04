/*
 * VLC Plus: user-initiated updater for independent GitHub releases.
 *
 * Derived project: VLC for Android, GPLv2 or later.
 * This is not an updater for official VideoLAN binaries.
 */
package org.videolan.vlc.util

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.videolan.vlc.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Reads only releases published in StrokeDot/vlc-android.
 *
 * GitHub releases must be tagged "vlcplus-<base version code>", and must
 * contain a signed VLC-Plus-*.apk and a matching .apk.sha256 text asset.
 * Android itself verifies the package signature when installing an update.
 */
object VlcPlusUpdater {
    private const val LATEST_RELEASE =
        "https://api.github.com/repos/StrokeDot/vlc-android/releases/latest"
    private const val TRUSTED_ASSET_PREFIX =
        "https://github.com/StrokeDot/vlc-android/releases/download/"
    private const val MAX_APK_BYTES = 800L * 1024L * 1024L

    data class Release(
        val title: String,
        val baseVersionCode: Int,
        val apkUrl: String,
        val checksumUrl: String
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkLatest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VLC-Plus-Android")
            .build()

        http.newCall(request).execute().use { response ->
            // A new personal fork may not have published a release yet.
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub update check failed: HTTP " + response.code)

            val json = JSONObject(response.body?.string() ?: throw IOException("Empty GitHub response"))
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null

            val tag = json.optString("tag_name")
            val code = Regex("^vlcplus-([0-9]+)$").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IOException("Unexpected VLC Plus release tag: " + tag)
            if (code <= BuildConfig.VLC_VERSION_CODE) return@withContext null

            val assets = json.optJSONArray("assets") ?: throw IOException("GitHub release has no APK assets")
            val supportedAbis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS.toList()
            } else {
                @Suppress("DEPRECATION")
                listOf(Build.CPU_ABI)
            }
            val suffixes = supportedAbis.map { "-" + it + ".apk" } + listOf("-all.apk")

            var selectedName: String? = null
            var selectedUrl: String? = null
            for (suffix in suffixes) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.startsWith("VLC-Plus-") && name.endsWith(suffix)) {
                        selectedName = name
                        selectedUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (selectedUrl != null) break
            }

            val apkName = selectedName ?: throw IOException("No VLC Plus APK matches this Android device")
            val apkUrl = selectedUrl ?: throw IOException("Missing APK download URL")
            val checksumName = apkName + ".sha256"
            var checksumUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") == checksumName) {
                    checksumUrl = asset.optString("browser_download_url")
                    break
                }
            }
            val shaUrl = checksumUrl ?: throw IOException("Update is missing its SHA-256 checksum")
            validateReleaseUrl(apkUrl)
            validateReleaseUrl(shaUrl)

            Release(json.optString("name", tag), code, apkUrl, shaUrl)
        }
    }

    /**
     * Download to a private cache file, validate SHA-256 and Android package
     * identity/version, then open Android's normal confirmation dialog.
     * Installation is never silent.
     */
    suspend fun downloadAndPromptInstall(activity: Activity, release: Release) {
        val apk = withContext(Dispatchers.IO) {
            validateReleaseUrl(release.apkUrl)
            validateReleaseUrl(release.checksumUrl)
            val expected = fetchText(release.checksumUrl).trim()
                .split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
            if (!Regex("^[0-9a-f]{64}$").matches(expected)) {
                throw IOException("Invalid VLC Plus release checksum")
            }

            val temp = File(activity.cacheDir, "vlc_plus_update.apk.part")
            val complete = File(activity.cacheDir, "vlc_plus_update.apk")
            try {
                if (temp.exists()) temp.delete()
                if (complete.exists()) complete.delete()

                val request = Request.Builder().url(release.apkUrl)
                    .header("User-Agent", "VLC-Plus-Android").build()
                val digest = MessageDigest.getInstance("SHA-256")
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("VLC Plus APK download failed: HTTP " + response.code)
                    }
                    val body = response.body ?: throw IOException("Empty VLC Plus APK response")
                    var total = 0L
                    body.byteStream().use { source ->
                        FileOutputStream(temp).use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = source.read(buffer)
                                if (read == -1) break
                                total += read
                                if (total > MAX_APK_BYTES) throw IOException("VLC Plus APK exceeds size limit")
                                digest.update(buffer, 0, read)
                                destination.write(buffer, 0, read)
                            }
                        }
                    }
                }

                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (actual != expected) throw IOException("Downloaded APK checksum does not match release")
                validateAndroidPackage(activity, temp)
                if (!temp.renameTo(complete)) throw IOException("Could not prepare VLC Plus APK")
                complete
            } catch (e: Exception) {
                temp.delete()
                complete.delete()
                throw e
            }
        }

        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(
                activity, activity.packageName + ".provider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(intent)
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "VLC-Plus-Android").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update metadata failed: HTTP " + response.code)
            response.body?.string() ?: throw IOException("Empty update metadata")
        }
    }

    private fun validateReleaseUrl(url: String) {
        if (!url.startsWith(TRUSTED_ASSET_PREFIX)) {
            throw IOException("Refusing APK outside the official VLC Plus repository")
        }
    }

    @Suppress("DEPRECATION")
    private fun validateAndroidPackage(activity: Activity, apk: File) {
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException("Downloaded file is not a valid Android APK")
        if (archive.packageName != activity.packageName) {
            throw IOException("Update package does not match this VLC Plus installation")
        }
        val installed = pm.getPackageInfo(activity.packageName, 0)
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
            else archive.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode
            else installed.versionCode.toLong()
        if (newCode <= oldCode) {
            throw IOException("This APK is not newer than your installed VLC Plus")
        }

        // Avoid inviting the user to install an APK that Android must reject.
        // Older Android versions will still check certificates in the system installer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val existing = pm.getPackageInfo(
                activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            val incoming = pm.getPackageArchiveInfo(
                apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
            )?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            if (!existing.isNullOrEmpty() && !incoming.isNullOrEmpty() && existing != incoming) {
                throw IOException("Update signing key differs from the installed version. Reinstall the first signed VLC Plus release once.")
            }
        }
    }
}
