package com.orbit.wellness.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.orbit.wellness.R
import com.orbit.wellness.data.biometrics.HealthConnectPermissions
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.InlineLoading
import com.orbit.wellness.ui.wearable.wearableMetricLabelRes
import com.orbit.wellness.util.DateUtils

@Composable
fun HealthConnectSection(
    viewModel: HealthConnectViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        viewModel.onPermissionsResult(granted)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAvailability()
    }

    CollectSnackbarMessages(
        messages = viewModel.messages,
        snackbarHostState = snackbarHostState,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.health_connect_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.health_connect_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(
                R.string.health_connect_status,
                viewModel.sdkStatusLabel(),
                if (uiState.hasPermissions) {
                    stringResource(R.string.health_connect_perms_granted)
                } else {
                    stringResource(R.string.health_connect_perms_needed)
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        if (!uiState.sdkAvailable) {
            Text(
                text = stringResource(R.string.health_connect_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (uiState.sdkStatusCode ==
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
            ) {
                TextButton(
                    onClick = {
                        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                        context.startActivity(intent)
                    },
                ) {
                    Text(stringResource(R.string.health_connect_open_settings))
                }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                TextButton(
                    onClick = {
                        val uri = Uri.parse(
                            "market://details?id=com.google.android.apps.healthdata",
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                ) {
                    Text(stringResource(R.string.health_connect_install))
                }
            }
        } else {
            if (!uiState.hasPermissions) {
                Button(
                    onClick = {
                        permissionLauncher.launch(HealthConnectPermissions.ALL_REQUESTED)
                    },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.health_connect_grant))
                }
            } else {
                TextButton(
                    onClick = {
                        permissionLauncher.launch(HealthConnectPermissions.ALL_REQUESTED)
                    },
                    enabled = !uiState.isBusy,
                ) {
                    Text(stringResource(R.string.health_connect_regrant))
                }
            }

            Button(
                onClick = viewModel::syncNow,
                enabled = !uiState.isBusy && uiState.hasPermissions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.health_connect_sync))
            }

            Text(
                text = stringResource(R.string.health_connect_sync_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.isBusy) {
            InlineLoading()
        }

        Text(
            text = stringResource(R.string.health_connect_status_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        if (!uiState.wearable.hasObservations) {
            Text(
                text = stringResource(R.string.health_connect_observations_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            uiState.wearable.latestImportedAt?.let { importedAt ->
                Text(
                    text = stringResource(
                        R.string.health_connect_latest_import,
                        DateUtils.datedTimeLabel(importedAt),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            var metricNames = ""
            for (count in uiState.wearable.countByMetric) {
                val name = stringResource(wearableMetricLabelRes(count.metric))
                metricNames = if (metricNames.isEmpty()) name else "$metricNames, $name"
            }
            Text(
                text = stringResource(R.string.health_connect_stored_metrics, metricNames),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (uiState.syncNotes.isNotEmpty()) {
            Text(
                text = stringResource(R.string.health_connect_notes_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            uiState.syncNotes.forEach { note ->
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

