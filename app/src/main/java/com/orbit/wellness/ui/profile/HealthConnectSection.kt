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
import androidx.compose.material3.OutlinedButton
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
import com.orbit.wellness.BuildConfig
import com.orbit.wellness.R
import com.orbit.wellness.data.biometrics.HealthConnectPermissions
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.InlineLoading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

            OutlinedButton(
                onClick = viewModel::encodeOnly,
                enabled = !uiState.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.health_connect_encode_only))
            }

            Text(
                text = stringResource(R.string.health_connect_encode_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (BuildConfig.DEBUG) {
            OutlinedButton(
                onClick = viewModel::seedSyntheticTopology,
                enabled = !uiState.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.health_connect_seed_synthetic))
            }
            Text(
                text = stringResource(R.string.health_connect_seed_synthetic_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.isBusy) {
            InlineLoading()
        }

        uiState.biometric?.let { bio ->
            Text(
                text = stringResource(R.string.health_connect_last_sync),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatInstant(bio.syncedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
                Text(
                    text = stringResource(
                        R.string.health_connect_bio_lines,
                        bio.restingHeartRateBpm?.toInt()?.toString() ?: "—",
                        bio.stepsToday?.toString() ?: "—",
                    ),
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

        if (uiState.axisRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.health_connect_vector_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            uiState.axisRows.forEach { (id, value) ->
                Text(
                    text = "$id = ${String.format("%.3f", value)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun formatInstant(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMillis))
}
