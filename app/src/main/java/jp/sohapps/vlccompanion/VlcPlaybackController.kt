package jp.sohapps.vlccompanion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackRequest
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import kotlin.math.abs

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

    fun currentDurationMs(): Long? {
        return runCatching { mediaPlayer?.length }.getOrNull()
            ?.takeIf { it > 0L }
    }

    fun release() {
        handler.removeCallbacks(seekRetryRunnable)
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
                }
            }
        }
    }

    private fun attachVideoView(player: MediaPlayer, videoView: TextureView) {
        runCatching { player.vlcVout.detachViews() }
        runCatching { player.setAspectRatio(null) }
        runCatching { player.setScale(0.0f) }
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
                // Layout/aspect behavior will be moved from CloudVideoPlayer in the UI migration step.
            }
        })
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
        const val SEEK_RETRY_INTERVAL_MS = 250L
        const val SEEK_DEADLINE_MS = 15_000L
        const val SEEK_COMPLETE_TOLERANCE_MS = 1_500L
        const val INITIAL_RESUME_VOUT_TIMEOUT_MS = 500L
        const val FALLBACK_METADATA_MIN_PLAYBACK_MS = 1_250L
        const val FALLBACK_METADATA_MAX_WARM_UP_MS = 3_000L
        const val FALLBACK_BUFFERING_SETTLE_MS = 150L
        const val FALLBACK_RENDER_ADVANCE_MS = 250L
    }
}
