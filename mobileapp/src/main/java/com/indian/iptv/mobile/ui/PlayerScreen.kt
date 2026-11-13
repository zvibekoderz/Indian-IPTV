package com.indian.iptv.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.indian.iptv.mobile.data.Channel
import com.indian.iptv.mobile.data.Epg
import com.indian.iptv.mobile.data.PlaylistFetcher
import com.indian.iptv.mobile.data.Settings
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Full-screen player, MX Player-style:
 *  - tap the video to show/hide the control bars
 *  - three explicit scale buttons (Fit / Fill / Zoom); the active one is
 *    highlighted and the choice is remembered across channels and sessions
 *  - the detected video resolution (and SAR for anamorphic streams) is shown
 *    in the control bar, so broken stream metadata is immediately visible
 *  - MX-style swipe gestures: brightness (left half), volume (right half),
 *    horizontal seek for VOD; double-tap play/pause; tap toggles the bars
 *  - landscape + immersive while playing; system bars restored on exit
 */
@Composable
fun PlayerScreen(
    channel: Channel,
    settings: Settings,
    nowPlaying: Epg.NowNext?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    BackHandler { onClose() }

    // Landscape + immersive full screen while playing; restore on exit.
    DisposableEffect(activity) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Sledgehammer for OEMs that ignore the insets controller.
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                // Undo the brightness gesture's window-level override.
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Scale modes, cycled from the control bar.
    val scaleModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    val scaleLabels = listOf("Fit", "Fill", "Zoom")
    val scaleHints = listOf(
        "whole picture (bars on the sides)",
        "stretched to every edge (may look distorted)",
        "fills the screen (top & bottom cropped)",
    )
    var scaleIndex by remember { mutableIntStateOf(settings.scaleMode) }
    var pixelRatio by remember { mutableStateOf(1f) }

    // Selecting a scale mode persists it and briefly explains the trade-off;
    // nothing appears when playback merely starts.
    var modeHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scaleIndex) {
        settings.scaleMode = scaleIndex
    }
    LaunchedEffect(modeHint) {
        if (modeHint != null) {
            delay(1400)
            modeHint = null
        }
    }

    fun selectScale(index: Int) {
        if (index == scaleIndex) return
        scaleIndex = index
        settings.scaleMode = index
        modeHint = scaleLabels[index] + " — " + scaleHints[index]
    }

    // MX-style swipe gestures: left half = brightness, right half = volume,
    // horizontal = seek (only when the stream has a duration, i.e. VOD),
    // double-tap = play/pause. A pill in the center shows what's happening.
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    var gestureText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gestureText) {
        if (gestureText != null) {
            delay(1200)
            gestureText = null
        }
    }

    val player = remember(channel.url) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(
                buildMap {
                    put("User-Agent", channel.userAgent ?: PlaylistFetcher.DEFAULT_USER_AGENT)
                    channel.referrer?.let { put("Referer", it) }
                },
            )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 5_000)
            .build()

        ExoPlayer.Builder(context, DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoSizeText by remember { mutableStateOf("") }

    val listener = remember(channel.url) {
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) errorMessage = null
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                pixelRatio = videoSize.pixelWidthHeightRatio
                videoSizeText =
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        "${videoSize.width}×${videoSize.height}"
                    } else {
                        ""
                    }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.errorCodeName.removePrefix("ERROR_")
            }
        }
    }

    LaunchedEffect(channel.url) {
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(channel.url) {
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // MX-style controls: hidden while playback starts — the video is clean
    // until the user taps; they auto-hide a few seconds after any appearance.
    var controlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible, scaleIndex) {
        if (controlsVisible && errorMessage == null) {
            delay(3000)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // PlayerView has its own controller disabled, so taps reach these
            // handlers: tap toggles the control bars, double-tap play/pause.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = {
                        player.playWhenReady = !player.playWhenReady
                        gestureText = if (player.playWhenReady) "Play" else "Pause"
                    },
                )
            }
            .pointerInput(Unit) {
                var mode = 0 // 0 undecided · 1 brightness · 2 volume · 3 seek
                var brightness = 0f
                var volume = 0
                var seekPosition = 0L
                detectDragGestures(
                    onDragStart = { _ ->
                        mode = 0
                        brightness = activity?.window?.attributes?.screenBrightness
                            ?.takeIf { it >= 0f } ?: 0.5f
                        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        seekPosition = player.currentPosition
                    },
                    onDrag = { change, amount ->
                        if (mode == 0) {
                            // Decide once, from the first movement: horizontal
                            // seeks when the stream has a duration (VOD);
                            // otherwise vertical adjusts brightness (left) or
                            // volume (right), decided by where the finger is.
                            mode = if (abs(amount.x) > abs(amount.y)) {
                                if (player.duration > 0) 3
                                else if (change.position.x < size.width / 2f) 1 else 2
                            } else if (change.position.x < size.width / 2f) {
                                1
                            } else {
                                2
                            }
                        }
                        when (mode) {
                            1 -> {
                                val window = activity?.window
                                if (window != null) {
                                    // Swipe up to brighten, down to dim.
                                    brightness = (brightness - amount.y / size.height * 1.5f)
                                        .coerceIn(0.01f, 1f)
                                    window.attributes = window.attributes.apply {
                                        screenBrightness = brightness
                                    }
                                    gestureText = "Brightness ${(brightness * 100).roundToInt()}%"
                                }
                            }
                            2 -> {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                volume = (volume + (-amount.y / size.height * max * 1.5f).roundToInt())
                                    .coerceIn(0, max)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                                gestureText = "Volume $volume/$max"
                            }
                            3 -> {
                                val duration = player.duration
                                if (duration > 0) {
                                    seekPosition = (seekPosition + amount.x * duration / size.width)
                                        .roundToLong()
                                        .coerceIn(0L, duration - 1000)
                                    player.seekTo(seekPosition)
                                    gestureText =
                                        "${formatTime(seekPosition)} / ${formatTime(duration)}"
                                }
                            }
                        }
                        change.consume()
                    },
                )
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false // our own Compose controls instead
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { view ->
                view.resizeMode = scaleModes[scaleIndex]
            },
        )

        if (buffering && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFFEF6C00),
            )
        }

        // Transient explanation of the current scale mode.
        modeHint?.let { hint ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (controlsVisible) 72.dp else 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(hint, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Transient gesture feedback (brightness / volume / seek / play state).
        gestureText?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (controlsVisible) {
            // Top bar: channel name (+ current programme) + close.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Text(
                        channel.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    nowPlaying?.now?.let { now ->
                        Text(
                            "Now: $now",
                            color = Color(0xFFB3B3BC),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            // Bottom bar: explicit scale buttons (active highlighted),
            // resolution/SAR readout, and play/pause.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scaleLabels.forEachIndexed { index, label ->
                    if (index == scaleIndex) {
                        Button(
                            onClick = { selectScale(index) },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Text(label)
                        }
                    } else {
                        TextButton(onClick = { selectScale(index) }) {
                            Text(label, color = Color(0xFFB3B3BC))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = buildString {
                        if (videoSizeText.isNotEmpty()) append(videoSizeText)
                        if (pixelRatio != 1f) {
                            if (isNotEmpty()) append(" ")
                            append("SAR %.2f".format(pixelRatio))
                        }
                    },
                    color = Color(0xFFB3B3BC),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                TextButton(onClick = { player.playWhenReady = !player.playWhenReady }) {
                    Text(if (isPlaying) "Pause" else "Play", color = Color.White)
                }
            }
        }

        errorMessage?.let { message ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Can't play this stream",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3B3BC),
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

/** "1:23:45" / "12:34" style time for the seek gesture readout. */
private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.US, minutes, seconds)
    }
}
