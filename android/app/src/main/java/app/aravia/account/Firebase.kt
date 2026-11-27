package app.aravia.account

import android.content.Context
import app.aravia.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** The same Firebase project as the iOS app (aravia-7b078), set up from build values. */
object FirebaseSetup {
    val isConfigured: Boolean get() = BuildConfig.FIREBASE_API_KEY.isNotEmpty() && BuildConfig.FIREBASE_APP_ID.isNotEmpty()

    fun start(context: Context) {
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
    }
}
