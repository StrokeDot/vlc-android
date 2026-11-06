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
            val tail = original.source.open(expected.size - 3L).use { it.readBytes() }
            assertArrayEquals(expected.takeLast(3).toByteArray(), tail)
        } finally { resolver.delete(uri, null, null) }
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
