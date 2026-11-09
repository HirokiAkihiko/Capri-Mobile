package com.capri.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * The little box you type to her in, over any app, like on the desktop: tap her and it opens with the keyboard. Sits at
 * the top of the screen so the keyboard never covers it; Send closes it and she answers in her bubble.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class TypeBox(context: Context, dp: Float, private val send: (String) -> Unit, private val close: () -> Unit) :
    LinearLayout(ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar)) {
    private val field = EditText(this.context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (8 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 12 * dp
            setStroke((1 * dp).toInt().coerceAtLeast(1), PINK)
        }
        val font = runCatching { ResourcesCompat.getFont(context, R.font.manrope_regular) }.getOrNull() ?: Typeface.DEFAULT
        field.apply {
            hint = tr("Type to her...")
            typeface = font
            textSize = 15f
            setTextColor(Color.BLACK)
            setHintTextColor(0xFF7A6A72.toInt())
            background = null
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
            }
        }
        addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val button = TextView(this.context).apply {
            text = tr("Send")
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.manrope_extrabold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((14 * dp).toInt(), (9 * dp).toInt(), (14 * dp).toInt(), (9 * dp).toInt())
            background = GradientDrawable().apply { setColor(PINK); cornerRadius = 8 * dp }
            setOnClickListener { submit() }
        }
        addView(button)
    }

    private fun submit() {
        val text = field.text.toString().trim()
        close()
        if (text.isNotEmpty()) send(text)
    }

    /** The window is up: focus the field and bring the keyboard. */
    fun open() {
        field.requestFocus()
        field.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { close(); return true }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { close(); return true }  // tapped anywhere else
        return super.onTouchEvent(event)
    }

    private companion object {
        const val PINK = 0xFFFF0048.toInt()  // her pink
    }
}
