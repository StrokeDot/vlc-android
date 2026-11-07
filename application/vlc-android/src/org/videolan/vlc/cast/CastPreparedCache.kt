/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** One completed conversion. Hard links give active sessions their own lifetime without copying. */
internal object CastPreparedCache {
    private const val MAX_BYTES = 8L * 1024 * 1024 * 1024

    fun prepare(context: Context, uri: Uri, directory: File, check: () -> Unit,
                progress: (String) -> Unit): CastPreparation.Prepared {
        val local = CastMovieLocation.localFile(context, uri)
        val source = local?.path?.let(::File)
        return getOrPrepare(File(context.cacheDir, "cast-prepared-v1"), source, directory, check, progress) {
            CastAudioPreparation.prepare(context, local ?: uri, directory, check, progress)
        }
    }

    internal fun getOrPrepare(cache: File, source: File?, directory: File, check: () -> Unit,
                              progress: (String) -> Unit, prepare: () -> CastPreparation.Prepared): CastPreparation.Prepared {
        check()
        val key = source?.let(::fingerprint) ?: return prepare()
        val saved = File(cache, "$key.mp4")
        val sessionFile = File(directory, "cached-audio.mp4")
        val reused = synchronized(this) {
            if (saved.isFile && saved.length() > 0 && link(saved, sessionFile)) true
            else {
                if (saved.isFile && saved.length() == 0L) saved.delete()
                cache.listFiles()?.filter { it.name != saved.name }?.forEach { it.delete() }
                false
            }
        }
        if (reused) {
            Log.i("VLCPlus/CastPerf", "path=prepared-cache-hit")
            progress("Using prepared audio…")
            check()
            return CastPreparation.Prepared(sessionFile, "video/mp4")
        }
        val prepared = prepare()
        check()
        if (prepared.mime == "video/mp4" && prepared.file.length() in 1..MAX_BYTES && fingerprint(source) == key) {
            synchronized(this) {
                if ((cache.isDirectory || cache.mkdirs()) && !saved.exists() && link(prepared.file, saved)) {
                    cache.listFiles()?.filter { it.name != saved.name }?.forEach { it.delete() }
                }
            }
        }
        return prepared
    }

    private fun link(from: File, to: File): Boolean = runCatching { Os.link(from.path, to.path) }.isSuccess

    private fun fingerprint(source: File): String? = runCatching {
        val file = source.canonicalFile
        if (!file.isFile || !file.canRead()) return null
        val stat = Os.stat(file.path)
        val identity = "${file.path}\u0000${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${file.lastModified()}:${stat.st_ctime}"
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
