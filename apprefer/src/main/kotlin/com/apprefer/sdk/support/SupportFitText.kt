package com.apprefer.sdk.support

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.abs

/**
 * One line of text that shrinks to fit its width, down to [minScale] of its size, and only then ends in "…".
 * The quick-help tile titles, so "Billing & refunds" stays whole on a 320 dp screen with large text, as it does
 * in the other SDKs (iOS minimumScaleFactor, Flutter FittedBox, React Native adjustsFontSizeToFit).
 */
@SuppressLint("AppCompatCustomView", "ViewConstructor")
internal class SupportFitTextView(context: Context, private val minScale: Float) : TextView(context) {
    private var basePx = 0f

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        basePx = textSize
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (basePx > 0f && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight).toFloat()
            val measure = android.text.TextPaint(paint).apply { textSize = basePx }
            val fitted = SupportHomeLayout.fitTextSize(basePx, measure.measureText(text.toString()), available, minScale)
            if (abs(fitted - textSize) > 0.25f) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
