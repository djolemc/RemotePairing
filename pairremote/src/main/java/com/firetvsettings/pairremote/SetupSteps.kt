package com.firetvsettings.pairremote

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.firetvsettings.pairremote.Ui.dp
import com.firetvsettings.pairremote.Ui.rounded
import com.firetvsettings.pairremote.Ui.text

/** A card of numbered setup steps, each with an optional command to type, ticked off once done. */
class SetupSteps(context: Context) : LinearLayout(context) {

    sealed class Item {
        data class Group(val title: String) : Item()
        data class Step(val text: String, val command: String?, val done: Boolean) : Item()
        data class Note(val text: String) : Item()
    }

    private var shown: List<Item>? = null

    init {
        orientation = VERTICAL
        with(context) {
            setPadding(dp(20), dp(8), dp(20), dp(16))
            background = rounded(Ui.SURFACE)
        }
    }

    fun show(items: List<Item>) {
        // Refreshed every few seconds; only rebuild when something changed.
        if (items == shown) return
        shown = items
        removeAllViews()
        var number = 0
        for (item in items) {
            when (item) {
                is Item.Group -> addView(context.text(15f, Ui.TEXT_DIM, bold = true).apply {
                    text = item.title
                    setPadding(0, context.dp(12), 0, context.dp(6))
                })
                is Item.Step -> addView(step(++number, item))
                is Item.Note -> addView(context.text(15f, Ui.TEXT_DIM).apply {
                    text = item.text
                    setPadding(0, context.dp(8), 0, 0)
                })
            }
        }
    }

    private fun step(number: Int, step: Item.Step): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, context.dp(6), 0, context.dp(6))
        val mark = context.text(16f, if (step.done) Ui.OK else Ui.TEXT_DIM, bold = true).apply {
            text = if (step.done) "✓" else "$number"
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(mark, LayoutParams(context.dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(context.text(16f, if (step.done) Ui.TEXT_DIM else Ui.TEXT).apply { text = step.text })
            if (step.command != null) {
                addView(context.text(14f, Ui.ACCENT_FOCUSED).apply {
                    text = step.command
                    typeface = Typeface.MONOSPACE
                    setPadding(0, context.dp(4), 0, 0)
                })
            }
        }
        addView(body, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
