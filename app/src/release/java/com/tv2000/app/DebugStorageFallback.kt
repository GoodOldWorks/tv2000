package com.tv2000.app

import android.content.Context
import android.net.Uri

/**
 * Release builds never fall back to app-owned test storage.
 */
object DebugStorageFallback {
    fun rootUri(context: Context): Uri? = null
}
