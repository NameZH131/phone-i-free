package com.phoneinteraction.free.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.phoneinteraction.free.AppServices
import com.phoneinteraction.free.MainActivity
import com.phoneinteraction.free.R
import com.phoneinteraction.free.core.Gesture
import com.phoneinteraction.free.core.StartOutcome
import com.phoneinteraction.free.data.AppLanguage
import com.phoneinteraction.free.data.toLocaleList
import com.phoneinteraction.free.data.model.Event
import com.phoneinteraction.free.data.model.EventType
import com.phoneinteraction.free.data.model.ExecutionResult
import com.phoneinteraction.free.ui.failureText

class MainForegroundService : LifecycleService() {

    private var visionPipeline: VisionPipeline? = null
    private var overlayManager: OverlayManager? = null
    private var voicePipeline: VoicePipeline? = null

    // Only allow "close all to exit foreground" after truly entering foreground once
    // Avoids false idle detection when service is first created with all three states still false
    private var foregroundStarted = false

    // Current in-app language (mirrors the DataStore setting) for resolving user-visible
    // strings from the service context, which per-app locale switching never updates.
    @Volatile
    private var uiLanguage: AppLanguage = AppLanguage.SYSTEM

    override fun onCreate() {
        super.onCreate()
        AppServices.init(applicationContext)
        // Voice stop() is async; onStop callback that immediately reads voiceRunning gets stale true,
        // causing "all closed" misdetection -> notification and foreground service persist.
        // Subscribe to real state; any moment all are off removes notification and ends service (self-heal).
        lifecycleScope.launch {
            combine(
                ServiceState.overlayVisible,
                ServiceState.visionRunning,
                ServiceState.voiceRunning
            ) { overlay, vision, voice -> overlay || vision || voice }
                .collect { anyActive -> if (!anyActive && foregroundStarted) stopForegroundIfIdle() }
        }
        // Language switch requires rebuilding spotter with new keywords file.
        // drop(1) skips DataStore first-frame current value; use isChinese on the settings value directly
        // to parse target language, not relying on AppCompat locale overlay (avoids race where
        // DataStore is updated but locale hasn't yet).
        // Keep uiLanguage fresh for string resolution; DataStore emits the current value first.
        lifecycleScope.launch {
            AppServices.settings.language.collect { uiLanguage = it }
        }
        // React to language changes: rebuild voice keywords, overlay windows and the
        // foreground notification so everything service-hosted follows the new language.
        lifecycleScope.launch {
            AppServices.settings.language.drop(1).distinctUntilChanged().collect { language ->
                restartVoiceForLanguage(VoiceKeywords.isChinese(language))
                recreateOverlayForLanguage()
                if (foregroundStarted) startInForeground()
            }
        }
        overlayManager = OverlayManager(
            this,
            lifecycleScope,
            AppServices.db,
            AppServices.actionManager,
            AppServices.queueScheduler
        )
        visionPipeline = VisionPipeline(this, lifecycleScope)
        AppServices.visionPipeline = visionPipeline
        // User-configured vision sleep timeout (0 = never sleep); applyIdleTimeoutSeconds
        // also wakes the pipeline when the setting changes to 0 while asleep.
        lifecycleScope.launch {
            // distinctUntilChanged: an unrelated DataStore write re-emits the same value,
            // which would needlessly re-arm the idle timer and delay sleep.
            AppServices.settings.visionIdleTimeoutSeconds.distinctUntilChanged().collect { seconds ->
                visionPipeline?.applyIdleTimeoutSeconds(seconds)
            }
        }
        // Vision result -> rule engine -> action or queue
        visionPipeline?.setListener { gesture, _ ->
            lifecycleScope.launch {
                // Write recognition to shared state for home page "recent recognition" card
                ServiceState.setLastRecognition(ServiceState.RecognitionSource.GESTURE, gesture)
                // Map MediaPipe label to user-localized gesture name
                val label = gestureLabel(gesture)
                val rule = AppServices.ruleEngine.matchRule(Event(EventType.GESTURE, gesture))
                if (rule == null) {
                    // No matching rule for a recognized gesture, so tell the user instead of staying quiet
                    toast(l10n(R.string.rule_no_match, label))
                    return@launch
                }
                toast(l10n(R.string.rule_fired, label))
                runRule(rule)
            }
        }
        voicePipeline = VoicePipeline(this, lifecycleScope) { keyword ->
            lifecycleScope.launch {
                // Voice recognition also visible to user, otherwise "said something no response" is undiagnosable
                ServiceState.setLastRecognition(ServiceState.RecognitionSource.VOICE, keyword)
                // Wake word revives the vision pipeline (timeout sleep -> voice wake)
                // Wake word list same source as rule page options (VoiceKeywords), not hardcoded constant
                // Use engine's loaded language for wake word to avoid desync during language switch
                val engineChinese = voicePipeline?.isChinese ?: VoiceKeywords.isChinese()
                if (keyword == VoiceKeywords.wakeWord(engineChinese)) {
                    AppServices.visionPipeline?.wakeUp()
                    toast(l10n(R.string.voice_woke))
                    return@launch
                }
                // Voice command -> rule engine -> action or queue
                val rule = AppServices.ruleEngine.matchRule(Event(EventType.VOICE_COMMAND, keyword))
                if (rule == null) {
                    // Command matched but no rule - give feedback
                    toast(l10n(R.string.rule_no_match, keyword))
                    return@launch
                }
                toast(l10n(R.string.rule_fired, keyword))
                runRule(rule)
            }
        }

        // Voice start failure (model load / audio init) shows visible toast instead of silent log.
        // Reset after consumption to ensure retry on new failure (compareAndSet avoids clearing unset yet).
        lifecycleScope.launch {
            voicePipeline?.startErrors?.collect { error ->
                if (error != null && voicePipeline?.consumeStartError(error) == true) {
                    toast(l10n(R.string.voice_start_failed))
                }
            }
        }
    }

