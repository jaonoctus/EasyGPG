package com.ngoline.easygpg

import android.content.Context
import android.view.Window
import android.view.WindowManager
import androidx.preference.PreferenceManager

/** True while the user has asked for content to be hidden from screenshots and recents. */
fun Context.isPrivacyModeEnabled(): Boolean =
    PreferenceManager.getDefaultSharedPreferences(this)
        .getBoolean(getString(R.string.privacy_mode), false)

/**
 * Applies privacy mode to a window. Dialogs get their own window, so each one that shows secret
 * input has to do this for itself — the flag on the activity does not cover them.
 */
fun Window.applyPrivacyMode(context: Context) {
    if (context.isPrivacyModeEnabled()) {
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
