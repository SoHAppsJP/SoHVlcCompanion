package jp.sohapps.vlccompanion

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackRequest
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackResultAction
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
    onFinish: (CompanionPlaybackResultAction) -> Unit,
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
    onFinish: (CompanionPlaybackResultAction) -> Unit,
    onRotationLockChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings = remember { CompanionPlayerSettings(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val initialDvdNavigationMode = remember(request.displayName, request.mimeType) {
        isLikelyDvdNavigationRequest(request)
    }
    val dvdNavigationMode = remember(request.mediaUri, request.displayName, request.mimeType) {
        mutableStateOf(initialDvdNavigationMode)
    }
    val dvdUiMode = remember(request.mediaUri) {
        mutableStateOf(false)
    }
    val lastDvdTapUpTime = remember(request.mediaUri) {
        mutableLongStateOf(0L)
    }

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

    val controlsState = uiState.controlsState
    val playbackState = uiState.playbackState
    val displayState = uiState.displayState
    val colorState = uiState.colorState
    val statusState = uiState.statusState
    val gestureFeedback = uiState.gestureFeedbackState
    val zoomState = uiState.zoomState
    var videoView by remember { mutableStateOf<TextureView?>(null) }

    PlayerUiEffects(
        state = uiState,
        window = activity?.window,
        controlsAutoHideMs = settings.getControlsAutoHideMs()
    )

    BackHandler(enabled = !uiState.controlsState.visible) {
        onFinish(CompanionPlaybackResultAction.RETURN_TO_LIST)
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
                                PlaybackEndAction.STOP -> Unit
                                PlaybackEndAction.NEXT -> {
                                    if (request.canNavigateNext) {
                                        onFinish(CompanionPlaybackResultAction.NEXT)
                                    }
                                }
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
            controller.refreshDvdNavigationState()
            if (controller.hasDvdNavigation && !dvdNavigationMode.value) {
                dvdNavigationMode.value = true
                dvdUiMode.value = false
                controlsState.hide()
            }
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
                    val textureView = this
                    isClickable = true
                    isFocusable = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            if (width > 0 && height > 0) {
                                controller.updateVideoSurfaceSize(width, height)
                                videoView = textureView
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            if (width > 0 && height > 0) {
                                controller.updateVideoSurfaceSize(width, height)
                                if (videoView !== textureView) {
                                    videoView = textureView
                                }
                            }
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            if (videoView === textureView) {
                                videoView = null
                            }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
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
                view.setOnTouchListener { _, event ->
                    if (dvdNavigationMode.value && !dvdUiMode.value) {
                        val handledByVlc = controller.sendDvdNavigationTouch(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            val previousTapUpTime = lastDvdTapUpTime.longValue
                            lastDvdTapUpTime.longValue = event.eventTime
                            if (previousTapUpTime > 0L &&
                                event.eventTime - previousTapUpTime <= DVD_UI_DOUBLE_TAP_MS
                            ) {
                                dvdUiMode.value = true
                                controlsState.hide()
                                lastDvdTapUpTime.longValue = 0L
                                return@setOnTouchListener true
                            }
                        }
                        handledByVlc
                    } else {
                        false
                    }
                }
                if (view.isAvailable && view.width > 0 && view.height > 0) {
                    controller.updateVideoSurfaceSize(view.width, view.height)
                    if (videoView !== view) {
                        videoView = view
                    }
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
                onPreviousClick = {
                    if (request.canNavigatePrevious) {
                        onFinish(CompanionPlaybackResultAction.PREVIOUS)
                    }
                },
                onSeekToStartClick = controller::seekToStart,
                onSeekBackClick = { controller.seekBack(settings.getButtonSeekBackMs()) },
                onPlayPauseClick = controller::togglePlayPause,
                onSeekForwardClick = { controller.seekForward(settings.getButtonSeekForwardMs()) },
                onSeekToEndClick = controller::seekToEnd,
                onNextClick = {
                    if (request.canNavigateNext) {
                        onFinish(CompanionPlaybackResultAction.NEXT)
                    }
                }
            ),
            controlsModifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            gestureEnabled = !dvdNavigationMode.value || dvdUiMode.value,
            topActions = {
                PlayerPanelActionButton(
                    iconRes = R.drawable.ic_player_eject,
                    label = "Listへ",
                    modifier = Modifier.width(64.dp),
                    onClick = {
                        onFinish(CompanionPlaybackResultAction.RETURN_TO_LIST)
                    }
                )
                PlayerPanelActionButton(
                    iconRes = R.drawable.ic_player_close,
                    label = "閉じる",
                    modifier = Modifier.width(64.dp),
                    onClick = uiState::hideControlsAndFeedback
                )
            },
            supplementalContent = {
                if (dvdNavigationMode.value) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        PlayerPanelActionButton(
                            iconRes = R.drawable.ic_dvd_home,
                            label = "HOME",
                            modifier = Modifier.weight(1f)
                        ) {
                            dvdUiMode.value = false
                            controlsState.hide()
                            gestureFeedback.clear()
                            mainHandler.postDelayed({
                                controller.sendDiscHomeCommand(videoView)
                            }, DVD_COMMAND_DELAY_MS)
                        }
                        PlayerPanelActionButton(
                            iconRes = R.drawable.ic_dvd_menu,
                            label = "MENU",
                            modifier = Modifier.weight(1f)
                        ) {
                            dvdUiMode.value = false
                            controlsState.hide()
                            gestureFeedback.clear()
                            mainHandler.postDelayed({
                                controller.sendDiscMenuCommand(videoView)
                            }, DVD_COMMAND_DELAY_MS)
                        }
                        PlayerPanelActionButton(
                            iconRes = R.drawable.ic_dvd_back,
                            label = "BACK",
                            modifier = Modifier.weight(1f)
                        ) {
                            dvdUiMode.value = false
                            controlsState.hide()
                            gestureFeedback.clear()
                            mainHandler.postDelayed({
                                controller.sendDiscBackCommand(videoView)
                            }, DVD_COMMAND_DELAY_MS)
                        }
                        if (dvdUiMode.value) {
                            PlayerPanelActionButton(
                                iconRes = R.drawable.ic_dvd_eject,
                                value = "操作へ",
                                modifier = Modifier.weight(1f)
                            ) {
                                dvdUiMode.value = false
                                controlsState.hide()
                                gestureFeedback.clear()
                            }
                        }
                    }
                }
            }
        )
    }
}

private fun isLikelyDvdNavigationRequest(request: CompanionPlaybackRequest): Boolean {
    val name = request.displayName.lowercase()
    val mimeType = request.mimeType.lowercase()
    return name.endsWith(".iso") ||
        name.endsWith(".img") ||
        name.endsWith(".ifo") ||
        mimeType.contains("iso9660") ||
        mimeType.contains("dvd")
}

private const val DVD_UI_DOUBLE_TAP_MS = 360L
private const val DVD_COMMAND_DELAY_MS = 80L

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
