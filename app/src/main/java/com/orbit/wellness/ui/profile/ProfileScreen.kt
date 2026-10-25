package com.orbit.wellness.ui.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orbit.wellness.OrbitApplication
import com.orbit.wellness.R
import com.orbit.wellness.data.ReminderPreferences
import com.orbit.wellness.ui.history.HistoryScreen
import com.orbit.wellness.ui.history.HistoryViewModel
import com.orbit.wellness.ui.journal.JournalSection
import com.orbit.wellness.ui.journal.JournalViewModel
import com.orbit.wellness.ui.theme.AccentOption
import com.orbit.wellness.ui.theme.AccentPicker
import com.orbit.wellness.ui.trends.TrendsViewModel
import com.orbit.wellness.ui.use.MyListsScreen
import com.orbit.wellness.ui.use.MyListsViewModel
import com.orbit.wellness.ui.util.CollectSavedFeedback
import com.orbit.wellness.worker.ReminderScheduler
import kotlinx.coroutines.launch

private enum class ProfileTab {
    JOURNAL,
    HISTORY,
    CONTACTS,
    MY_LISTS,
    HEALTH,
    SETTINGS,
}

@Composable
fun ProfileScreen(
    listsViewModel: MyListsViewModel,
    trustedContactsViewModel: TrustedContactsViewModel,
    journalViewModel: JournalViewModel,
    historyViewModel: HistoryViewModel,
    trendsViewModel: TrendsViewModel,
    healthConnectViewModel: HealthConnectViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val journalUiState by journalViewModel.uiState.collectAsState()
    val journalSaved = stringResource(R.string.journal_saved)
    var activeTab by remember { mutableStateOf(ProfileTab.JOURNAL) }
    val application = LocalContext.current.applicationContext as OrbitApplication
    val selectedAccent by application.themePreferences.accent.collectAsState(initial = AccentOption.DEFAULT)
    val reminderTime by application.reminderPreferences.reminderTime.collectAsState(
        initial = ReminderPreferences.DEFAULT_TIME,
    )
    val scope = rememberCoroutineScope()

    CollectSavedFeedback(
        pulses = journalViewModel.savedFeedback,
        message = journalSaved,
        snackbarHostState = snackbarHostState,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        AccentPicker(
            selected = selectedAccent,
            onSelect = { option ->
                scope.launch { application.themePreferences.setAccent(option) }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = activeTab == ProfileTab.JOURNAL,
                onClick = { activeTab = ProfileTab.JOURNAL },
                label = { Text(stringResource(R.string.journal_title)) },
            )
            FilterChip(
                selected = activeTab == ProfileTab.HISTORY,
                onClick = { activeTab = ProfileTab.HISTORY },
                label = { Text(stringResource(R.string.history_title)) },
            )
            FilterChip(
                selected = activeTab == ProfileTab.CONTACTS,
                onClick = { activeTab = ProfileTab.CONTACTS },
                label = { Text(stringResource(R.string.trusted_contacts_title)) },
            )
            FilterChip(
                selected = activeTab == ProfileTab.MY_LISTS,
                onClick = { activeTab = ProfileTab.MY_LISTS },
                label = { Text(stringResource(R.string.my_lists_title)) },
            )
            FilterChip(
                selected = activeTab == ProfileTab.HEALTH,
                onClick = { activeTab = ProfileTab.HEALTH },
                label = { Text(stringResource(R.string.health_connect_tab)) },
            )
            FilterChip(
                selected = activeTab == ProfileTab.SETTINGS,
                onClick = { activeTab = ProfileTab.SETTINGS },
                label = { Text(stringResource(R.string.settings_tab)) },
            )
        }

        when (activeTab) {
            ProfileTab.HISTORY -> {
                HistoryScreen(
                    historyViewModel = historyViewModel,
                    trendsViewModel = trendsViewModel,
                    snackbarHostState = snackbarHostState,
                    embedded = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (activeTab) {
                        ProfileTab.JOURNAL -> {
                            JournalSection(
                                uiState = journalUiState,
                                onNoteChanged = journalViewModel::onNoteChanged,
                                onSave = journalViewModel::saveJournal,
                                onEdit = journalViewModel::startEdit,
                                onRequestDelete = journalViewModel::requestDelete,
                                onDismissDelete = journalViewModel::dismissDelete,
                                onConfirmDelete = journalViewModel::confirmDelete,
                                onCancelEdit = journalViewModel::cancelEdit,
                            )
                        }
                        ProfileTab.CONTACTS -> {
                            TrustedContactsSection(
                                viewModel = trustedContactsViewModel,
                                snackbarHostState = snackbarHostState,
                            )
                        }
                        ProfileTab.MY_LISTS -> {
                            MyListsScreen(
                                viewModel = listsViewModel,
                                snackbarHostState = snackbarHostState,
                                embedded = true,
                            )
                        }
                        ProfileTab.HEALTH -> {
                            HealthConnectSection(
                                viewModel = healthConnectViewModel,
                                snackbarHostState = snackbarHostState,
                            )
                        }
                        ProfileTab.SETTINGS -> {
                            SettingsSection(
                                reminderTime = reminderTime,
                                onReminderTimeChanged = { time ->
                                    scope.launch {
                                        application.reminderPreferences.setReminderTime(time)
                                        ReminderScheduler.scheduleDailyReminder(
                                            application,
                                            time,
                                        )
                                    }
                                },
                            )
                        }
                        ProfileTab.HISTORY -> Unit
                    }
                }
            }
        }
    }
}
