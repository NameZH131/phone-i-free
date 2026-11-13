package com.phoneinteraction.free.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.phoneinteraction.free.R

// Row layout: name takes full width with ellipsis truncation, primary action inline, secondary actions in overflow menu.
// Prevents English/narrow screens from squashing the name (e.g. "Edit in floating window / Delete queue").
data class RowMenuAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false
)

// Row tail: overflow menu button. Does not render when actions is empty.
@Composable
fun RowOverflowMenu(
    actions: List<RowMenuAction>,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.row_more_actions)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            // Destructive actions use error color consistently with inline hierarchy hint.
                            color = if (action.destructive) MaterialTheme.colorScheme.error
                            else LocalContentColor.current
                        )
                    },
                    enabled = action.enabled,
                    onClick = {
                        expanded = false
                        action.onClick()
                    }
                )
            }
        }
    }
}
