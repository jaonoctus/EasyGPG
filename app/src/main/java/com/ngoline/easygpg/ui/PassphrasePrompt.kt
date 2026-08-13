package com.ngoline.easygpg.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.ngoline.easygpg.PassphraseCache
import com.ngoline.easygpg.R
import com.ngoline.easygpg.applyPrivacyMode
import com.ngoline.easygpg.copyToCharArray
import com.ngoline.easygpg.wipe

/**
 * Modal prompt for a secret key ring passphrase, with the "Remember" selector that decides how
 * long [PassphraseCache] keeps it.
 *
 * The entered passphrase is handed to the caller as a [CharArray] so it can be wiped once the
 * OpenPGP operation is done; the input fields are cleared as soon as the dialog goes away.
 */
object PassphrasePrompt {

    /** Shortest passphrase accepted for a secret key ring. */
    const val MIN_LENGTH = 8

    fun show(
        context: Context,
        titleRes: Int,
        messageRes: Int? = null,
        confirm: Boolean = false,
        onPassphrase: (CharArray, PassphraseCache.Remember) -> Unit,
    ) {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }
        if (messageRes != null) {
            container.addView(TextView(context).apply { setText(messageRes) })
        }
        val passphraseInput = passphraseField(context, R.string.passphrase_hint)
        container.addView(passphraseInput)
        val confirmInput = if (confirm) {
            passphraseField(context, R.string.passphrase_confirm_hint).also { container.addView(it) }
        } else {
            null
        }
        val rememberSpinner = rememberSpinner(context)
        container.addView(rememberRow(context, rememberSpinner))

        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.applyPrivacyMode(context)

        // Validate before dismissing, which the default button listener would not allow.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val passphrase = passphraseInput.text.copyToCharArray()
                if (passphrase.size < MIN_LENGTH) {
                    passphrase.wipe()
                    Toast.makeText(
                        context,
                        context.resources.getQuantityString(
                            R.plurals.passphrase_too_short,
                            MIN_LENGTH,
                            MIN_LENGTH
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val confirmation = confirmInput?.let { it.text.copyToCharArray() }
                if (confirmation != null && !confirmation.contentEquals(passphrase)) {
                    passphrase.wipe()
                    confirmation.wipe()
                    Toast.makeText(context, R.string.passphrase_mismatch, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                confirmation?.wipe()
                val remember = PassphraseCache.Remember.entries
                    .getOrNull(rememberSpinner.selectedItemPosition) ?: PassphraseCache.DEFAULT
                dialog.dismiss()
                onPassphrase(passphrase, remember)
            }
        }
        dialog.setOnDismissListener {
            passphraseInput.text?.clear()
            confirmInput?.text?.clear()
        }
        dialog.show()
    }

    /** "Remember  [ for one hour ]", mirroring OpenKeychain's passphrase dialog. */
    private fun rememberRow(context: Context, spinner: Spinner) = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val gap = (8 * context.resources.displayMetrics.density).toInt()
        addView(TextView(context).apply {
            setText(R.string.remember_passphrase)
            setPadding(0, gap, gap, 0)
        })
        addView(spinner)
    }

    private fun rememberSpinner(context: Context) = Spinner(context).apply {
        val labels = PassphraseCache.Remember.entries.map { context.getString(it.labelRes) }
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(PassphraseCache.Remember.entries.indexOf(PassphraseCache.lastRemember(context)))
    }

    private fun passphraseField(context: Context, hintRes: Int) = IncognitoEditText(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setHint(hintRes)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
}
