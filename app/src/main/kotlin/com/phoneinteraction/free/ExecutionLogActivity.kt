package com.phoneinteraction.free

import android.app.Activity
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneinteraction.free.core.ExecutionFailure
import com.phoneinteraction.free.R
import com.phoneinteraction.free.data.SettingsRepository
import com.phoneinteraction.free.data.ThemeMode
import com.phoneinteraction.free.data.model.ExecutionRecordEntity
import com.phoneinteraction.free.data.model.ExecutionResult
import com.phoneinteraction.free.ui.failureText
import com.phoneinteraction.free.ui.theme.PifTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ExecutionLogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppServices.init(applicationContext)
        val settings = SettingsRepository(applicationContext)
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            PifTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ExecutionLogScreen()
                }
            }
        }
    }
}

@Composable
private fun ExecutionLogScreen() {
    val resources = LocalResources.current
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<ExecutionRecordEntity>>(emptyList()) }
    var names by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var queueNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        records = AppServices.db.executionRecordDao().getRecent(200)
        names = AppServices.db.actionDao().getAllWithSteps()
            .associate { it.action.id to it.action.name }
        queueNames = AppServices.db.queueDao().getAllQueuesWithItems()
            .associate { it.queue.id to it.queue.name }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { (context as? Activity)?.finish() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back)
                )
            }
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (records.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.history_clear)
                    )
                }
            }
        }
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Execution log list is capped; tell the user older entries are hidden.
            Text(
                stringResource(R.string.history_limit_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(records, key = { it.recordId }) { record ->
                    ExecutionRecordRow(record = record, names = names, queueNames = queueNames, resources = resources)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.history_clear_confirm_title)) },
            text = { Text(stringResource(R.string.history_clear_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            AppServices.db.executionRecordDao().deleteAll()
                            records = emptyList()
                            names = emptyMap()
                            queueNames = emptyMap()
                            showClearDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun ExecutionRecordRow(
    record: ExecutionRecordEntity,
    names: Map<Long, String>,
    queueNames: Map<Long, String>,
    resources: Resources
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = names[record.actionId]
                    ?: stringResource(R.string.overlay_action_name, record.actionId.toString()),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (record.queueId != null) {
                Spacer(Modifier.width(8.dp))
                val qName = queueNames[record.queueId]
                Text(
                    text = if (qName != null) stringResource(R.string.history_in_queue_name, qName) else stringResource(R.string.history_in_queue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // errorMessage stores an ExecutionFailure enum name (see ActionExecutor);
        // unknown names in older records fall back to UNKNOWN instead of crashing on valueOf.
        val failure = record.errorMessage
            ?.let { name ->
                runCatching { ExecutionFailure.valueOf(name) }.getOrDefault(ExecutionFailure.UNKNOWN)
            }
            ?: ExecutionFailure.NONE
        val resultText = if (record.result == ExecutionResult.SUCCESS) {
            stringResource(R.string.history_ok)
        } else {
            stringResource(R.string.history_failed, failureText(resources, failure))
        }
        Text(
            text = DateFormat.getDateTimeInstance(
        DateFormat.DEFAULT,
        DateFormat.DEFAULT,
        resources.configuration.locales[0]
    ).format(Date(record.executionTime)) +
                stringResource(R.string.item_separator) + stringResource(R.string.unit_ms, record.duration.toString()) +
                stringResource(R.string.item_separator) + resultText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
