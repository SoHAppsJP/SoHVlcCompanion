package jp.sohapps.vlccompanion

import android.app.Activity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackRequest
import jp.sohapps.sohplayerkit.core.model.PlaybackEndAction
import jp.sohapps.sohplayerkit.core.model.PlaybackVideoInfo
import jp.sohapps.sohplayerkit.ui.controls.PlayerPanelActionButton
import jp.sohapps.sohplayerkit.ui.controls.PlayerSettingsIcons
import jp.sohapps.sohplayerkit.ui.controls.PlayerTransportIcons
import jp.sohapps.sohplayerkit.ui.gesture.playerZoomGraphicsLayer
import jp.sohapps.sohplayerkit.ui.playback.PLAYER_RESUME_PREPARING_STATUS_TEXT
import jp.sohapps.sohplayerkit.ui.playback.PlayerUiActions
import jp.sohapps.sohplayerkit.ui.playback.PlayerUiConfig
import jp.sohapps.sohplayerkit.ui.playback.PlayerUiEffects
import jp.sohapps.sohplayerkit.ui.playback.PlayerUiHost
import jp.sohapps.sohplayerkit.ui.playback.PlayerUiInitialValues
import jp.sohapps.sohplayerkit.ui.playback.PlayerUiStateBindings
import jp.sohapps.sohplayerkit.ui.playback.PlayerUiStateKeys
import jp.sohapps.sohplayerkit.ui.playback.PlayerSurfaceHost
import jp.sohapps.sohplayerkit.ui.playback.applyPlayerColorFilter
import jp.sohapps.sohplayerkit.ui.playback.rememberPlayerUiState
import kotlinx.coroutines.delay

@Composable
internal fun VlcCompanionPlayerScreen(
    request: CompanionPlaybackRequest,
    controller: VlcPlaybackController,
    onFinish: () -> Unit,
    onRotationLockChanged: (Boolean) -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        VlcCompanionPlayerContent(
            request = request,
            controller = controller,
            onFinish = onFinish,
            onRotationLockChanged = onRotationLockChanged
        )
    }
}

