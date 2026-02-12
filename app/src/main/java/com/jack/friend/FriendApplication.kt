package com.jack.friend

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.cloudinary.android.MediaManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FriendApplication : Application(), Application.ActivityLifecycleCallbacks {
    
    private var activityCount = 0
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    companion object {
        lateinit var instance: FriendApplication
            private set
        var isAppInForeground: Boolean = false
        var currentOpenedChatId: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)
        
        val config = mapOf(
            "cloud_name" to "dagdvifyz",
            "api_key" to "515648516698279",
            "api_secret" to "CKubGcQuYFyGat2n5I0Q0eZi-QQ"
        )
        MediaManager.init(this, config)
    }

    override fun onActivityStarted(activity: Activity) {
        activityCount++
        if (activityCount == 1) {
            isAppInForeground = true
            _isForeground.value = true
        }
    }

    override fun onActivityStopped(activity: Activity) {
        activityCount--
        if (activityCount == 0) {
            isAppInForeground = false
            _isForeground.value = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
