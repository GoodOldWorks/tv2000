package com.tv2000.app.ui

import android.view.ViewGroup
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tv2000.app.R
import com.tv2000.app.playback.AppMode
import com.tv2000.app.playback.ResourceKind
import com.tv2000.app.playback.Tv2000UiState
import com.tv2000.app.scanner.ScanFailure
import java.util.Locale

@Composable
fun Tv2000App(
    state: Tv2000UiState,
    player: ExoPlayer,
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (state.mode == AppMode.READY) {
                PlayerSurface(player)
            }

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

            if (state.settingsMenuVisible) {
                SettingsMenu(state)
            }

            if (state.resourceMenuVisible) {
                ResourceMenu(state)
            }
        }
    }
}

@Composable
private fun StorageFailureStatus(state: Tv2000UiState) {
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
@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerSurface(player: ExoPlayer) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
        },
    )
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
                .background(Color(0xE6111418))
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message ?: buildString {
                    append(episode?.title.orEmpty())
                    if (episode != null) {
                        append("  ·  第 ")
                        append(state.currentEpisodeIndex + 1)
                        append("/")
                        append(channel.episodes.size)
                        append(" 集  ·  ")
                        append(formatPlaybackTime(state.channelOverlayPositionMs))
                    }
                },
                color = Color(0xFFB7B1A2),
                fontSize = 22.sp,
            )
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
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(520.dp)
            .background(Color(0xF20B0D10))
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
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = state.channels,
                    key = { _, channel -> channel.id },
                ) { index, channel ->
                    val selected = index == state.channelListSelection
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
        }
    }
}

@Composable
private fun SettingsMenu(state: Tv2000UiState) {
    val items = listOf(
        stringResource(R.string.select_resource),
        stringResource(R.string.clear_index),
        stringResource(R.string.reset_current_channel_progress),
        stringResource(R.string.reset_all_channel_progress),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(680.dp)
                .background(Color(0xF20B0D10))
                .padding(horizontal = 40.dp, vertical = 36.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                color = Color(0xFFEDE7D5),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(24.dp))
            items.forEachIndexed { index, label ->
                val selected = index == state.settingsMenuSelection
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Color(0xFFEDE7D5) else Color.Transparent,
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    color = if (selected) Color(0xFF111418) else Color(0xFFEDE7D5),
                    fontSize = 26.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (index != items.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_local_data_note),
                color = Color(0xFFB7B1A2),
                fontSize = 19.sp,
            )
        }
    }
}

@Composable
private fun ResourceMenu(state: Tv2000UiState) {
    val usbLabel = buildString {
        append(stringResource(R.string.usb_resource))
        if (state.activeResourceKind == ResourceKind.USB) {
            append("  ·  ")
            append(stringResource(R.string.active_resource))
        }
    }
    val smbName = state.smbResourceName
    val items = buildList {
        add(usbLabel)
        if (smbName != null) {
            add(
                buildString {
                    append("SMB  ·  ")
                    append(smbName)
                    if (state.activeResourceKind == ResourceKind.SMB) {
                        append("  ·  ")
                        append(stringResource(R.string.active_resource))
                    }
                },
            )
        }
        add(
            stringResource(
                if (smbName == null) R.string.add_smb_resource else R.string.edit_smb_resource,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .background(Color(0xF20B0D10))
                .padding(horizontal = 40.dp, vertical = 36.dp),
        ) {
            Text(
                text = stringResource(R.string.resources),
                color = Color(0xFFEDE7D5),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(24.dp))
            items.forEachIndexed { index, label ->
                val selected = index == state.resourceMenuSelection
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Color(0xFFEDE7D5) else Color.Transparent,
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    color = if (selected) Color(0xFF111418) else Color(0xFFEDE7D5),
                    fontSize = 24.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (index != items.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_local_data_note),
                color = Color(0xFFB7B1A2),
                fontSize = 19.sp,
            )
        }
    }
}
