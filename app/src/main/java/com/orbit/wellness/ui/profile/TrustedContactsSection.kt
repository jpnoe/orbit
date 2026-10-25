package com.orbit.wellness.ui.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.orbit.wellness.R
import com.orbit.wellness.data.local.TrustedContact
import com.orbit.wellness.data.sharing.SmsChunkCodec
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.rememberHideKeyboard

@Composable
fun TrustedContactsSection(
    viewModel: TrustedContactsViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val hideKeyboard = rememberHideKeyboard()

    var hasSmsPermissions by remember { mutableStateOf(checkSmsPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasSmsPermissions = checkSmsPermissions(context)
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
            text = stringResource(R.string.trusted_contacts_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.trusted_contacts_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.sharing_privacy_disclosure),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!hasSmsPermissions) {
                    OutlinedButton(
                        onClick = {
                            val needed = buildList {
                                add(Manifest.permission.RECEIVE_SMS)
                                add(Manifest.permission.SEND_SMS)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }.filter {
                                ContextCompat.checkSelfPermission(context, it) !=
                                    PackageManager.PERMISSION_GRANTED
                            }
                            if (needed.isNotEmpty()) {
                                permissionLauncher.launch(needed.toTypedArray())
                            }
                        },
                    ) {
                        Text(stringResource(R.string.enable_sms_permissions))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.sms_permissions_enabled),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                uiState.lastInboundAudit?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        uiState.contacts.forEach { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openContact(contact) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = contact.contactName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "+${contact.phoneE164}" +
                            if (contact.autoApprove) " · auto" else " · review",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
            HorizontalDivider()
        }

        Button(
            onClick = {
                val needed = listOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                ).filter {
                    ContextCompat.checkSelfPermission(context, it) !=
                        PackageManager.PERMISSION_GRANTED
                }
                if (needed.isNotEmpty()) {
                    permissionLauncher.launch(needed.toTypedArray())
                }
                viewModel.openAddDialog()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(
                text = stringResource(R.string.add_trusted_contact),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (uiState.showAddDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddDialog,
            title = { Text(stringResource(R.string.add_trusted_contact)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.newName,
                        onValueChange = viewModel::onNewNameChanged,
                        label = { Text(stringResource(R.string.contact_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = uiState.newPhone,
                        onValueChange = viewModel::onNewPhoneChanged,
                        label = { Text(stringResource(R.string.contact_phone)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideKeyboard()
                        viewModel.addContact()
                    },
                    enabled = !uiState.isSaving,
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    uiState.editingContact?.let { contact ->
        ContactPermissionsDialog(
            contact = contact,
            onDismiss = viewModel::dismissEditor,
            onChange = viewModel::updateEditing,
            onDelete = viewModel::deleteEditing,
            onSendUpdate = { viewModel.sendUpdate(contact) },
        )
    }

    uiState.pendingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingRequest,
            title = {
                Text(stringResource(R.string.status_request_review_title, request.contactName))
            },
            text = {
                Text(text = SmsChunkCodec.forDisplay(request.generatedBody))
            },
            confirmButton = {
                TextButton(onClick = viewModel::approvePendingRequest) {
                    Text(stringResource(R.string.send))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::denyPendingRequest) {
                    Text(stringResource(R.string.deny))
                }
            },
        )
    }
}

@Composable
private fun ContactPermissionsDialog(
    contact: TrustedContact,
    onDismiss: () -> Unit,
    onChange: ((TrustedContact) -> TrustedContact) -> Unit,
    onDelete: () -> Unit,
    onSendUpdate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(contact.contactName) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "+${contact.phoneE164}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onSendUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.send_update))
                }
                PermissionSwitch(
                    label = stringResource(R.string.perm_enabled),
                    checked = contact.enabled,
                    onCheckedChange = { checked -> onChange { it.copy(enabled = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_auto_approve),
                    checked = contact.autoApprove,
                    onCheckedChange = { checked -> onChange { it.copy(autoApprove = checked) } },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                PermissionSwitch(
                    label = stringResource(R.string.perm_mood),
                    checked = contact.shareMood,
                    onCheckedChange = { checked -> onChange { it.copy(shareMood = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_journal),
                    checked = contact.shareJournal,
                    onCheckedChange = { checked -> onChange { it.copy(shareJournal = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_sleep),
                    checked = contact.shareSleep,
                    onCheckedChange = { checked -> onChange { it.copy(shareSleep = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_diet),
                    checked = contact.shareDiet,
                    onCheckedChange = { checked -> onChange { it.copy(shareDiet = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_exercise),
                    checked = contact.shareExercise,
                    onCheckedChange = { checked -> onChange { it.copy(shareExercise = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_activity),
                    checked = contact.shareActivity,
                    onCheckedChange = { checked -> onChange { it.copy(shareActivity = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_supportive),
                    checked = contact.shareSupportive,
                    onCheckedChange = { checked -> onChange { it.copy(shareSupportive = checked) } },
                )
                PermissionSwitch(
                    label = stringResource(R.string.perm_coping),
                    checked = contact.shareCoping,
                    onCheckedChange = { checked -> onChange { it.copy(shareCoping = checked) } },
                )
                TextButton(onClick = onDelete) {
                    Text(
                        text = stringResource(R.string.delete_contact),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun PermissionSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun checkSmsPermissions(context: Context): Boolean {
    val receiveGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECEIVE_SMS,
    ) == PackageManager.PERMISSION_GRANTED
    val sendGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED
    return receiveGranted && sendGranted
}
