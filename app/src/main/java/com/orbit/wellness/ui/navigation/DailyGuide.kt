package com.orbit.wellness.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.R

object DailyGuide {
    val steps = listOf(
        OrbitDestination.CheckIn,
        OrbitDestination.Activity,
        OrbitDestination.Exercise,
        OrbitDestination.Diet,
        OrbitDestination.Use,
    )

    fun indexOf(route: String?): Int {
        if (route == null) return -1
        return steps.indexOfFirst { it.route == route }
    }

    fun nextAfter(route: String?): OrbitDestination? {
        val index = indexOf(route)
        if (index < 0) return steps.first()
        return steps.getOrNull(index + 1)
    }

    fun isLast(route: String?): Boolean {
        val index = indexOf(route)
        return index == steps.lastIndex
    }
}

@Composable
fun DailyGuideBanner(
    currentRoute: String?,
    onContinue: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = DailyGuide.indexOf(currentRoute).let { if (it < 0) 0 else it }
    val stepName = stringResource(stepLabelRes(DailyGuide.steps[index]))
    val isLast = index >= DailyGuide.steps.lastIndex

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.daily_guide_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.daily_guide_step,
                            stepName,
                            index + 1,
                            DailyGuide.steps.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onExit) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.daily_guide_exit),
                    )
                }
            }
            Text(
                text = stringResource(R.string.daily_guide_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (isLast) R.string.daily_guide_done else R.string.daily_guide_continue,
                    ),
                )
            }
        }
    }
}

private fun stepLabelRes(destination: OrbitDestination): Int = when (destination) {
    OrbitDestination.CheckIn -> R.string.tab_check_in
    OrbitDestination.Activity -> R.string.tab_activity
    OrbitDestination.Exercise -> R.string.tab_exercise
    OrbitDestination.Diet -> R.string.tab_diet
    OrbitDestination.Use -> R.string.tab_use
    OrbitDestination.Profile -> R.string.tab_profile
}
