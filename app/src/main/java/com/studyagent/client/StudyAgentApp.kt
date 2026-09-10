package com.studyagent.client

import android.app.Application
import com.studyagent.client.core.common.AppLogger
import com.studyagent.client.di.ServiceLocator

class StudyAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.i("StudyAgentApp", "Application initializing...")
        ServiceLocator.initialize(this)
    }
}
