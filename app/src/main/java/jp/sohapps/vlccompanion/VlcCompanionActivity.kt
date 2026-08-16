package jp.sohapps.vlccompanion

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackContract
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackRequest
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackResult
import jp.sohapps.sohplayerkit.companion.contract.CompanionPlaybackResultAction

class VlcCompanionActivity : ComponentActivity() {
    private val playbackController = VlcPlaybackController()
    private var playbackRequest: CompanionPlaybackRequest? = null
    private var playerSettings: CompanionPlayerSettings? = null
    private var playbackResultAction = CompanionPlaybackResultAction.RETURN_TO_LIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = CompanionPlaybackContract.parsePlayRequest(intent)
        if (request == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        playbackRequest = request
        playerSettings = CompanionPlayerSettings(this).also { settings ->
            request.playerSettings?.let(settings::applySnapshot)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateSystemBarsForOrientation(resources.configuration)

        setContent {
            VlcCompanionPlayerScreen(
                request = request,
                controller = playbackController,
                onFinish = ::finishWithPlaybackResult,
                onRotationLockChanged = ::applyRotationLock
            )
        }
        updateActivityResult()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateSystemBarsForOrientation(resources.configuration)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBarsForOrientation(newConfig)
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

    private fun finishWithPlaybackResult(action: CompanionPlaybackResultAction) {
        playbackResultAction = action
        updateActivityResult()
        finish()
    }

    private fun updateActivityResult() {
        val request = playbackRequest ?: return
        val result = CompanionPlaybackResult(
            positionMs = playbackController.currentResultPositionMs(),
            durationMs = playbackController.currentDurationMs() ?: request.durationMs,
            action = playbackResultAction,
            playerSettings = playerSettings?.toSnapshot() ?: request.playerSettings
        )
        setResult(
            Activity.RESULT_OK,
            CompanionPlaybackContract.createResultIntent(result)
        )
    }

    private fun applyRotationLock(locked: Boolean) {
        requestedOrientation = if (locked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun updateSystemBarsForOrientation(configuration: Configuration) {
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            hideSystemBars()
        } else {
            showSystemBars()
        }
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

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
}
