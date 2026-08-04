package com.tv2000.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.tv2000.app.playback.AddSmbResourceResult
import com.tv2000.app.playback.PlaybackCodecPreference
import com.tv2000.app.playback.PlaybackCoordinator
import com.tv2000.app.playback.RemoteResult
import com.tv2000.app.scanner.ChannelScanner
import com.tv2000.app.storage.MediaCatalogRepository
import com.tv2000.app.storage.PlaybackHistoryStore
import com.tv2000.app.storage.UsbStorageResolver
import com.tv2000.app.storage.db.Tv2000Database
import com.tv2000.app.smb.SmbjMediaClient
import com.tv2000.app.smb.SmbResource
import com.tv2000.app.smb.Tv2000DataSource
import com.tv2000.app.ui.Tv2000App
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var rootView: FrameLayout
    private lateinit var startupView: View
    private var playerView: PlayerView? = null
    private val launchStartedElapsedMs = SystemClock.elapsedRealtime()
    private var playerFirstFrameReported = false
    private var pendingStorageRoot: Uri? = null
    private var retryStorageSelectionWhenReady = false
    private var storagePickerOpen = false
    private var smbSetupDialog: AlertDialog? = null
    private var smbSetupJob: Job? = null
    private var usbDirectoryDialog: AlertDialog? = null
    private var usbDirectoryJob: Job? = null
    private var usbReconciliationJob: Job? = null
    private var usbMonitoringReady = false
    private var mediaReceiverRegistered = false
    private var smbDetailsDialog: AlertDialog? = null
    private var confirmationDialog: AlertDialog? = null

    private val mediaMountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val removedRoot = intent.data.takeIf {
                intent.action in USB_REMOVAL_ACTIONS
            }
            Log.i(USB_LOG_TAG, "media_action=${intent.action} root=$removedRoot")
            scheduleUsbVolumeReconciliation(removedRoot)
        }
    }

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

        openStorageRoot(uri)
    }

    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        storagePickerOpen = false
        if (!granted) return@registerForActivityResult
        if (::coordinator.isInitialized) {
            openStoragePicker()
        } else {
            retryStorageSelectionWhenReady = true
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTelevisionWindow()
        logStartupTimestamp(APP_LAUNCH_STARTED, launchStartedElapsedMs)

        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        startupView = TextView(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.rgb(237, 231, 213))
            text = getString(R.string.app_name)
            textSize = 36f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        rootView.addView(startupView, matchParentLayoutParams())
        setContentView(rootView)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::coordinator.isInitialized) {
                        handleRemoteResult(coordinator.handleKey(KeyEvent.KEYCODE_BACK))
                    } else {
                        finish()
                    }
                }
            },
        )
        initializePlaybackAfterFirstDraw()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializePlayback() {
        if (isFinishing || isDestroyed || ::coordinator.isInitialized) return

        val database = Tv2000Database.get(applicationContext)
        val historyStore = PlaybackHistoryStore(applicationContext, database)
        val smbClient = SmbjMediaClient()
        val codecPreference = PlaybackCodecPreference()
        val dataSourceFactory = Tv2000DataSource.Factory(
            context = applicationContext,
            smbClient = smbClient,
            resourceProvider = historyStore::cachedSmbResource,
            onSourceOpening = codecPreference::onSourceOpening,
        )
        val renderersFactory = DefaultRenderersFactory(this)
            .setMediaCodecSelector(codecPreference.mediaCodecSelector)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        coordinator = PlaybackCoordinator(
            context = applicationContext,
            player = player,
            scanner = ChannelScanner(
                smbClient = smbClient,
                smbResourceProvider = historyStore::smbResource,
            ),
            catalogRepository = MediaCatalogRepository(database),
            historyStore = historyStore,
            smbClient = smbClient,
            scope = lifecycleScope,
            onFirstFrameRendered = ::onPlayerFirstFrameRendered,
        )

        // Keep video outside Compose so UI compatibility rendering never changes its Surface.
        val playbackView = PlayerView(this).apply {
            this.player = player
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            subtitleView?.setStyle(
                CaptionStyleCompat(
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.BLACK,
                    Typeface.DEFAULT,
                ),
            )
            subtitleView?.setApplyEmbeddedStyles(false)
        }
        playerView = playbackView
        val overlayView = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                val state = coordinator.state.collectAsStateWithLifecycle().value
                Tv2000App(state = state)
            }
        }
        rootView.addView(playbackView, 0, matchParentLayoutParams())
        rootView.addView(overlayView, 1, matchParentLayoutParams())
        removeStartupViewAfterFirstOverlayDraw(overlayView)
        logStartupTimestamp(PLAYBACK_DEPENDENCIES_READY)

        lifecycleScope.launch {
            pendingStorageRoot?.let { rootUri ->
                pendingStorageRoot = null
                coordinator.onStorageGranted(rootUri)
                return@launch
            }
            if (retryStorageSelectionWhenReady) {
                retryStorageSelectionWhenReady = false
                openStoragePicker()
                return@launch
            }
            val hasStoredRoot = coordinator.restore()
            usbMonitoringReady = true
            if (hasStoredRoot) {
                scheduleUsbVolumeReconciliation()
            } else {
                openStoragePicker()
            }
        }
    }

    private fun initializePlaybackAfterFirstDraw() {
        var handled = false
        rootView.viewTreeObserver.addOnDrawListener(
            object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (handled) return
                    handled = true
                    val firstFrameElapsedMs = SystemClock.elapsedRealtime()
                    rootView.post {
                        if (rootView.viewTreeObserver.isAlive) {
                            rootView.viewTreeObserver.removeOnDrawListener(this)
                        }
                        logStartupTimestamp(APP_CONTENT_FIRST_FRAME, firstFrameElapsedMs)
                        initializePlayback()
                    }
                }
            },
        )
    }

    private fun removeStartupViewAfterFirstOverlayDraw(overlayView: View) {
        var handled = false
        overlayView.viewTreeObserver.addOnDrawListener(
            object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (handled) return
                    handled = true
                    overlayView.post {
                        if (overlayView.viewTreeObserver.isAlive) {
                            overlayView.viewTreeObserver.removeOnDrawListener(this)
                        }
                        rootView.removeView(startupView)
                    }
                }
            },
        )
    }

    private fun onPlayerFirstFrameRendered() {
        if (playerFirstFrameReported) return
        playerFirstFrameReported = true
        logStartupTimestamp(PLAYER_FIRST_FRAME)
        reportFullyDrawn()
    }

    private fun logStartupTimestamp(
        event: String,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        Log.i(
            STARTUP_LOG_TAG,
            "$event=$elapsedMs launch_duration_ms=${elapsedMs - launchStartedElapsedMs}",
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!::coordinator.isInitialized) {
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode !in REMOTE_CONTROL_KEYS) {
            return super.onKeyDown(keyCode, event)
        }

        if (event.repeatCount > 0) {
            return super.onKeyDown(keyCode, event)
        }

        return handleRemoteResult(coordinator.handleKey(keyCode)) ||
            super.onKeyDown(keyCode, event)
    }

    private fun matchParentLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

    private fun handleRemoteResult(result: RemoteResult): Boolean {
        return when (result) {
            RemoteResult.CONSUMED -> true
            RemoteResult.REQUEST_STORAGE -> {
                openStoragePicker()
                true
            }

            RemoteResult.REQUEST_USB_EDIT -> {
                openUsbDirectoryDialog()
                true
            }

            RemoteResult.REQUEST_SMB_SETUP -> {
                openSmbSetupDialog()
                true
            }

            RemoteResult.REQUEST_SMB_VIEW -> {
                coordinator.selectedManagedSmbResource()?.let(::openSmbDetailsDialog)
                true
            }

            RemoteResult.REQUEST_SMB_EDIT -> {
                coordinator.selectedManagedSmbResource()?.let(::openSmbSetupDialog)
                true
            }

            RemoteResult.REQUEST_CONFIRMATION -> {
                openConfirmationDialog()
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

    override fun onStart() {
        super.onStart()
        startUsbVolumeMonitoring()
        if (::coordinator.isInitialized) {
            if (usbMonitoringReady) scheduleUsbVolumeReconciliation()
            coordinator.onForeground()
        }
    }

    override fun onStop() {
        stopUsbVolumeMonitoring()
        if (::coordinator.isInitialized) coordinator.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        smbSetupJob?.cancel()
        usbDirectoryJob?.cancel()
        usbReconciliationJob?.cancel()
        smbSetupDialog?.dismiss()
        usbDirectoryDialog?.dismiss()
        smbDetailsDialog?.dismiss()
        confirmationDialog?.dismiss()
        playerView?.player = null
        playerView = null
        if (::coordinator.isInitialized) coordinator.release()
        super.onDestroy()
    }

    private fun startUsbVolumeMonitoring() {
        if (mediaReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaMountReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mediaMountReceiver, filter)
        }
        mediaReceiverRegistered = true
    }

    private fun stopUsbVolumeMonitoring() {
        usbReconciliationJob?.cancel()
        usbReconciliationJob = null
        if (!mediaReceiverRegistered) return
        unregisterReceiver(mediaMountReceiver)
        mediaReceiverRegistered = false
    }

    private fun scheduleUsbVolumeReconciliation(removedRoot: Uri? = null) {
        if (!usbMonitoringReady || !::coordinator.isInitialized) return
        reconcileMountedUsbVolumes(removedRoot)
        usbReconciliationJob?.cancel()
        usbReconciliationJob = lifecycleScope.launch {
            USB_RECONCILIATION_DELAYS_MS.forEach { delayMs ->
                delay(delayMs)
                reconcileMountedUsbVolumes()
            }
        }
    }

    private fun reconcileMountedUsbVolumes(removedRoot: Uri? = null) {
        if (!usbMonitoringReady || !::coordinator.isInitialized) return
        val removedIdentity = removedRoot?.let { rootUri ->
            UsbStorageResolver.volumeIdentity(rootUri)
        }
        val mountedRoots = UsbStorageResolver.findMountedUsbRoots(this)
            .filterNot { rootUri ->
                removedIdentity != null &&
                    UsbStorageResolver.volumeIdentity(rootUri) == removedIdentity
            }
        coordinator.onMountedUsbRootsChanged(
            mountedRoots,
        )
    }

    private fun openStoragePicker() {
        if (storagePickerOpen || isFinishing) return

        val mountedUsbRoot = UsbStorageResolver.findMountedUsbRoot(this)
        if (mountedUsbRoot != null) {
            val permission = UsbStorageResolver.requiredReadPermission()
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                storagePickerOpen = true
                mediaPermission.launch(permission)
                return
            }

            openStorageRoot(mountedUsbRoot)
            return
        }

        val pickerComponent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .resolveActivity(packageManager)
        val pickerUnavailable = pickerComponent == null ||
            pickerComponent.packageName.contains("frameworkpackagestubs")

        if (pickerUnavailable) {
            val rootUri = DebugStorageFallback.rootUri(this)
                ?: return
            openStorageRoot(rootUri)
            return
        }

        storagePickerOpen = true
        storagePicker.launch(null)
    }

    private fun openStorageRoot(rootUri: Uri) {
        if (!::coordinator.isInitialized) {
            pendingStorageRoot = rootUri
            return
        }
        storagePickerOpen = true
        lifecycleScope.launch {
            try {
                coordinator.onStorageGranted(rootUri)
            } finally {
                storagePickerOpen = false
            }
        }
    }

    private fun openSmbSetupDialog(existing: SmbResource? = null) {
        if (smbSetupDialog?.isShowing == true || isFinishing) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 13, 16))
            val horizontal = 28.dp
            setPadding(horizontal, 8.dp, horizontal, 0)
        }
        val addressField = container.addField(
            hint = getString(R.string.smb_address),
            value = existing?.address.orEmpty(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val usernameField = container.addField(
            hint = getString(R.string.smb_username),
            value = existing?.username.orEmpty(),
        )
        val passwordField = container.addField(
            hint = getString(R.string.smb_password),
            value = existing?.password.orEmpty(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val domainField = container.addField(
            hint = getString(R.string.smb_domain),
            value = existing?.domain.orEmpty(),
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.smb_setup_title)
            .setMessage(R.string.smb_setup_help)
            .setView(container)
            .setPositiveButton(
                if (existing == null) R.string.save else R.string.save_changes,
                null,
            )
            .setNegativeButton(R.string.cancel, null)
            .create()
        smbSetupDialog = dialog
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            saveButton.setOnClickListener {
                val parsed = SmbResource.parse(
                    address = addressField.text.toString(),
                    username = usernameField.text.toString(),
                    password = passwordField.text.toString(),
                    domain = domainField.text.toString(),
                )
                parsed.onSuccess { resource ->
                    addressField.error = null
                    saveButton.isEnabled = false
                    cancelButton.isEnabled = false
                    saveButton.setText(R.string.connecting)
                    smbSetupJob = lifecycleScope.launch {
                        val result = if (existing == null) {
                            coordinator.addSmbResource(resource)
                        } else {
                            coordinator.updateSmbResource(existing.id, resource)
                        }
                        when (result) {
                            AddSmbResourceResult.Success -> {
                                smbSetupJob = null
                                dialog.dismiss()
                            }

                            is AddSmbResourceResult.Failure -> if (dialog.isShowing) {
                                smbSetupJob = null
                                addressField.error = result.message
                                addressField.requestFocus()
                                saveButton.isEnabled = true
                                cancelButton.isEnabled = true
                                saveButton.setText(
                                    if (existing == null) R.string.save else R.string.save_changes,
                                )
                            }
                        }
                    }
                }.onFailure { error ->
                    addressField.error = error.message ?: getString(R.string.invalid_smb_address)
                    addressField.requestFocus()
                }
            }
        }
        dialog.setOnDismissListener {
            smbSetupJob?.cancel()
            smbSetupJob = null
            if (smbSetupDialog === dialog) smbSetupDialog = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(11, 13, 16)))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 1f }
        }
        addressField.requestFocus()
        addressField.setSelection(addressField.text.length)
    }

    private fun openUsbDirectoryDialog() {
        if (usbDirectoryDialog?.isShowing == true || isFinishing) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 13, 16))
            val horizontal = 28.dp
            setPadding(horizontal, 8.dp, horizontal, 0)
        }
        val directoryField = container.addField(
            hint = getString(R.string.usb_video_directory_hint),
            value = coordinator.usbVideoDirectory(),
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.usb_settings_title)
            .setMessage(R.string.usb_settings_help)
            .setView(container)
            .setPositiveButton(R.string.save_changes, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        usbDirectoryDialog = dialog
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            saveButton.setOnClickListener {
                val normalized = UsbStorageResolver.normalizeVideoDirectory(
                    directoryField.text.toString(),
                )
                if (normalized == null) {
                    directoryField.error = getString(R.string.invalid_usb_video_directory)
                    directoryField.requestFocus()
                    return@setOnClickListener
                }

                directoryField.error = null
                saveButton.isEnabled = false
                cancelButton.isEnabled = false
                usbDirectoryJob = lifecycleScope.launch {
                    coordinator.updateUsbVideoDirectory(normalized)
                    usbDirectoryJob = null
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            usbDirectoryJob?.cancel()
            usbDirectoryJob = null
            if (usbDirectoryDialog === dialog) usbDirectoryDialog = null
        }
        dialog.show()
        styleTelevisionDialog(dialog)
        directoryField.requestFocus()
        directoryField.setSelection(directoryField.text.length)
    }

    private fun openSmbDetailsDialog(resource: SmbResource) {
        if (smbDetailsDialog?.isShowing == true || isFinishing) return

        val notSet = getString(R.string.not_set)
        val message = listOf(
            getString(R.string.smb_details_address, resource.address),
            getString(
                R.string.smb_details_username,
                resource.username.ifBlank { notSet },
            ),
            getString(R.string.smb_details_domain, resource.domain.ifBlank { notSet }),
            getString(
                if (resource.password.isEmpty()) {
                    R.string.smb_details_password_empty
                } else {
                    R.string.smb_details_password_set
                },
            ),
        ).joinToString("\n")
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.smb_details_title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .create()
        smbDetailsDialog = dialog
        dialog.setOnDismissListener {
            if (smbDetailsDialog === dialog) smbDetailsDialog = null
        }
        dialog.show()
        styleTelevisionDialog(dialog)
    }

    private fun openConfirmationDialog() {
        if (confirmationDialog?.isShowing == true || isFinishing) return
        val request = coordinator.confirmationRequest() ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle(request.title)
            .setMessage(request.message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                coordinator.confirmPendingAction()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                coordinator.cancelPendingConfirmation()
            }
            .create()
        confirmationDialog = dialog
        dialog.setOnDismissListener {
            coordinator.cancelPendingConfirmation()
            if (confirmationDialog === dialog) confirmationDialog = null
        }
        dialog.show()
        styleTelevisionDialog(dialog)
    }

    private fun styleTelevisionDialog(dialog: AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(11, 13, 16)))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 1f }
        }
    }

    private fun LinearLayout.addField(
        hint: String,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ): EditText = EditText(context).also { field ->
        field.hint = hint
        field.setText(value)
        field.setSingleLine(true)
        field.inputType = inputType
        field.textSize = 20f
        field.setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        addView(
            field,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 8.dp },
        )
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun configureTelevisionWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private companion object {
        const val STARTUP_LOG_TAG = "TV2000.Startup"
        const val USB_LOG_TAG = "TV2000.Usb"
        const val APP_LAUNCH_STARTED = "app_launch_started_elapsed_ms"
        const val APP_CONTENT_FIRST_FRAME = "app_content_first_frame_elapsed_ms"
        const val PLAYBACK_DEPENDENCIES_READY = "playback_dependencies_ready_elapsed_ms"
        const val PLAYER_FIRST_FRAME = "player_first_frame_elapsed_ms"
        val USB_RECONCILIATION_DELAYS_MS = listOf(500L, 1_500L, 3_000L)
        val USB_REMOVAL_ACTIONS = setOf(
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
        )
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
            KeyEvent.KEYCODE_MENU,
        )
    }
}
