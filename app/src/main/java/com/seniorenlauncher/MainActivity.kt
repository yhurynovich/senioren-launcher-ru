package com.seniorenlauncher
import com.seniorenlauncher.R
import com.seniorenlauncher.util.i18n

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seniorenlauncher.ui.AppNavigation
import com.seniorenlauncher.ui.components.RadioMiniPlayer
import com.seniorenlauncher.ui.screens.*
import com.seniorenlauncher.ui.theme.SeniorenLauncherTheme
import com.seniorenlauncher.ui.screens.home.*
import com.seniorenlauncher.service.SeniorInCallService
import com.seniorenlauncher.util.UpdateManager
import android.telecom.Call
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.seniorenlauncher.service.StepCounterService

class MainActivity : ComponentActivity() {
    
    private var currentAlarmId = mutableStateOf<Long>(-1L)
    private var alarmTriggered = mutableStateOf<String?>(null)
    private var alarmSoundUri = mutableStateOf<String?>(null)
    private var alarmIsRemote = mutableStateOf(false)
    private var navigateToSmsAddress = mutableStateOf<String?>(null)
    private var navigateToIncomingCall = mutableStateOf(false)
    private var navigateToWeatherAfterAlarm = mutableStateOf(false)
    private var fallDetectedTriggered = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.seniorenlauncher.util.LocaleHelper.setLocale(newBase, com.seniorenlauncher.util.LocaleHelper.saved(newBase)))
    }

    /**
     * The home screen shows its own clock, battery, Wi-Fi and notification bell, so the system
     * status bar is hidden. A swipe down from the top edge shows it briefly (and the notification
     * shade still opens with a second swipe).
     */
    private fun hideStatusBar() {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the keyboard or other apps can bring the bar back; hide it again when we regain focus.
        if (hasFocus) hideStatusBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.getStringExtra("NAVIGATE_TO") == "alarm_trigger" || intent?.getStringExtra("NAVIGATE_TO") == "fall_detected") {
            setupLockscreenBypass()
        }
        
        super.onCreate(savedInstanceState)
        
        // Android 15/16 Edge-to-Edge ondersteuning
        // Light status icons on a dark backing, so the bar is readable when it is swiped into view
        // (with the default style a phone in light mode drew dark icons on our dark background).
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(0xCC000000.toInt()))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        hideStatusBar()
        
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Predictive Back support: do not intercept if there's no custom UI showing
                if (alarmTriggered.value == null) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            val settingsVm: SettingsViewModel = viewModel()
            val radioVm: RadioViewModel = viewModel()
            val settings by settingsVm.settings.collectAsState()
            
            val triggerLabel by alarmTriggered
            val alarmId by currentAlarmId
            val soundUri by alarmSoundUri
            val isRemote by alarmIsRemote
            val smsAddress by navigateToSmsAddress
            val showIncomingCall by navigateToIncomingCall
            val shouldNavToWeather by navigateToWeatherAfterAlarm
            val isFallDetected by fallDetectedTriggered
            
            val currentCall by SeniorInCallService.currentCall.collectAsState()
            
            var currentRoute by remember { mutableStateOf<String?>(null) }

            // Auto-update check bij opstarten
            LaunchedEffect(Unit) {
                val updateManager = UpdateManager(this@MainActivity)
                val release = updateManager.checkForUpdates()
                if (release != null) {
                    updateManager.downloadAndInstall(release)
                }

                // Start stappenteller als we de permissie hebben
                if (hasStepPermission()) {
                    startForegroundService(Intent(this@MainActivity, StepCounterService::class.java))
                }
            }

            if (triggerLabel != null) {
                LaunchedEffect(Unit) {
                    setupLockscreenBypass()
                }
            }

            SeniorenLauncherTheme(appTheme = settings.theme, fontSize = settings.fontSize) {
                // The status bar is hidden. The main app uses the top area itself and only moves what would
                // sit under the camera hole (see rememberTopCutout); the setup wizard and the full-screen
                // alarm/fall screens keep a plain band clear of the cutout.
                val clearCutoutBand = isFallDetected || triggerLabel != null || !settings.hasCompletedSetup
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { Box(
                    if (clearCutoutBand) Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                    else Modifier.fillMaxSize()
                ) {
                    if (isFallDetected) {
                        FallDetectionDialog(
                            onConfirm = {
                                fallDetectedTriggered.value = false
                                val intent = Intent(this@MainActivity, com.seniorenlauncher.service.SOSService::class.java)
                                startForegroundService(intent)
                            },
                            onCancel = {
                                fallDetectedTriggered.value = false
                            }
                        )
                    } else if (triggerLabel != null) {
                        AlarmTriggerScreen(
                            label = triggerLabel!!,
                            alarmId = alarmId,
                            soundName = soundUri,
                            isForcedRemote = isRemote,
                            onDismiss = {
                                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                nm.cancel(alarmId.toInt())
                                if (triggerLabel!!.startsWith("Medicijn:")) {
                                    nm.cancel(alarmId.toInt() + 5000)
                                }
                                alarmTriggered.value = null
                                alarmSoundUri.value = null
                                alarmIsRemote.value = false
                                currentAlarmId.value = -1L
                            },
                            onNavigateToWeather = {
                                navigateToWeatherAfterAlarm.value = true
                            }
                        )
                    } else if (!settings.hasCompletedSetup) {
                        SetupWizardScreen(
                            onFinished = { settingsVm.completeSetup() },
                            settingsVm = settingsVm
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                        ) {
                            Box(Modifier.weight(1f)) {
                                AppNavigation(
                                    settingsVm = settingsVm, 
                                    radioVm = radioVm, 
                                    initialSmsAddress = smsAddress,
                                    initialIncomingCall = showIncomingCall || (currentCall != null && currentCall?.state == Call.STATE_RINGING),
                                    initialWeatherNav = shouldNavToWeather,
                                    onNavigatedToSms = { navigateToSmsAddress.value = null },
                                    onNavigatedToCall = { navigateToIncomingCall.value = false },
                                    onNavigatedToWeather = { navigateToWeatherAfterAlarm.value = false },
                                    onRouteChanged = { currentRoute = it }
                                )
                            }
                            if (currentRoute != "radio") {
                                RadioMiniPlayer(
                                    radioVm = radioVm,
                                    onOpenRadio = {
                                        // Optioneel: Navigeer naar radio
                                    }
                                )
                            }
                        }
                    }
                } }
            }
        }
    }

    private fun setupLockscreenBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("NAVIGATE_TO") == "alarm_trigger" || intent.getStringExtra("NAVIGATE_TO") == "fall_detected") {
            setupLockscreenBypass()
        }
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val navigateTo = it.getStringExtra("NAVIGATE_TO")
            if (navigateTo == "alarm_trigger") {
                val label = it.getStringExtra("ALARM_LABEL") ?: i18n(R.string.wekker)
                val id = it.getLongExtra("ALARM_ID", -1L)
                val isRemote = it.getStringExtra("agendaTitle") == "BERICHT VAN BEHEERDER"
                
                Log.d("MainActivity", "Wekker ontvangen: $label met ID: $id (Remote: $isRemote)")
                alarmTriggered.value = label
                currentAlarmId.value = id
                alarmIsRemote.value = isRemote
                alarmSoundUri.value = it.getStringExtra("ALARM_SOUND")
            } else if (navigateTo == "fall_detected") {
                fallDetectedTriggered.value = true
            } else if (navigateTo == "sms") {
                navigateToSmsAddress.value = it.getStringExtra("SMS_ADDRESS")
            } else if (navigateTo == "incoming_call") {
                navigateToIncomingCall.value = true
            }
        }
    }

    private fun hasStepPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true
    }
}