    // Unified rule execution entry. Every case informs the user, including when the target
    // action or queue was deleted.
    private suspend fun runRule(rule: com.phoneinteraction.free.data.model.RuleEntity) {
        val queueId = rule.actionQueueId
        if (queueId != null) {
            when (AppServices.queueScheduler.startQueue(queueId)) {
                // Accessibility not connected: scheduler rejects start, must inform user.
                StartOutcome.ENVIRONMENT_NOT_READY -> toast(l10n(R.string.rule_a11y_missing))
                StartOutcome.ALREADY_RUNNING -> toast(l10n(R.string.rule_queue_busy))
                StartOutcome.STARTED -> Unit
            }
        } else {
            val actionId = rule.actionId ?: return
            val action = AppServices.actionManager.getAction(actionId)
            if (action == null) {
                toast(l10n(R.string.rule_target_missing))
                return
            }
            // Solo action execution needs a timeout; without it the call can hang forever.
            // The "solo action timeout" setting applies here.
            // Report failures to the user for rule-triggered actions too.
            val outcome = AppServices.executor.executeAction(action, timeoutMs = AppServices.queueScheduler.actionTimeoutMs)
            if (outcome.record.result != ExecutionResult.SUCCESS) {
                toast(l10n(R.string.action_executed_fail, failureText(localeContext().resources, outcome.failure)))
            }
        }
    }

    // Context override for user-visible strings: per-app locale switching (AppCompat)
    // recreates activities only, so the service context resolves resources in the system
    // locale on API 31/32. Wrap it explicitly with the in-app language setting.
    private fun localeContext(): Context {
        if (uiLanguage == AppLanguage.SYSTEM) return this
        val conf = Configuration(resources.configuration)
        conf.setLocales(android.os.LocaleList.forLanguageTags(uiLanguage.toLocaleList().toLanguageTags()))
        return createConfigurationContext(conf)
    }

    // Resolve a string resource against the in-app language (see localeContext).
    private fun l10n(resId: Int, vararg args: Any?): String = localeContext().getString(resId, *args)

    // Map MediaPipe internal labels to localized gesture names; fall back to original if no mapping.
    private fun gestureLabel(id: String): String =
        Gesture.fromId(id)?.let { l10n(it.labelRes) } ?: id

    // Only restart when target language differs from current expectation to avoid unnecessary rebuilds.
    // Compare targetIsChinese (not loaded) to prevent losing last switch in rapid consecutive switches.
    private fun restartVoiceForLanguage(chinese: Boolean) {
        val pipeline = voicePipeline ?: return
        if (ServiceState.voiceRunning.value && pipeline.targetIsChinese != chinese) {
            pipeline.restart(chinese)
        }
    }

