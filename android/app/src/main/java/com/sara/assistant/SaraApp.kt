package com.sara.assistant

import android.app.Application
import com.sara.assistant.core.SaraContainer

/**
 * Application entry point. Builds the single dependency container that the rest of the
 * app reads from. Kept deliberately tiny: this is the foundation prompt, feature modules
 * (vision, memory, more subagents) attach here later.
 */
class SaraApp : Application() {

    lateinit var container: SaraContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = SaraContainer(this)
    }
}
