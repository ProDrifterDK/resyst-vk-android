package com.resyst.vk.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * DownloadManager's ACTION_DOWNLOAD_COMPLETE for the update download. Exported because the
 * sender is the system downloads provider; a forged broadcast can only make [Updater] query
 * DownloadManager for its own pending id, and the bytes are SHA-256-verified anyway.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        Updater.onDownloadComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
    }
}
