package com.orbit.wellness.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R

@Composable
fun AccentPicker(
    selected: AccentOption,
    onSelect: (AccentOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.accent_color_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.accent_color_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentOption.entries.forEach { option ->
                val label = stringResource(option.labelRes)
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .background(option.seed, CircleShape)
                        .semantics {
                            contentDescription = label
                            role = Role.RadioButton
                            this.selected = isSelected
                        }
                        .clickable { onSelect(option) },
                )
            }
        }
        Text(
            text = stringResource(selected.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
