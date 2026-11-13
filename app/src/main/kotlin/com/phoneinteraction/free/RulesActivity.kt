package com.phoneinteraction.free

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phoneinteraction.free.core.Gesture
import com.phoneinteraction.free.core.RuleEngine
import com.phoneinteraction.free.data.ActionManager
import com.phoneinteraction.free.data.db.QueueDao
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.QueueWithItems
import com.phoneinteraction.free.data.model.RuleEntity
import com.phoneinteraction.free.data.ThemeMode
import com.phoneinteraction.free.data.model.TriggerType
import com.phoneinteraction.free.service.VoiceKeywords
import com.phoneinteraction.free.ui.theme.PifTheme
import kotlinx.coroutines.launch

// Rules page: connects gestures/voice triggers to actions/queues.
class RulesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppServices.init(applicationContext)
        setContent {
            val themeMode by AppServices.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            PifTheme(themeMode = themeMode) {
                RulesScreen(
                    engine = AppServices.ruleEngine,
                    actionManager = AppServices.actionManager,
                    queueDao = AppServices.db.queueDao(),
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun RulesScreen(
    engine: RuleEngine,
    actionManager: ActionManager,
    queueDao: QueueDao,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var rules by remember { mutableStateOf<List<RuleEntity>>(emptyList()) }
    var actions by remember { mutableStateOf<List<ActionWithSteps>>(emptyList()) }
    var queues by remember { mutableStateOf<List<QueueWithItems>>(emptyList()) }
    var toDelete by remember { mutableStateOf<RuleEntity?>(null) }
    // Rule inline editing.
    var editingRuleId by remember { mutableStateOf<Long?>(null) }

    var triggerVoice by remember { mutableStateOf(false) }
    // Voice command word filtering.
    var voiceSearchQuery by remember { mutableStateOf("") }
    // Triggers are selectable from model types; values cannot be entered manually.
    var triggerValue by remember { mutableStateOf<String?>(null) }
    var targetIsQueue by remember { mutableStateOf(false) }
    var selectedActionId by remember { mutableStateOf<Long?>(null) }
    var selectedQueueId by remember { mutableStateOf<Long?>(null) }

    suspend fun reload() {
        rules = engine.getRules()
        actions = actionManager.getAllActions()
        queues = queueDao.getAllQueuesWithItems()
    }

    // Edit existing rule.
    fun startEdit(rule: RuleEntity) {
        editingRuleId = rule.id
        triggerVoice = rule.triggerType == TriggerType.VOICE
        triggerValue = rule.triggerValue
        targetIsQueue = rule.actionQueueId != null
        selectedQueueId = rule.actionQueueId
        selectedActionId = rule.actionId
    }

    // Reset form on cancel or successful save.
    fun resetForm() {
        editingRuleId = null
        triggerValue = null
        selectedActionId = null
        selectedQueueId = null
    }

    LaunchedEffect(Unit) { reload() }

    // Reload data when returning to this page to avoid stale content.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { reload() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val actionNames = actions.associate { it.action.id to it.action.name }
    val queueNames = queues.associate { it.queue.id to it.queue.name }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Safe area padding for gesture navigation / system bars.
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button in top-left (Android convention).
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back)
                    )
                }
                Text(
                    text = stringResource(R.string.rule_title),
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (rules.isEmpty()) {
                Text(
                    text = stringResource(R.string.rule_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            rules.forEach { rule ->
                val triggerLabel = if (rule.triggerType == TriggerType.VOICE) {
                    stringResource(R.string.rule_trigger_voice)
                } else {
                    stringResource(R.string.rule_trigger_gesture)
                }
                val targetName = rule.actionQueueId?.let { queueNames[it] ?: stringResource(R.string.rule_none) }
                    ?: rule.actionId?.let { actionNames[it] ?: stringResource(R.string.rule_none) }
                    ?: stringResource(R.string.rule_none)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tap rule row to edit.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { startEdit(rule) }
                    ) {
                        // Show readable label for both gestures and voice words.
                        val triggerName = if (rule.triggerType == TriggerType.VOICE) {
                            rule.triggerValue
                        } else {
                            Gesture.fromId(rule.triggerValue)?.let { stringResource(it.labelRes) }
                                ?: rule.triggerValue
                        }
                        Text(
                            text = "$triggerLabel${stringResource(R.string.item_separator)}$triggerName",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.rule_target_row, targetName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { toDelete = rule }) {
                        Text(stringResource(R.string.rule_delete))
                    }
                }
            }

            Text(
                text = stringResource(R.string.rule_add_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.rule_trigger_type),
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !triggerVoice,
                    // Switching trigger type clears previous selection (different value types).
                    onClick = { triggerVoice = false; triggerValue = null },
                    label = { Text(stringResource(R.string.rule_trigger_gesture)) }
                )
                FilterChip(
                    selected = triggerVoice,
                    onClick = { triggerVoice = true; triggerValue = null },
                    label = { Text(stringResource(R.string.rule_trigger_voice)) }
                )
            }
            Text(
                text = stringResource(
                    if (triggerVoice) R.string.rule_value_voice else R.string.rule_value_gesture
                ),
                style = MaterialTheme.typography.labelMedium
            )
            // Command words come from engine's hardcoded list; must explain "only these words work".
            if (triggerVoice) {
                Text(
                    text = stringResource(R.string.rule_voice_words_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Voice command list supports text filtering.
            if (triggerVoice) {
                val words = remember(resources) { VoiceKeywords.commands(context) }
                val filteredWords = if (voiceSearchQuery.isBlank()) {
                    words
                } else {
                    words.filter { it.contains(voiceSearchQuery, ignoreCase = true) }
                }
                if (words.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rule_no_voice_words),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Voice command search box.
                    OutlinedTextField(
                        value = voiceSearchQuery,
                        onValueChange = { voiceSearchQuery = it },
                        placeholder = { Text(stringResource(R.string.rule_search_placeholder), style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (filteredWords.isEmpty()) {
                        Text(
                            text = stringResource(R.string.rule_search_no_results),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredWords, key = { it }) { w ->
                                SelectableRow(
                                    text = w,
                                    selected = triggerValue == w,
                                    onClick = { triggerValue = w }
                                )
                            }
                        }
                    }
                }
            } else {
                val gestures = remember { Gesture.values().toList() }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(gestures, key = { it.id }) { g ->
                        // Store internal ID for matching; display human-readable name.
                        SelectableRow(
                            text = stringResource(g.labelRes),
                            selected = triggerValue == g.id,
                            onClick = { triggerValue = g.id }
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.rule_target),
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !targetIsQueue,
                    onClick = { targetIsQueue = false },
                    label = { Text(stringResource(R.string.rule_target_action)) }
                )
                FilterChip(
                    selected = targetIsQueue,
                    onClick = { targetIsQueue = true },
                    label = { Text(stringResource(R.string.rule_target_queue)) }
                )
            }
            // Target must exist before creating rule; show empty library message with action link.
            if (actions.isEmpty() && queues.isEmpty()) {
                Text(
                    text = stringResource(R.string.panel_empty_library),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    context.startActivity(Intent(context, ActionsActivity::class.java))
                }) { Text(stringResource(R.string.rule_go_actions)) }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (targetIsQueue) {
                        items(queues, key = { it.queue.id }) { q ->
                            val selected = selectedQueueId == q.queue.id
                            SelectableRow(
                                text = q.queue.name,
                                selected = selected,
                                onClick = {
                                    selectedQueueId = q.queue.id
                                    selectedActionId = null
                                }
                            )
                        }
                    } else {
                        items(actions, key = { it.action.id }) { a ->
                            val selected = selectedActionId == a.action.id
                            SelectableRow(
                                text = a.action.name,
                                selected = selected,
                                onClick = {
                                    selectedActionId = a.action.id
                                    selectedQueueId = null
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val value = triggerValue
                        val queueId = selectedQueueId
                        val actionId = selectedActionId
                        // Smart cast to non-null after boolean check.
                        if (value == null || (queueId == null && actionId == null)) {
                            Toast.makeText(context, R.string.rule_invalid, Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                val type = if (triggerVoice) TriggerType.VOICE else TriggerType.GESTURE
                                // Trigger value must be unique; same trigger cannot have multiple rules.
                                val duplicate = rules.any {
                                    it.id != editingRuleId && it.triggerType == type && it.triggerValue == value
                                }
                                if (duplicate) {
                                    Toast.makeText(context, R.string.rule_duplicate, Toast.LENGTH_SHORT).show()
                                } else {
                                    val editing = editingRuleId?.let { eid -> rules.firstOrNull { it.id == eid } }
                                    if (editing != null) {
                                        engine.updateRule(
                                            editing.copy(
                                                triggerType = type,
                                                triggerValue = value,
                                                actionId = if (targetIsQueue) null else actionId,
                                                actionQueueId = if (targetIsQueue) queueId else null
                                            )
                                        )
                                    } else {
                                        engine.addRule(
                                            RuleEntity(
                                                triggerType = type,
                                                triggerValue = value,
                                                actionId = if (targetIsQueue) null else actionId,
                                                actionQueueId = if (targetIsQueue) queueId else null,
                                                priority = 0,
                                                updatedAt = 0L
                                            )
                                        )
                                    }
                                    val wasEditing = editing != null
                                    resetForm()
                                    reload()
                                    Toast.makeText(
                                        context,
                                        if (wasEditing) R.string.rule_updated else R.string.rule_added,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    // Show save button with different text when editing.
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(
                            if (editingRuleId != null) R.string.rule_save_changes else R.string.queue_add
                        )
                    )
                }
                if (editingRuleId != null) {
                    TextButton(onClick = { resetForm() }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            }
        }
    }

    toDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.rule_delete_confirm_title)) },
            text = { Text(stringResource(R.string.rule_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            engine.deleteRule(target.id)
                            // Reset form if deleted rule was being edited to prevent reviving deleted rule.
                            if (editingRuleId == target.id) resetForm()
                            reload()
                            Toast.makeText(
                                context,
                                resources.getString(R.string.rule_deleted),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        toDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }
}

@Composable
private fun SelectableRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Minimum touch target 48dp.
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
