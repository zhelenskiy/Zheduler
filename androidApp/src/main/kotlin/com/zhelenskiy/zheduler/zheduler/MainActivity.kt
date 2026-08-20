package com.zhelenskiy.zheduler.zheduler

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.zhelenskiy.zheduler.zheduler.di.initAndroidDependencies

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Already done in ZhedulerApplication; harmless, and keeps the activity working if it is
        // ever hosted by an application class that does not.
        initAndroidDependencies(application)
        askForNotificationPermission()

        setContent {
            App()
        }
    }

    /**
     * Asked for on the way in rather than at the moment a reminder is due, which would be while
     * the app is in the background and the dialog cannot be shown. Refusing it stops the alerts,
     * not the schedule: due dates still advance and recurrence still comes round.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
