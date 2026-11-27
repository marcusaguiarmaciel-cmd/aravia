package app.aravia

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.aravia.account.Account
import app.aravia.account.AccountFlow
import app.aravia.account.FirebaseSetup
import app.aravia.arara.AraraNest
import app.aravia.arara.AraraShapes
import app.aravia.band.ActivityStore
import app.aravia.band.BandLink
import app.aravia.band.BandSettings
import app.aravia.band.BandVault
import app.aravia.band.FindPhone
import app.aravia.health.ProfileStore
import app.aravia.integrations.Strava
import app.aravia.routes.RouteStore
import app.aravia.training.TrainingStore
import app.aravia.training.WorkoutController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aravia.account.LegalDialog
import app.aravia.band.StandardHrLink
import app.aravia.health.HealthConnectBridge
import app.aravia.health.HealthConnectStore
import app.aravia.onboarding.QuickStart
import app.aravia.onboarding.QuickStartView
import app.aravia.ui.Appearance
import app.aravia.ui.AraviaTheme

class MainActivity : ComponentActivity() {
    private var showHealthPrivacyRationale by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FirebaseSetup.start(this)
        app.aravia.account.SupporterAccess.init(this)
        app.aravia.account.RewardedPremium.init(this)
        AraraShapes.load(this)
        AraraNest.init(this)
        app.aravia.arara.AraraCare.init(this)
        Appearance.init(this)
        ProfileStore.init(this)
        BandVault.init(this)
        QuickStart.init(this)
        TrainingStore.init(this)
        RouteStore.init(this)
        app.aravia.routes.RoutePrivacy.init(this)
        BandLink.init(this)
        StandardHrLink.init(this)
        ActivityStore.init(this)
        HealthConnectStore.init(this)
        BandSettings.init(this)
        FindPhone.init(this)
        WorkoutController.init(this)
        Strava.init(this)
        Demo.read(intent)
        BandSettings.updateHold()
        handleIntent(intent)
        if (!Demo.active) {
            lifecycleScope.launch { HealthConnectBridge.syncIfPermitted(applicationContext) }
        }
        setContent {
            AraviaTheme {
                val account by Account.state.collectAsState()
                LaunchedEffect(Unit) { Account.restore() }
                // The app opens only for a signed-in account with its e-mail confirmed and the
                // current terms accepted, as on iOS.
                val uid = account.uid
                when {
                    // Emulator screenshots of a debug build (Demo.kt) skip the sign-in.
                    Demo.active -> MainTabs()
                    !account.ready || uid == null -> AccountFlow()
                    // The first sign-in on this phone goes through the Quick Start once.
                    QuickStart.shows(uid) -> QuickStartView(uid)
                    else -> MainTabs()
                }
                if (showHealthPrivacyRationale) {
                    LegalDialog("privacy") { showHealthPrivacyRationale = false }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        if (action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            action == "android.intent.action.VIEW_PERMISSION_USAGE"
        ) {
            showHealthPrivacyRationale = true
            return
        }
        if (intent?.getBooleanExtra(FindPhone.EXTRA_STOP, false) == true) {
            FindPhone.stop()
            return
        }
        handleLink(intent?.data)
    }

    /** aravia://ninho/CODE (a nest invitation) or aravia://localhost/strava (Strava's answer). */
    private fun handleLink(uri: android.net.Uri?) {
        if (Strava.isCallback(uri)) lifecycleScope.launch { Strava.finishSignIn(uri!!) }
        else AraraNest.handleInvite(uri)
    }
}
