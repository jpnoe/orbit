package com.orbit.wellness.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Diet-style recent-entry row: timestamp, then one body line per title, then an optional footer.
 */
@Composable
fun LoggedEntry(
    timeLabel: String,
    titles: List<String>,
    modifier: Modifier = Modifier,
    footer: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        titles.filter { it.isNotBlank() }.forEach { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LoggedEntry(
    timeLabel: String,
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    onClick: (() -> Unit)? = null,
) {
    LoggedEntry(
        timeLabel = timeLabel,
        titles = listOf(title),
        modifier = modifier,
        footer = footer,
        onClick = onClick,
    )
}
