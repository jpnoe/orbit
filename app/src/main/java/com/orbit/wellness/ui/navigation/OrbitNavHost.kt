package com.orbit.wellness.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.orbit.wellness.OrbitApplication
import com.orbit.wellness.R
import com.orbit.wellness.ui.checkin.CheckInLogScreen
import com.orbit.wellness.ui.checkin.CheckInScreen
import com.orbit.wellness.ui.checkin.CheckInViewModel
import com.orbit.wellness.ui.context.PendingCheckInSession
import com.orbit.wellness.ui.context.ContextOverviewScreen
import com.orbit.wellness.ui.context.ContextOverviewViewModel
import com.orbit.wellness.ui.context.ContextRoutes
import com.orbit.wellness.ui.context.ContextualActivityScreen
import com.orbit.wellness.ui.context.ContextualActivityViewModel
import com.orbit.wellness.ui.context.ContextualDietScreen
import com.orbit.wellness.ui.context.ContextualDietViewModel
import com.orbit.wellness.ui.context.ContextualUsageScreen
import com.orbit.wellness.ui.context.ContextualUsageViewModel
import com.orbit.wellness.ui.context.ContextualWorkoutScreen
import com.orbit.wellness.ui.context.ContextualWorkoutViewModel
import com.orbit.wellness.ui.util.CollectSnackbarMessages
import com.orbit.wellness.ui.util.showBriefMessage
import com.orbit.wellness.ui.history.HistoryScreen
import com.orbit.wellness.ui.history.HistoryViewModel
import com.orbit.wellness.ui.insights.InsightsScreen
import com.orbit.wellness.ui.insights.InsightsViewModel
import com.orbit.wellness.ui.profile.HealthConnectViewModel
import com.orbit.wellness.ui.profile.ProfileScreen
import com.orbit.wellness.ui.profile.TrustedContactsSection
import com.orbit.wellness.ui.profile.TrustedContactsViewModel
import com.orbit.wellness.ui.trends.TrendsViewModel
import com.orbit.wellness.ui.use.MyListsViewModel

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val checkInSavedMessage = stringResource(R.string.mood_saved)
    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry.value?.destination?.route
    val activity = LocalContext.current as ComponentActivity
    var dailyGuideActive by rememberSaveable { mutableStateOf(false) }

    val checkInViewModel: CheckInViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = CheckInViewModel.Factory(
            moodRepository = application.moodRepository,
            checkInRepository = application.checkInRepository,
            sleepRepository = application.sleepRepository,
            currentMoodStore = application.currentMoodStore,
            biometricRepository = application.biometricRepository,
        ),
    )
    CollectSnackbarMessages(
        messages = checkInViewModel.messages,
        snackbarHostState = snackbarHostState,
    )

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
        navController.navigate(OrbitNavigation.STATUS_REQUEST) {
            launchSingleTop = true
        }
        onStatusRequestConsumed()
    }

    val destinations = OrbitNavigation.topLevel

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
            if (!OrbitNavigation.hidesBottomBar(currentRoute)) NavigationBar {
                destinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateToTab(destination.route) },
                        icon = {
                            when (destination) {
                                OrbitDestination.CheckIn ->
                                    Icon(Icons.Default.Today, contentDescription = null)
                                OrbitDestination.History ->
                                    Icon(Icons.Default.History, contentDescription = null)
                                OrbitDestination.Insights ->
                                    Icon(Icons.Default.Insights, contentDescription = null)
                                OrbitDestination.Profile ->
                                    Icon(Icons.Default.Person, contentDescription = null)
                            }
                        },
                        label = {
                            Text(
                                text = when (destination) {
                                    OrbitDestination.CheckIn -> stringResource(R.string.tab_check_in)
                                    OrbitDestination.History -> stringResource(R.string.tab_history)
                                    OrbitDestination.Insights -> stringResource(R.string.tab_insights)
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
                CheckInScreen(
                    viewModel = checkInViewModel,
                    snackbarHostState = snackbarHostState,
                    onOpenLog = {
                        checkInViewModel.beginEntry()
                        navController.navigate(OrbitNavigation.CHECK_IN_LOG)
                    },
                )
            }
            composable(OrbitNavigation.CHECK_IN_LOG) {
                CheckInLogScreen(
                    viewModel = checkInViewModel,
                    snackbarHostState = snackbarHostState,
                    onOpenContext = { checkInId ->
                        navController.navigate(ContextRoutes.overview(checkInId)) {
                            launchSingleTop = true
                        }
                    },
                    onDiscarded = {
                        navController.popBackStack(OrbitNavigation.entryReturnsTo(), inclusive = false)
                    },
                )
            }
            composable(
                route = ContextRoutes.OVERVIEW,
                arguments = listOf(
                    navArgument(ContextRoutes.CHECK_IN_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                val checkInId = entry.arguments?.getString(ContextRoutes.CHECK_IN_ID).orEmpty()
                val viewModel: ContextOverviewViewModel = viewModel(
                    factory = ContextOverviewViewModel.Factory(
                        checkInId = checkInId,
                        checkInRepository = application.checkInRepository,
                        pending = checkInViewModel.pendingSession(),
                    ),
                )
                val checkInState by checkInViewModel.uiState.collectAsState()
                ContextOverviewScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    onDone = { navController.leaveContextWorkflow() },
                    onSave = {
                        if (checkInId == PendingCheckInSession.ID) {
                            checkInViewModel.commitEntry {
                                scope.launch {
                                    snackbarHostState.showBriefMessage(checkInSavedMessage)
                                }
                                navController.leaveContextWorkflow()
                            }
                        } else {
                            viewModel.finish()
                        }
                    },
                    onDiscard = {
                        checkInViewModel.discardEntry {
                            navController.popBackStack(OrbitNavigation.entryReturnsTo(), inclusive = false)
                        }
                    },
                    discarding = checkInState.isDiscarding,
                    saving = checkInState.isSaving,
                    onOpenActivity = {
                        navController.navigate(ContextRoutes.activity(checkInId))
                    },
                    onOpenWorkout = {
                        navController.navigate(ContextRoutes.workout(checkInId))
                    },
                    onOpenUsage = {
                        navController.navigate(ContextRoutes.usage(checkInId))
                    },
                    onOpenDiet = {
                        navController.navigate(ContextRoutes.diet(checkInId))
                    },
                )
            }
            composable(
                route = ContextRoutes.ACTIVITY,
                arguments = listOf(
                    navArgument(ContextRoutes.CHECK_IN_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                val checkInId = entry.arguments?.getString(ContextRoutes.CHECK_IN_ID).orEmpty()
                val viewModel: ContextualActivityViewModel = viewModel(
                    factory = ContextualActivityViewModel.Factory(
                        checkInId = checkInId,
                        checkInRepository = application.checkInRepository,
                        activityRepository = application.activityRepository,
                        pending = checkInViewModel.pendingSession(),
                    ),
                )
                ContextualActivityScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                    onFinished = { navController.popBackStack() },
                )
            }
            composable(
                route = ContextRoutes.WORKOUT,
                arguments = listOf(
                    navArgument(ContextRoutes.CHECK_IN_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                val checkInId = entry.arguments?.getString(ContextRoutes.CHECK_IN_ID).orEmpty()
                val viewModel: ContextualWorkoutViewModel = viewModel(
                    factory = ContextualWorkoutViewModel.Factory(
                        checkInId = checkInId,
                        checkInRepository = application.checkInRepository,
                        exerciseRepository = application.exerciseRepository,
                        pending = checkInViewModel.pendingSession(),
                    ),
                )
                ContextualWorkoutScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                    onFinished = { navController.popBackStack() },
                )
            }
            composable(
                route = ContextRoutes.USAGE,
                arguments = listOf(
                    navArgument(ContextRoutes.CHECK_IN_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                val checkInId = entry.arguments?.getString(ContextRoutes.CHECK_IN_ID).orEmpty()
                val viewModel: ContextualUsageViewModel = viewModel(
                    factory = ContextualUsageViewModel.Factory(
                        checkInId = checkInId,
                        checkInRepository = application.checkInRepository,
                        substanceRepository = application.substanceRepository,
                        pending = checkInViewModel.pendingSession(),
                    ),
                )
                ContextualUsageScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                    onFinished = { navController.popBackStack() },
                )
            }
            composable(
                route = ContextRoutes.DIET,
                arguments = listOf(
                    navArgument(ContextRoutes.CHECK_IN_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                val checkInId = entry.arguments?.getString(ContextRoutes.CHECK_IN_ID).orEmpty()
                val viewModel: ContextualDietViewModel = viewModel(
                    factory = ContextualDietViewModel.Factory(
                        checkInId = checkInId,
                        checkInRepository = application.checkInRepository,
                        dietRepository = application.dietRepository,
                        pending = checkInViewModel.pendingSession(),
                    ),
                )
                ContextualDietScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                    onFinished = { navController.popBackStack() },
                )
            }
            composable(OrbitDestination.History.route) {
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = HistoryViewModel.Factory(
                        historyRepository = application.historyRepository,
                        journalRepository = application.journalRepository,
                        checkInRepository = application.checkInRepository,
                    ),
                )
                val trendsViewModel: TrendsViewModel = viewModel(
                    factory = TrendsViewModel.Factory(application.moodRepository),
                )
                HistoryScreen(
                    historyViewModel = historyViewModel,
                    trendsViewModel = trendsViewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(OrbitDestination.Insights.route) {
                val insightsViewModel: InsightsViewModel = viewModel(
                    factory = InsightsViewModel.Factory(
                        repository = application.checkInAnalysisRepository,
                        wearableObservations = application.wearableObservationRepository,
                    ),
                )
                InsightsScreen(
                    viewModel = insightsViewModel,
                    onOpenLog = { navigateToTab(OrbitDestination.CheckIn.route) },
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
                val healthConnectViewModel: HealthConnectViewModel = viewModel(
                    factory = HealthConnectViewModel.Factory(
                        biometricRepository = application.biometricRepository,
                        wearableObservations = application.wearableObservationRepository,
                    ),
                )
                ProfileScreen(
                    listsViewModel = listsViewModel,
                    healthConnectViewModel = healthConnectViewModel,
                    snackbarHostState = snackbarHostState,
                )
            }
            composable(OrbitNavigation.STATUS_REQUEST) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TrustedContactsSection(
                        viewModel = trustedContactsViewModel,
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
            OrbitNavigation.retiredTopLevelRoutes.forEach { retiredRoute ->
                composable(retiredRoute) {
                    LaunchedEffect(retiredRoute) {
                        navController.navigate(OrbitNavigation.entryReturnsTo()) {
                            popUpTo(OrbitNavigation.entryReturnsTo()) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
        }
    }
}

/**
 * Returns to check-in and drops the finished context screens so Back does not reopen them.
 */
private fun NavController.leaveContextWorkflow() {
    val left = popBackStack(OrbitNavigation.entryReturnsTo(), inclusive = false)
    if (!left) {
        navigate(OrbitNavigation.entryReturnsTo()) {
            launchSingleTop = true
        }
    }
}
