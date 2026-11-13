package com.phoneinteraction.free

import android.content.Context
import android.util.Log
import com.phoneinteraction.free.core.ActionExecutor
import com.phoneinteraction.free.core.FailurePolicy
import com.phoneinteraction.free.core.QueueScheduler
import com.phoneinteraction.free.core.RuleEngine
import com.phoneinteraction.free.data.ActionManager
import com.phoneinteraction.free.data.FailurePolicySetting
import com.phoneinteraction.free.data.SettingsRepository
import com.phoneinteraction.free.data.db.AppDatabase
import com.phoneinteraction.free.service.AccessibilityBridge
import com.phoneinteraction.free.service.VisionPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AppServices"

object AppServices {

    lateinit var db: AppDatabase
        private set
    lateinit var actionManager: ActionManager
        private set
    lateinit var ruleEngine: RuleEngine
        private set
    lateinit var queueScheduler: QueueScheduler
        private set
    lateinit var executor: ActionExecutor
        private set
    lateinit var settings: SettingsRepository
        private set

    var visionPipeline: VisionPipeline? = null

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = AppDatabase.get(context)
        actionManager = ActionManager(db)
        executor = ActionExecutor(context, AccessibilityBridge, db.executionRecordDao())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queueScheduler = QueueScheduler(db, executor, scope)
        // Inject accessibility connection state into scheduler. When disconnected, dispatch fails immediately.
        // Without this check, infinite queue (repeat=0) would become a fast-fail infinite loop. Scheduler
        // refuses to start based on this and reports the reason.
        queueScheduler.environmentReady = { AccessibilityBridge.serviceConnected.value }
        ruleEngine = RuleEngine(db)
        settings = SettingsRepository(context)
        // Sync settings page timeout and failure policy to scheduler in real time (including first read after app restart).
        scope.launch {
            settings.failurePolicy.collect { p ->
                queueScheduler.failurePolicy = when (p) {
                    FailurePolicySetting.SKIP -> FailurePolicy.SKIP
                    FailurePolicySetting.STOP -> FailurePolicy.STOP
                    FailurePolicySetting.RETRY -> FailurePolicy.RETRY
                }
            }
        }
        scope.launch {
            settings.actionTimeoutMs.collect { queueScheduler.actionTimeoutMs = it.toLong() }
        }
        // Seed default gesture/voice mappings on first launch (up/down queues). One-time execution.
        // Runs in coroutine to avoid slowing down other init entries. Failure logged only;
        // retry on next launch since flag not set.
        scope.launch {
            try {
                // Pass context to get localized seed names based on effective language.
                InitialMapper.seedIfNeeded(context.applicationContext)
            } catch (e: CancellationException) {
                // Cancellation must propagate upward; never swallow it as a seeding failure
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "initial mapper seed failed", e)
            }
        }
    }
}
