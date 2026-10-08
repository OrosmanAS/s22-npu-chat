package com.npuchat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.npuchat.log.AppLog

/**
 * Lives in the ":restart" process. It kills the main process (whose NPU state cannot be reset)
 * and launches the app again in a fresh one.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_PID, -1)
        if (mainPid > 0) Process.killProcess(mainPid)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PID = "main_pid"

        fun restartApp(context: Context) {
            AppLog.i("App", "restarting app process")
            // Only our own pid gets killed below; stop the CPU model server so it can't linger.
            com.npuchat.engine.LlamaServer.stop()
            val intent = Intent(context, RestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PID, Process.myPid())
            context.startActivity(intent)
        }
    }
}
