package com.whatsapp.webclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.whatsapp.webclient.UpdateWorkScheduler

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, scheduling update check")
            UpdateWorkScheduler.schedule(context)
        }
    }
}
