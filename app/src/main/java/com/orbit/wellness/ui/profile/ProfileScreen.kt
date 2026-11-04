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
import com.orbit.wellness.ui.theme.AccentOption
import com.orbit.wellness.ui.use.MyListsScreen
import com.orbit.wellness.ui.use.MyListsViewModel
import com.orbit.wellness.worker.ReminderScheduler
import kotlinx.coroutines.launch

enum class ProfileSection {
    MY_LISTS,
    HEALTH,
    SETTINGS,
}

object ProfileSections {
    val visible: List<ProfileSection> = listOf(
        ProfileSection.MY_LISTS,
        ProfileSection.HEALTH,
        ProfileSection.SETTINGS,
    )
    val defaultSection: ProfileSection = ProfileSection.MY_LISTS
}

@Composable
fun ProfileScreen(
    listsViewModel: MyListsViewModel,
    healthConnectViewModel: HealthConnectViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var activeSection by remember { mutableStateOf(ProfileSections.defaultSection) }
    val application = LocalContext.current.applicationContext as OrbitApplication
    val selectedAccent by application.themePreferences.accent.collectAsState(initial = AccentOption.DEFAULT)
    val reminderTime by application.reminderPreferences.reminderTime.collectAsState(
        initial = ReminderPreferences.DEFAULT_TIME,
    )
    val scope = rememberCoroutineScope()

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileSections.visible.forEach { section ->
                FilterChip(
                    selected = activeSection == section,
                    onClick = { activeSection = section },
                    label = { Text(stringResource(section.labelRes())) },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (activeSection) {
                ProfileSection.MY_LISTS -> {
                    MyListsScreen(
                        viewModel = listsViewModel,
                        snackbarHostState = snackbarHostState,
                        embedded = true,
                    )
                }
                ProfileSection.HEALTH -> {
                    HealthConnectSection(
                        viewModel = healthConnectViewModel,
                        snackbarHostState = snackbarHostState,
                    )
                }
                ProfileSection.SETTINGS -> {
                    SettingsSection(
                        reminderTime = reminderTime,
                        onReminderTimeChanged = { time ->
                            scope.launch {
                                application.reminderPreferences.setReminderTime(time)
                                ReminderScheduler.scheduleDailyReminder(application, time)
                            }
                        },
                        selectedAccent = selectedAccent,
                        onAccentSelected = { option ->
                            scope.launch { application.themePreferences.setAccent(option) }
                        },
                    )
                }
            }
        }
    }
}

private fun ProfileSection.labelRes(): Int = when (this) {
    ProfileSection.MY_LISTS -> R.string.my_lists_title
    ProfileSection.HEALTH -> R.string.health_connect_tab
    ProfileSection.SETTINGS -> R.string.settings_tab
}
