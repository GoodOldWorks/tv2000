package com.tv2000.app.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tv2000.app.R
import com.tv2000.app.model.Channel
import com.tv2000.app.playback.AppMode
import com.tv2000.app.playback.ResourceKind
import com.tv2000.app.playback.Tv2000UiState
import com.tv2000.app.scanner.ScanFailure
import java.util.Locale

@Composable
fun Tv2000App(
    state: Tv2000UiState,
) {
    ComposeRenderingCompatibility(
        enabled = state.channelListVisible ||
            state.mainMenuVisible ||
            state.resourceSettingsVisible ||
            state.usbResourceActionsVisible ||
            state.smbResourceActionsVisible ||
            state.advancedSettingsVisible,
    )

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (state.mode == AppMode.READY) Color.Transparent else Color.Black,
                ),
        ) {
            when (state.mode) {
                AppMode.LOADING,
                AppMode.SCANNING,
                -> CenteredStatus(
                    title = if (state.mode == AppMode.LOADING) {
                        stringResource(R.string.app_name)
                    } else {
                        stringResource(R.string.scanning_usb)
                    },
                    subtitle = null,
                )

                AppMode.NEEDS_STORAGE_ACCESS -> CenteredStatus(
                    title = stringResource(R.string.select_usb),
                    subtitle = stringResource(R.string.storage_permission_explanation),
                )

                AppMode.NO_CONTENT -> CenteredStatus(
                    title = stringResource(R.string.no_playable_content),
                    subtitle = stringResource(R.string.put_videos_in_directories),
                )

                AppMode.STORAGE_UNAVAILABLE -> StorageFailureStatus(state)

                AppMode.READY -> Unit
            }

            if (state.mode == AppMode.READY && state.channelOverlayVisible) {
                ChannelOverlay(state)
            }

            if (state.mode == AppMode.READY && state.channelListVisible) {
                ChannelList(state)
            }

            if (state.mainMenuVisible) {
                MainMenu(state)
            }

            if (state.resourceSettingsVisible) {
                ResourceManagement(state)
            }

            if (state.usbResourceActionsVisible) {
                UsbResourceActions(state)
            }

            if (state.smbResourceActionsVisible) {
                SmbResourceActions(state)
            }

            if (state.advancedSettingsVisible) {
                AdvancedSettings(state)
            }
        }
    }
}

@Composable
private fun ComposeRenderingCompatibility(enabled: Boolean) {
    // Some TV GPUs do not invalidate selection highlights reliably in hardware. This
    // Compose view is a sibling above PlayerView, so its software layer cannot black out video.
    val overlayView = LocalView.current
    DisposableEffect(overlayView, enabled) {
        overlayView.setLayerType(
            if (enabled) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE,
            null,
        )
        onDispose {
            overlayView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }
}

@Composable
private fun StorageFailureStatus(state: Tv2000UiState) {
    if (state.scanFailure == ScanFailure.USB_REMOVED) {
        CenteredStatus(
            title = stringResource(R.string.insert_original_or_another_usb),
            subtitle = null,
        )
        return
    }

    val title = when (state.scanFailure) {
        ScanFailure.SMB_AUTHENTICATION -> stringResource(R.string.smb_authentication_failed)
        ScanFailure.SMB_SHARE_NOT_FOUND -> stringResource(R.string.smb_share_not_found)
        ScanFailure.SMB_CONNECTION -> stringResource(R.string.smb_connection_failed)
        ScanFailure.SMB_PROTOCOL -> stringResource(R.string.smb_protocol_failed)
        ScanFailure.SMB_UNKNOWN -> stringResource(R.string.smb_unknown_failed)
        else -> stringResource(R.string.device_removed)
    }
    val help = when (state.scanFailure) {
        ScanFailure.SMB_AUTHENTICATION -> stringResource(R.string.smb_authentication_help)
        ScanFailure.SMB_SHARE_NOT_FOUND -> stringResource(R.string.smb_share_not_found_help)
        ScanFailure.SMB_CONNECTION -> stringResource(R.string.smb_connection_help)
        ScanFailure.SMB_PROTOCOL -> stringResource(R.string.smb_protocol_help)
        ScanFailure.SMB_UNKNOWN -> stringResource(R.string.smb_unknown_help)
        else -> stringResource(R.string.reinsert_usb)
    }
    val subtitle = state.scanDiagnostic
        ?.takeIf(String::isNotBlank)
        ?.let { diagnostic ->
            "$help\n${stringResource(R.string.diagnostic_detail, diagnostic)}"
        }
        ?: help
    CenteredStatus(title = title, subtitle = subtitle)
}

@Composable
private fun CenteredStatus(
    title: String,
    subtitle: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = Color(0xFFEDE7D5),
            fontSize = 36.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = subtitle,
                color = Color(0xFFB7B1A2),
                fontSize = 24.sp,
            )
        }
    }
}