    // Overlay windows host Compose content built from the service context, which per-app locale
    // switching never updates (API 31/32 AppCompat wraps activities only; API 33+ leaves existing
    // WindowManager compositions untouched). Recreate them so panel/capsule text follows the language.
    private suspend fun recreateOverlayForLanguage() {
        val manager = overlayManager ?: return
        if (!manager.isShowing()) return
        // Preserve the edit target and expanded state across the rebuild; selection reset is
        // acceptable for a rare, user-initiated language switch.
        val editingAction = OverlayState.targetActionId.value.takeIf { it >= 0L }
        val expanded = OverlayState.panelExpanded.value
        // show() rethrows on failure (addView / DB errors); a language switch must never crash
        // the app - degrade to "overlay closed" the same way the intent-path failure does.
        runCatching {
            manager.hide()
            if (editingAction != null) OverlayState.pendingEditActionId = editingAction
            manager.show()
            if (expanded) OverlayState.setPanelExpanded(true)
        }.onFailure {
            Toast.makeText(this, l10n(R.string.service_start_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // Language changes in system settings trigger keyword file switch.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        restartVoiceForLanguage(VoiceKeywords.isChinese())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // Execution scope is process-level; service destroy doesn't stop in-flight queue.
        AppServices.queueScheduler.stopNow()
        overlayManager?.hide()
        overlayManager = null
        voicePipeline?.stop()
        voicePipeline = null
        ServiceState.setVoiceRunning(false)
        // Synchronous camera stop and state write to avoid home page still showing "on" after service destroyed.
        visionPipeline?.stopCameraNow()
        AppServices.visionPipeline = null
        visionPipeline = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        // Off actions should not bring service back to foreground.
        val isOff = action != null && action in OFF_ACTIONS
        if (!isOff) startInForeground()
        when (action) {
            // Start failures (no camera permission, model load failure, addView failure etc) unified fallback with visible feedback.
            ACTION_VISION_ON -> launchSafely {
                visionPipeline?.startCamera(this@MainForegroundService)
            }
            ACTION_VISION_OFF -> launchSafely {
                visionPipeline?.stopCamera()
                stopForegroundIfIdle()
            }
            // Overlay window (two-state), lifecycle managed by the foreground service.
            ACTION_OVERLAY_ON -> launchSafely {
                // Edit target from intent extras (queue or action) written to OverlayState for show to consume.
                val queueId = intent.getLongExtra(EXTRA_QUEUE_ID, -1L)
                val actionId = intent.getLongExtra(EXTRA_ACTION_ID, -1L)
                if (queueId > 0L) OverlayState.pendingEditQueueId = queueId
                if (actionId > 0L) OverlayState.pendingEditActionId = actionId
                overlayManager?.show()
            }
            ACTION_OVERLAY_OFF -> {
                overlayManager?.hide()
                stopForegroundIfIdle()
            }
            // Voice control: KeywordSpotter offline decoding, mic always on.
            ACTION_VOICE_ON -> launchSafely { voicePipeline?.start(VoiceKeywords.isChinese()) }
            ACTION_VOICE_OFF -> {
                voicePipeline?.stop()
                stopForegroundIfIdle()
            }
            // Notification "Stop" button: fallback stop not dependent on touch detection.
            ACTION_STOP_ALL -> stopEverything()
            // Only stop current queue run: this is the notification-specific stop, an alternative to tapping the screen.
            ACTION_STOP_QUEUE -> launchSafely { AppServices.queueScheduler.stopQueue() }
        }
        // Non-sticky: shouldn't be resurrected with notification-only when all features closed.
        return START_NOT_STICKY
    }

    private fun launchSafely(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainForegroundService,
                    l10n(R.string.service_start_failed),
                    Toast.LENGTH_SHORT
                ).show()
                // ON intent already called startInForeground (notification attached).
                // On failure no feature state becomes true -> self-heal never receives event,
                // notification permanently lingers. Retry on failure.
                stopForegroundIfIdle()
            }
        }
    }

