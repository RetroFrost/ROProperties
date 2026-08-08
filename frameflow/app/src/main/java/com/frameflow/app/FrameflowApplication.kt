package com.frameflow.app

import android.app.Application
import android.content.Context

class FrameflowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
