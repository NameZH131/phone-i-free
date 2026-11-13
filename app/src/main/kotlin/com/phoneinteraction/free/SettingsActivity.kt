package com.phoneinteraction.free

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.phoneinteraction.free.data.AppLanguage
import com.phoneinteraction.free.data.FailurePolicySetting
import com.phoneinteraction.free.data.SettingsRepository
import com.phoneinteraction.free.data.ThemeMode
import com.phoneinteraction.free.data.toLocaleList
import com.phoneinteraction.free.ui.theme.PifTheme
import kotlinx.coroutines.launch

// Extends AppCompatActivity for in-app language switching support
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsRepository(applicationContext)
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val language by settings.language.collectAsState(initial = AppLanguage.SYSTEM)
            val failurePolicy by settings.failurePolicy.collectAsState(initial = FailurePolicySetting.SKIP)
            val actionTimeout by settings.actionTimeoutMs.collectAsState(initial = SettingsRepository.DEFAULT_TIMEOUT_MS)
            // Vision auto-sleep idle seconds (0 = never sleep)
            val visionIdleTimeout by settings.visionIdleTimeoutSeconds.collectAsState(
                initial = SettingsRepository.DEFAULT_VISION_IDLE_TIMEOUT_SECONDS
            )
            // Hand skeleton visualization on recent recognition card
            val handSkeleton by settings.handSkeletonVisible.collectAsState(initial = false)
            // Voice command visualization on recent recognition card
            val voiceVisual by settings.voiceVisualVisible.collectAsState(initial = false)
            PifTheme(themeMode = themeMode) {
                SettingsScreen(
                    themeMode = themeMode,
                    language = language,
                    failurePolicy = failurePolicy,
                    actionTimeoutMs = actionTimeout,
                    visionIdleTimeoutSeconds = visionIdleTimeout,
                    handSkeleton = handSkeleton,
                    voiceVisual = voiceVisual,
                    onTheme = { mode ->
                        lifecycleScope.launch { settings.setThemeMode(mode) }
                    },
                    onLanguage = { lang ->
                        // Persist first then switch (sequential); switch takes effect via Activity recreate
                        lifecycleScope.launch {
                            settings.setLanguage(lang)
                            AppCompatDelegate.setApplicationLocales(lang.toLocaleList())
                        }
                    },
                    onFailurePolicy = { p ->
                        lifecycleScope.launch { settings.setFailurePolicy(p) }
                    },
                    onActionTimeout = { ms ->
                        lifecycleScope.launch { settings.setActionTimeoutMs(ms) }
                    },
                    onVisionIdleTimeout = { seconds ->
                        lifecycleScope.launch { settings.setVisionIdleTimeoutSeconds(seconds) }
                    },
                    onHandSkeleton = { visible ->
                        lifecycleScope.launch { settings.setHandSkeletonVisible(visible) }
                    },
                    onVoiceVisual = { visible ->
                        lifecycleScope.launch { settings.setVoiceVisualVisible(visible) }
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
// Use FlowRow for auto-wrap to prevent chip overflow on large font / narrow screen
@OptIn(ExperimentalLayoutApi::class)
private fun SettingsScreen(
    themeMode: ThemeMode,
    language: AppLanguage,
    failurePolicy: FailurePolicySetting,
    actionTimeoutMs: Int,
    visionIdleTimeoutSeconds: Int,
    handSkeleton: Boolean,
    voiceVisual: Boolean,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onFailurePolicy: (FailurePolicySetting) -> Unit,
    onActionTimeout: (Int) -> Unit,
    onVisionIdleTimeout: (Int) -> Unit,
    onHandSkeleton: (Boolean) -> Unit,
    onVoiceVisual: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Safe area padding for gesture navigation + scrollable content
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button in top-left follows Android convention
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Theme three-state driven by Compose state for immediate effect
                FilterChip(
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { onTheme(ThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.theme_system)) }
                )
                FilterChip(
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onTheme(ThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.theme_light)) }
                )
                FilterChip(
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onTheme(ThemeMode.DARK) },
                    label = { Text(stringResource(R.string.theme_dark)) }
                )
            }
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = language == AppLanguage.SYSTEM,
                    onClick = { onLanguage(AppLanguage.SYSTEM) },
                    label = { Text(stringResource(R.string.lang_system)) }
                )
                FilterChip(
                    selected = language == AppLanguage.ZH_CN,
                    onClick = { onLanguage(AppLanguage.ZH_CN) },
                    label = { Text(stringResource(R.string.lang_zh)) }
                )
                FilterChip(
                    selected = language == AppLanguage.EN,
                    onClick = { onLanguage(AppLanguage.EN) },
                    label = { Text(stringResource(R.string.lang_en)) }
                )
            }

            // Configurable failure handling and action timeout
            Text(
                text = stringResource(R.string.settings_failure_policy),
                style = MaterialTheme.typography.titleMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = failurePolicy == FailurePolicySetting.SKIP,
                    onClick = { onFailurePolicy(FailurePolicySetting.SKIP) },
                    label = { Text(stringResource(R.string.failure_policy_skip)) }
                )
                FilterChip(
                    selected = failurePolicy == FailurePolicySetting.STOP,
                    onClick = { onFailurePolicy(FailurePolicySetting.STOP) },
                    label = { Text(stringResource(R.string.failure_policy_stop)) }
                )
                FilterChip(
                    selected = failurePolicy == FailurePolicySetting.RETRY,
                    onClick = { onFailurePolicy(FailurePolicySetting.RETRY) },
                    label = { Text(stringResource(R.string.failure_policy_retry)) }
                )
            }
            Text(
                text = stringResource(R.string.failure_policy_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var timeoutText by remember(actionTimeoutMs) {
                mutableStateOf(actionTimeoutMs.toString())
            }
            Text(
                text = stringResource(R.string.settings_action_timeout),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { input -> timeoutText = input.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    // Clamp to valid range and sync display to ensure show value = applied value
                    val parsed = timeoutText.toIntOrNull()
                    if (parsed == null) {
                        Toast.makeText(context, resources.getString(R.string.settings_timeout_invalid), Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val clamped = parsed.coerceIn(500, 120_000)
                    timeoutText = clamped.toString()
                    onActionTimeout(clamped)
                }) { Text(stringResource(R.string.settings_apply)) }
            }

            // Vision auto-sleep idle timeout: custom input first (floating label carries the
            // unit, same style as the overlay action-config inputs), preset chips below
            Text(
                text = stringResource(R.string.settings_vision_idle_timeout),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_vision_idle_timeout_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var visionIdleText by remember(visionIdleTimeoutSeconds) {
                mutableStateOf(visionIdleTimeoutSeconds.toString())
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = visionIdleText,
                    onValueChange = { input -> visionIdleText = input.filter { it.isDigit() }.take(4) },
                    // Floating label carries the unit, matching the overlay action-config inputs
                    label = { Text(stringResource(R.string.settings_vision_idle_input_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    // Clamp to valid range and sync display to ensure show value = applied value
                    val parsed = visionIdleText.toIntOrNull()
                    if (parsed == null) {
                        Toast.makeText(context, resources.getString(R.string.settings_vision_idle_invalid), Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val clamped = if (parsed <= 0) 0 else parsed.coerceIn(5, 3600)
                    visionIdleText = clamped.toString()
                    onVisionIdleTimeout(clamped)
                }) { Text(stringResource(R.string.settings_apply)) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                intArrayOf(15, 30, 60).forEach { seconds ->
                    FilterChip(
                        selected = visionIdleTimeoutSeconds == seconds,
                        onClick = { onVisionIdleTimeout(seconds) },
                        label = { Text(stringResource(R.string.settings_vision_idle_seconds, seconds)) }
                    )
                }
                FilterChip(
                    selected = visionIdleTimeoutSeconds == 0,
                    onClick = { onVisionIdleTimeout(0) },
                    label = { Text(stringResource(R.string.settings_vision_idle_never)) }
                )
            }

            // Toggle hand keypoint skeleton rendering on the recent recognition card
            Text(
                text = stringResource(R.string.settings_hand_skeleton),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_hand_skeleton_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = handSkeleton, onCheckedChange = onHandSkeleton)
            }

            // Voice command visualization: show listened words, highlight on hit ~1.5s
            Text(
                text = stringResource(R.string.settings_voice_visual),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_voice_visual_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = voiceVisual, onCheckedChange = onVoiceVisual)
            }

            // Execution log provides diagnostic read access
            TextButton(onClick = {
                context.startActivity(Intent(context, ExecutionLogActivity::class.java))
            }) {
                Text(stringResource(R.string.settings_execution_history))
            }
        }
    }
}