@Composable
private fun ChannelOverlay(state: Tv2000UiState) {
    val channel = state.currentChannel ?: return
    val episode = channel.episodes.getOrNull(state.currentEpisodeIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 56.dp, bottom = 48.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xB3111418))
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .width(440.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.number.toString(),
                    color = Color(0xFFE85D3F),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(18.dp))
                Text(
                    text = channel.name,
                    color = Color(0xFFEDE7D5),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (state.message != null) {
                Text(
                    text = state.message,
                    color = Color(0xFFB7B1A2),
                    fontSize = 22.sp,
                )
            } else if (episode != null) {
                Text(
                    text = stringResource(
                        R.string.episode_progress,
                        state.currentEpisodeIndex + 1,
                        channel.episodes.size,
                        formatPlaybackTime(state.channelOverlayPositionMs),
                    ),
                    color = Color(0xFFEDE7D5),
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = episode.title,
                    color = Color(0xFFB7B1A2),
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun ChannelList(state: Tv2000UiState) {
    val listState = rememberLazyListState()
    val panelRepaintToken = rememberPanelRepaintToken(state.channelListSelection)
    val rows = state.channels.mapIndexed { index, channel ->
        ChannelListRow(
            channel = channel,
            selected = index == state.channelListSelection,
        )
    }
    LaunchedEffect(state.channelListSelection, state.channels.size) {
        val selectedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index == state.channelListSelection
        }
        val selectionFullyVisible = selectedItem?.let { item ->
            isLazyListItemFullyVisible(
                itemOffset = item.offset,
                itemSize = item.size,
                viewportStartOffset = listState.layoutInfo.viewportStartOffset,
                viewportEndOffset = listState.layoutInfo.viewportEndOffset,
            )
        } == true
        if (state.channelListSelection in state.channels.indices && !selectionFullyVisible) {
            listState.scrollToItem(state.channelListSelection)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(520.dp)
            .background(panelBackgroundFor(panelRepaintToken))
            .padding(horizontal = 32.dp, vertical = 36.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.channels),
                color = Color(0xFFEDE7D5),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(24.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = rows,
                    key = { row -> row.channel.id },
                ) { row ->
                    val channel = row.channel
                    val selected = row.selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) Color(0xFFEDE7D5) else Color.Transparent,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = channel.number.toString(),
                            color = if (selected) Color(0xFF111418) else Color(0xFFE85D3F),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        Text(
                            text = channel.name,
                            color = if (selected) Color(0xFF111418) else Color(0xFFEDE7D5),
                            fontSize = 24.sp,
                        )
                    }
                }
            }
            if (state.exitPromptVisible) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.press_back_again_to_exit),
                    color = Color(0xFF8F8B82),
                    fontSize = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun MainMenu(state: Tv2000UiState) {
    val items = buildList {
        if (state.usbResourceConfigured) {
            add(
                resourceLabel(
                    type = stringResource(R.string.usb_resource),
                    name = null,
                    active = state.activeResourceKind == ResourceKind.USB,
                    activeLabel = stringResource(R.string.active_resource),
                ),
            )
        }
        state.smbResources.forEach { resource ->
            add(
                resourceLabel(
                    type = "SMB",
                    name = resource.name,
                    active = state.activeResourceKind == ResourceKind.SMB &&
                        state.activeResourceId == resource.id,
                    activeLabel = stringResource(R.string.active_resource),
                ),
            )
        }
        add(stringResource(R.string.resource_management))
        add(stringResource(R.string.advanced_settings))
    }

    MenuPanel(
        title = stringResource(R.string.resources),
        items = items,
        selectedIndex = state.mainMenuSelection,
    )
}

private fun resourceLabel(
    type: String,
    name: String?,
    active: Boolean,
    activeLabel: String,
): String = buildString {
    append(type)
    if (!name.isNullOrBlank()) {
        append("  ·  ")
        append(name)
    }
    if (active) {
        append("  ·  ")
        append(activeLabel)
    }
}

@Composable
private fun ResourceManagement(state: Tv2000UiState) {
    val items = buildList {
        if (state.usbResourceConfigured) {
            add(
                resourceLabel(
                    type = stringResource(R.string.usb_resource),
                    name = null,
                    active = state.activeResourceKind == ResourceKind.USB,
                    activeLabel = stringResource(R.string.active_resource),
                ),
            )
        }
        add(stringResource(R.string.add_remote_resource))
        state.smbResources.forEach { resource ->
            add(
                resourceLabel(
                    type = "SMB",
                    name = resource.name,
                    active = state.activeResourceKind == ResourceKind.SMB &&
                        state.activeResourceId == resource.id,
                    activeLabel = stringResource(R.string.active_resource),
                ),
            )
        }
    }

    MenuPanel(
        title = stringResource(R.string.resource_management),
        items = items,
        selectedIndex = state.resourceSettingsSelection,
        note = stringResource(R.string.resource_management_note),
    )
}

@Composable
private fun UsbResourceActions(state: Tv2000UiState) {
    val directory = state.usbVideoDirectory.ifBlank {
        stringResource(R.string.usb_root_directory)
    }
    MenuPanel(
        title = stringResource(R.string.usb_resource),
        items = listOf(stringResource(R.string.edit_resource)),
        selectedIndex = 0,
        note = stringResource(R.string.usb_video_directory_note, directory),
    )
}

@Composable
private fun SmbResourceActions(state: Tv2000UiState) {
    val resource = state.smbResources.firstOrNull { summary ->
        summary.id == state.managedSmbResourceId
    } ?: return
    val isActive = state.activeResourceKind == ResourceKind.SMB &&
        state.activeResourceId == resource.id
    val items = listOf(
        stringResource(R.string.view_resource),
        stringResource(R.string.edit_resource),
        stringResource(
            if (isActive) R.string.delete_active_resource_disabled else R.string.delete_resource,
        ),
    )

    MenuPanel(
        title = resource.name,
        items = items,
        selectedIndex = state.smbResourceActionsSelection,
        note = stringResource(
            if (isActive) {
                R.string.active_resource_cannot_delete_note
            } else {
                R.string.resource_actions_note
            },
        ),
        disabledIndices = if (isActive) setOf(2) else emptySet(),
    )
}

@Composable
private fun AdvancedSettings(state: Tv2000UiState) {
    val items = listOf(
        stringResource(R.string.clear_index),
        stringResource(R.string.reset_current_channel_progress),
        stringResource(R.string.reset_all_channel_progress),
        stringResource(R.string.reset_app_data),
    )

    MenuPanel(
        title = stringResource(R.string.advanced_settings),
        items = items,
        selectedIndex = state.advancedSettingsSelection,
        note = stringResource(R.string.settings_local_data_note),
    )
}

@Composable
private fun MenuPanel(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    note: String? = null,
    disabledIndices: Set<Int> = emptySet(),
) {
    val listState = rememberLazyListState()
    val panelRepaintToken = rememberPanelRepaintToken(selectedIndex)
    val rows = items.mapIndexed { index, label ->
        MenuListRow(
            index = index,
            label = label,
            selected = index == selectedIndex,
            disabled = index in disabledIndices,
        )
    }
    LaunchedEffect(selectedIndex, items.size) {
        val selectedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index == selectedIndex
        }
        val selectionFullyVisible = selectedItem?.let { item ->
            isLazyListItemFullyVisible(
                itemOffset = item.offset,
                itemSize = item.size,
                viewportStartOffset = listState.layoutInfo.viewportStartOffset,
                viewportEndOffset = listState.layoutInfo.viewportEndOffset,
            )
        } == true
        if (selectedIndex in items.indices && !selectionFullyVisible) {
            listState.scrollToItem(selectedIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(680.dp)
                .fillMaxHeight(0.88f)
                .background(panelBackgroundFor(panelRepaintToken))
                .padding(horizontal = 40.dp, vertical = 36.dp),
        ) {
            Text(
                text = title,
                color = Color(0xFFEDE7D5),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(24.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = rows,
                    key = { row -> row.index },
                ) { row ->
                    val selected = row.selected
                    val disabled = row.disabled
                    Text(
                        text = row.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    selected && disabled -> Color(0xFF34373B)
                                    selected -> Color(0xFFEDE7D5)
                                    else -> Color.Transparent
                                },
                            )
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        color = when {
                            disabled -> Color(0xFF777A80)
                            selected -> Color(0xFF111418)
                            else -> Color(0xFFEDE7D5)
                        },
                        fontSize = 24.sp,
                        fontWeight = if (selected) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                    )
                }
            }
            if (note != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = note,
                    color = Color(0xFFB7B1A2),
                    fontSize = 19.sp,
                )
            }
        }
    }
}

private data class ChannelListRow(
    val channel: Channel,
    val selected: Boolean,
)

private data class MenuListRow(
    val index: Int,
    val label: String,
    val selected: Boolean,
    val disabled: Boolean,
)

internal fun isLazyListItemFullyVisible(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
): Boolean = itemOffset >= viewportStartOffset &&
    itemOffset + itemSize <= viewportEndOffset

@Composable
private fun rememberPanelRepaintToken(selection: Int): Int {
    var repaintToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(selection) {
        repaintToken = repaintToken xor 1
    }
    return repaintToken
}

private fun panelBackgroundFor(repaintToken: Int): Color = if (repaintToken == 0) {
    Color(0xF20B0D10)
} else {
    Color(0xF20B0D11)
}
