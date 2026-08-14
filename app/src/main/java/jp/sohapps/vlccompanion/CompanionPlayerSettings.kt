package jp.sohapps.vlccompanion

import android.content.Context
import jp.sohapps.sohplayerkit.core.model.PlaybackEndAction
import jp.sohapps.sohplayerkit.core.model.PlayerAspectMode
import jp.sohapps.sohplayerkit.core.model.PlayerColorPreset
import jp.sohapps.sohplayerkit.ui.playback.PlayerColorValues

internal class CompanionPlayerSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getButtonSeekBackMs(): Long = DEFAULT_BUTTON_SEEK_BACK_MS

    fun getButtonSeekForwardMs(): Long = DEFAULT_BUTTON_SEEK_FORWARD_MS

    fun getDoubleTapSeekBackMs(): Long = DEFAULT_DOUBLE_TAP_SEEK_BACK_MS

    fun getDoubleTapSeekForwardMs(): Long = DEFAULT_DOUBLE_TAP_SEEK_FORWARD_MS

    fun getControlsAutoHideMs(): Long = DEFAULT_CONTROLS_AUTO_HIDE_MS

    fun getPlaybackSpeed(): Float = preferences.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED)

    fun setPlaybackSpeed(value: Float) {
        preferences.edit().putFloat(KEY_PLAYBACK_SPEED, value).apply()
    }

    fun getPlaybackEndAction(): PlaybackEndAction {
        return enumValueOrDefault(
            value = preferences.getString(KEY_PLAYBACK_END_ACTION, null),
            defaultValue = PlaybackEndAction.STOP
        )
    }

    fun setPlaybackEndAction(value: PlaybackEndAction) {
        preferences.edit().putString(KEY_PLAYBACK_END_ACTION, value.name).apply()
    }

    fun getAspectMode(): PlayerAspectMode {
        return enumValueOrDefault(
            value = preferences.getString(KEY_ASPECT_MODE, null),
            defaultValue = PlayerAspectMode.FIT
        )
    }

    fun setAspectMode(value: PlayerAspectMode) {
        preferences.edit().putString(KEY_ASPECT_MODE, value.name).apply()
    }

    fun getCustomAspectWidth(): Float = preferences.getFloat(KEY_CUSTOM_ASPECT_WIDTH, 16.0f)

    fun setCustomAspectWidth(value: Float) {
        preferences.edit().putFloat(KEY_CUSTOM_ASPECT_WIDTH, value).apply()
    }

    fun getCustomAspectHeight(): Float = preferences.getFloat(KEY_CUSTOM_ASPECT_HEIGHT, 9.0f)

    fun setCustomAspectHeight(value: Float) {
        preferences.edit().putFloat(KEY_CUSTOM_ASPECT_HEIGHT, value).apply()
    }

    fun isRotationLocked(): Boolean = preferences.getBoolean(KEY_ROTATION_LOCKED, false)

    fun setRotationLocked(value: Boolean) {
        preferences.edit().putBoolean(KEY_ROTATION_LOCKED, value).apply()
    }

    fun isAvoidCutoutEnabled(): Boolean = preferences.getBoolean(KEY_AVOID_CUTOUT, true)

    fun setAvoidCutoutEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_AVOID_CUTOUT, value).apply()
    }

    fun getColorPreset(): PlayerColorPreset {
        return enumValueOrDefault(
            value = preferences.getString(KEY_COLOR_PRESET, null),
            defaultValue = PlayerColorPreset.NORMAL
        )
    }

    fun setColorPreset(value: PlayerColorPreset) {
        preferences.edit().putString(KEY_COLOR_PRESET, value.name).apply()
        if (value != PlayerColorPreset.CUSTOM) {
            val defaults = colorPresetValues(value)
            preferences.edit()
                .putFloat(KEY_COLOR_BRIGHTNESS, defaults.brightness)
                .putFloat(KEY_COLOR_CONTRAST, defaults.contrast)
                .putFloat(KEY_COLOR_SATURATION, defaults.saturation)
                .putFloat(KEY_COLOR_GAMMA, defaults.gamma)
                .putFloat(KEY_COLOR_TEMPERATURE, defaults.temperature)
                .apply()
        }
    }

    fun getColorValues(): PlayerColorValues {
        return PlayerColorValues(
            brightness = preferences.getFloat(KEY_COLOR_BRIGHTNESS, 0.0f),
            contrast = preferences.getFloat(KEY_COLOR_CONTRAST, 1.0f),
            saturation = preferences.getFloat(KEY_COLOR_SATURATION, 1.0f),
            gamma = preferences.getFloat(KEY_COLOR_GAMMA, 1.0f),
            temperature = preferences.getFloat(KEY_COLOR_TEMPERATURE, 0.0f)
        )
    }

    fun setColorBrightness(value: Float) {
        preferences.edit().putFloat(KEY_COLOR_BRIGHTNESS, value).apply()
    }

    fun setColorContrast(value: Float) {
        preferences.edit().putFloat(KEY_COLOR_CONTRAST, value).apply()
    }

    fun setColorSaturation(value: Float) {
        preferences.edit().putFloat(KEY_COLOR_SATURATION, value).apply()
    }

    fun setColorGamma(value: Float) {
        preferences.edit().putFloat(KEY_COLOR_GAMMA, value).apply()
    }

    fun setColorTemperature(value: Float) {
        preferences.edit().putFloat(KEY_COLOR_TEMPERATURE, value).apply()
    }

    private fun colorPresetValues(preset: PlayerColorPreset): PlayerColorValues {
        return when (preset) {
            PlayerColorPreset.NORMAL -> PlayerColorValues(0.0f, 1.0f, 1.0f, 1.0f, 0.0f)
            PlayerColorPreset.BRIGHT -> PlayerColorValues(0.12f, 1.04f, 1.02f, 0.95f, 0.0f)
            PlayerColorPreset.VIVID -> PlayerColorValues(0.03f, 1.12f, 1.28f, 1.0f, 0.0f)
            PlayerColorPreset.CINEMA -> PlayerColorValues(-0.03f, 1.12f, 1.08f, 1.03f, -0.08f)
            PlayerColorPreset.NIGHT -> PlayerColorValues(-0.10f, 0.95f, 0.90f, 1.08f, -0.15f)
            PlayerColorPreset.SOFT -> PlayerColorValues(0.04f, 0.92f, 0.92f, 1.02f, 0.04f)
            PlayerColorPreset.CUSTOM -> getColorValues()
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        defaultValue: T
    ): T {
        return value?.let { stored ->
            enumValues<T>().firstOrNull { it.name == stored }
        } ?: defaultValue
    }

    private companion object {
        const val PREFERENCES_NAME = "vlc_companion_player_settings"

        const val KEY_PLAYBACK_SPEED = "playback_speed"
        const val KEY_PLAYBACK_END_ACTION = "playback_end_action"
        const val KEY_ASPECT_MODE = "aspect_mode"
        const val KEY_CUSTOM_ASPECT_WIDTH = "custom_aspect_width"
        const val KEY_CUSTOM_ASPECT_HEIGHT = "custom_aspect_height"
        const val KEY_ROTATION_LOCKED = "rotation_locked"
        const val KEY_AVOID_CUTOUT = "avoid_cutout"
        const val KEY_COLOR_PRESET = "color_preset"
        const val KEY_COLOR_BRIGHTNESS = "color_brightness"
        const val KEY_COLOR_CONTRAST = "color_contrast"
        const val KEY_COLOR_SATURATION = "color_saturation"
        const val KEY_COLOR_GAMMA = "color_gamma"
        const val KEY_COLOR_TEMPERATURE = "color_temperature"

        const val DEFAULT_BUTTON_SEEK_BACK_MS = 5_000L
        const val DEFAULT_BUTTON_SEEK_FORWARD_MS = 15_000L
        const val DEFAULT_DOUBLE_TAP_SEEK_BACK_MS = 5_000L
        const val DEFAULT_DOUBLE_TAP_SEEK_FORWARD_MS = 10_000L
        const val DEFAULT_CONTROLS_AUTO_HIDE_MS = 3_000L
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }
}