@Composable
private fun VlcCompanionPlayerContent(
    request: CompanionPlaybackRequest,
    controller: VlcPlaybackController,
    onFinish: () -> Unit,
    onRotationLockChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings = remember { CompanionPlayerSettings(context) }

    val uiState = rememberPlayerUiState(
        context = context,
        window = activity?.window,
        keys = PlayerUiStateKeys(
            playback = request.mediaUri,
            playbackSettings = controller,
            displaySettings = controller,
            colorSettings = settings,
            videoMetadata = request.mediaUri,
            seek = controller
        ),
        initialValues = PlayerUiInitialValues(
            playbackSpeed = settings.getPlaybackSpeed(),
            playbackEndAction = settings.getPlaybackEndAction(),
            aspectMode = settings.getAspectMode(),
            customAspectWidth = settings.getCustomAspectWidth(),
            customAspectHeight = settings.getCustomAspectHeight(),
            rotationLocked = settings.isRotationLocked(),
            avoidCutout = settings.isAvoidCutoutEnabled(),
            colorPreset = settings.getColorPreset(),
            colorValues = settings.getColorValues(),
            isPlaying = false,
            currentPositionMs = request.resumePositionMs,
            durationMs = request.durationMs ?: 0L,
            videoWidth = request.videoWidth ?: 0,
            videoHeight = request.videoHeight ?: 0,
            videoFrameRate = request.videoFps ?: 0.0f,
            playbackVideoInfo = PlaybackVideoInfo()
        ),
        bindings = PlayerUiStateBindings(
            seekAction = controller::seekTo,
            readColorValues = settings::getColorValues,
            onPlaybackSpeedChanged = { speed ->
                settings.setPlaybackSpeed(speed)
                controller.setPlaybackSpeed(speed)
            },
            onPlaybackEndActionChanged = settings::setPlaybackEndAction,
            onAspectModeChanged = { mode ->
                settings.setAspectMode(mode)
                controller.setAspectMode(mode)
            },
            onCustomAspectWidthChanged = { width ->
                settings.setCustomAspectWidth(width)
                controller.setCustomAspect(width, settings.getCustomAspectHeight())
            },
            onCustomAspectHeightChanged = { height ->
                settings.setCustomAspectHeight(height)
                controller.setCustomAspect(settings.getCustomAspectWidth(), height)
            },
            onRotationLockedChanged = { locked ->
                settings.setRotationLocked(locked)
                onRotationLockChanged(locked)
            },
            onAvoidCutoutChanged = settings::setAvoidCutoutEnabled,
            onColorPresetChanged = settings::setColorPreset,
            onColorBrightnessChanged = settings::setColorBrightness,
            onColorContrastChanged = settings::setColorContrast,
            onColorSaturationChanged = settings::setColorSaturation,
            onColorGammaChanged = settings::setColorGamma,
            onColorTemperatureChanged = settings::setColorTemperature
        )
    )

    val playbackState = uiState.playbackState
    val displayState = uiState.displayState
    val colorState = uiState.colorState
    val statusState = uiState.statusState
    val zoomState = uiState.zoomState
    var videoView by remember { mutableStateOf<TextureView?>(null) }

    PlayerUiEffects(
        state = uiState,
        window = activity?.window,
        controlsAutoHideMs = settings.getControlsAutoHideMs()
    )

    BackHandler(enabled = !uiState.controlsState.visible) {
        onFinish()
    }

    LaunchedEffect(displayState.rotationLocked) {
        onRotationLockChanged(displayState.rotationLocked)
    }

    DisposableEffect(videoView, request.mediaUri) {
        val view = videoView
        if (view != null) {
            controller.setPlaybackSpeed(settings.getPlaybackSpeed())
            controller.setCustomAspect(
                settings.getCustomAspectWidth(),
                settings.getCustomAspectHeight()
            )
            controller.setAspectMode(settings.getAspectMode())
            controller.start(
                context = context,
                videoView = view,
                request = request,
                listener = object : VlcPlaybackController.Listener {
                    override fun onPreparingChanged(preparing: Boolean) {
                        activity?.runOnUiThread {
                            if (preparing) {
                                statusState.update(PLAYER_RESUME_PREPARING_STATUS_TEXT)
                            } else if (statusState.isResumePreparing) {
                                statusState.clear()
                            }
                        }
                    }

                    override fun onPlaybackStarted() {
                        activity?.runOnUiThread {
                            statusState.clear()
                        }
                    }

                    override fun onPlaybackEnded() {
                        activity?.runOnUiThread {
                            when (uiState.playbackSettingsState.playbackEndAction) {
                                PlaybackEndAction.REPEAT -> controller.restartFromStart()
                                PlaybackEndAction.STOP,
                                PlaybackEndAction.NEXT -> onFinish()
                            }
                        }
                    }

                    override fun onPlaybackError() {
                        activity?.runOnUiThread {
                            statusState.update("VLC再生でエラーが発生しました")
                        }
                    }
                }
            )
        }

        onDispose { }
    }

    LaunchedEffect(controller, request.mediaUri) {
        while (true) {
            playbackState.update(
                isPlaying = controller.isPlaying(),
                currentPositionMs = controller.currentPositionMs(),
                durationMs = controller.currentDurationMs() ?: request.durationMs ?: 0L
            )
            delay(250L)
        }
    }

    PlayerSurfaceHost(
        state = uiState,
        window = activity?.window,
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                        val width = right - left
                        val height = bottom - top
                        if (width > 0 && height > 0) {
                            controller.updateVideoSurfaceSize(width, height)
                        } else if (view.width > 0 && view.height > 0) {
                            controller.updateVideoSurfaceSize(view.width, view.height)
                        }
                    }
                    applyPlayerColorFilter(
                        view = this,
                        brightness = colorState.brightness,
                        contrast = colorState.contrast,
                        saturation = colorState.saturation,
                        gamma = colorState.gamma,
                        temperature = colorState.temperature
                    )
                    videoView = this
                }
            },
            update = { view ->
                applyPlayerColorFilter(
                    view = view,
                    brightness = colorState.brightness,
                    contrast = colorState.contrast,
                    saturation = colorState.saturation,
                    gamma = colorState.gamma,
                    temperature = colorState.temperature
                )
                if (videoView !== view) {
                    videoView = view
                }
                if (view.width > 0 && view.height > 0) {
                    controller.updateVideoSurfaceSize(view.width, view.height)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .playerZoomGraphicsLayer(zoomState)
        )

        PlayerUiHost(
            state = uiState,
            config = PlayerUiConfig(
                seekBackMs = settings.getButtonSeekBackMs(),
                seekForwardMs = settings.getButtonSeekForwardMs(),
                transportIcons = VLC_COMPANION_TRANSPORT_ICONS,
                settingsIcons = VLC_COMPANION_SETTINGS_ICONS
            ),
            actions = PlayerUiActions(
                onDoubleTapLeft = { count ->
                    controller.seekDoubleTapBack(count, settings.getDoubleTapSeekBackMs())
                },
                onDoubleTapCenter = controller::togglePlayPause,
                onDoubleTapRight = { count ->
                    controller.seekDoubleTapForward(count, settings.getDoubleTapSeekForwardMs())
                },
                // Playlist navigation needs a companion-contract result command and is migrated next.
                onPreviousClick = {},
                onSeekToStartClick = controller::seekToStart,
                onSeekBackClick = { controller.seekBack(settings.getButtonSeekBackMs()) },
                onPlayPauseClick = controller::togglePlayPause,
                onSeekForwardClick = { controller.seekForward(settings.getButtonSeekForwardMs()) },
                onSeekToEndClick = controller::seekToEnd,
                onNextClick = {}
            ),
            controlsModifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            topActions = {
                PlayerPanelActionButton(
                    iconRes = R.drawable.ic_player_eject,
                    label = "Listへ",
                    modifier = Modifier.width(64.dp),
                    onClick = onFinish
                )
                PlayerPanelActionButton(
                    iconRes = R.drawable.ic_player_close,
                    label = "閉じる",
                    modifier = Modifier.width(64.dp),
                    onClick = uiState::hideControlsAndFeedback
                )
            }
        )
    }
}

private val VLC_COMPANION_TRANSPORT_ICONS = PlayerTransportIcons(
    previous = R.drawable.ic_player_skip_previous,
    seekToStart = R.drawable.ic_player_first,
    seekBack = R.drawable.ic_player_replay,
    play = R.drawable.ic_player_play,
    pause = R.drawable.ic_player_pause,
    seekForward = R.drawable.ic_player_forward,
    seekToEnd = R.drawable.ic_player_last,
    next = R.drawable.ic_player_skip_next
)

private val VLC_COMPANION_SETTINGS_ICONS = PlayerSettingsIcons(
    speed = R.drawable.ic_player_speed,
    repeat = R.drawable.ic_player_repeat,
    aspect = R.drawable.ic_player_aspect,
    info = R.drawable.ic_player_info,
    color = R.drawable.ic_player_color,
    rotation = R.drawable.ic_player_rotation,
    rotationLock = R.drawable.ic_player_rotation_lock,
    notch = R.drawable.ic_player_notch
)
