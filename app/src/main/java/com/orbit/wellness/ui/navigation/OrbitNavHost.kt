package com.orbit.wellness.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.orbit.wellness.OrbitApplication
import com.orbit.wellness.R
import com.orbit.wellness.ui.activity.ActivityScreen
import com.orbit.wellness.ui.activity.ActivityViewModel
import com.orbit.wellness.ui.checkin.CheckInScreen
import com.orbit.wellness.ui.checkin.CheckInViewModel
import com.orbit.wellness.ui.diet.DietScreen
import com.orbit.wellness.ui.diet.DietViewModel
import com.orbit.wellness.ui.exercise.ExerciseScreen
import com.orbit.wellness.ui.exercise.ExerciseViewModel
import com.orbit.wellness.ui.history.HistoryViewModel
import com.orbit.wellness.ui.journal.JournalViewModel
import com.orbit.wellness.ui.profile.HealthConnectViewModel
import com.orbit.wellness.ui.profile.ProfileScreen
import com.orbit.wellness.ui.profile.TrustedContactsViewModel
import com.orbit.wellness.ui.trends.TrendsViewModel
import com.orbit.wellness.ui.use.MyListsViewModel
import com.orbit.wellness.ui.use.UseScreen
import com.orbit.wellness.ui.use.UseViewModel

import kotlinx.coroutines.flow.first

enum class OrbitDestination(
    val route: String,
) {
    CheckIn("check_in"),
    Activity("activity"),
    Diet("diet"),
    Exercise("exercise"),
    Use("use"),
    Profile("profile"),
}

@Composable
fun OrbitApp(
    application: OrbitApplication,
    startDailyGuide: Boolean,
    onDailyGuideConsumed: () -> Unit,
    pendingStatusRequestId: String? = null,
    onStatusRequestConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry.value?.destination?.route
    val activity = LocalContext.current as ComponentActivity
    var dailyGuideActive by rememberSaveable { mutableStateOf(false) }

    val trustedContactsViewModel: TrustedContactsViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = TrustedContactsViewModel.Factory(application.sharingRepository),
    )

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(startDailyGuide) {
        if (startDailyGuide) {
            dailyGuideActive = true
            navController.currentBackStackEntryFlow.first()
            navigateToTab(OrbitDestination.CheckIn.route)
            onDailyGuideConsumed()
        }
    }

    LaunchedEffect(pendingStatusRequestId) {
        val requestId = pendingStatusRequestId ?: return@LaunchedEffect
        trustedContactsViewModel.loadPendingRequest(requestId)
        navigateToTab(OrbitDestination.Profile.route)
        onStatusRequestConsumed()
    }

    val destinations = listOf(
        OrbitDestination.CheckIn,
        OrbitDestination.Activity,
        OrbitDestination.Exercise,
        OrbitDestination.Diet,
        OrbitDestination.Use,
        OrbitDestination.Profile,
    )

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (dailyGuideActive) {
                DailyGuideBanner(
                    currentRoute = currentRoute,
                    onContinue = {
                        if (DailyGuide.isLast(currentRoute)) {
                            dailyGuideActive = false
                        } else {
                            val next = DailyGuide.nextAfter(currentRoute)
                                ?: DailyGuide.steps.first()
                            navigateToTab(next.route)
                        }
                    },
                    onExit = { dailyGuideActive = false },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateToTab(destination.route) },
                        icon = {
                            when (destination) {
                                OrbitDestination.CheckIn ->
                                    Icon(Icons.Default.Today, contentDescription = null)
                                OrbitDestination.Activity ->
                                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null)
                                OrbitDestination.Diet ->
                                    Icon(Icons.Default.Restaurant, contentDescription = null)
                                OrbitDestination.Exercise ->
                                    Icon(Icons.Default.FitnessCenter, contentDescription = null)
                                OrbitDestination.Use ->
                                    Icon(Icons.Default.Medication, contentDescription = null)
                                OrbitDestination.Profile ->
                                    Icon(Icons.Default.Person, contentDescription = null)
                            }
                        },
                        label = {
                            Text(
                                text = when (destination) {
                                    OrbitDestination.CheckIn -> stringResource(R.string.tab_check_in)
                                    OrbitDestination.Activity -> stringResource(R.string.tab_activity)
                                    OrbitDestination.Diet -> stringResource(R.string.tab_diet)
                                    OrbitDestination.Exercise -> stringResource(R.string.tab_exercise)
                                    OrbitDestination.Use -> stringResource(R.string.tab_use)
                                    OrbitDestination.Profile -> stringResource(R.string.tab_profile)
                                },
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = OrbitDestination.CheckIn.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(OrbitDestination.CheckIn.route) {
                val viewModel: CheckInViewModel = viewModel(
                    factory = CheckInViewModel.Factory(
                        moodRepository = application.moodRepository,
                        sleepRepository = application.sleepRepository,
                        currentMoodStore = application.currentMoodStore,
                        biometricRepository = application.biometricRepository,
                    ),
                )
                CheckInScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(OrbitDestination.Activity.route) {
                val viewModel: ActivityViewModel = viewModel(
                    factory = ActivityViewModel.Factory(
                        activityRepository = application.activityRepository,
                        currentMoodStore = application.currentMoodStore,
                    ),
                )
                ActivityScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(OrbitDestination.Diet.route) {
                val viewModel: DietViewModel = viewModel(
                    factory = DietViewModel.Factory(application.dietRepository),
                )
                DietScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(OrbitDestination.Exercise.route) {
                val viewModel: ExerciseViewModel = viewModel(
                    factory = ExerciseViewModel.Factory(
                        repository = application.exerciseRepository,
                        currentMoodStore = application.currentMoodStore,
                    ),
                )
                ExerciseScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(OrbitDestination.Use.route) {
                val viewModel: UseViewModel = viewModel(
                    factory = UseViewModel.Factory(
                        repository = application.substanceRepository,
                        currentMoodStore = application.currentMoodStore,
                    ),
                )
                UseScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(OrbitDestination.Profile.route) {
                val listsViewModel: MyListsViewModel = viewModel(
                    factory = MyListsViewModel.Factory(
                        substanceRepository = application.substanceRepository,
                        dietRepository = application.dietRepository,
                        activityRepository = application.activityRepository,
                        exerciseRepository = application.exerciseRepository,
                        moodRepository = application.moodRepository,
                    ),
                )
                val journalViewModel: JournalViewModel = viewModel(
                    factory = JournalViewModel.Factory(application.journalRepository),
                )
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = HistoryViewModel.Factory(
                        historyRepository = application.historyRepository,
                        journalRepository = application.journalRepository,
                    ),
                )
                val trendsViewModel: TrendsViewModel = viewModel(
                    factory = TrendsViewModel.Factory(application.moodRepository),
                )
                val healthConnectViewModel: HealthConnectViewModel = viewModel(
                    factory = HealthConnectViewModel.Factory(
                        biometricRepository = application.biometricRepository,
                        topologyHarness = application.topologyHarness,
                    ),
                )
                ProfileScreen(
                    listsViewModel = listsViewModel,
                    trustedContactsViewModel = trustedContactsViewModel,
                    journalViewModel = journalViewModel,
                    historyViewModel = historyViewModel,
                    trendsViewModel = trendsViewModel,
                    healthConnectViewModel = healthConnectViewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}
