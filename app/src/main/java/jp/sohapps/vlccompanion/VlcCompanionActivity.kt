package jp.sohapps.vlccompanion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackContract
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackRequest
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackResult

class VlcCompanionActivity : ComponentActivity() {
    private val playbackController = VlcPlaybackController()
    private var playbackRequest: CompanionPlaybackRequest? = null
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = CompanionPlaybackContract.parsePlayRequest(intent)
        if (request == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        playbackRequest = request

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installBackHandler()

        val videoView = TextureView(this)
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            text = "再生準備中…"
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                videoView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        setContentView(root)
        hideSystemBars()

        playbackController.start(
            context = this,
            videoView = videoView,
            request = request,
            listener = object : VlcPlaybackController.Listener {
                override fun onPreparingChanged(preparing: Boolean) {
                    statusView.text = if (preparing) "再生位置を準備中…" else ""
                    statusView.visibility = if (preparing) View.VISIBLE else View.GONE
                }

                override fun onPlaybackStarted() {
                    statusView.text = ""
                    statusView.visibility = View.GONE
                }

                override fun onPlaybackEnded() {
                    finishWithPlaybackResult()
                }

                override fun onPlaybackError() {
                    statusView.text = "VLC再生でエラーが発生しました"
                    statusView.visibility = View.VISIBLE
                }
            }
        )
        updateActivityResult()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onPause() {
        updateActivityResult()
        super.onPause()
    }

    override fun onDestroy() {
        updateActivityResult()
        playbackController.release()
        super.onDestroy()
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishWithPlaybackResult()
                }
            }
        )
    }

    private fun finishWithPlaybackResult() {
        updateActivityResult()
        finish()
    }

    private fun updateActivityResult() {
        val request = playbackRequest ?: return
        val result = CompanionPlaybackResult(
            positionMs = playbackController.currentResultPositionMs(),
            durationMs = playbackController.currentDurationMs() ?: request.durationMs
        )
        setResult(
            Activity.RESULT_OK,
            CompanionPlaybackContract.createResultIntent(result)
        )
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
