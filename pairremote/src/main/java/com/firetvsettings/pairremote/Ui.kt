package com.firetvsettings.pairremote

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Colors and views for a D-pad friendly TV interface, built in code (no XML layouts). */
object Ui {

    val BACKGROUND = Color.rgb(12, 20, 36)
    val SURFACE = Color.rgb(24, 36, 60)
    val SURFACE_FOCUSED = Color.rgb(38, 58, 96)
    val ACCENT = Color.rgb(77, 163, 255)
    val ACCENT_FOCUSED = Color.rgb(120, 186, 255)
    val TEXT = Color.WHITE
    val TEXT_DIM = Color.rgb(154, 168, 191)
    val OK = Color.rgb(64, 214, 160)
    val WARN = Color.rgb(255, 181, 71)

    enum class Tone(val color: Int) { INFO(TEXT_DIM), BUSY(ACCENT), OK(Ui.OK), WARN(Ui.WARN) }

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun Context.rounded(color: Int, strokeColor: Int = Color.TRANSPARENT, radius: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            setStroke(dp(3), strokeColor)
        }

    /** Background that shows D-pad focus as a lighter fill with a white outline. */
    private fun Context.focusable(normal: Int, focused: Int, selected: Int? = null, radius: Int = 12): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(focused, TEXT, radius))
            if (selected != null) addState(intArrayOf(android.R.attr.state_selected), rounded(selected, radius = radius))
            addState(intArrayOf(), rounded(normal, radius = radius))
        }

    fun Context.text(sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    fun Context.sectionTitle(textRes: Int): TextView = text(14f, TEXT_DIM, bold = true).apply {
        text = getString(textRes).uppercase()
        letterSpacing = 0.08f
        setPadding(0, dp(24), 0, dp(10))
    }

    fun Context.tab(textRes: Int, onClick: () -> Unit): TextView = text(18f, bold = true).apply {
        setText(textRes)
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(10), dp(24), dp(10))
        background = focusable(Color.TRANSPARENT, SURFACE_FOCUSED, selected = ACCENT, radius = 24)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun Context.button(textRes: Int, primary: Boolean = false, onClick: () -> Unit): TextView = text(17f, bold = true).apply {
        setText(textRes)
        gravity = Gravity.CENTER
        minWidth = dp(140)
        setPadding(dp(22), dp(12), dp(22), dp(12))
        background = if (primary) focusable(ACCENT, ACCENT_FOCUSED) else focusable(SURFACE, SURFACE_FOCUSED)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** A selectable list row with a title and a dimmed detail line. */
    class Row(context: Context, onClick: () -> Unit) : LinearLayout(context) {
        val title: TextView = context.text(19f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val detail: TextView = context.text(15f, TEXT_DIM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        init {
            orientation = VERTICAL
            with(context) {
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = focusable(SURFACE, SURFACE_FOCUSED)
            }
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            addView(title)
            addView(detail)
        }

        fun set(title: String, detail: String) {
            this.title.text = title
            this.detail.text = detail
        }
    }

    /** A card with a colored status dot and a message; the minimum height keeps the layout from jumping. */
    class StatusCard(context: Context) : LinearLayout(context) {
        private val dot = View(context)
        private val message: TextView = context.text(18f).apply { minLines = 4 }

        init {
            orientation = HORIZONTAL
            with(context) {
                setPadding(dp(20), dp(18), dp(20), dp(18))
                background = rounded(SURFACE)
                addView(dot, LayoutParams(dp(12), dp(12)).apply {
                    topMargin = dp(8)
                    marginEnd = dp(16)
                })
            }
            addView(message, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        fun set(text: String, tone: Tone) {
            message.text = text
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tone.color)
            }
        }
    }

    fun Context.card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = rounded(SURFACE)
    }

    fun Context.textField(hintRes: Int): EditText = EditText(this).apply {
        setHint(hintRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(TEXT)
        setHintTextColor(TEXT_DIM)
        isSingleLine = true
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = focusable(SURFACE, SURFACE_FOCUSED)
        backgroundTintList = null
        highlightColor = ACCENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable?.setTintList(ColorStateList.valueOf(ACCENT))
    }

    /** Two columns side by side, as on a TV settings screen. */
    fun Context.columns(left: View, right: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f))
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f).apply {
            marginStart = dp(40)
        })
    }

    fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun Context.horizontal(vararg views: View, gap: Int = 16): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { if (index > 0) marginStart = dp(gap) })
        }
    }

    fun Context.listGap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(10) }
}
