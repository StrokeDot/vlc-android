/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.*

/** Phone preview and saved defaults for the receiver's standard caption styles. */
internal object CastSubtitleAppearance {
    private const val PREFS = "cast_subtitle_style"
    fun load(context: Context): CastSubtitleStyle = runCatching {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        CastSubtitleStyle(p.getInt("family", 0), p.getFloat("scale", 1f).toDouble(), p.getInt("color", 0),
            p.getInt("edge", 1), p.getInt("background", 0), p.getBoolean("bold", false), p.getBoolean("italic", false))
    }.getOrDefault(CastSubtitleStyle())

    private fun save(context: Context, style: CastSubtitleStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("family", style.family).putFloat("scale", style.scale.toFloat()).putInt("color", style.color)
            .putInt("edge", style.edge).putInt("background", style.background)
            .putBoolean("bold", style.bold).putBoolean("italic", style.italic).apply()
    }

    fun show(owner: Context, onApply: (CastSubtitleStyle) -> Unit): AlertDialog {
        val context = ContextThemeWrapper(owner, android.R.style.Theme_Material_Dialog_Alert)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val initial = load(owner)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val scroll = ScrollView(context).apply { addView(column) }
        val preview = Preview(context).apply {
            tag = "cast_style_preview"; text = "Every story begins\nwith a moment."
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        column.addView(FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff233b4d.toInt(), 0xff101519.toInt()))
                .apply { cornerRadius = dp(12).toFloat() }
            addView(preview, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(16) })
        }, LinearLayout.LayoutParams(-1, dp(180)))
        column.addView(TextView(context).apply {
            text = "Preview — TV fonts may vary."; setTextColor(0xffaaaaaa.toInt()); textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        })
        fun choice(label: String, tag: String, options: Array<String>, selected: Int): Spinner {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); textSize = 15f }, LinearLayout.LayoutParams(0, dp(48), 1f))
            val spinner = Spinner(context).apply {
                this.tag = tag
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(selected)
            }
            row.addView(spinner, LinearLayout.LayoutParams(0, dp(48), 1.4f)); column.addView(row)
            return spinner
        }
        val family = choice("Font", "cast_style_font", arrayOf("Sans serif", "Serif", "Monospace"), initial.family)
        val scales = doubleArrayOf(0.75, 1.0, 1.25, 1.5, 2.0)
        val size = choice("Size", "cast_style_size", arrayOf("75%", "100%", "125%", "150%", "200%"), scales.indices.minByOrNull { kotlin.math.abs(scales[it] - initial.scale) } ?: 1)
        val color = choice("Text color", "cast_style_color", arrayOf("White", "Yellow", "Cyan"), initial.color)
        val edge = choice("Edge", "cast_style_edge", arrayOf("None", "Outline", "Shadow"), initial.edge)
        val background = choice("Background", "cast_style_background", arrayOf("Transparent", "Dark"), initial.background)
        val weights = LinearLayout(context)
        val bold = CheckBox(context).apply { tag = "cast_style_bold"; text = "Bold"; isChecked = initial.bold }
        val italic = CheckBox(context).apply { tag = "cast_style_italic"; text = "Italic"; isChecked = initial.italic }
        weights.addView(bold, LinearLayout.LayoutParams(0, dp(48), 1f)); weights.addView(italic, LinearLayout.LayoutParams(0, dp(48), 1f)); column.addView(weights)
        fun chosen() = CastSubtitleStyle(family.selectedItemPosition, scales[size.selectedItemPosition], color.selectedItemPosition,
            edge.selectedItemPosition, background.selectedItemPosition, bold.isChecked, italic.isChecked)
        fun render() {
            val style = chosen()
            preview.textSize = (20 * style.scale).toFloat()
            preview.typeface = Typeface.create(arrayOf("sans-serif", "serif", "monospace")[style.family],
                (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0))
            preview.setTextColor(intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN)[style.color])
            preview.setBackgroundColor(if (style.background == 1) 0xa0000000.toInt() else Color.TRANSPARENT)
            preview.edge = style.edge
            preview.setShadowLayer(if (style.edge == 2) dp(3).toFloat() else 0f, dp(1).toFloat(), dp(1).toFloat(), Color.BLACK)
            preview.invalidate()
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = render()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        listOf(family, size, color, edge, background).forEach { it.onItemSelectedListener = listener }
        bold.setOnCheckedChangeListener { _, _ -> render() }; italic.setOnCheckedChangeListener { _, _ -> render() }
        render()
        return AlertDialog.Builder(context).setTitle("Casting subtitle style").setView(scroll)
            .setPositiveButton("Apply") { _, _ -> val style = chosen(); save(owner, style); onApply(style) }
            .setNegativeButton("Cancel", null).create().apply {
                show(); getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xff2486ef.toInt())
            }
    }

    private class Preview(context: Context) : TextView(context) {
        var edge = 1
        override fun onDraw(canvas: Canvas) {
            if (edge == 1) {
                val color = paint.color
                paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * resources.displayMetrics.density
                canvas.save(); canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
                layout?.draw(canvas); canvas.restore()
                paint.color = color; paint.style = Paint.Style.FILL
            }
            super.onDraw(canvas)
        }
    }
}
