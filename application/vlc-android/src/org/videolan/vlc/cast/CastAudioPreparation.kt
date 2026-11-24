/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CancellationException

/** Prepare compatible audio alongside copied video, with the existing VLC encoder as fallback. */
internal object CastAudioPreparation {
    // Android PCM→AAC acceleration passed synthetic fixtures but is not yet
    // validated on long 4K receiver handoffs. Prefer the established VLC AAC
    // pipeline for real casts until we can measure it on physical devices.
    private const val ENABLE_EXPERIMENTAL_PCM = false
    fun prepare(context: Context, uri: Uri, directory: File, check: () -> Unit,
                progress: (String) -> Unit): CastPreparation.Prepared = try {
        CastPreparation.prepare(context, uri, directory, true, check, progress)
    } catch (unsupported: CastPreparation.AudioUnsupported) {
        File(directory, "prepared.mp4").delete()
        prepareUnsupported(context, unsupported.input, directory, check, progress)
    }

    private fun prepareUnsupported(context: Context, input: File, directory: File, check: () -> Unit,
                                   progress: (String) -> Unit): CastPreparation.Prepared {
        if (ENABLE_EXPERIMENTAL_PCM && android.os.Build.VERSION.SDK_INT >= 28) {
            val candidate = File(directory, "platform-aac.mp4")
            val started = SystemClock.elapsedRealtime()
            try {
                val result = CastPipelinedPreparation.prepare(context, input, directory, check, progress, platformEncoder = true)
                Log.i("VLCPlus/CastPerf", "path=platform-aac totalMs=${SystemClock.elapsedRealtime() - started}")
                return result
            } catch (cancelled: CancellationException) {
                candidate.delete()
                throw cancelled
            } catch (failed: Exception) {
                candidate.delete()
                check()
                Log.w("VLCPlus/CastPerf", "Platform audio preparation unavailable, using VLC AAC", failed)
            }
        }
        val output = File(directory, "pipelined.mp4")
        val started = SystemClock.elapsedRealtime()
        return try {
            require(android.os.Build.VERSION.SDK_INT >= 23) { "Use compatibility preparation on this Android version" }
            val prepared = CastPipelinedPreparation.prepare(context, input, directory, check, progress)
            Log.i("VLCPlus/CastPerf", "path=pipelined totalMs=${SystemClock.elapsedRealtime() - started}")
            prepared
        } catch (cancelled: CancellationException) { output.delete(); throw cancelled }
        catch (failed: Exception) {
            output.delete(); check()
            Log.w("VLCPlus/CastPerf", "path=compatibility-fallback elapsedMs=${SystemClock.elapsedRealtime() - started}", failed)
            val audio = File(directory, "compatible-audio.m4a")
            try {
                CastNativePreparation.convert(context, input, audio, false, check, progress)
                CastPreparation.withAudio(input, audio, directory, check, progress)
            } finally { audio.delete() }
        }
    }
}
