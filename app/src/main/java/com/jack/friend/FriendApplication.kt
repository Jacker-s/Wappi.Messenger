package com.jack.friend

import android.app.Application
import com.cloudinary.android.MediaManager

class FriendApplication : Application() {
    
    companion object {
        lateinit var instance: FriendApplication
            private set
        var isAppInForeground: Boolean = false
        var currentOpenedChatId: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        val config = mapOf(
            "cloud_name" to "dagdvifyz",
            "api_key" to "515648516698279",
            "api_secret" to "CKubGcQuYFyGat2n5I0Q0eZi-QQ"
        )
        MediaManager.init(this, config)
    }
}
