package com.phoneinteraction.free

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.phoneinteraction.free.core.Gesture
import com.phoneinteraction.free.data.SettingsRepository
import com.phoneinteraction.free.data.ThemeMode
import com.phoneinteraction.free.service.AccessibilityBridge
import com.phoneinteraction.free.service.MainForegroundService
import com.phoneinteraction.free.service.ServiceState
import com.phoneinteraction.free.service.VoiceKeywords
import com.phoneinteraction.free.ui.theme.PifTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// AppCompatDelegate.setApplicationLocales only works in AppCompatActivity (silently ignored under
// ComponentActivity); Theme.PIF is an AppCompat-derived theme so it applies
class MainActivity : AppCompatActivity() {

    // Bumped in onResume so permission-dependent UI state re-reads after returning from system settings
    private val resumeTick = MutableStateFlow(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppServices.init(applicationContext)
        // Recover unfinished queue execution state at startup; runCatching keeps a DB failure from crashing app launch
        lifecycleScope.launch { runCatching { AppServices.queueScheduler.recoverFromDisk() } }
        val settings = AppServices.settings
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            // No features until the accessibility disclosure is agreed (Play accessibility API policy)
            val disclosureAgreed by settings.a11yDisclosureAgreed.collectAsStateWithLifecycle(initialValue = null)
            val scope = rememberCoroutineScope()
            PifTheme(themeMode = themeMode) {
                when (disclosureAgreed) {
                    // null = first DataStore frame not read yet; show blank to avoid flashing the disclosure page
                    null -> Unit
                    false -> AccessibilityDisclosure(
                        onAgree = { scope.launch { settings.setA11yDisclosureAgreed(true) } },
                        onDecline = { finish() }
                    )
                    true -> HomeScreen(resumeTick)
                }
            }
        }
    }
}

// A permanently denied permission never re-shows the system dialog; only the app settings page can re-enable it
private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )
}

// Privacy policy must be publicly reachable; the URL comes from the privacy_policy_url string resource
private fun openPrivacyPolicy(context: android.content.Context) {
    val url = context.getString(R.string.privacy_policy_url).trim()
    // Treat the example.com placeholder as unconfigured so users never open a dead 404 page
    if (!url.startsWith("http") || url.contains("example.com")) {
        Toast.makeText(context, R.string.privacy_policy_missing, Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, R.string.privacy_policy_missing, Toast.LENGTH_LONG).show()
    }
}

