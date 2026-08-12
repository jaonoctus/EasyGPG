package com.ngoline.easygpg.ui.decrypt

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.ngoline.easygpg.DecryptionResult
import com.ngoline.easygpg.PGPKeyManager
import com.ngoline.easygpg.PassphraseCache
import com.ngoline.easygpg.R
import com.ngoline.easygpg.databinding.FragmentDecryptBinding
import com.ngoline.easygpg.wipe
import com.ngoline.easygpg.ui.DeviceAuth
import com.ngoline.easygpg.ui.PassphrasePrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DecryptFragment : Fragment() {

    private var useYubikey: Boolean = true // Set this based on your app's settings or logic

    private var _binding: FragmentDecryptBinding? = null

    private lateinit var editTextMessage: EditText
    private lateinit var buttonDecrypt: Button
    private lateinit var keyManager: PGPKeyManager
    private lateinit var textView: TextView

    /** Buffer behind the decrypted text on screen, wiped as soon as it stops being displayed. */
    private var shownPlaintext: CharArray? = null

    private val binding get() = _binding!!

    override fun onAttach(context: Context) {
        super.onAttach(context)
        keyManager = PGPKeyManager(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val encryptViewModel =
            ViewModelProvider(this)[DecryptViewModel::class.java]

        _binding = FragmentDecryptBinding.inflate(inflater, container, false)
        val root: View = binding.root

        editTextMessage = root.findViewById(R.id.editTextCipher)
        buttonDecrypt = root.findViewById(R.id.buttonDecrypt)
        textView = root.findViewById(R.id.textViewDecrypted)

        buttonDecrypt.setOnClickListener {
            val encryptedMessage = editTextMessage.text.toString()
            if (encryptedMessage.isBlank()) {
                wipeShownPlaintext(getString(R.string.enter_message_to_decrypt))
                return@setOnClickListener
            }
            decrypt(encryptedMessage)
        }

        editTextMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                buttonDecrypt.isEnabled = true
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val encryptedMessage = arguments?.getString("encrypted_message") ?: ""
        if (encryptedMessage.isEmpty()) {
            wipeShownPlaintext(getString(R.string.waiting_for_encrypted_message))
            return root
        }

        buttonDecrypt.isEnabled = false
        editTextMessage.setText(encryptedMessage)
        decrypt(encryptedMessage)

        return root
    }

    /**
     * Decrypts with the passphrase still held by [PassphraseCache], or asks for it — migrating key
     * rings that still carry the old placeholder passphrase first.
     */
    private fun decrypt(encryptedMessage: String) {
        lifecycleScope.launch {
            val needsMigration = keyOperation {
                withContext(Dispatchers.IO) { keyManager.hasLegacyProtectedKeys() }
            } ?: return@launch
            if (!isAdded) return@launch
            if (needsMigration) {
                promptForMigration(encryptedMessage)
                return@launch
            }
            val cached = PassphraseCache.get()
            if (cached != null) {
                decryptWith(encryptedMessage, cached, remember = null)
            } else {
                promptForPassphrase(encryptedMessage)
            }
        }
    }

    private fun promptForPassphrase(encryptedMessage: String) {
        PassphrasePrompt.show(
            requireContext(),
            titleRes = R.string.passphrase_decrypt_title,
        ) { passphrase, remember ->
            lifecycleScope.launch { decryptWith(encryptedMessage, passphrase, remember) }
        }
    }

    private fun promptForMigration(encryptedMessage: String) {
        PassphrasePrompt.show(
            requireContext(),
            titleRes = R.string.passphrase_migrate_title,
            messageRes = R.string.passphrase_migrate_message,
            confirm = true,
        ) { passphrase, remember ->
            lifecycleScope.launch {
                val migrated = keyOperation {
                    withContext(Dispatchers.IO) { keyManager.migrateLegacyKeyPassphrases(passphrase) }
                }
                if (migrated == null || !isAdded) {
                    passphrase.wipe()
                    return@launch
                }
                Toast.makeText(
                    requireContext(),
                    if (migrated > 0) {
                        resources.getQuantityString(
                            R.plurals.passphrase_migrate_done,
                            migrated,
                            migrated
                        )
                    } else {
                        getString(R.string.passphrase_migrate_failed)
                    },
                    Toast.LENGTH_LONG
                ).show()
                decryptWith(encryptedMessage, passphrase, remember)
            }
        }
    }

    /**
     * Decrypts and wipes [passphrase] afterwards, caching it for [remember] only once it has
     * actually opened the message. A null [remember] means the passphrase came from the cache, so a
     * passphrase that no longer works is dropped and asked for again instead of being reported.
     */
    private suspend fun decryptWith(
        encryptedMessage: String,
        passphrase: CharArray,
        remember: PassphraseCache.Remember?,
    ) {
        if (!isAdded) {
            passphrase.wipe()
            return
        }
        val context = requireContext()
        wipeShownPlaintext(getString(R.string.decrypting))
        val result = try {
            keyOperation {
                withContext(Dispatchers.IO) { keyManager.decryptMessage(encryptedMessage, passphrase) }
            }?.also {
                if (it is DecryptionResult.Decrypted && remember != null) {
                    PassphraseCache.store(context, passphrase, remember)
                }
            }
        } finally {
            passphrase.wipe()
        }
        if (!isAdded) {
            (result as? DecryptionResult.Decrypted)?.plaintext?.wipe()
            return
        }
        when (result) {
            null -> {} // keyOperation already said why
            is DecryptionResult.Decrypted -> {
                showPlaintext(result.plaintext)
                if (shouldClearFieldsAfterOperation()) {
                    editTextMessage.setText("")
                }
            }
            is DecryptionResult.WrongPassphrase -> {
                PassphraseCache.clear()
                if (remember == null) {
                    promptForPassphrase(encryptedMessage)
                } else {
                    wipeShownPlaintext(getString(R.string.decrypt_wrong_passphrase))
                }
            }
            is DecryptionResult.NoUsableKey -> {
                wipeShownPlaintext(getString(R.string.decrypt_no_usable_key))
            }
        }
    }

    /**
     * Shows decrypted text straight from [plaintext] — `TextView` wraps the array instead of
     * copying it — and keeps it so it can be wiped as soon as it stops being displayed.
     */
    private fun showPlaintext(plaintext: CharArray) {
        wipeShownPlaintext()
        shownPlaintext = plaintext
        textView.setText(plaintext, 0, plaintext.size)
    }

    /** Replaces displayed plaintext with [message] (or nothing) and wipes the buffer behind it. */
    private fun wipeShownPlaintext(message: String = "") {
        val plaintext = shownPlaintext
        shownPlaintext = null
        // Drop the reference the TextView holds before overwriting the buffer it was showing.
        textView.text = message
        plaintext?.wipe()
    }

    /** Runs a key manager call, prompting for authentication and reporting a locked key ring. */
    private suspend fun <T> keyOperation(block: suspend () -> T): T? =
        DeviceAuth.withKeyAccess(this, { reason -> if (isAdded) wipeShownPlaintext(reason) }, block)

    private fun shouldClearFieldsAfterOperation(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getBoolean(getString(R.string.clear_fields_after_operation), true)
    }

    override fun onDestroyView() {
        // Leaving the screen must not leave the decrypted text sitting in memory.
        wipeShownPlaintext()
        _binding = null
        super.onDestroyView()
    }
}