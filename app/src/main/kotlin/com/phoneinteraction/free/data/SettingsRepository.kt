package com.phoneinteraction.free.data

import android.content.Context
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Language has three modes (follow system / Chinese / English), switched via AppCompatDelegate.
enum class AppLanguage { SYSTEM, ZH_CN, EN }

// Failure policy: skip / stop / retry (retry count fixed at 3).
enum class FailurePolicySetting { SKIP, STOP, RETRY }

fun AppLanguage.toLocaleList(): LocaleListCompat = when (this) {
    AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
    AppLanguage.ZH_CN -> LocaleListCompat.forLanguageTags("zh-CN")
    AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
}

class SettingsRepository(context: Context) {

    private val dataStore = context.dataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[KEY_THEME]) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    val language: Flow<AppLanguage> = dataStore.data.map { prefs ->
        when (prefs[KEY_LANGUAGE]) {
            "zh-CN" -> AppLanguage.ZH_CN
            "en" -> AppLanguage.EN
            else -> AppLanguage.SYSTEM
        }
    }

    // Single action timeout (ms) and failure policy. Default: 5s / skip.
    val actionTimeoutMs: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_ACTION_TIMEOUT] ?: DEFAULT_TIMEOUT_MS
    }

    // 0 = never auto-sleep; otherwise seconds of gesture-idle before the pipeline sleeps.
    val visionIdleTimeoutSeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_VISION_IDLE] ?: DEFAULT_VISION_IDLE_TIMEOUT_SECONDS
    }

    val failurePolicy: Flow<FailurePolicySetting> = dataStore.data.map { prefs ->
        when (prefs[KEY_FAILURE_POLICY]) {
            "stop" -> FailurePolicySetting.STOP
            "retry" -> FailurePolicySetting.RETRY
            else -> FailurePolicySetting.SKIP
        }
    }

    // Play policy requires prominent disclosure + explicit consent before accessibility API use.
    // DataStore returns null for unset keys - cannot distinguish from "first frame not yet read".
    // Treat unset as false (not agreed) to avoid new installs getting stuck on null branch (blank page).
    // Consumers get a Boolean on the first emission, never null.
    val a11yDisclosureAgreed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_A11Y_DISCLOSURE_AGREED] ?: false
    }

    suspend fun setA11yDisclosureAgreed(agreed: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_A11Y_DISCLOSURE_AGREED] = agreed }
    }

    // Guide shown only once. Non-null type; "first frame" semantics provided by collectAsState(initial=null).
    val guideShown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_GUIDE_SHOWN] ?: false
    }

    suspend fun setGuideShown(shown: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_GUIDE_SHOWN] = shown }
    }

    // First-launch seed flag for default gesture/voice mappings (up/down queues). One-time on first seed.
    // After seed, deleted queues/rules are not auto-restored.
    val initialMapperSeeded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_INITIAL_MAPPER_SEEDED] ?: false
    }

    suspend fun setInitialMapperSeeded(seeded: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_INITIAL_MAPPER_SEEDED] = seeded }
    }

    // Hand skeleton visualization toggle. When enabled, shows detected hand keypoints (21-point skeleton)
    // centered below "last recognition" card. When disabled, no rendering/layout impact.
    val handSkeletonVisible: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HAND_SKELETON] ?: false
    }

    suspend fun setHandSkeletonVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_HAND_SKELETON] = visible }
    }

    // Voice recognition visualization toggle. Shows command words below "last recognition" card;
    // highlights matched word for ~1.5s when recognized. No rendering when disabled.
    val voiceVisualVisible: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VOICE_VISUAL] ?: false
    }

    suspend fun setVoiceVisualVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_VOICE_VISUAL] = visible }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME] = when (mode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
            }
        }
    }

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = when (language) {
                AppLanguage.SYSTEM -> "system"
                AppLanguage.ZH_CN -> "zh-CN"
                AppLanguage.EN -> "en"
            }
        }
    }

    suspend fun setActionTimeoutMs(ms: Int) {
        // Clamp to the allowed range to prevent 0 or extreme values.
        dataStore.edit { prefs -> prefs[KEY_ACTION_TIMEOUT] = ms.coerceIn(500, 120_000) }
    }

    suspend fun setVisionIdleTimeoutSeconds(seconds: Int) {
        // 0 disables auto-sleep; any other value clamps to 5..3600 seconds.
        val clamped = if (seconds <= 0) 0 else seconds.coerceIn(5, 3600)
        dataStore.edit { prefs -> prefs[KEY_VISION_IDLE] = clamped }
    }

    suspend fun setFailurePolicy(policy: FailurePolicySetting) {
        dataStore.edit { prefs ->
            prefs[KEY_FAILURE_POLICY] = when (policy) {
                FailurePolicySetting.SKIP -> "skip"
                FailurePolicySetting.STOP -> "stop"
                FailurePolicySetting.RETRY -> "retry"
            }
        }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("ui_theme")
        private val KEY_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_ACTION_TIMEOUT = intPreferencesKey("action_timeout_ms")
        private val KEY_VISION_IDLE = intPreferencesKey("vision_idle_timeout_seconds")
        private val KEY_FAILURE_POLICY = stringPreferencesKey("failure_policy")
        private val KEY_A11Y_DISCLOSURE_AGREED = booleanPreferencesKey("a11y_disclosure_agreed")
        private val KEY_GUIDE_SHOWN = booleanPreferencesKey("guide_shown")
        private val KEY_INITIAL_MAPPER_SEEDED = booleanPreferencesKey("initial_mapper_seeded")
        private val KEY_HAND_SKELETON = booleanPreferencesKey("hand_skeleton_visible")
        private val KEY_VOICE_VISUAL = booleanPreferencesKey("voice_visual_visible")
        const val DEFAULT_TIMEOUT_MS = 5_000
        const val DEFAULT_VISION_IDLE_TIMEOUT_SECONDS = 30
    }
}
