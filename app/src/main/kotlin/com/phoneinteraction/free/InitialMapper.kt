package com.phoneinteraction.free

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.phoneinteraction.free.core.Gesture
import com.phoneinteraction.free.data.model.ActionEntity
import com.phoneinteraction.free.data.model.ActionStepEntity
import com.phoneinteraction.free.data.model.ActionType
import com.phoneinteraction.free.data.model.RuleEntity
import com.phoneinteraction.free.data.model.StepType
import com.phoneinteraction.free.data.model.TriggerType
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import java.util.Locale

// Seed default swipe queues on first launch: up-queue and down-queue with centered swipe action (100ms, 1 repeat).
// Also seed gesture rules (fist/palm) and voice rules (NEXT/PREV or Chinese equivalents).
// Executes only once when never seeded; deleted queues or rules are not auto-restored.
object InitialMapper {

    // Swipe from screen center horizontally. Down-swipe goes top-to-bottom (0.35->0.65), up-swipe bottom-to-top.
    // Symmetric around center, spans ~30% screen height for stable detection without being too close to edges.
    private const val CENTER_X = 0.5f
    private const val UPPER_Y = 0.35f
    private const val LOWER_Y = 0.65f
    private const val SWIPE_DURATION_MS = 100L

    suspend fun seedIfNeeded(context: Context) {
        if (AppServices.settings.initialMapperSeeded.first()) return

        // Seed localized queue/action names and voice keywords to match app language.
        // Language detection mirrors VoiceKeywords: app locale override > system fallback.
        // Queue names and voice keywords must match the same language for rules page to show correctly.
        // Seeding is one-time; existing Chinese-seeded data for old users is not migrated.
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val locale: Locale = if (!appLocales.isEmpty) appLocales[0] ?: Locale.getDefault() else Locale.getDefault()
        val chinese = locale.language.startsWith("zh")
        val localized = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(locale) }
        )

        // All DB writes in one transaction - rollback on any failure to avoid "half-seeded" state.
        // If transaction succeeds but flag write fails, seed runs again on next launch (no duplicate due to unique names).
        AppServices.db.withTransaction {
            val upQueueId = createSwipeQueue(
                name = localized.getString(R.string.seed_queue_up),
                actionName = localized.getString(R.string.seed_action_up),
                fromY = LOWER_Y,
                toY = UPPER_Y
            )
            val downQueueId = createSwipeQueue(
                name = localized.getString(R.string.seed_queue_down),
                actionName = localized.getString(R.string.seed_action_down),
                fromY = UPPER_Y,
                toY = LOWER_Y
            )

            // Vision: Closed_Fist -> queue up, Open_Palm -> queue down (gesture ids come from the model, language-independent)
            AppServices.ruleEngine.addRule(gestureRule(Gesture.CLOSED_FIST.id, upQueueId))
            AppServices.ruleEngine.addRule(gestureRule(Gesture.OPEN_PALM.id, downQueueId))

            // Voice: seed by active language. The literal must exactly match the keyword file display word (case included),
            // otherwise the engine-returned word never matches the rule value and the rule never fires.
            if (chinese) {
                AppServices.ruleEngine.addRule(voiceRule("下一个", upQueueId))
                AppServices.ruleEngine.addRule(voiceRule("上一个", downQueueId))
            } else {
                AppServices.ruleEngine.addRule(voiceRule("NEXT", upQueueId))
                AppServices.ruleEngine.addRule(voiceRule("PREVIOUS", downQueueId))
            }
        }

        // Flag set only after transaction commits (DataStore is separate storage from Room, cannot be in same transaction).
        AppServices.settings.setInitialMapperSeeded(true)
    }

    private suspend fun createSwipeQueue(
        name: String,
        actionName: String,
        fromY: Float,
        toY: Float
    ): Long {
        val queueId = AppServices.queueScheduler.createQueue(name)
        val actionId = AppServices.actionManager.addAction(
            ActionEntity(
                name = actionName,
                type = ActionType.SWIPE,
                normalizedX = CENTER_X,
                normalizedY = fromY,
                duration = SWIPE_DURATION_MS,
                delay = 0L,
                repeatCount = 1
            ),
            swipeSteps(fromY = fromY, toY = toY)
        )
        AppServices.queueScheduler.addItem(queueId, actionId, order = 0)
        return queueId
    }

    // SWIPE must use steps, otherwise executor throws SWIPE_REQUIRES_STEPS.
    // Three points (DOWN->MOVE->UP) ensure multi-step path dispatch with timestamps evenly spaced by duration.
    private fun swipeSteps(fromY: Float, toY: Float): List<ActionStepEntity> {
        val ys = listOf(fromY, (fromY + toY) / 2f, toY)
        return ys.mapIndexed { index, y ->
            ActionStepEntity(
                actionId = 0,
                type = when (index) {
                    0 -> StepType.POINTER_DOWN
                    ys.lastIndex -> StepType.POINTER_UP
                    else -> StepType.POINTER_MOVE
                },
                normalizedX = CENTER_X,
                normalizedY = y,
                timestamp = (SWIPE_DURATION_MS * index) / ys.lastIndex
            )
        }
    }

    private fun gestureRule(gestureId: String, queueId: Long): RuleEntity = RuleEntity(
        triggerType = TriggerType.GESTURE,
        triggerValue = gestureId,
        actionId = null,
        actionQueueId = queueId,
        priority = 0,
        updatedAt = 0L
    )

    private fun voiceRule(word: String, queueId: Long): RuleEntity = RuleEntity(
        triggerType = TriggerType.VOICE,
        triggerValue = word,
        actionId = null,
        actionQueueId = queueId,
        priority = 0,
        updatedAt = 0L
    )
}