// Open the project homepage on GitHub. The URL lives in a string resource so the
// placeholder can be swapped for the real repository without touching code.
private fun openGithub(context: android.content.Context) {
    val url = context.getString(R.string.github_url).trim()
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

// Play accessibility API policy: require in-app prominent disclosure before any use; declining exits the app
@Composable
private fun AccessibilityDisclosure(onAgree: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.disclosure_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.disclosure_body),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { openPrivacyPolicy(context) }) {
                Text(stringResource(R.string.disclosure_policy_link))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.disclosure_agree))
            }
            TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.disclosure_decline))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(resumeTick: StateFlow<Int>) {
    val context = LocalContext.current
    // tick changes (onResume) trigger recomposition and re-read the permission states
    val tick by resumeTick.collectAsStateWithLifecycle()
    // Privacy note is presented as an in-app panel
    var showPrivacy by remember { mutableStateOf(false) }
    // Sideloaded apps get the accessibility toggle greyed out by restricted settings; explain the unlock steps first
    var showA11yHelp by remember { mutableStateOf(false) }
    // One-time first-run guide explaining the core concepts (actions, queue, rules, editor)
    val guideShown by AppServices.settings.guideShown.collectAsStateWithLifecycle(initialValue = null)
    var showGuide by remember { mutableStateOf(false) }
    val guideScope = rememberCoroutineScope()
    LaunchedEffect(guideShown) {
        if (guideShown == false) {
            showGuide = true
            guideScope.launch { AppServices.settings.setGuideShown(true) }
        }
    }
    // Lifecycle-aware collection pauses updates while the UI isn't visible, so per-frame states stop driving recomposition
    val serviceConnected by AccessibilityBridge.serviceConnected.collectAsStateWithLifecycle()
    val overlayVisible by ServiceState.overlayVisible.collectAsStateWithLifecycle()
    val visionRunning by ServiceState.visionRunning.collectAsStateWithLifecycle()
    val voiceRunning by ServiceState.voiceRunning.collectAsStateWithLifecycle()
    // Expose sleep state and the latest recognition so users can tell the feature is actually running
    val visionSleeping by ServiceState.visionSleeping.collectAsStateWithLifecycle()
    // Configured idle seconds for the asleep hint; same setting VisionPipeline consumes
    val visionIdleTimeout by AppServices.settings.visionIdleTimeoutSeconds.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_VISION_IDLE_TIMEOUT_SECONDS
    )
    val lastRecognition by ServiceState.lastRecognition.collectAsStateWithLifecycle()
    // Skeleton renders only when enabled; per-frame collection lives inside HandSkeletonView to avoid recomposing this card
    val handSkeletonVisible by AppServices.settings.handSkeletonVisible.collectAsStateWithLifecycle(initialValue = false)
    // Voice word list renders only when enabled; voice hits are stored separately so gesture hits don't overwrite them
    val voiceVisualVisible by AppServices.settings.voiceVisualVisible.collectAsStateWithLifecycle(initialValue = false)
    val lastVoiceHit by ServiceState.lastVoiceHit.collectAsStateWithLifecycle()
    // Word list is read from an assets file inside LaunchedEffect to avoid disk I/O during composition
    var voiceWords by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { voiceWords = VoiceKeywords.all(context) }
    // All permission reads key on tick so they refresh when returning from settings
    val overlayGranted = remember(tick) { Settings.canDrawOverlays(context) }
    val micGranted = remember(tick) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    // Tracks whether a request was already made: denied again on second tap = user selected "do not ask again"; guide to app settings
    var micRequested by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MainForegroundService.start(context, MainForegroundService.ACTION_VOICE_ON)
        } else {
            Toast.makeText(context, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }
    // Without camera permission, CameraX binding throws SecurityException
    val cameraGranted = remember(tick) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    var cameraRequested by remember { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            MainForegroundService.start(context, MainForegroundService.ACTION_VISION_ON)
        } else {
            Toast.makeText(context, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }
    // Without POST_NOTIFICATIONS on Android 13+ the foreground-service notification stays hidden
    val notificationGranted = remember(tick) {
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, the feature continues; the service shows its own hint */ }
    // After a permanent denial the system never re-prompts; the next tap falls back to app settings
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    // Request notification permission before starting any feature (the system prompts only once)
    fun ensureNotificationPermission() {
        if (!notificationGranted) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keep content clear of system bars under gesture navigation
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { openGithub(context) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = stringResource(R.string.home_github_desc),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_hint),
                style = MaterialTheme.typography.bodyMedium
            )

            // Setup checklist: required (overlay/accessibility) and optional (notification/mic/camera) items grouped
            val requiredReady = overlayGranted && serviceConnected
            val optionalReady = notificationGranted && micGranted && cameraGranted
            if (!requiredReady || !optionalReady) {
                HomeCard(title = stringResource(R.string.setup_title)) {
                    if (!requiredReady) {
                        Text(
                            text = stringResource(R.string.setup_group_required),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    SetupRow(
                        label = stringResource(R.string.setup_overlay),
                        done = overlayGranted,
                        // Button label reflects the actual action instead of a generic "go to settings"
                        actionLabel = stringResource(R.string.setup_go_grant)
                    ) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                    SetupRow(
                        label = stringResource(R.string.setup_a11y),
                        done = serviceConnected,
                        actionLabel = stringResource(R.string.setup_go_enable)
                    ) { showA11yHelp = true }
                    if (!optionalReady) {
                        Text(
                            text = stringResource(R.string.setup_group_optional),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    SetupRow(
                        label = stringResource(R.string.setup_notifications),
                        done = notificationGranted,
                        actionLabel = stringResource(R.string.setup_go_grant)
                    ) {
                        if (notificationRequested) {
                            openAppSettings(context)
                        } else {
                            notificationRequested = true
                            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    SetupRow(
                        label = stringResource(R.string.setup_mic),
                        done = micGranted,
                        actionLabel = stringResource(R.string.setup_go_grant)
                    ) {
                        if (micRequested) {
                            openAppSettings(context)
                        } else {
                            micRequested = true
                            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    SetupRow(
                        label = stringResource(R.string.setup_camera),
                        done = cameraGranted,
                        actionLabel = stringResource(R.string.setup_go_grant)
                    ) {
                        if (cameraRequested) {
                            openAppSettings(context)
                        } else {
                            cameraRequested = true
                            cameraLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    }
                }
            }

            // Gesture and voice produce no visible output; this record is the only feedback that they work
            HomeCard(title = stringResource(R.string.home_last_recognition)) {
                val rec = lastRecognition
                if (rec == null) {
                    Text(
                        text = stringResource(R.string.home_none_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val gestureLabel = if (rec.source == ServiceState.RecognitionSource.GESTURE) {
                        Gesture.fromId(rec.value)?.let { stringResource(it.labelRes) } ?: rec.value
                    } else {
                        // Wake-word engine uses underscores internally; restore spaces for display
                        rec.value.replace('_', ' ')
                    }
                    val sourceLabel = stringResource(
                        if (rec.source == ServiceState.RecognitionSource.GESTURE) {
                            R.string.rule_trigger_gesture
                        } else {
                            R.string.rule_trigger_voice
                        }
                    )
                    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    Text(
                        text = "$sourceLabel${stringResource(R.string.item_separator)}$gestureLabel",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = timeFormat.format(Date(rec.at)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Renders only when enabled; the empty-state hint is handled inside the component
                if (handSkeletonVisible) {
                    HandSkeletonView()
                }

                // Voice engine reports only the matched word (no partials), so feedback is word list + hit highlight
                if (voiceVisualVisible) {
                    val hit = lastVoiceHit
                    var highlighted by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(hit) {
                        val h = hit ?: return@LaunchedEffect
                        highlighted = h.value
                        delay(1_500L)
                        highlighted = null
                    }
                    if (!voiceRunning) {
                        Text(
                            text = stringResource(R.string.home_voice_words_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        voiceWords.forEach { word ->
                            val on = word == highlighted
                            // Animated color transition keeps the hit highlight smooth
                            val chipColor by animateColorAsState(
                                targetValue = if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                animationSpec = tween(durationMillis = 200),
                                label = "voiceChipColor"
                            )
                            val chipTextColor by animateColorAsState(
                                targetValue = if (on) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                animationSpec = tween(durationMillis = 200),
                                label = "voiceChipTextColor"
                            )
                            Surface(
                                color = chipColor,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    text = word.replace('_', ' '),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = chipTextColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Group features into cards for clearer information hierarchy
            HomeCard(title = stringResource(R.string.home_group_record)) {
                // One-line usage hint for each feature toggle
                Text(
                    text = stringResource(R.string.overlay_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeatureStatus(overlayVisible)
                    Button(onClick = {
                        if (overlayVisible) {
                            MainForegroundService.start(context, MainForegroundService.ACTION_OVERLAY_OFF)
                        } else if (Settings.canDrawOverlays(context)) {
                            ensureNotificationPermission()
                            MainForegroundService.start(context, MainForegroundService.ACTION_OVERLAY_ON)
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }) {
                        Text(stringResource(if (overlayVisible) R.string.overlay_off else R.string.overlay_on))
                    }
                }
            }

            HomeCard(title = stringResource(R.string.home_group_control)) {
                Text(
                    text = stringResource(R.string.vision_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeatureStatus(visionRunning)
                    Button(onClick = {
                        when {
                            visionRunning ->
                                MainForegroundService.start(context, MainForegroundService.ACTION_VISION_OFF)
                            !cameraGranted -> {
                                // Request camera permission first; after a permanent denial fall back to app settings
                                if (cameraRequested) {
                                    openAppSettings(context)
                                } else {
                                    cameraRequested = true
                                    cameraLauncher.launch(android.Manifest.permission.CAMERA)
                                }
                            }
                            else -> {
                                ensureNotificationPermission()
                                MainForegroundService.start(context, MainForegroundService.ACTION_VISION_ON)
                            }
                        }
                    }) {
                        Text(stringResource(if (visionRunning) R.string.vision_off else R.string.vision_on))
                    }
                }
                // Make the sleep state visible and offer a manual, non-voice wake-up button
                if (visionRunning && visionSleeping) {
                    Text(
                        text = stringResource(R.string.vision_sleeping_hint, visionIdleTimeout, VoiceKeywords.wakeWord().replace("_", " ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { AppServices.visionPipeline?.wakeUp() }) {
                        Text(stringResource(R.string.vision_wake_now))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.voice_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeatureStatus(voiceRunning)
                    Button(onClick = {
                        when {
                            voiceRunning ->
                                MainForegroundService.start(context, MainForegroundService.ACTION_VOICE_OFF)
                            micGranted -> {
                                ensureNotificationPermission()
                                MainForegroundService.start(context, MainForegroundService.ACTION_VOICE_ON)
                            }
                            else -> {
                                // After a permanent denial the system never re-prompts; fall back to app settings
                                if (micRequested) {
                                    openAppSettings(context)
                                } else {
                                    micRequested = true
                                    micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    }) {
                        Text(stringResource(if (voiceRunning) R.string.voice_off else R.string.voice_on))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Accessibility service status hint; drives a degraded hint while the service is off
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (serviceConnected) {
                            stringResource(R.string.a11y_connected)
                        } else {
                            stringResource(R.string.a11y_disconnected)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (!serviceConnected) {
                        Button(onClick = { showA11yHelp = true }) {
                            Text(stringResource(R.string.a11y_go_settings))
                        }
                    }
                }
            }

            HomeCard(title = stringResource(R.string.home_group_settings)) {
                Button(
                    onClick = { context.startActivity(Intent(context, ActionsActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.actions_title))
                }
                // Rules need a configuration entry point or recognition results never match any rule
                Button(
                    onClick = { context.startActivity(Intent(context, RulesActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.home_rules))
                }
                Button(
                    onClick = { context.startActivity(Intent(context, QueueActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.queue_title))
                }
                Button(
                    onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_title))
                }
                // Privacy note entry point
                TextButton(onClick = { showPrivacy = true }) {
                    Text(stringResource(R.string.settings_privacy))
                }
            }
        }
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.privacy_title)) },
            text = { Text(stringResource(R.string.privacy_body)) },
            // Privacy policy must be publicly reachable; provide an in-app link
            dismissButton = {
                TextButton(onClick = {
                    showPrivacy = false
                    openPrivacyPolicy(context)
                }) { Text(stringResource(R.string.disclosure_policy_link)) }
            },
            confirmButton = {
                // M3 convention: dialog confirm actions are TextButtons
                TextButton(onClick = { showPrivacy = false }) { Text(stringResource(R.string.dialog_ok)) }
            }
        )
    }

    // Sideloaded apps have toggles greyed out by restricted settings; show unlock steps and direct entries
    if (showA11yHelp) {
        AlertDialog(
            onDismissRequest = { showA11yHelp = false },
            title = { Text(stringResource(R.string.a11y_restricted_title)) },
            text = { Text(stringResource(R.string.a11y_restricted_body)) },
            dismissButton = {
                TextButton(onClick = {
                    showA11yHelp = false
                    openAppSettings(context)
                }) { Text(stringResource(R.string.a11y_restricted_open_app)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    showA11yHelp = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(stringResource(R.string.a11y_restricted_open_settings)) }
            }
        )
    }

    // One-time first-run guide: where actions come from and how they run
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(stringResource(R.string.guide_title)) },
            text = { Text(stringResource(R.string.guide_body)) },
            confirmButton = {
                TextButton(onClick = { showGuide = false }) { Text(stringResource(R.string.guide_ok)) }
            }
        )
    }
}

// Home cards (title + content) replace a bare column of buttons
// Standard MediaPipe 21-point hand connections, same order as the official HAND_CONNECTIONS
private val HAND_BONES = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,          // thumb
    0 to 5, 5 to 6, 6 to 7, 7 to 8,          // index
    5 to 9, 9 to 10, 10 to 11, 11 to 12,     // middle
    9 to 13, 13 to 14, 14 to 15, 15 to 16,   // ring
    13 to 17, 17 to 18, 18 to 19, 19 to 20,  // pinky
    0 to 17                                  // palm base
)

// Draws the latest hand landmarks as a centered skeleton; coordinates are normalized 0..1, scaled to fit a square canvas.
// Collection lives inside this composable (~10fps updates) so recomposition stays local and pauses while hidden.
@Composable
private fun HandSkeletonView() {
    val skeleton by ServiceState.handSkeleton.collectAsStateWithLifecycle()
    val boneColor = MaterialTheme.colorScheme.primary
    val jointColor = MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(160.dp)) {
                val pts = skeleton?.points
                if (pts == null || pts.size < 21) return@Canvas
                // Landmarks are normalized to the full frame; scale the hand's bounding box into the canvas (12% margin)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (p in pts) {
                    if (p.first < minX) minX = p.first
                    if (p.first > maxX) maxX = p.first
                    if (p.second < minY) minY = p.second
                    if (p.second > maxY) maxY = p.second
                }
                val spanX = (maxX - minX).coerceAtLeast(0.001f)
                val spanY = (maxY - minY).coerceAtLeast(0.001f)
                val scale = 0.88f * minOf(size.width / spanX, size.height / spanY)
                val offX = (size.width - spanX * scale) / 2f
                val offY = (size.height - spanY * scale) / 2f
                fun screen(i: Int) = Offset(
                    x = (pts[i].first - minX) * scale + offX,
                    y = (pts[i].second - minY) * scale + offY
                )
                for ((a, b) in HAND_BONES) {
                    drawLine(color = boneColor, start = screen(a), end = screen(b), strokeWidth = 5f, cap = StrokeCap.Round)
                }
                for (i in 0 until 21) {
                    drawCircle(color = jointColor, radius = 7f, center = screen(i))
                }
            }
        }
        if (skeleton == null) {
            Text(
                text = stringResource(R.string.home_skeleton_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

// One setup checklist row: entry button while pending, "ready" label once done
@Composable
private fun SetupRow(label: String, done: Boolean, actionLabel: String, action: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (done) {
            Text(
                text = stringResource(R.string.setup_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            TextButton(onClick = action) { Text(actionLabel) }
        }
    }
}

// On/off status text for feature toggles
@Composable
private fun FeatureStatus(on: Boolean) {
    Text(
        text = stringResource(if (on) R.string.feature_on_status else R.string.feature_off_status),
        style = MaterialTheme.typography.bodyMedium,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
