package org.videolan.vlc.cast

import android.app.Activity
import android.os.Bundle

/** Synthetic state, exact production view, no media or network access. */
class ControllerPreviewActivity : Activity(), CastControlsView.Actions {
    lateinit var controller: CastControlsView
    var convertClicks = 0
    var subtitleClicks = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CastControlsView.prepareWindow(window)
        controller = CastControlsView(this, this)
        setContentView(controller)
        controller.requestApplyInsets()
        controller.render("Movie preview · VLC Plus", "Living room Chromecast", "Playing on Living room Chromecast",
            active = true, connected = true, preparing = false, playing = true,
            position = 28 * 60 * 1000L + 13000, durationMs = 93 * 60 * 1000L + 49000,
            volume = 0.7, subtitles = true)
    }
    override fun back() = finish()
    override fun devices() = Unit
    override fun stop() = Unit
    override fun subtitles() { subtitleClicks++ }
    override fun convert() { convertClicks++ }
    override fun help() = Unit
    override fun playPause() = Unit
    override fun jump(delta: Long) = Unit
    override fun seek(position: Long) = Unit
    override fun volume(delta: Double) = Unit
    override fun mute() = Unit
    override fun legacy() = Unit
}
