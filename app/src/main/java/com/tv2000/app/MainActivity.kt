package com.tv2000.app

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tv2000.app.playback.AddSmbResourceResult
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var coordinator: PlaybackCoordinator
    private var storagePickerOpen = false
    private var smbSetupDialog: AlertDialog? = null
    private var smbSetupJob: Job? = null
    private var smbDetailsDialog: AlertDialog? = null
    private var confirmationDialog: AlertDialog? = null

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

    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        storagePickerOpen = false
        if (granted) openStoragePicker()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureTelevisionWindow()

        val database = Tv2000Database.get(applicationContext)
        val historyStore = PlaybackHistoryStore(applicationContext, database)
        val smbClient = SmbjMediaClient()
        val dataSourceFactory = Tv2000DataSource.Factory(
            context = applicationContext,
            smbClient = smbClient,
            resourceProvider = historyStore::cachedSmbResource,
        )
        val player = ExoPlayer.Builder(this)
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

    override fun onStop() {
        coordinator.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        smbSetupJob?.cancel()
        smbSetupDialog?.dismiss()
        smbDetailsDialog?.dismiss()
        confirmationDialog?.dismiss()
        coordinator.release()
        super.onDestroy()
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

    private fun openStorageRoot(rootUri: android.net.Uri) {
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
