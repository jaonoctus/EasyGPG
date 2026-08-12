package com.ngoline.easygpg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Keeps the secret key passphrase available for as long as the user asked for, following the
 * strategy of OpenKeychain's `PassphraseCacheService`:
 *
 * - the passphrase is only ever held in memory, and is overwritten the moment it is evicted;
 * - only the chosen duration is persisted, never the passphrase itself;
 * - "until screen off" entries are dropped on [Intent.ACTION_SCREEN_OFF];
 * - timed entries are evicted actively, not just when someone next reads them.
 *
 * Unlike OpenKeychain, which has to keep its cache in a sticky service so other processes can
 * reach it, this cache lives in the app process and therefore also dies with the app.
 */
object PassphraseCache {

    /** How long an entered passphrase stays cached. `0` seconds means "until screen off". */
    enum class Remember(val labelRes: Int, val ttlSeconds: Long) {
        UNTIL_SCREEN_OFF(R.string.cache_ttl_lock_screen, 0),
        ONE_HOUR(R.string.cache_ttl_one_hour, 60 * 60),
        ONE_DAY(R.string.cache_ttl_one_day, 24 * 60 * 60),
    }

    val DEFAULT = Remember.ONE_HOUR

    private const val PREF_LAST_REMEMBER = "passphrase_cache_last_remember"

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var cached: CharArray? = null
    private var expiresAtElapsedRealtime = Long.MAX_VALUE
    private var expiry: Runnable? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    /** The duration the user picked last time, so the prompt can preselect it. */
    fun lastRemember(context: Context): Remember {
        val stored = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_LAST_REMEMBER, null) ?: return DEFAULT
        return Remember.entries.firstOrNull { it.name == stored } ?: DEFAULT
    }

    /**
     * Caches a copy of [passphrase] for [remember]. Only call this once the passphrase has been
     * shown to actually unlock a key ring, so a mistyped one is never kept.
     */
    @Synchronized
    fun store(context: Context, passphrase: CharArray, remember: Remember) {
        clear()
        appContext = context.applicationContext
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_LAST_REMEMBER, remember.name)
        }
        cached = passphrase.copyOf()
        if (remember.ttlSeconds > 0) {
            val ttlMillis = remember.ttlSeconds * 1000
            expiresAtElapsedRealtime = SystemClock.elapsedRealtime() + ttlMillis
            expiry = Runnable { clear() }.also { handler.postDelayed(it, ttlMillis) }
        } else {
            registerScreenOffReceiver()
        }
    }

    /** A copy of the cached passphrase, or null if nothing is cached. The caller must [wipe] it. */
    @Synchronized
    fun get(): CharArray? {
        val passphrase = cached ?: return null
        // elapsedRealtime, so moving the system clock cannot extend the lifetime.
        if (SystemClock.elapsedRealtime() >= expiresAtElapsedRealtime) {
            clear()
            return null
        }
        return passphrase.copyOf()
    }

    @Synchronized
    fun clear() {
        cached?.wipe()
        cached = null
        expiresAtElapsedRealtime = Long.MAX_VALUE
        expiry?.let { handler.removeCallbacks(it) }
        expiry = null
        screenOffReceiver?.let { receiver ->
            appContext?.unregisterReceiver(receiver)
            screenOffReceiver = null
        }
    }

    private fun registerScreenOffReceiver() {
        val context = appContext ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = clear()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenOffReceiver = receiver
    }
}
