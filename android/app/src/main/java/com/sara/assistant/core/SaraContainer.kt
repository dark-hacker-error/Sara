package com.sara.assistant.core

import android.app.Application
import com.sara.assistant.agent.DelegationRouter
import com.sara.assistant.backend.BackendClient
import com.sara.assistant.data.SettingsRepository
import com.sara.assistant.live.LiveSessionManager
import com.sara.assistant.phone.AppRegistry
import com.sara.assistant.phone.PhoneController
import com.sara.assistant.safety.SafetyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency container. No DI framework on purpose: the graph is small and
 * explicit, which keeps the module boundaries obvious for the follow-up prompts.
 */
class SaraContainer(app: Application) {

    /** Application-lifetime scope for anything that outlives a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository by lazy { SettingsRepository(app) }

    val phone: PhoneController by lazy { PhoneController(app) }

    val appRegistry: AppRegistry by lazy { AppRegistry(app) }

    val safety: SafetyGuard by lazy { SafetyGuard() }

    val backend: BackendClient by lazy { BackendClient(settings) }

    val router: DelegationRouter by lazy {
        DelegationRouter(
            backend = backend,
            phone = phone,
            safety = safety,
            scope = scope,
        )
    }

    val live: LiveSessionManager by lazy {
        LiveSessionManager(
            context = app,
            settings = settings,
            safety = safety,
            tools = router,
        )
    }
}
