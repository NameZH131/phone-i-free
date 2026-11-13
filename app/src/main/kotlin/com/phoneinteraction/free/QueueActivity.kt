package com.phoneinteraction.free

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phoneinteraction.free.core.StartOutcome
import com.phoneinteraction.free.data.ThemeMode
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.QueueItemEntity
import com.phoneinteraction.free.data.model.QueueRunStatus
import com.phoneinteraction.free.service.MainForegroundService
import com.phoneinteraction.free.service.OverlayState
import com.phoneinteraction.free.ui.RowMenuAction
import com.phoneinteraction.free.ui.RowOverflowMenu
import androidx.compose.ui.text.style.TextOverflow
import com.phoneinteraction.free.ui.theme.PifTheme
import kotlinx.coroutines.launch

class QueueActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Init must be idempotent because only the top Activity is recreated on restart
        AppServices.init(applicationContext)
        setContent {
            val themeMode by AppServices.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            PifTheme(themeMode = themeMode) {
                QueueScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun QueueScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Queue/action data uses Room Flow subscription to stay in sync with the overlay window
    val queuesFlow = remember { AppServices.db.queueDao().observeAllQueuesWithItems() }
    val queues by queuesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val actionsFlow = remember { AppServices.db.actionDao().observeAllWithSteps() }
    val actions by actionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedQueueId by remember { mutableStateOf<Long?>(null) }
    var newQueueName by remember { mutableStateOf("") }
    var toRenameQueueId by remember { mutableStateOf<Long?>(null) }
    var queueRenameInput by remember { mutableStateOf("") }
    var toDeleteQueueId by remember { mutableStateOf<Long?>(null) }
    // Confirm before removing: item deletion also removes unreferenced actions
    var toRemoveItem by remember { mutableStateOf<QueueItemEntity?>(null) }
    var toRenameAction by remember { mutableStateOf<ActionWithSteps?>(null) }
    var actionRenameInput by remember { mutableStateOf("") }
    val runState by AppServices.queueScheduler.state.collectAsStateWithLifecycle()
    // Queue overlay edit: store pending queue ID, launch overlay after permission granted
    var pendingLaunchQueueId by remember { mutableLongStateOf(-1L) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingLaunchQueueId > 0L) {
            MainForegroundService.startOverlayForQueue(context, pendingLaunchQueueId)
            pendingLaunchQueueId = -1L
        }
    }

    suspend fun refresh() {
        // Refresh overlay panel after queue/action changes
        OverlayState.refreshCurrent(AppServices.db)
    }

    // Auto-select first available queue when selected is deleted or data first loads
    LaunchedEffect(queues) {
        val ids = queues.map { it.queue.id }
        if (selectedQueueId !in ids) selectedQueueId = queues.firstOrNull()?.queue?.id
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Safe area padding to avoid system bar overlap on gesture navigation devices
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button in upper left (Android convention)
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back)
                    )
                }
                Text(
                    text = stringResource(R.string.queue_title),
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newQueueName,
                    onValueChange = { newQueueName = it },
                    label = { Text(stringResource(R.string.queue_new_hint)) },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    if (newQueueName.isNotBlank()) {
                        scope.launch {
                            AppServices.queueScheduler.createQueue(newQueueName.trim())
                            newQueueName = ""
                            refresh()
                        }
                    } else {
                        // Show feedback when empty name is submitted
                        Toast.makeText(context, R.string.panel_name_required, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.queue_create)) }
            }

            if (queues.isEmpty()) {
                Text(text = stringResource(R.string.queue_empty), style = MaterialTheme.typography.bodyMedium)
            }

            // Queue selector: all queues scroll horizontally instead of showing only the first 3
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(queues, key = { it.queue.id }) { q ->
                    // FilterChip clearly shows which queue is currently selected
                    FilterChip(
                        selected = selectedQueueId == q.queue.id,
                        onClick = { selectedQueueId = q.queue.id },
                        label = {
                            // Count made localizable via string resource
                            Text(stringResource(R.string.queue_with_count, q.queue.name, q.items.size.toString()))
                        }
                    )
                }
            }

            val selected = queues.firstOrNull { it.queue.id == selectedQueueId }
            selected?.let { q ->
                // Run status is a global singleton; scope it to current queue to avoid confusing B when A has error
                val isCurrentQueue = AppServices.queueScheduler.currentRunningQueueId() == q.queue.id
                val queueRunState = if (isCurrentQueue) runState else QueueRunStatus.IDLE
                val actionNames = actions.associate { it.action.id to it.action.name }
                val ordered = q.items.sortedBy { it.order }
                Text(
                    text = stringResource(R.string.queue_state_prefix) +
                        when (queueRunState) {
                            // READY only appears in data saved by older versions; show it with the IDLE label
                            QueueRunStatus.IDLE, QueueRunStatus.READY -> stringResource(R.string.queue_state_idle)
                            QueueRunStatus.RUNNING -> stringResource(R.string.queue_state_running)
                            QueueRunStatus.PAUSED -> stringResource(R.string.queue_state_paused)
                            QueueRunStatus.STOPPED -> stringResource(R.string.queue_state_stopped)
                            QueueRunStatus.ERROR -> stringResource(R.string.queue_state_error)
                        },
                    style = MaterialTheme.typography.bodyLarge
                )
                // Queue items can be enabled/disabled and removed (actions stay in library)
                // Height limit + scroll keeps "add action" reachable when many items exist
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ordered.forEachIndexed { idx, item ->
                        val itemName = actionNames[item.actionId] ?: stringResource(R.string.item_missing_name)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.queue_item_row,
                                    (idx + 1).toString(),
                                    itemName
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            // Up/down buttons reorder and persist to the order field
                            IconButton(
                                enabled = idx > 0 && queueRunState != QueueRunStatus.RUNNING && queueRunState != QueueRunStatus.PAUSED,
                                onClick = {
                                    scope.launch {
                                        val ids = ordered.map { it.itemId }.toMutableList()
                                        val tmp = ids[idx]; ids[idx] = ids[idx - 1]; ids[idx - 1] = tmp
                                        AppServices.queueScheduler.reorderItems(q.queue.id, ids)
                                        refresh()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.queue_item_move_up)
                                )
                            }
                            IconButton(
                                enabled = idx < ordered.lastIndex && queueRunState != QueueRunStatus.RUNNING && queueRunState != QueueRunStatus.PAUSED,
                                onClick = {
                                    scope.launch {
                                        val ids = ordered.map { it.itemId }.toMutableList()
                                        val tmp = ids[idx]; ids[idx] = ids[idx + 1]; ids[idx + 1] = tmp
                                        AppServices.queueScheduler.reorderItems(q.queue.id, ids)
                                        refresh()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.queue_item_move_down)
                                )
                            }
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        AppServices.db.queueDao().updateItems(listOf(item.copy(enabled = checked)))
                                        refresh()
                                    }
                                },
                                // TalkBack needs action name for context on unlabeled Switch
                                modifier = Modifier.semantics { contentDescription = itemName }
                            )
                            IconButton(onClick = {
                                // Also removes action from library if no other references exist
                                toRemoveItem = item
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.queue_item_delete),
                                    // Destructive operations use error color for visual warning
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = q.queue.name,
                        style = MaterialTheme.typography.titleSmall,
                        // Queue name uses weight + ellipsis to avoid crowding out actions
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Edit, rename and delete live in the overflow menu to fit long English labels
                    RowOverflowMenu(
                        actions = listOf(
                            RowMenuAction(
                                label = stringResource(R.string.queue_overlay_edit),
                                onClick = {
                                    // Overlay is global singleton claimed by any running queue;
                                    val running = runState == QueueRunStatus.RUNNING || runState == QueueRunStatus.PAUSED
                                    if (running) {
                                        Toast.makeText(context, R.string.edit_stop_first, Toast.LENGTH_SHORT).show()
                                    } else if (!Settings.canDrawOverlays(context)) {
                                        // Guide to settings when overlay permission not granted
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                "package:${context.packageName}".toUri()
                                            )
                                        )
                                    } else {
                                        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
                                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                        if (needsNotif) {
                                            pendingLaunchQueueId = q.queue.id
                                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            MainForegroundService.startOverlayForQueue(context, q.queue.id)
                                        }
                                    }
                                }
                            ),
                            // Visible rename entry for queues
                            RowMenuAction(
                                label = stringResource(R.string.action_rename),
                                onClick = {
                                    queueRenameInput = q.queue.name
                                    toRenameQueueId = q.queue.id
                                }
                            ),
                            // Delete uses error color as destructive operation
                            RowMenuAction(
                                label = stringResource(R.string.queue_delete),
                                destructive = true,
                                onClick = { toDeleteQueueId = q.queue.id }
                            )
                        )
                    )
                }

                // Primary actions filled, secondary outlined, stop uses error color based on run state
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                when (AppServices.queueScheduler.startQueue(q.queue.id)) {
                                    // Report startup failure reason to user instead of silent no-op
                                    StartOutcome.ENVIRONMENT_NOT_READY ->
                                        Toast.makeText(
                                            context,
                                            R.string.panel_a11y_required,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    StartOutcome.ALREADY_RUNNING ->
                                        Toast.makeText(
                                            context,
                                            R.string.panel_queue_running,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    StartOutcome.STARTED -> Unit
                                }
                            }
                        },
                        enabled = queueRunState != QueueRunStatus.RUNNING && queueRunState != QueueRunStatus.PAUSED
                    ) { Text(stringResource(R.string.queue_start)) }
                    OutlinedButton(
                        onClick = {
                            scope.launch { AppServices.queueScheduler.pauseQueue() }
                        },
                        enabled = queueRunState == QueueRunStatus.RUNNING
                    ) { Text(stringResource(R.string.panel_pause)) }
                    OutlinedButton(
                        onClick = {
                            scope.launch { AppServices.queueScheduler.resumeQueue() }
                        },
                        enabled = queueRunState == QueueRunStatus.PAUSED
                    ) { Text(stringResource(R.string.panel_resume)) }
                    OutlinedButton(
                        onClick = {
                            scope.launch { AppServices.queueScheduler.stopQueue() }
                        },
                        enabled = queueRunState == QueueRunStatus.RUNNING || queueRunState == QueueRunStatus.PAUSED,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.queue_stop)) }
                }
                if (queueRunState == QueueRunStatus.ERROR) {
                    // In the ERROR state the user can retry or skip
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = {
                            scope.launch { AppServices.queueScheduler.retryCurrent() }
                        }) { Text(stringResource(R.string.queue_error_retry)) }
                        OutlinedButton(onClick = {
                            scope.launch { AppServices.queueScheduler.skipCurrent() }
                        }) { Text(stringResource(R.string.queue_error_skip)) }
                        // In the ERROR state the user can also reset or abandon
                        OutlinedButton(onClick = {
                            scope.launch { AppServices.queueScheduler.resetQueue() }
                        }) { Text(stringResource(R.string.queue_error_reset)) }
                        OutlinedButton(
                            onClick = {
                                scope.launch { AppServices.queueScheduler.abandonQueue() }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text(stringResource(R.string.queue_error_abandon)) }
                    }
                    // Clarify: reset and abandon have similar endpoints, one line helps users understand
                    Text(
                        text = stringResource(R.string.queue_error_buttons_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Add action to queue
                Text(
                    text = stringResource(R.string.queue_add_title),
                    style = MaterialTheme.typography.titleSmall
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(actions, key = { it.action.id }) { a ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = a.action.name,
                                // Action name uses weight + ellipsis to avoid being pushed aside by buttons
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // Add is the inline primary action; rename is a secondary entry in the overflow menu
                            Button(onClick = {
                                scope.launch {
                                    AppServices.queueScheduler.addItem(
                                        q.queue.id,
                                        a.action.id,
                                        // New item gets max order + 1 to avoid duplicates after deletion
                                        (q.items.maxOfOrNull { it.order } ?: -1) + 1
                                    )
                                    refresh()
                                }
                            }) { Text(stringResource(R.string.queue_add)) }
                            RowOverflowMenu(
                                actions = listOf(
                                    // Visible rename entry for actions
                                    RowMenuAction(
                                        label = stringResource(R.string.action_rename),
                                        onClick = {
                                            actionRenameInput = a.action.name
                                            toRenameAction = a
                                        }
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    toRemoveItem?.let { item ->
        AlertDialog(
            onDismissRequest = { toRemoveItem = null },
            title = { Text(stringResource(R.string.queue_item_remove_title)) },
            text = { Text(stringResource(R.string.queue_item_remove_text)) },
            confirmButton = {
                // Destructive confirm uses error color text button
                TextButton(
                    onClick = {
                        toRemoveItem = null
                        scope.launch {
                            AppServices.queueScheduler.removeItem(item.itemId)
                            // Also clean up unreferenced auto-created actions to avoid library bloat
                            if (AppServices.db.queueDao().countItemsByActionId(item.actionId) == 0) {
                                AppServices.actionManager.deleteAction(item.actionId)
                            }
                            refresh()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.queue_item_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toRemoveItem = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    toRenameQueueId?.let { qid ->
        AlertDialog(
            onDismissRequest = { toRenameQueueId = null },
            title = { Text(stringResource(R.string.rename_queue_title)) },
            text = {
                OutlinedTextField(
                    value = queueRenameInput,
                    onValueChange = { queueRenameInput = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = queueRenameInput.trim()
                    val target = queues.firstOrNull { it.queue.id == qid }?.queue
                    if (newName.isEmpty()) {
                        // Show feedback when empty name is submitted instead of silent dismiss
                        Toast.makeText(context, R.string.panel_name_required, Toast.LENGTH_SHORT).show()
                    } else if (target != null) {
                        scope.launch {
                            AppServices.db.queueDao().updateQueue(target.copy(name = newName))
                            refresh()
                        }
                        toRenameQueueId = null
                    } else {
                        toRenameQueueId = null
                    }
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { toRenameQueueId = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    toDeleteQueueId?.let { qid ->
        AlertDialog(
            onDismissRequest = { toDeleteQueueId = null },
            title = { Text(stringResource(R.string.queue_delete_confirm_title)) },
            text = { Text(stringResource(R.string.queue_delete_confirm_text)) },
            confirmButton = {
                // Destructive confirm uses error color text button
                TextButton(
                    onClick = {
                        scope.launch {
                            AppServices.queueScheduler.deleteQueue(qid)
                            if (selectedQueueId == qid) selectedQueueId = null
                            toDeleteQueueId = null
                            refresh()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toDeleteQueueId = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    toRenameAction?.let { target ->
        AlertDialog(
            onDismissRequest = { toRenameAction = null },
            title = { Text(stringResource(R.string.rename_action_title)) },
            text = {
                OutlinedTextField(
                    value = actionRenameInput,
                    onValueChange = { actionRenameInput = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = actionRenameInput.trim()
                    if (newName.isEmpty()) {
                        // Show feedback when empty name is submitted instead of silent dismiss
                        Toast.makeText(context, R.string.panel_name_required, Toast.LENGTH_SHORT).show()
                    } else {
                        scope.launch {
                            AppServices.actionManager.updateAction(target.action.copy(name = newName))
                            refresh()
                        }
                        toRenameAction = null
                    }
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { toRenameAction = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }
}
