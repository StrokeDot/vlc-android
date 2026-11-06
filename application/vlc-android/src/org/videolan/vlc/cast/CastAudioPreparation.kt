/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CancellationException

/** Preserve .7 recovery while avoiding its normal second movie-writing pass. */
internal object CastAudioPreparation {
    fun prepare(context: Context, uri: Uri, directory: File, check: () -> Unit,
                progress: (String) -> Unit): CastPreparation.Prepared = try {
        CastPreparation.prepare(context, uri, directory, true, check, progress)
    } catch (unsupported: CastPreparation.AudioUnsupported) {
        File(directory, "prepared.mp4").delete()
        val output = File(directory, "native-prepared.mp4")
        val started = SystemClock.elapsedRealtime()
        try {
            CastNativePreparation.convert(context, unsupported.input, output, true, check, progress)
            val converted = SystemClock.elapsedRealtime()
            progress("Checking prepared video…")
            require(CastVideoValidation.matches(unsupported.input, output, check)) { "Native output needs the compatibility fallback" }
            Log.i("VLCPlus/CastPerf", "path=native-combined conversionMs=${converted - started} validationMs=${SystemClock.elapsedRealtime() - converted}")
            CastPreparation.Prepared(output, "video/mp4")
        } catch (cancelled: CancellationException) { output.delete(); throw cancelled }
        catch (failed: Exception) {
            output.delete(); check()
            Log.w("VLCPlus/CastPerf", "path=compatibility-fallback elapsedMs=${SystemClock.elapsedRealtime() - started}", failed)
            val audio = File(directory, "compatible-audio.m4a")
            try {
                CastNativePreparation.convert(context, unsupported.input, audio, false, check, progress)
                CastPreparation.withAudio(unsupported.input, audio, directory, check, progress)
            } finally { audio.delete() }
        }
    }
}
