/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.*
import android.widget.*
import java.util.Locale

/** Shared native controller view; the same view is rendered by emulator UI checks. */
class CastControlsView(context: Context, private val actions: Actions) : ScrollView(context) {
    interface Actions {
        fun back(); fun devices(); fun stop(); fun subtitles(); fun convert(); fun help()
        fun playPause(); fun jump(delta: Long); fun seek(position: Long); fun volume(delta: Double); fun mute(); fun legacy()
    }
    private val blue = Color.rgb(55, 143, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(160, 163, 171)
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = label("Cast to TV", 20f, white)
    private val status = label("Choose your TV or Chromecast", 13f, muted)
    private val subtitleText = label("Subtitle", 13f, muted)
    private val conversionText = label("Convert", 13f, muted)
    private val volumeText = label("100%", 13f, muted)
    private val elapsed = label("0:00", 13f, muted)
    private val duration = label("0:00", 13f, muted)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; isIndeterminate = true; progressTintList = ColorStateList.valueOf(blue)
        indeterminateTintList = ColorStateList.valueOf(blue); visibility = View.GONE
    }
    private val seek = SeekBar(context).apply {
        max = 1000; progressTintList = ColorStateList.valueOf(blue); thumbTintList = ColorStateList.valueOf(white)
    }
    private val pause = image(Glyph.PLAY, "Play or pause", 52) { actions.playPause() }
    private val rewind = image(Glyph.REWIND, "Back 15 seconds", 48) { actions.jump(-15000) }
    private val forward = image(Glyph.FORWARD, "Forward 15 seconds", 48) { actions.jump(15000) }
    private lateinit var subtitleCard: View
    private lateinit var convertCard: View
    private lateinit var stopCard: View
    private lateinit var volumeRail: View
    private var length = 0L
    private var dragging = false

    init {
        setBackgroundColor(Color.BLACK)
        isFillViewport = false
        clipToPadding = true
        addView(body, LayoutParams(-1, -2))
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(image(Glyph.BACK, "Back", 44) { actions.back() })
        title.apply { tag = "cast_title"; setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        toolbar.addView(image(Glyph.CAST, "Choose casting device", 44) { actions.devices() })
        body.addView(toolbar)
        status.setOnClickListener { actions.devices() }
        body.addView(status, params(top = 8).apply { bottomMargin = dp(4) })
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(6) })

