package com.tv2000.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import com.tv2000.app.playback.PlaybackCoordinator
import com.tv2000.app.playback.RemoteResult
import com.tv2000.app.scanner.ChannelScanner
import com.tv2000.app.storage.MediaCatalogRepository
import com.tv2000.app.storage.PlaybackHistoryStore
import com.tv2000.app.storage.db.Tv2000Database
import com.tv2000.app.ui.Tv2000App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var coordinator: PlaybackCoordinator
    private var storagePickerOpen = false

    private val storagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        storagePickerOpen = false
        if (uri == null) return@registerForActivityResult

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        lifecycleScope.launch {
            coordinator.onStorageGranted(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTelevisionWindow()

        val player = ExoPlayer.Builder(this).build()
        val database = Tv2000Database.get(applicationContext)
        coordinator = PlaybackCoordinator(
            context = applicationContext,
            player = player,
            scanner = ChannelScanner(),
            catalogRepository = MediaCatalogRepository(database),
            historyStore = PlaybackHistoryStore(applicationContext, database),
            scope = lifecycleScope,
        )
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleRemoteResult(coordinator.handleKey(KeyEvent.KEYCODE_BACK))
                }
            },
        )

        setContent {
            val state = coordinator.state.collectAsStateWithLifecycle().value
            Tv2000App(
                state = state,
                player = player,
            )
        }

        lifecycleScope.launch {
            val hasStoredRoot = coordinator.restore()
            if (!hasStoredRoot) openStoragePicker()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in REMOTE_CONTROL_KEYS) {
            return super.onKeyDown(keyCode, event)
        }

        if (event.repeatCount > 0) {
            return super.onKeyDown(keyCode, event)
        }

        return handleRemoteResult(coordinator.handleKey(keyCode)) ||
            super.onKeyDown(keyCode, event)
    }

    private fun handleRemoteResult(result: RemoteResult): Boolean {
        return when (result) {
            RemoteResult.CONSUMED -> true
            RemoteResult.REQUEST_STORAGE -> {
                openStoragePicker()
                true
            }

            RemoteResult.EXIT -> {
                lifecycleScope.launch { coordinator.checkpoint() }
                finish()
                true
            }

            RemoteResult.NOT_HANDLED -> false
        }
    }

    override fun onStop() {
        coordinator.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        coordinator.release()
        super.onDestroy()
    }

    private fun openStoragePicker() {
        if (storagePickerOpen || isFinishing) return

        val pickerComponent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .resolveActivity(packageManager)
        val pickerUnavailable = pickerComponent == null ||
            pickerComponent.packageName.contains("frameworkpackagestubs")

        if (pickerUnavailable) {
            val debugRootUri = DebugStorageFallback.rootUri(this) ?: return
            storagePickerOpen = true
            lifecycleScope.launch {
                try {
                    coordinator.onStorageGranted(debugRootUri)
                } finally {
                    storagePickerOpen = false
                }
            }
            return
        }

        storagePickerOpen = true
        storagePicker.launch(null)
    }

    @Suppress("DEPRECATION")
    private fun configureTelevisionWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private companion object {
        val REMOTE_CONTROL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_NEXT,
        )
    }
}
