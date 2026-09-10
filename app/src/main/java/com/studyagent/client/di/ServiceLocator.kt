package com.studyagent.client.di

import android.content.Context

object ServiceLocator {
    private var container: AppContainer? = null

    fun initialize(context: Context) {
        if (container == null) {
            container = DefaultAppContainer(context.applicationContext)
        }
    }

    val appContainer: AppContainer
        get() = checkNotNull(container) {
            "ServiceLocator not initialized. Call ServiceLocator.initialize(context) in Application.onCreate()"
        }

    fun setTestContainer(testContainer: AppContainer?) {
        container = testContainer
    }
}
