package com.tv2000.app

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Android TV emulator images can ship only a DocumentsUI stub. In debug builds,
 * use an app-owned external directory as a deterministic virtual USB root.
 */
object DebugStorageFallback {
    fun rootUri(context: Context): Uri? {
        val externalRoot = context.getExternalFilesDir(null) ?: return null
        val testRoot = File(externalRoot, TEST_ROOT_NAME)
        if (!testRoot.exists() && !testRoot.mkdirs()) return null
        return Uri.fromFile(testRoot)
    }

    private const val TEST_ROOT_NAME = "TV2000-Test"
}
