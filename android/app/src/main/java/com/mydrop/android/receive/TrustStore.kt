package com.mydrop.android.receive

import android.content.Context

/** Devices that may send us files without asking each time. */
class TrustStore(context: Context) {
    private val prefs = context.getSharedPreferences("mydrop", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet("trustedSenders", emptySet()) ?: emptySet()
    fun isTrusted(name: String) = name in all()
    fun add(name: String) { prefs.edit().putStringSet("trustedSenders", all() + name).apply() }
    fun remove(name: String) { prefs.edit().putStringSet("trustedSenders", all() - name).apply() }
}

/** Simple on/off switches that need to survive restarts. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("mydrop", Context.MODE_PRIVATE)
    fun receiveEnabled(c: Context) = p(c).getBoolean("receiveEnabled", true)
    fun setReceiveEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("receiveEnabled", on).apply()
    fun shareWrite(c: Context) = p(c).getBoolean("shareWrite", false)
    fun setShareWrite(c: Context, on: Boolean) = p(c).edit().putBoolean("shareWrite", on).apply()
    fun mirror(c: Context) = p(c).getBoolean("mirror", false)
    fun setMirror(c: Context, on: Boolean) = p(c).edit().putBoolean("mirror", on).apply()
    fun syncClipboard(c: Context) = p(c).getBoolean("syncClipboard", false)
    fun setSyncClipboard(c: Context, on: Boolean) = p(c).edit().putBoolean("syncClipboard", on).apply()
    fun shareFiles(c: Context) = p(c).getBoolean("shareFiles", false)
    fun setShareFiles(c: Context, on: Boolean) = p(c).edit().putBoolean("shareFiles", on).apply()
    /** The code the Mac must enter once to browse this phone. */
    fun shareCode(c: Context): String =
        p(c).getString("shareCode", null) ?: "%06d".format(java.security.SecureRandom().nextInt(1_000_000)).also { p(c).edit().putString("shareCode", it).apply() }
}