    // Remove foreground notification and stop service when all three features off.
    private fun stopForegroundIfIdle() {
        val anyActive = ServiceState.overlayVisible.value ||
            ServiceState.visionRunning.value ||
            ServiceState.voiceRunning.value
        if (!anyActive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // One-click stop all features and running queue.
    private fun stopEverything() {
        AppServices.queueScheduler.stopNow()
        overlayManager?.hide()
        visionPipeline?.stopCameraNow()
        voicePipeline?.stop()
        ServiceState.setVoiceRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        // Mark "entered foreground" for self-heal idle detection via state subscription.
        foregroundStarted = true
        createChannel(localeContext())
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(l10n(R.string.notification_title))
            // Add explanation to avoid confusing users with unexplained foreground notification.
            .setContentText(l10n(R.string.notification_text))
            // Monochrome paw icon avoids colorful icon showing as white square in notification bar.
            .setSmallIcon(R.drawable.ic_paw)
            .setContentIntent(openAppIntent())
            // Tap notification to open app; stop-run is queue-only, stop-all is master switch.
            .addAction(0, l10n(R.string.notification_stop_run), stopRunIntent())
            .addAction(0, l10n(R.string.notification_stop), stopAllIntent())
            .setOngoing(true)
            .build()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists on API 34+; unknown bits are rejected on API 31-33.
        val types = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            // camera|microphone|specialUse combined types; must start while the app is in the foreground.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (e: SecurityException) {
            // Background relaunch rejects camera/mic due to the while-in-use limit.
            // Downgrade to specialUse-only ("accessibility + queue execution" mode).
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e2: Exception) {
                // API 31-33 without specialUse bit or extremely restricted - final fallback to 2-param startForeground.
                runCatching { startForeground(NOTIFICATION_ID, notification) }
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun stopAllIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, MainForegroundService::class.java).apply { action = ACTION_STOP_ALL },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // PendingIntents: requestCode 2 distinguishes this queue-only stop from stopAll (requestCode 0).
    private fun stopRunIntent(): PendingIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, MainForegroundService::class.java).apply { action = ACTION_STOP_QUEUE },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL_ID = "phone_interaction_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_VISION_ON = "com.phoneinteraction.free.action.VISION_ON"
        const val ACTION_VISION_OFF = "com.phoneinteraction.free.action.VISION_OFF"
        const val ACTION_OVERLAY_ON = "com.phoneinteraction.free.action.OVERLAY_ON"
        const val ACTION_OVERLAY_OFF = "com.phoneinteraction.free.action.OVERLAY_OFF"
        const val ACTION_VOICE_ON = "com.phoneinteraction.free.action.VOICE_ON"
        const val ACTION_VOICE_OFF = "com.phoneinteraction.free.action.VOICE_OFF"
        const val ACTION_STOP_ALL = "com.phoneinteraction.free.action.STOP_ALL"
        const val ACTION_STOP_QUEUE = "com.phoneinteraction.free.action.STOP_QUEUE"
        // Edit target: queue or action id carried when opening overlay.
        const val EXTRA_QUEUE_ID = "com.phoneinteraction.free.extra.QUEUE_ID"
        const val EXTRA_ACTION_ID = "com.phoneinteraction.free.extra.ACTION_ID"

        // Off actions: onStartCommand decides whether to re-foreground.
        private val OFF_ACTIONS = setOf(ACTION_VISION_OFF, ACTION_OVERLAY_OFF, ACTION_VOICE_OFF)

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                // Add channel description - "foreground service" is internal terminology.
                description = context.getString(R.string.notification_channel_desc)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun start(context: Context, action: String? = null) {
            createChannel(context)
            val intent = Intent(context, MainForegroundService::class.java).apply {
                this.action = action
            }
            // Off actions use regular startService to avoid re-foregrounding just to close one feature.
            if (action != null && action in OFF_ACTIONS) {
                runCatching { context.startService(intent) }
            } else {
                context.startForegroundService(intent)
            }
        }

        // Open overlay and bind to a queue - used by queue page "edit this queue in overlay".
        fun startOverlayForQueue(context: Context, queueId: Long) {
            createChannel(context)
            val intent = Intent(context, MainForegroundService::class.java).apply {
                action = ACTION_OVERLAY_ON
                putExtra(EXTRA_QUEUE_ID, queueId)
            }
            context.startForegroundService(intent)
        }

        // Open overlay and bind to a single action - used by action library "edit this action".
        fun startOverlayForAction(context: Context, actionId: Long) {
            createChannel(context)
            val intent = Intent(context, MainForegroundService::class.java).apply {
                action = ACTION_OVERLAY_ON
                putExtra(EXTRA_ACTION_ID, actionId)
            }
            context.startForegroundService(intent)
        }
    }
}
