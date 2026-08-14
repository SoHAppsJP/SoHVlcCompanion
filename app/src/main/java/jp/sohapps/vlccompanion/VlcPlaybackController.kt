package jp.sohapps.vlccompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackRequest
import jp.sohapps.sohplayerkit.core.model.PlayerAspectMode
import jp.sohapps.sohplayerkit.core.seek.PLAYER_PSEUDO_FRAME_STEP_MS
import jp.sohapps.sohplayerkit.core.seek.playerPseudoFrameStepMs
import jp.sohapps.sohplayerkit.core.seek.scaledPlayerSeekMs
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class VlcPlaybackController {
    interface Listener {
        fun onPreparingChanged(preparing: Boolean)
        fun onPlaybackStarted()
        fun onPlaybackEnded()
        fun onPlaybackError()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var listener: Listener? = null
    private var request: CompanionPlaybackRequest? = null

    private var pendingSeekMs: Long? = null
    private var pendingSeekDeadlineMs = 0L
    private var hasRenderedVideo = false
    private var initialResumePreparing = false
    private var initialResumeSeekIssuedAtMs = 0L
    private var initialResumeTargetMs: Long? = null
    private var initialResumeFallbackActive = false
    private var fallbackWarmUpStartedAtMs = 0L
    private var fallbackSeekIssued = false
    private var fallbackBufferingCompletedAtMs = 0L

    private var playbackSpeed = 1.0f
    private var currentAspectMode = PlayerAspectMode.FIT
    private var customAspectWidth = 16.0f
    private var customAspectHeight = 9.0f
    private var videoSurfaceWidth = 0
    private var videoSurfaceHeight = 0
    private var videoDisplayWidth = 0
    private var videoDisplayHeight = 0
    private var frameRate = 0.0f

    private var doubleTapSeekBasePositionMs = 0L
    private var doubleTapSeekSingleStepMs = PLAYER_PSEUDO_FRAME_STEP_MS
    private var pseudoFrameDirection = 0
    private var pseudoFrameRequestedSteps = 0
    private var pseudoFrameProcessedSteps = 0
    private var pseudoFrameTargetPositionMs = 0L
    private var pseudoFrameStepScheduled = false

    var hasDvdNavigation: Boolean = false
        private set

    private val pseudoFrameStepRunnable = object : Runnable {
        override fun run() {
            pseudoFrameStepScheduled = false
            val player = mediaPlayer ?: return
            if (player.isPlaying || pseudoFrameProcessedSteps >= pseudoFrameRequestedSteps) {
                return
            }

            val length = runCatching { player.length }.getOrDefault(0L)
            val frameStepMs = currentPseudoFrameStepMs()
            val nextTarget = pseudoFrameTargetPositionMs +
                (frameStepMs * pseudoFrameDirection.toLong())
            pseudoFrameTargetPositionMs = if (length > 0L) {
                nextTarget.coerceIn(0L, length)
            } else {
                maxOf(0L, nextTarget)
            }
            pseudoFrameProcessedSteps += 1
            seekTo(pseudoFrameTargetPositionMs)

            if (pseudoFrameProcessedSteps < pseudoFrameRequestedSteps) {
                scheduleNextPseudoFrameStep()
            }
        }
    }

    private val seekRetryRunnable = object : Runnable {
        override fun run() {
            tickSeekRetry()
            if (pendingSeekMs != null) {
                handler.postDelayed(this, SEEK_RETRY_INTERVAL_MS)
            }
        }
    }

    fun start(
        context: Context,
        videoView: TextureView,
        request: CompanionPlaybackRequest,
        listener: Listener
    ) {
        release()
        this.request = request
        this.listener = listener
        frameRate = request.videoFps?.takeIf { it.isFinite() && it > 0f } ?: 0.0f
        videoDisplayWidth = request.videoWidth?.takeIf { it > 0 } ?: 0
        videoDisplayHeight = request.videoHeight?.takeIf { it > 0 } ?: 0
        hasDvdNavigation = false

        val newLibVlc = LibVLC(
            context.applicationContext,
            arrayListOf(
                "--no-video-title-show",
                "--network-caching=300",
                "--file-caching=300",
                "--live-caching=300",
                "--clock-jitter=0",
                "--clock-synchro=0",
                "--avcodec-hw=none",
                "--no-mediacodec-dr",
                "--no-omxil-dr",
                "--drop-late-frames",
                "--skip-frames"
            )
        )
        val player = MediaPlayer(newLibVlc)
        libVlc = newLibVlc
        mediaPlayer = player

        installPlayerEvents(player)
        attachVideoView(player, videoView)

        pendingSeekMs = request.resumePositionMs.takeIf { it > 0L }
        pendingSeekDeadlineMs = if (pendingSeekMs != null) {
            System.currentTimeMillis() + SEEK_DEADLINE_MS
        } else {
            0L
        }
        initialResumeTargetMs = pendingSeekMs
        initialResumePreparing = pendingSeekMs != null
        initialResumeSeekIssuedAtMs = 0L
        initialResumeFallbackActive = false
        fallbackWarmUpStartedAtMs = 0L
        fallbackSeekIssued = false
        fallbackBufferingCompletedAtMs = 0L
        hasRenderedVideo = false
        if (initialResumePreparing) {
            listener.onPreparingChanged(true)
        }

        val media = Media(newLibVlc, request.mediaUri).apply {
            setHWDecoderEnabled(false, false)
            addOption(":network-caching=300")
            addOption(":file-caching=300")
            addOption(":live-caching=300")
            addOption(":no-video-title-show")
            addOption(":avcodec-hw=none")
            addOption(":no-mediacodec-dr")
            addOption(":no-omxil-dr")
            addOption(":drop-late-frames")
            addOption(":skip-frames")
        }
        player.media = media
        media.release()
        player.play()
        runCatching { player.rate = playbackSpeed }
        applyAspectMode(player)

        handler.removeCallbacks(seekRetryRunnable)
        if (pendingSeekMs != null) {
            handler.postDelayed(seekRetryRunnable, SEEK_RETRY_INTERVAL_MS)
        }
    }

    fun currentResultPositionMs(): Long {
        return pendingSeekMs?.takeIf { it > 0L }
            ?: runCatching { mediaPlayer?.time }.getOrNull()?.coerceAtLeast(0L)
            ?: 0L
    }

    fun currentPositionMs(): Long {
        return runCatching { mediaPlayer?.time }.getOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    fun currentDurationMs(): Long? {
        return runCatching { mediaPlayer?.length }.getOrNull()
            ?.takeIf { it > 0L }
    }

    fun isPlaying(): Boolean {
        return runCatching { mediaPlayer?.isPlaying }.getOrDefault(false) ?: false
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekBack(ms: Long) {
        seekRelative(-ms)
    }

    fun seekForward(ms: Long) {
        seekRelative(ms)
    }

    fun seekDoubleTapBack(stepCount: Int = 1, baseSeekMs: Long = DEFAULT_DOUBLE_TAP_BACK_MS) {
        seekDoubleTapFromSequence(
            stepCount = stepCount,
            direction = -1,
            baseSeekMs = baseSeekMs
        )
    }

    fun seekDoubleTapForward(stepCount: Int = 1, baseSeekMs: Long = DEFAULT_DOUBLE_TAP_FORWARD_MS) {
        seekDoubleTapFromSequence(
            stepCount = stepCount,
            direction = 1,
            baseSeekMs = baseSeekMs
        )
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        val length = runCatching { player.length }.getOrDefault(0L)
        val target = if (length > 0L) {
            positionMs.coerceIn(0L, length)
        } else {
            maxOf(0L, positionMs)
        }

        clearInitialResumeState()
        pendingSeekMs = target
        pendingSeekDeadlineMs = System.currentTimeMillis() + MANUAL_SEEK_DEADLINE_MS
        issueSeek(player, target)
        ensureSeekRetryScheduled()
    }

    fun seekToStart() {
        seekTo(0L)
    }

    fun seekToEnd() {
        val length = currentDurationMs() ?: return
        seekTo(length)
    }

    fun restartFromStart() {
        val player = mediaPlayer ?: return
        cancelPseudoFrameSequence()
        clearPendingSeek()
        clearInitialResumeState()
        runCatching { player.time = 0L }
        val length = runCatching { player.length }.getOrDefault(0L)
        if (length > 0L) {
            runCatching { player.position = 0.0f }
        }
        runCatching { player.play() }
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.1f, 4.0f)
        runCatching { mediaPlayer?.rate = playbackSpeed }
    }

    fun setAspectMode(mode: PlayerAspectMode) {
        currentAspectMode = mode
        mediaPlayer?.let(::applyAspectMode)
    }

    fun setCustomAspect(width: Float, height: Float) {
        if (width > 0f) {
            customAspectWidth = width
        }
        if (height > 0f) {
            customAspectHeight = height
        }
        if (currentAspectMode == PlayerAspectMode.CUSTOM) {
            mediaPlayer?.let(::applyAspectMode)
        }
    }

    fun updateVideoSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        videoSurfaceWidth = width
        videoSurfaceHeight = height
        val player = mediaPlayer ?: return
        runCatching { player.vlcVout.setWindowSize(width, height) }
        applyAspectMode(player)
    }

    fun refreshDvdNavigationState() {
        val player = mediaPlayer ?: return
        val detected = runCatching {
            val titleDescriptions = player.javaClass.methods.firstOrNull { method ->
                method.name == "getTitleDescriptions" && method.parameterTypes.isEmpty()
            }?.invoke(player)
            val titleCount = when (titleDescriptions) {
                is Array<*> -> titleDescriptions.size
                is Collection<*> -> titleDescriptions.size
                null -> 0
                else -> 0
            }
            if (titleCount > 1) {
                true
            } else {
                val chapterMethod = player.javaClass.methods.firstOrNull { method ->
                    method.name == "getChapterDescriptions" && method.parameterTypes.size == 1
                }
                val maxTitlesToProbe = min(max(titleCount, 1), 8)
                (0 until maxTitlesToProbe).any { index ->
                    val chapters = chapterMethod?.invoke(player, index)
                    when (chapters) {
                        is Array<*> -> chapters.size > 1
                        is Collection<*> -> chapters.size > 1
                        else -> false
                    }
                }
            }
        }.getOrDefault(false)
        if (detected) {
            hasDvdNavigation = true
        }
    }

    fun sendDiscHomeCommand(videoView: TextureView?): Boolean {
        val byTitleZero = invokeMediaPlayerIntMethod("setTitle", 0)
        val byPopup = sendDiscPopupMenuCommand(videoView)
        return byTitleZero || byPopup
    }

    fun sendDiscMenuCommand(videoView: TextureView?): Boolean {
        val byPopup = sendDiscPopupMenuCommand(videoView)
        val byTitleZero = invokeMediaPlayerIntMethod("setTitle", 0)
        return byPopup || byTitleZero
    }

    fun sendDiscBackCommand(videoView: TextureView?): Boolean {
        val byViewBack = sendDiscKeyEventToView(videoView, KeyEvent.KEYCODE_BACK)
        val byVoutBack = sendDiscKeyEventToVout(KeyEvent.KEYCODE_BACK)
        if (byViewBack || byVoutBack) {
            return true
        }
        return sendDiscPopupMenuCommand(videoView)
    }

    fun sendDvdNavigationTouch(event: MotionEvent): Boolean {
        val player = mediaPlayer ?: return false
        val vout = runCatching { player.vlcVout }.getOrNull() ?: return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_UP -> 1
            MotionEvent.ACTION_MOVE -> 2
            else -> return false
        }
        val x = event.x.toInt()
        val y = event.y.toInt()
        val sent = runCatching {
            val method = vout.javaClass.methods.firstOrNull { method ->
                method.name == "sendMouseEvent" && method.parameterTypes.size == 4
            } ?: return@runCatching false
            method.invoke(vout, action, 0, x, y)
            true
        }.getOrDefault(false)
        if (sent) {
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            runCatching {
                val navigate = player.javaClass.methods.firstOrNull { method ->
                    method.name == "navigate" && method.parameterTypes.size == 1
                } ?: return@runCatching false
                navigate.invoke(player, VLC_NAVIGATE_ACTIVATE)
                true
            }.getOrDefault(false)
        }
        return true
    }

    fun release() {
        handler.removeCallbacks(seekRetryRunnable)
        cancelPseudoFrameSequence()
        listener = null
        clearPendingSeek()
        clearInitialResumeState(notify = false)

        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching { player.setEventListener(null) }
            runCatching { player.vlcVout.detachViews() }
            runCatching { player.stop() }
            runCatching { player.release() }
        }

        val lib = libVlc
        libVlc = null
        runCatching { lib?.release() }
        request = null
        hasRenderedVideo = false
        hasDvdNavigation = false
    }

    private fun sendDiscNavigationCommand(command: Int): Boolean {
        val player = mediaPlayer ?: return false
        return runCatching {
            player.navigate(command)
            true
        }.recoverCatching {
            val method = player.javaClass.methods.firstOrNull { method ->
                method.name == "navigate" && method.parameterTypes.size == 1
            } ?: throw it
            method.invoke(player, command)
            true
        }.getOrDefault(false)
    }

    private fun sendDiscKeyEventToView(view: TextureView?, keyCode: Int): Boolean {
        val target = view ?: return false
        return runCatching {
            target.requestFocus()
            val handledDown = target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            val handledUp = target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            handledDown || handledUp
        }.getOrDefault(false)
    }

    private fun sendDiscKeyEventToVout(keyCode: Int): Boolean {
        val player = mediaPlayer ?: return false
        val vout = runCatching { player.vlcVout }.getOrNull() ?: return false
        return runCatching {
            val method = vout.javaClass.methods.firstOrNull { method ->
                method.name == "sendKeyEvent" && method.parameterTypes.size == 2
            } ?: return@runCatching false
            method.invoke(vout, KeyEvent.ACTION_DOWN, keyCode)
            method.invoke(vout, KeyEvent.ACTION_UP, keyCode)
            true
        }.getOrDefault(false)
    }

    private fun invokeMediaPlayerIntMethod(methodName: String, value: Int): Boolean {
        val player = mediaPlayer ?: return false
        return runCatching {
            val method = player.javaClass.methods.firstOrNull { method ->
                method.name == methodName &&
                    method.parameterTypes.size == 1 &&
                    (method.parameterTypes[0] == Int::class.javaPrimitiveType ||
                        method.parameterTypes[0] == Integer.TYPE)
            } ?: return false
            method.invoke(player, value)
            true
        }.getOrDefault(false)
    }

    private fun sendDiscPopupMenuCommand(videoView: TextureView?): Boolean {
        val byNavigate = sendDiscNavigationCommand(VLC_NAVIGATE_POPUP_MENU)
        val byViewMenu = sendDiscKeyEventToView(videoView, KeyEvent.KEYCODE_MENU)
        val byVoutMenu = sendDiscKeyEventToVout(KeyEvent.KEYCODE_MENU)
        return byNavigate || byViewMenu || byVoutMenu
    }

    private fun seekDoubleTapFromSequence(
        stepCount: Int,
        direction: Int,
        baseSeekMs: Long
    ) {
        val player = mediaPlayer ?: return
        val safeStepCount = stepCount.coerceAtLeast(1)
        if (!player.isPlaying) {
            enqueuePseudoFrameSteps(safeStepCount, direction)
            return
        }

        cancelPseudoFrameSequence()
        if (safeStepCount == 1) {
            doubleTapSeekBasePositionMs = runCatching { player.time }.getOrDefault(0L)
            doubleTapSeekSingleStepMs = scaledPlayerSeekMs(
                baseSeekMs,
                playbackSpeed
            )
        }

        val totalDeltaMs = doubleTapSeekSingleStepMs * safeStepCount.toLong()
        val unclampedTarget = if (direction < 0) {
            doubleTapSeekBasePositionMs - totalDeltaMs
        } else {
            doubleTapSeekBasePositionMs + totalDeltaMs
        }
        val length = runCatching { player.length }.getOrDefault(0L)
        val target = if (length > 0L) {
            unclampedTarget.coerceIn(0L, length)
        } else {
            maxOf(0L, unclampedTarget)
        }
        seekTo(target)
    }

    private fun enqueuePseudoFrameSteps(stepCount: Int, direction: Int) {
        val player = mediaPlayer ?: return
        if (stepCount == 1 || pseudoFrameDirection != direction) {
            handler.removeCallbacks(pseudoFrameStepRunnable)
            pseudoFrameDirection = direction
            pseudoFrameRequestedSteps = 0
            pseudoFrameProcessedSteps = 0
            pseudoFrameTargetPositionMs = runCatching { player.time }.getOrDefault(0L)
            pseudoFrameStepScheduled = false
        }

        pseudoFrameRequestedSteps = maxOf(pseudoFrameRequestedSteps, stepCount)
        if (!pseudoFrameStepScheduled && pseudoFrameProcessedSteps < pseudoFrameRequestedSteps) {
            pseudoFrameStepScheduled = true
            handler.post(pseudoFrameStepRunnable)
        }
    }

    private fun scheduleNextPseudoFrameStep() {
        if (pseudoFrameStepScheduled) {
            return
        }
        pseudoFrameStepScheduled = true
        handler.postDelayed(pseudoFrameStepRunnable, PSEUDO_FRAME_DISPLAY_INTERVAL_MS)
    }

    private fun cancelPseudoFrameSequence() {
        handler.removeCallbacks(pseudoFrameStepRunnable)
        pseudoFrameDirection = 0
        pseudoFrameRequestedSteps = 0
        pseudoFrameProcessedSteps = 0
        pseudoFrameStepScheduled = false
    }

    private fun currentPseudoFrameStepMs(): Long {
        return playerPseudoFrameStepMs(frameRate = frameRate)
    }

    private fun seekRelative(deltaMs: Long) {
        val player = mediaPlayer ?: return
        val current = runCatching { player.time }.getOrDefault(0L)
        val length = runCatching { player.length }.getOrDefault(0L)
        val unclamped = current + deltaMs
        val target = if (length > 0L) {
            unclamped.coerceIn(0L, length)
        } else {
            maxOf(0L, unclamped)
        }
        seekTo(target)
    }

    private fun installPlayerEvents(player: MediaPlayer) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    if (initialResumeFallbackActive &&
                        fallbackSeekIssued &&
                        event.buffering >= 99.0f
                    ) {
                        fallbackBufferingCompletedAtMs = System.currentTimeMillis()
                    }
                }

                MediaPlayer.Event.Playing -> {
                    runCatching { player.rate = playbackSpeed }
                    applyAspectMode(player)
                    refreshDvdNavigationState()
                    if (!initialResumePreparing) {
                        listener?.onPlaybackStarted()
                    }
                    val target = initialResumeTargetMs
                    if (target != null &&
                        initialResumeSeekIssuedAtMs == 0L &&
                        !initialResumeFallbackActive
                    ) {
                        hasRenderedVideo = false
                        initialResumeSeekIssuedAtMs = System.currentTimeMillis()
                        issueSeek(player, target)
                    }
                    if (initialResumeFallbackActive && fallbackWarmUpStartedAtMs == 0L) {
                        fallbackWarmUpStartedAtMs = System.currentTimeMillis()
                    }
                }

                MediaPlayer.Event.EndReached -> {
                    clearPendingSeek()
                    clearInitialResumeState()
                    listener?.onPlaybackEnded()
                }

                MediaPlayer.Event.EncounteredError -> {
                    clearPendingSeek()
                    clearInitialResumeState()
                    listener?.onPlaybackError()
                }

                MediaPlayer.Event.Vout -> {
                    hasRenderedVideo = true
                    applyAspectMode(player)
                    refreshDvdNavigationState()
                }
            }
        }
    }

    private fun attachVideoView(player: MediaPlayer, videoView: TextureView) {
        runCatching { player.vlcVout.detachViews() }
        player.vlcVout.setVideoView(videoView)
        player.vlcVout.attachViews(object : IVLCVout.OnNewVideoLayoutListener {
            override fun onNewVideoLayout(
                vlcVout: IVLCVout,
                width: Int,
                height: Int,
                visibleWidth: Int,
                visibleHeight: Int,
                sarNum: Int,
                sarDen: Int
            ) {
                val resolvedWidth = visibleWidth.takeIf { it > 0 } ?: width
                val resolvedHeight = visibleHeight.takeIf { it > 0 } ?: height
                if (resolvedWidth > 0 && resolvedHeight > 0) {
                    videoDisplayWidth = resolvedWidth
                    videoDisplayHeight = resolvedHeight
                    applyAspectMode(player)
                }
            }
        })
        if (videoView.width > 0 && videoView.height > 0) {
            updateVideoSurfaceSize(videoView.width, videoView.height)
        } else if (videoSurfaceWidth > 0 && videoSurfaceHeight > 0) {
            runCatching {
                player.vlcVout.setWindowSize(videoSurfaceWidth, videoSurfaceHeight)
            }
            applyAspectMode(player)
        } else {
            applyAspectMode(player)
        }
    }

    private fun applyAspectMode(player: MediaPlayer) {
        when (currentAspectMode) {
            PlayerAspectMode.FIT -> {
                runCatching { player.setAspectRatio(null) }
                runCatching { player.setScale(0.0f) }
            }

            PlayerAspectMode.FILL -> {
                if (videoSurfaceWidth > 0 && videoSurfaceHeight > 0) {
                    runCatching {
                        player.setAspectRatio("$videoSurfaceWidth:$videoSurfaceHeight")
                    }
                }
                runCatching { player.setScale(0.0f) }
            }

            PlayerAspectMode.ZOOM -> {
                runCatching { player.setAspectRatio(null) }
                if (videoDisplayWidth > 0 && videoDisplayHeight > 0 &&
                    videoSurfaceWidth > 0 && videoSurfaceHeight > 0
                ) {
                    val scale = max(
                        videoSurfaceWidth.toFloat() / videoDisplayWidth.toFloat(),
                        videoSurfaceHeight.toFloat() / videoDisplayHeight.toFloat()
                    )
                    runCatching { player.setScale(scale) }
                } else {
                    runCatching { player.setScale(0.0f) }
                }
            }

            PlayerAspectMode.ORIGINAL_100 -> {
                runCatching { player.setAspectRatio(null) }
                runCatching { player.setScale(1.0f) }
            }

            PlayerAspectMode.RATIO_16_9 -> {
                runCatching { player.setAspectRatio("16:9") }
                runCatching { player.setScale(0.0f) }
            }

            PlayerAspectMode.RATIO_4_3 -> {
                runCatching { player.setAspectRatio("4:3") }
                runCatching { player.setScale(0.0f) }
            }

            PlayerAspectMode.CUSTOM -> {
                if (customAspectWidth > 0f && customAspectHeight > 0f) {
                    runCatching {
                        player.setAspectRatio(
                            "${customAspectWidth.toInt()}:${customAspectHeight.toInt()}"
                        )
                    }
                }
                runCatching { player.setScale(0.0f) }
            }
        }
    }

    private fun tickSeekRetry() {
        val player = mediaPlayer ?: return
        val target = pendingSeekMs ?: return
        val now = System.currentTimeMillis()
        val current = runCatching { player.time }.getOrDefault(0L)

        if (!initialResumeFallbackActive) {
            if (abs(current - target) <= SEEK_COMPLETE_TOLERANCE_MS && hasRenderedVideo) {
                clearPendingSeek()
                clearInitialResumeState()
                return
            }
            if (initialResumeSeekIssuedAtMs > 0L &&
                now - initialResumeSeekIssuedAtMs >= INITIAL_RESUME_VOUT_TIMEOUT_MS &&
                !hasRenderedVideo
            ) {
                beginInitialResumeFallback(player)
                return
            }
        } else {
            if (!fallbackSeekIssued) {
                val warmUpStarted = fallbackWarmUpStartedAtMs > 0L
                val warmUpElapsed = if (warmUpStarted) now - fallbackWarmUpStartedAtMs else 0L
                val playbackReady = current >= FALLBACK_METADATA_MIN_PLAYBACK_MS
                if (warmUpStarted &&
                    (playbackReady || warmUpElapsed >= FALLBACK_METADATA_MAX_WARM_UP_MS)
                ) {
                    hasRenderedVideo = false
                    fallbackSeekIssued = true
                    fallbackBufferingCompletedAtMs = 0L
                    issueSeek(player, target)
                }
                return
            }

            val bufferingCompleted = fallbackBufferingCompletedAtMs > 0L
            val bufferingSettled = bufferingCompleted &&
                now - fallbackBufferingCompletedAtMs >= FALLBACK_BUFFERING_SETTLE_MS
            val resumedPlaybackAdvanced = current >= target + FALLBACK_RENDER_ADVANCE_MS
            if (bufferingSettled && resumedPlaybackAdvanced) {
                clearPendingSeek()
                clearInitialResumeState()
                return
            }
        }

        if (now > pendingSeekDeadlineMs) {
            clearPendingSeek()
            clearInitialResumeState()
            return
        }

        if (!initialResumeFallbackActive && initialResumeSeekIssuedAtMs > 0L) {
            issueSeek(player, target)
        }
    }

    private fun issueSeek(player: MediaPlayer, targetMs: Long) {
        // Keep the existing CloudVideoPlayer behavior: both assignments are intentional.
        runCatching { player.time = targetMs }

        val length = runCatching { player.length }.getOrDefault(0L)
        if (length > 0L) {
            runCatching {
                player.position = (targetMs.toFloat() / length.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    private fun beginInitialResumeFallback(player: MediaPlayer) {
        initialResumeFallbackActive = true
        initialResumePreparing = true
        fallbackWarmUpStartedAtMs = 0L
        fallbackSeekIssued = false
        fallbackBufferingCompletedAtMs = 0L
        listener?.onPreparingChanged(true)
        hasRenderedVideo = false
        runCatching { player.stop() }
        runCatching { player.play() }
    }

    private fun ensureSeekRetryScheduled() {
        handler.removeCallbacks(seekRetryRunnable)
        if (pendingSeekMs != null) {
            handler.postDelayed(seekRetryRunnable, SEEK_RETRY_INTERVAL_MS)
        }
    }

    private fun clearPendingSeek() {
        pendingSeekMs = null
        pendingSeekDeadlineMs = 0L
        handler.removeCallbacks(seekRetryRunnable)
    }

    private fun clearInitialResumeState(notify: Boolean = true) {
        initialResumePreparing = false
        initialResumeSeekIssuedAtMs = 0L
        initialResumeTargetMs = null
        initialResumeFallbackActive = false
        fallbackWarmUpStartedAtMs = 0L
        fallbackSeekIssued = false
        fallbackBufferingCompletedAtMs = 0L
        if (notify) {
            listener?.onPreparingChanged(false)
        }
    }

    private companion object {
        const val VLC_NAVIGATE_ACTIVATE = 0
        const val VLC_NAVIGATE_POPUP_MENU = 5
        const val SEEK_RETRY_INTERVAL_MS = 250L
        const val SEEK_DEADLINE_MS = 15_000L
        const val MANUAL_SEEK_DEADLINE_MS = 4_000L
        const val SEEK_COMPLETE_TOLERANCE_MS = 1_500L
        const val INITIAL_RESUME_VOUT_TIMEOUT_MS = 500L
        const val FALLBACK_METADATA_MIN_PLAYBACK_MS = 1_250L
        const val FALLBACK_METADATA_MAX_WARM_UP_MS = 3_000L
        const val FALLBACK_BUFFERING_SETTLE_MS = 150L
        const val FALLBACK_RENDER_ADVANCE_MS = 250L
        const val PSEUDO_FRAME_DISPLAY_INTERVAL_MS = 120L
        const val DEFAULT_DOUBLE_TAP_BACK_MS = 5_000L
        const val DEFAULT_DOUBLE_TAP_FORWARD_MS = 10_000L
    }
}
