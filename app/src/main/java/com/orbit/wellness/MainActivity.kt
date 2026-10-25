package com.orbit.wellness

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.orbit.wellness.ui.navigation.OrbitApp
import com.orbit.wellness.ui.theme.OrbitTheme
import com.orbit.wellness.util.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val startDailyGuide = MutableStateFlow(false)
    private val pendingStatusRequestId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        consumeIntent(intent)

        val application = application as OrbitApplication

        setContent {
            val startGuide by startDailyGuide.collectAsState()
            val statusRequestId by pendingStatusRequestId.collectAsState()
            OrbitTheme {
                OrbitApp(
                    application = application,
                    startDailyGuide = startGuide,
                    onDailyGuideConsumed = { startDailyGuide.value = false },
                    pendingStatusRequestId = statusRequestId,
                    onStatusRequestConsumed = { pendingStatusRequestId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent.startsDailyGuide()) {
            startDailyGuide.value = true
        }
        intent.statusRequestId()?.let { pendingStatusRequestId.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        val permission = NotificationHelper.notificationPermission()
        if (permission != null) {
            notificationPermissionLauncher.launch(permission)
        }
    }
}

private fun Intent?.startsDailyGuide(): Boolean {
    if (this == null) return false
    return getBooleanExtra(NotificationHelper.EXTRA_START_DAILY_GUIDE, false) ||
        getBooleanExtra(NotificationHelper.EXTRA_NAVIGATE_TO_CHECK_IN, false)
}

private fun Intent?.statusRequestId(): String? {
    return this?.getStringExtra(NotificationHelper.EXTRA_STATUS_REQUEST_ID)
}
