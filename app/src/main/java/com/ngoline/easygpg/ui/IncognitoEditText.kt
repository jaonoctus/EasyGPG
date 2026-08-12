package com.ngoline.easygpg.ui

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.appcompat.widget.AppCompatEditText

/**
 * Text input that asks the IME not to retain entered content for personalized learning.
 */
class IncognitoEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

    init {
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        return super.onCreateInputConnection(outAttrs).also {
            outAttrs.imeOptions =
                outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }
}
