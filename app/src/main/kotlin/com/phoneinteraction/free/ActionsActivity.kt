package com.phoneinteraction.free

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneinteraction.free.data.ActionManager
import com.phoneinteraction.free.data.model.ActionWithSteps
import com.phoneinteraction.free.data.model.ActionType
import com.phoneinteraction.free.data.model.QueueRunStatus
import com.phoneinteraction.free.data.ThemeMode
import com.phoneinteraction.free.service.MainForegroundService
import com.phoneinteraction.free.service.OverlayState
import com.phoneinteraction.free.ui.RowMenuAction
import com.phoneinteraction.free.ui.RowOverflowMenu
import com.phoneinteraction.free.ui.failureText
import com.phoneinteraction.free.ui.theme.PifTheme
import kotlinx.coroutines.launch

class ActionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Process death recovery only restores the top Activity, so init must be idempotent
        AppServices.init(applicationContext)
        setContent {
            val themeMode by AppServices.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            PifTheme(themeMode = themeMode) {
                ActionsScreen(
                    manager = AppServices.actionManager,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ActionsScreen(manager: ActionManager, onBack: () -> Unit) {
    val context = LocalContext.current
    // lint: LocalContext resources remain valid across config changes; use LocalResources instead
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // Cross-interface sync: the action library uses Room Flow, so an overlay single-action edit updates the DB and this page auto-refreshes
    // The Flow is wrapped in remember so instances are not re-created, which would duplicate subscriptions and rerun queries
    val actions by remember { AppServices.db.actionDao().observeAllWithSteps() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var toDelete by remember { mutableStateOf<ActionWithSteps?>(null) }
    var toRename by remember { mutableStateOf<ActionWithSteps?>(null) }
    var renameInput by remember { mutableStateOf("") }
    // Batch delete: select mode toggle + selected action ids (by id, no shift after delete/reorder) + batch delete confirm dialog
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var toBatchDelete by remember { mutableStateOf(false) }
    // One-click cleanup: orphan actions (not referenced by any queue or rule); show preview confirm when non-empty
    var toCleanup by remember { mutableStateOf<List<ActionWithSteps>?>(null) }
    // One-click cleanup: disable confirm button during delete to prevent double-tap (second run would find 0 items)
    var cleaning by remember { mutableStateOf(false) }
    // Single-action edit: action with shared impact + ref count; confirm then edit coords/params via overlay
    var toEdit by remember { mutableStateOf<ActionWithSteps?>(null) }
    var editRefCount by remember { mutableIntStateOf(0) }
    // Single-action edit: notification permission is async; remember the pending action id and open the overlay after the auth callback
    var pendingLaunchActionId by remember { mutableLongStateOf(-1L) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingLaunchActionId > 0L) {
            MainForegroundService.startOverlayForAction(context, pendingLaunchActionId)
            pendingLaunchActionId = -1L
        }
    }
    // Single-action edit: open the overlay for that action; first ensure notification permission (the foreground service needs it), then bind the action
    fun launchActionEditor(id: Long) {
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotif) {
            pendingLaunchActionId = id
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MainForegroundService.startOverlayForAction(context, id)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Add safe area padding for full-screen/gesture bar areas that get blocked by system bars
                .safeDrawingPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selecting) {
                    // Batch delete: select mode top bar = Done / selected count / Select All / Deselect All
                    TextButton(onClick = {
                        selecting = false
                        selected = emptySet()
                    }) { Text(stringResource(R.string.actions_select_done)) }
                    Text(
                        text = stringResource(R.string.actions_selected_count, selected.size),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                    val allSelected = actions.isNotEmpty() && selected.size == actions.size
                    TextButton(onClick = {
                        selected = if (allSelected) emptySet() else actions.map { it.action.id }.toSet()
                    }) {
                        Text(
                            stringResource(
                                if (allSelected) R.string.actions_select_none else R.string.actions_select_all
                            )
                        )
                    }
                } else {
                    // Back button in the upper-left corner (Android convention)
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                    Text(
                        text = stringResource(R.string.actions_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // Batch delete: no entry when list is empty (nothing to select)
                    if (actions.isNotEmpty()) {
                        // One-click cleanup: always-visible entry; check and show Toast when nothing to clean to avoid "clicked but no response"
                        TextButton(onClick = {
                            scope.launch {
                                val orphans = manager.getUnreferencedActions()
                                if (orphans.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        R.string.actions_cleanup_none,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    toCleanup = orphans
                                }
                            }
                        }) { Text(stringResource(R.string.actions_cleanup)) }
                        TextButton(onClick = { selecting = true }) {
                            Text(stringResource(R.string.actions_select))
                        }
                    }
                }
            }
            if (actions.isEmpty()) {
                // Empty state: a "go record one" hint without an entry is a dead end,
                // Clearly state actions come from overlay drawing and provide direct button to main page.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = stringResource(R.string.actions_empty), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.actions_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = {
                        context.startActivity(Intent(context, MainActivity::class.java))
                        onBack()
                    }) {
                        Text(stringResource(R.string.actions_go_overlay))
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(actions, key = { it.action.id }) { item ->
                    val isSelected = item.action.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Batch delete: selected row highlighted with theme light background; check state visible at a glance
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                } else {
                                    Modifier
                                }
                            )
                            // Batch delete: select mode entire row clickable to toggle; normal mode no click (events handled by in-row buttons)
                            .then(
                                if (selecting) {
                                    Modifier.clickable {
                                        selected = if (isSelected) {
                                            selected - item.action.id
                                        } else {
                                            selected + item.action.id
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selecting) {
                            // Batch delete: checkbox display only; clicks handled uniformly by row to avoid two places competing for events
                            Checkbox(checked = isSelected, onCheckedChange = null)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                // Batch delete: long-press name area to enter select mode and select; only in normal mode
                                // (select mode would conflict with row click, avoid duplicate triggers)
                                .then(
                                    if (selecting) {
                                        Modifier
                                    } else {
                                        Modifier.pointerInput(item.action.id) {
                                            detectTapGestures(onLongPress = {
                                                selecting = true
                                                selected = selected + item.action.id
                                            })
                                        }
                                    }
                                )
                        ) {
                            Text(
                                text = item.action.name,
                                style = MaterialTheme.typography.bodyLarge,
                                // Name width is guaranteed by weight(1f); ellipsis for overflow, consistent with the queue page naming row (avoids wrapping inflating row height)
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val typeLabel = when (item.action.type) {
                                // Show a localized type label instead of the internal enum name
                                ActionType.CLICK -> stringResource(R.string.action_type_click)
                                ActionType.LONG_PRESS -> stringResource(R.string.action_type_long_press)
                                ActionType.SWIPE -> stringResource(R.string.action_type_swipe)
                                ActionType.GLOBAL -> stringResource(R.string.action_type_global)
                            }
                            Text(
                                text = typeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Batch delete: hide row operations in select mode to avoid accidental touches
                        if (!selecting) {
                            // "Execute" stays inline; the other row operations live in the overflow menu.
                            // The name takes weight(1f) with ellipsis so the buttons never squeeze it out of width.
                            Button(onClick = {
                                scope.launch {
                                    // executeAction returns ActionOutcome with a localized failure enum; the core layer does not leak Chinese/enum names to the user
                                    // Single action execution must also use the "single action timeout" from settings;
                                    // otherwise if callback never comes, hangs forever with no record/feedback
                                    val outcome = AppServices.executor.executeAction(
                                        item,
                                        timeoutMs = AppServices.queueScheduler.actionTimeoutMs
                                    )
                                    val msg =
                                        if (outcome.record.result == com.phoneinteraction.free.data.model.ExecutionResult.SUCCESS) {
                                            resources.getString(R.string.action_executed_ok)
                                        } else {
                                            resources.getString(
                                                R.string.action_executed_fail,
                                                failureText(resources, outcome.failure)
                                            )
                                        }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(stringResource(R.string.action_execute)) }
                            RowOverflowMenu(
                                actions = listOfNotNull(
                                    // Single-action edit: only actions with screen coordinates can use canvas edit; system actions (Back/Home etc.) have no position, not offered
                                    if (item.action.type != ActionType.GLOBAL) {
                                        RowMenuAction(
                                            label = stringResource(R.string.action_edit),
                                            onClick = {
                                                // Running guard: prevent edit while queue is running/paused to avoid changing while executing
                                                val st = AppServices.queueScheduler.state.value
                                                if (st == QueueRunStatus.RUNNING || st == QueueRunStatus.PAUSED) {
                                                    Toast.makeText(context, R.string.edit_stop_first, Toast.LENGTH_SHORT).show()
                                                } else if (!Settings.canDrawOverlays(context)) {
                                                    // Permission guide: redirect to grant overlay permission when not yet authorized
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                            "package:${context.packageName}".toUri()
                                                        )
                                                    )
                                                } else {
                                                    scope.launch {
                                                        // Shared impact: count queue + rule refs; confirm when referenced, direct edit when not
                                                        val refs = AppServices.db.queueDao()
                                                            .countItemsByActionId(item.action.id) +
                                                            AppServices.db.ruleDao().countByActionId(item.action.id)
                                                        if (refs == 0) {
                                                            launchActionEditor(item.action.id)
                                                        } else {
                                                            editRefCount = refs
                                                            toEdit = item
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    } else null,
                                    // Visible rename entry
                                    RowMenuAction(
                                        label = stringResource(R.string.action_rename),
                                        onClick = {
                                            renameInput = item.action.name
                                            toRename = item
                                        }
                                    ),
                                    // Destructive operations use danger color
                                    RowMenuAction(
                                        label = stringResource(R.string.action_delete),
                                        destructive = true,
                                        onClick = { toDelete = item }
                                    )
                                )
                            )
                        }
                    }
                }
            }
            // Batch delete: bottom action bar in select mode; disabled when nothing selected
            if (selecting) {
                Button(
                    onClick = { toBatchDelete = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text(stringResource(R.string.actions_batch_delete, selected.size)) }
            }
        }
    }

    toDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                // Destructive confirm uses a danger-colored text button, conforming to the M3 dialog spec
                TextButton(
                    onClick = {
                        scope.launch {
                            manager.deleteAction(target.action.id)
                            // Sync refresh overlay panel
                            OverlayState.refreshCurrent(AppServices.db)
                            toDelete = null
                        }
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

    if (toBatchDelete) {
        AlertDialog(
            onDismissRequest = { toBatchDelete = false },
            title = { Text(stringResource(R.string.actions_batch_delete_title)) },
            text = { Text(stringResource(R.string.actions_batch_delete_text, selected.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selected
                        scope.launch {
                            // Batch delete: reuse single delete semantics; remove queue refs, cleanup rule refs, delete steps, then delete the action
                            ids.forEach { manager.deleteAction(it) }
                            // Sync refresh overlay panel
                            OverlayState.refreshCurrent(AppServices.db)
                            Toast.makeText(
                                context,
                                resources.getString(R.string.actions_batch_deleted, ids.size),
                                Toast.LENGTH_SHORT
                            ).show()
                            selected = emptySet()
                            selecting = false
                            toBatchDelete = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toBatchDelete = false }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    toCleanup?.let { targets ->
        AlertDialog(
            onDismissRequest = { toCleanup = null },
            title = { Text(stringResource(R.string.actions_cleanup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.actions_cleanup_text, targets.size))
                    // One-click cleanup: preview limited to a few names to avoid dialog overflow; excess shown as "and N more"
                    val previewLimit = 8
                    Text(
                        text = targets.take(previewLimit).joinToString("\n") { it.action.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (targets.size > previewLimit) {
                        Text(
                            text = stringResource(
                                R.string.actions_cleanup_more,
                                targets.size - previewLimit
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !cleaning,
                    onClick = {
                        cleaning = true
                        scope.launch {
                            // One-click cleanup: query again at delete time (preview set may have changed)
                            val removed = manager.deleteUnreferencedActions()
                            // Sync refresh overlay panel
                            OverlayState.refreshCurrent(AppServices.db)
                            // One-click cleanup: set may have changed after preview; show "nothing to clean" when actual delete is 0
                            val msg = if (removed.isEmpty()) {
                                resources.getString(R.string.actions_cleanup_none)
                            } else {
                                resources.getString(R.string.actions_cleanup_done, removed.size)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            cleaning = false
                            toCleanup = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { toCleanup = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    toRename?.let { target ->
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text(stringResource(R.string.rename_action_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = renameInput.trim()
                        val current = toRename
                        // Empty name must not be silent; otherwise the button looks broken
                        if (newName.isEmpty()) {
                            Toast.makeText(context, R.string.panel_name_required, Toast.LENGTH_SHORT).show()
                        } else if (current != null) {
                            scope.launch {
                                manager.updateAction(current.action.copy(name = newName))
                                // Sync refresh overlay panel
                                OverlayState.refreshCurrent(AppServices.db)
                            }
                        }
                        toRename = null
                    }
                ) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { toRename = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }

    // Single-action edit: when action is referenced by queue/rule, confirm "changes sync to all" before editing to avoid accidental modification
    toEdit?.let { target ->
        AlertDialog(
            onDismissRequest = { toEdit = null },
            title = { Text(stringResource(R.string.edit_action_warn_title)) },
            text = { Text(pluralStringResource(R.plurals.edit_action_ref_count, editRefCount, editRefCount)) },
            confirmButton = {
                TextButton(onClick = {
                    launchActionEditor(target.action.id)
                    toEdit = null
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { toEdit = null }) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
    }
}
