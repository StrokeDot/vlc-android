/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** One completed conversion, moved between the cache and the active cast directory. */
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
            val hit = saved.isFile && saved.length() > 0 && saved.renameTo(sessionFile)
            cache.listFiles()?.forEach { if (it != saved || it.length() == 0L) it.delete() }
            hit
        }
        if (reused) {
            Log.i("VLCPlus/CastPerf", "path=prepared-cache-hit")
            progress("Using prepared audio…")
            check()
            return CastPreparation.Prepared(sessionFile, "video/mp4", saved)
        }
        val prepared = prepare()
        check()
        return if (prepared.mime == "video/mp4" && prepared.file.length() in 1..MAX_BYTES && fingerprint(source) == key) {
            prepared.copy(cacheSlot = saved)
        } else {
            prepared
        }
    }

    /** Call only after the file server has closed, before deleting the session directory. */
    @Synchronized fun remember(prepared: CastPreparation.Prepared?) {
        val slot = prepared?.cacheSlot ?: return
        val cache = requireNotNull(slot.parentFile)
        if ((cache.isDirectory || cache.mkdirs()) && prepared.file.renameTo(slot)) {
            cache.listFiles()?.forEach { if (it != slot) it.delete() }
        }
    }

    private fun fingerprint(source: File): String? = runCatching {
        val file = source.canonicalFile
        if (!file.isFile || !file.canRead()) return null
        val stat = Os.stat(file.path)
        val identity = "${file.path}\u0000${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${file.lastModified()}:${stat.st_ctime}"
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
