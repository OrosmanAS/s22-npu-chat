package com.npuchat

import android.app.Application
import com.npuchat.engine.NpuEnv
import com.npuchat.log.AppLog
import com.npuchat.log.Diagnostics

class NpuChatApp : Application() {
    lateinit var settings: Settings
        private set

    /** True when the previous NPU init never returned (the process died inside it). */
    var lastInitCrashed = false
        private set

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(":restart")) return

        settings = Settings(this)
        AppLog.minLevel = settings.current.logLevel
        AppLog.init(this)
        AppLog.i(TAG, "==== app start ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) pid=${android.os.Process.myPid()} ====")

        // Must happen before any QNN library is loaded in this process.
        NpuEnv.configure(this)
        com.npuchat.engine.LlamaServer.killStale()

        lastInitCrashed = settings.initInProgress
        if (lastInitCrashed) {
            AppLog.e(TAG, "Previous NPU init did not return: the process died during Engine.initialize(). Auto-init disabled for this launch.")
            settings.initInProgress = false
        }
        AppLog.i(TAG, "previous exits:\n${Diagnostics.previousExits(this)}")
    }

    private companion object {
        const val TAG = "App"
    }
}
