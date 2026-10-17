package com.whatsapp.webclient

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val info = applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
            Log.d(TAG, "Update check: v${info.versionName}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
    }
}
