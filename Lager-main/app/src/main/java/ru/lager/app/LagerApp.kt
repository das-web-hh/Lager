package ru.lager.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class LagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey(FirebaseConfig.API_KEY)
                .setApplicationId(FirebaseConfig.APP_ID)
                .setProjectId(FirebaseConfig.PROJECT_ID)
                .setStorageBucket(FirebaseConfig.STORAGE_BUCKET)
                .setGcmSenderId(FirebaseConfig.SENDER_ID)
                .build()
            FirebaseApp.initializeApp(this, options)
        }
    }
}
