package org.videolan.vlc.cast

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CastControllerDeviceTest {
    @Test fun originalMatroskaIsNotPreparedOrCopied() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.cacheDir, "direct-source-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, "movie.mkv")
            instrumentation.context.assets.open("cast-fixture.mkv").use { src -> input.outputStream().use { src.copyTo(it) } }
            val original = CastInput.open(instrumentation.targetContext, Uri.fromFile(input))
            assertEquals("video/x-matroska", original.mime)
            assertEquals(input.length(), original.source.length())
            assertEquals(listOf("movie.mkv"), folder.listFiles()!!.map { it.name })
            val tail = original.source.open(input.length() - 3).use { it.readBytes() }
            assertArrayEquals(input.readBytes().takeLast(3).toByteArray(), tail)
        } finally { folder.deleteRecursively() }
    }

    @Test fun originalContentUriSupportsSeekingWithoutCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "cast-fixture-${System.nanoTime()}.mkv")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/x-matroska")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/VLCPlusTests")
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            val expected = instrumentation.context.assets.open("cast-fixture.mkv").use { it.readBytes() }
            resolver.openOutputStream(uri)!!.use { it.write(expected) }
            val original = CastInput.open(context, uri)
            assertEquals("video/x-matroska", original.mime)
            assertEquals(expected.size.toLong(), original.source.length())
            val folder = File(context.cacheDir, "provider-preparation-${System.nanoTime()}").apply { mkdirs() }
            try {
                CastPreparation.prepare(context, uri, folder, false, {}, {})
                if (CastMovieLocation.localFile(context, uri) != null)
                    assertFalse("Readable MediaStore files must not be staged", File(folder, "source").exists())
            } finally { folder.deleteRecursively() }
            val tail = original.source.open(expected.size - 3L).use { it.readBytes() }
            assertArrayEquals(expected.takeLast(3).toByteArray(), tail)
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun channelDeliveryPreservesUnalignedRangeAndSubtitles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "channel-range.bin")
        val bytes = ByteArray(512 * 1024) { (it * 31).toByte() }
        file.writeBytes(bytes)
        try {
            CastFileServer(java.net.InetAddress.getLoopbackAddress(), file, "video/mp4").use { server ->
                val connection = java.net.URL(server.mediaUrl()).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("Range", "bytes=17-400019")
                try {
                    assertEquals(206, connection.responseCode)
                    assertArrayEquals(bytes.copyOfRange(17, 400020), connection.inputStream.use { it.readBytes() })
                } finally { connection.disconnect() }
                val captions = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nTest\n"
                assertEquals(captions, java.net.URL(server.setSubtitles(captions)).openStream().use { it.reader().readText() })
            }
        } finally { file.delete() }
    }

    @Test fun subtitlePreviewAppliesOnlyAfterConfirmationAndPersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("cast_subtitle_style", 0).edit().clear().commit()
        ActivityScenario.launch(ControllerPreviewActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var applied = 0
                var dialog = CastSubtitleAppearance.show(activity) { applied++ }
                dialog.window!!.decorView.findViewWithTag<android.widget.Spinner>("cast_style_font").setSelection(2)
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
                assertEquals(0, applied); assertEquals(0, CastSubtitleAppearance.load(activity).family)
                dialog = CastSubtitleAppearance.show(activity) { applied++ }
                val root = dialog.window!!.decorView
                root.findViewWithTag<android.widget.Spinner>("cast_style_font").setSelection(1)
                root.findViewWithTag<android.widget.Spinner>("cast_style_color").setSelection(1)
                root.findViewWithTag<android.widget.Spinner>("cast_style_size").setSelection(3)
                root.findViewWithTag<android.widget.CheckBox>("cast_style_bold").isChecked = true
                assertTrue(root.findViewWithTag<android.view.View>("cast_style_preview").isShown)
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                val style = CastSubtitleAppearance.load(activity)
                assertEquals(1, applied); assertEquals(1, style.family); assertEquals(1, style.color)
                assertEquals(1.5, style.scale, 0.001); assertTrue(style.bold)
            }
        }
        context.getSharedPreferences("cast_subtitle_style", 0).edit().clear().commit()
    }

    @Test fun subtitleFolderHandlesDocumentAndFileUris() {
        val authority = "com.android.externalstorage.documents"
        for ((path, expected) in listOf(
            "primary:Movies/Film/movie.mkv" to "primary:Movies/Film",
            "ABCD-1234:Movies/Film/movie.mkv" to "ABCD-1234:Movies/Film",
            "primary:movie.mkv" to "primary:"
        )) {
            val uri = android.provider.DocumentsContract.buildDocumentUri(authority, path)
            val parent = CastMovieLocation.initialFolder(uri)!!
            assertEquals(expected, android.provider.DocumentsContract.getDocumentId(parent))
        }
        assertEquals("primary:Movies/Film", android.provider.DocumentsContract.getDocumentId(
            CastMovieLocation.initialFolder(Uri.parse("file:///storage/emulated/0/Movies/Film/movie.mkv"))!!))
    }

    @Test fun progressAndReceiverUpdatesAreAtomicAndStopIsTerminal() {
        data class State(val active: Boolean, val progress: Int = 0, val position: Int = 0, val preparing: Boolean = true)
        val store = CastStateStore(State(true)) { it.active }
        val gate = java.util.concurrent.CountDownLatch(1)
        val workers = (0 until 4).map { worker ->
            Thread {
                gate.await()
                repeat(500) {
                    if (worker % 2 == 0) store.update { it.copy(progress = it.progress + 1) }
                    else store.update { it.copy(position = it.position + 1) }
                }
            }.apply { start() }
        }
        gate.countDown(); workers.forEach { it.join() }
        assertEquals(1000, store.value.progress); assertEquals(1000, store.value.position)
        store.update { it.copy(preparing = false) }
        store.update { it.copy(active = false) }
        assertNull(store.update { it.copy(active = true, preparing = true, position = -1) })
        assertFalse(store.value.active); assertFalse(store.value.preparing); assertEquals(1000, store.value.position)
        store.update(true) { State(true) }
        assertTrue(store.value.active); assertEquals(0, store.value.position)
    }

    @Test fun activePlaybackKeepsActionsAvailableAndAvoidsSystemBars() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ControllerPreviewActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            val image = instrumentation.uiAutomation.takeScreenshot()
            val png = File(instrumentation.targetContext.getExternalFilesDir(null), "cast-controls.png")
            png.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            scenario.onActivity { activity ->
                val convert = activity.controller.findViewWithTag<View>("cast_convert")
                val subtitles = activity.controller.findViewWithTag<View>("cast_subtitles")
                assertTrue(convert.isEnabled); assertTrue(subtitles.isEnabled)
                assertTrue(convert.performClick()); assertTrue(subtitles.performClick())
                assertEquals(1, activity.convertClicks); assertEquals(1, activity.subtitleClicks)
                val title = Rect(); val legacy = Rect()
                activity.controller.findViewWithTag<View>("cast_title").getGlobalVisibleRect(title)
                activity.controller.findViewWithTag<View>("cast_legacy").getGlobalVisibleRect(legacy)
                val insets = activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.systemBars())
                val screenHeight = activity.window.decorView.height
                assertTrue("Title overlaps status bar", title.top >= insets.top)
                assertTrue("Footer overlaps navigation bar", legacy.bottom <= screenHeight - insets.bottom)
                assertTrue("Footer must remain visible", legacy.height() > 0)
            }
        }
    }
}