        val actionRow = LinearLayout(context)
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context)
        val bottom = LinearLayout(context)
        stopCard = actionCard(Glyph.STOP, label("Stop", 13f, muted), "Stop casting") { actions.stop() }
        subtitleCard = actionCard(Glyph.SUBTITLE, subtitleText, "Choose subtitles") { actions.subtitles() }.apply { tag = "cast_subtitles" }
        convertCard = actionCard(Glyph.CONVERT, conversionText, "Convert audio") { actions.convert() }.apply { tag = "cast_convert" }
        top.addView(stopCard, LinearLayout.LayoutParams(0, dp(88), 1f).apply { rightMargin = dp(10) })
        top.addView(subtitleCard, LinearLayout.LayoutParams(0, dp(88), 1f))
        bottom.addView(convertCard, LinearLayout.LayoutParams(0, dp(88), 1f).apply { rightMargin = dp(10) })
        bottom.addView(actionCard(Glyph.HELP, label("Help", 13f, muted), "Casting help") { actions.help() }, LinearLayout.LayoutParams(0, dp(88), 1f))
        grid.addView(top); grid.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        actionRow.addView(grid, LinearLayout.LayoutParams(0, -2, 1f))
        volumeRail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground()
            addView(TextView(context).apply { text = "+"; textSize = 30f; setTextColor(white); gravity = Gravity.CENTER; contentDescription = "Volume up"; setOnClickListener { actions.volume(0.05) } }, LinearLayout.LayoutParams(-1, dp(56)))
            addView(image(Glyph.VOLUME, "Mute or unmute", 38) { actions.mute() })
            addView(volumeText, LinearLayout.LayoutParams(-2, 0, 1f))
            addView(TextView(context).apply { text = "−"; textSize = 30f; setTextColor(white); gravity = Gravity.CENTER; contentDescription = "Volume down"; setOnClickListener { actions.volume(-0.05) } }, LinearLayout.LayoutParams(-1, dp(56)))
        }
        actionRow.addView(volumeRail, LinearLayout.LayoutParams(dp(80), dp(186)).apply { leftMargin = dp(10) })
        body.addView(actionRow, params(top = 14))

        val transport = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = cardBackground()
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        transport.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        val times = LinearLayout(context)
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(duration)
        transport.addView(times)
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        controls.addView(rewind, LinearLayout.LayoutParams(dp(64), dp(64)))
        controls.addView(pause, LinearLayout.LayoutParams(dp(88), dp(64)).apply { leftMargin = dp(20); rightMargin = dp(20) })
        controls.addView(forward, LinearLayout.LayoutParams(dp(64), dp(64)))
        transport.addView(controls, params(top = 8))
        body.addView(transport, params(top = 18))
        body.addView(TextView(context).apply {
            text = "Use VLC casting"; textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER
            tag = "cast_legacy"; minimumHeight = dp(48); setOnClickListener { actions.legacy() }
        }, params(top = 6))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onProgressChanged(bar: SeekBar, position: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = format(length * position / 1000)
            }
            override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; actions.seek(length * bar.progress / 1000) }
        })
        setPadding(dp(16), dp(8), dp(16), dp(16))
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                setPadding(dp(16) + safe.left, dp(8) + safe.top, dp(16) + safe.right, dp(16) + safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                setPadding(dp(16) + insets.systemWindowInsetLeft, dp(8) + insets.systemWindowInsetTop,
                    dp(16) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    fun render(movie: String, device: String, message: String, active: Boolean, connected: Boolean,
               preparing: Boolean, playing: Boolean, position: Long, durationMs: Long, volume: Double, subtitles: Boolean) {
        title.text = movie.ifBlank { "Cast to TV" }
        status.text = message.ifBlank { device.ifBlank { "Choose a device" } }
        length = durationMs
        duration.text = format(length)
        if (!dragging) { elapsed.text = format(position); seek.progress = if (length > 0) (position * 1000 / length).toInt().coerceIn(0, 1000) else 0 }
        enabled(seek, connected && length > 0); enabled(rewind, connected && length > 0); enabled(forward, connected && length > 0)
        enabled(pause, connected); enabled(stopCard, active)
        // Subtitle and conversion controls stay available during playback.
        enabled(subtitleCard, !preparing); enabled(convertCard, !preparing)
        enabled(volumeRail, connected)
        pause.setImageDrawable(GlyphDrawable(if (playing) Glyph.PAUSE else Glyph.PLAY, white))
        subtitleText.text = if (subtitles) (if (connected) "Subtitle • On" else "Subtitle • Ready") else "Subtitle"
        subtitleText.setTextColor(if (subtitles) blue else muted)
        conversionText.text = if (preparing) "Preparing…" else "Convert"
        volumeText.text = "${(volume * 100).toInt()}%"
        progress.visibility = if (preparing) View.VISIBLE else View.GONE
        val percent = Regex("(\\d+)%").find(message)?.groupValues?.get(1)?.toIntOrNull()
        progress.isIndeterminate = percent == null
        if (percent != null) progress.progress = percent
    }

    private fun enabled(view: View, value: Boolean) { view.isEnabled = value; view.alpha = if (value) 1f else 0.35f }
    private fun label(value: String, size: Float, color: Int) = TextView(context).apply { text = value; textSize = size; setTextColor(color) }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun cardBackground(): Drawable {
        fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(15).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x20ffffff), shape(Color.rgb(31, 31, 33)), shape(white))
    }
    private fun image(glyph: Glyph, description: String, size: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(GlyphDrawable(glyph, white)); contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = null; setPadding(dp(10), dp(10), dp(10), dp(10)); minimumWidth = dp(size); minimumHeight = dp(size)
        setOnClickListener { action() }
    }
    private fun actionCard(glyph: Glyph, text: TextView, description: String, action: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = cardBackground()
        isClickable = true; isFocusable = true; contentDescription = description; setOnClickListener { action() }
        addView(ImageView(context).apply { setImageDrawable(GlyphDrawable(glyph, if (glyph == Glyph.CONVERT) blue else white)) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(text, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(9) })
    }
    private enum class Glyph { BACK, CAST, STOP, SUBTITLE, CONVERT, HELP, VOLUME, PLAY, PAUSE, REWIND, FORWARD }
    private class GlyphDrawable(private val glyph: Glyph, private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val saved = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 32f, bounds.height() / 32f)
            paint.color = color; paint.strokeWidth = 2f; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
            fun path(vararg xy: Float) { val p = Path(); p.moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) p.lineTo(xy[i], xy[i + 1]); canvas.drawPath(p, paint) }
            fun text(value: String, size: Float) { paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = size; paint.typeface = Typeface.create("sans-serif", Typeface.BOLD); canvas.drawText(value, 16f, 20f, paint) }
            when (glyph) {
                Glyph.BACK -> { path(17f, 7f, 8f, 16f, 17f, 25f); path(8f, 16f, 27f, 16f) }
                Glyph.CAST -> { path(6f, 10f, 6f, 6f, 28f, 6f, 28f, 24f, 21f, 24f); canvas.drawArc(RectF(-9f, 12f, 19f, 40f), 270f, 90f, false, paint); canvas.drawArc(RectF(-3f, 18f, 13f, 34f), 270f, 90f, false, paint); paint.style = Paint.Style.FILL; canvas.drawCircle(5f, 26f, 2f, paint) }
                Glyph.STOP -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(7f, 7f, 25f, 25f, 1.5f, 1.5f, paint) }
                Glyph.SUBTITLE -> { canvas.drawRoundRect(3f, 6f, 29f, 26f, 2f, 2f, paint); text("CC", 12f) }
                Glyph.HELP -> { canvas.drawCircle(16f, 16f, 12f, paint); text("?", 20f) }
                Glyph.CONVERT -> { canvas.drawArc(RectF(5f, 5f, 27f, 27f), 210f, 160f, false, paint); canvas.drawArc(RectF(5f, 5f, 27f, 27f), 30f, 160f, false, paint); path(22f, 5f, 27f, 8f, 27f, 2f); path(10f, 27f, 5f, 24f, 5f, 30f) }
                Glyph.VOLUME -> { path(4f, 12f, 10f, 12f, 17f, 6f, 17f, 26f, 10f, 20f, 4f, 20f, 4f, 12f); canvas.drawArc(RectF(13f, 8f, 29f, 24f), -60f, 120f, false, paint) }
                Glyph.PLAY -> { paint.style = Paint.Style.FILL; path(9f, 5f, 27f, 16f, 9f, 27f, 9f, 5f) }
                Glyph.PAUSE -> { paint.style = Paint.Style.FILL; canvas.drawRect(8f, 5f, 13f, 27f, paint); canvas.drawRect(19f, 5f, 24f, 27f, paint) }
                Glyph.REWIND, Glyph.FORWARD -> { canvas.drawArc(RectF(4f, 4f, 28f, 28f), if (glyph == Glyph.REWIND) -90f else -60f, 300f, false, paint); if (glyph == Glyph.REWIND) path(10f, 3f, 4f, 8f, 11f, 10f) else path(22f, 3f, 28f, 8f, 21f, 10f); text("15", 11f) }
            }
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        override fun getIntrinsicWidth() = 32
        override fun getIntrinsicHeight() = 32
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    companion object {
        @Suppress("DEPRECATION")
        fun prepareWindow(window: Window) {
            if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
            else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }
        private fun format(ms: Long) = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
    }
}
