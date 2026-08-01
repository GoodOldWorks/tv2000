package com.tv2000.app

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Android TV emulator images can ship only a DocumentsUI stub. In debug builds,
 * use an app-owned internal directory as a deterministic virtual USB root.
 * Internal storage remains writable through `run-as` on recent emulator images,
 * whose scoped external storage rejects files pushed by the adb shell user.
 */
object DebugStorageFallback {
    fun rootUri(context: Context): Uri? {
        if (!isEmulator()) return null

        val testRoot = internalTestRoot(context)
        if (!testRoot.exists() && !testRoot.mkdirs()) return null
        return Uri.fromFile(testRoot)
    }

    fun shouldDiscardStoredRoot(context: Context, uri: Uri): Boolean {
        val isLegacyExternalRoot = uri == legacyExternalFallbackUri(context)
        return if (isEmulator()) {
            isLegacyExternalRoot
        } else {
            uri == Uri.fromFile(internalTestRoot(context)) || isLegacyExternalRoot
        }
    }

    private fun internalTestRoot(context: Context): File =
        File(context.filesDir, TEST_ROOT_NAME)

    private fun legacyExternalFallbackUri(context: Context): Uri? {
        val externalRoot = context.getExternalFilesDir(null) ?: return null
        return Uri.fromFile(File(externalRoot, TEST_ROOT_NAME))
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)

    private const val TEST_ROOT_NAME = "TV2000-Test"
}
