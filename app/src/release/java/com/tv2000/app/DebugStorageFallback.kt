package com.tv2000.app

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Release builds never fall back to app-owned test storage.
 */
object DebugStorageFallback {
    fun rootUri(context: Context): Uri? = null

    fun shouldDiscardStoredRoot(context: Context, uri: Uri): Boolean {
        val internalRoot = Uri.fromFile(File(context.filesDir, TEST_ROOT_NAME))
        val externalRoot = context.getExternalFilesDir(null)
            ?.let { root -> Uri.fromFile(File(root, TEST_ROOT_NAME)) }
        return uri == internalRoot || uri == externalRoot
    }

    private const val TEST_ROOT_NAME = "TV2000-Test"
}
