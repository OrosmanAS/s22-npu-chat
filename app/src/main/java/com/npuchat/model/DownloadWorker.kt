package com.npuchat.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.npuchat.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a model from Hugging Face as a foreground job (survives leaving the app), resuming a
 * partial file with an HTTP Range request, then verifies SHA-256 before the file is used.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val variant = ModelVariant.valueOf(inputData.getString(KEY_VARIANT)!!)
        val ctx = applicationContext
        val part = ModelStore.partFile(ctx, variant)
        val dest = ModelStore.file(ctx, variant)
        AppLog.i(TAG, "download start ${variant.fileName} attempt=$runAttemptCount have=${part.length()}")
        setForeground(foregroundInfo(variant, "Starting…", 0, 0))

        try {
            if (part.length() < variant.sizeBytes) download(variant, part)
            ensureActive()

            setProgress(workDataOf(KEY_PHASE to PHASE_VERIFY, KEY_DONE to 0L, KEY_TOTAL to variant.sizeBytes))
            AppLog.i(TAG, "verifying SHA-256 of ${part.length()} bytes")
            var lastNotify = 0L
            val sha = part.inputStream().use { input ->
                ModelStore.sha256(input) { done ->
                    setProgressAsync(workDataOf(KEY_PHASE to PHASE_VERIFY, KEY_DONE to done, KEY_TOTAL to variant.sizeBytes))
                    if (done - lastNotify > 128L shl 20) {
                        lastNotify = done
                        notify(variant, "Verifying", done, variant.sizeBytes)
                    }
                }
            }
            if (!sha.equals(variant.sha256, ignoreCase = true)) {
                AppLog.e(TAG, "SHA-256 mismatch: got $sha expected ${variant.sha256}; deleting partial file")
                part.delete()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "SHA-256 mismatch (got ${sha.take(12)}…). File deleted, try again."))
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename ${part.name} -> ${dest.name} failed")
            ModelStore.markVerified(ctx, variant)
            AppLog.i(TAG, "download complete and verified: ${dest.absolutePath}")
            Result.success()
        } catch (e: IOException) {
            AppLog.w(TAG, "download interrupted at ${part.length()} bytes (attempt $runAttemptCount), will resume", e)
            if (runAttemptCount < 20) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to "Download failed: ${e.message}"))
        }
    }

    private suspend fun download(variant: ModelVariant, part: java.io.File) {
        val have = part.length()
        val conn = (URL(variant.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "s22-npu-chat")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            AppLog.i(TAG, "HTTP $code from ${conn.url.host}, content-length=${conn.contentLengthLong}, resume-from=$have")
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                416 -> { AppLog.w(TAG, "HTTP 416, restarting from zero"); part.delete(); throw IOException("range not satisfiable") }
                else -> throw IOException("HTTP $code")
            }
            var done = if (append) have else 0L
            val total = variant.sizeBytes
            var lastUpdate = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1000) {
                            lastUpdate = now
                            setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOAD, KEY_DONE to done, KEY_TOTAL to total))
                            notify(variant, "Downloading", done, total)
                        }
                    }
                }
            }
            AppLog.i(TAG, "stream ended at $done / $total bytes")
            if (done < total) throw IOException("stream ended early at $done of $total")
        } finally {
            conn.disconnect()
        }
    }

    private fun notify(variant: ModelVariant, phase: String, done: Long, total: Long) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(variant, phase, done, total))
    }

    private fun foregroundInfo(variant: ModelVariant, phase: String, done: Long, total: Long) =
        ForegroundInfo(NOTIFICATION_ID, buildNotification(variant, phase, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun buildNotification(variant: ModelVariant, phase: String, done: Long, total: Long): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("$phase ${variant.fileName}")
            .setContentText("%.2f / %.2f GB".format(done / 1e9, total / 1e9))
            .setProgress(100, pct, total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "Download"
        private const val CHANNEL = "download"
        private const val NOTIFICATION_ID = 42
        const val WORK_NAME = "model-download"
        const val KEY_VARIANT = "variant"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_VERIFY = "verify"

        fun start(context: Context, variant: ModelVariant) {
            AppLog.i(TAG, "enqueue download of ${variant.name}")
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_VARIANT to variant.name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(variant.name)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            AppLog.i(TAG, "cancel download")
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
