package com.dlang.homewx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dlang.homewx.MainActivity

/** Launches [MainActivity] once the device finishes booting, so this always-on wall-mounted
 *  tablet comes back up on the dashboard instead of sitting on the home screen after a reboot.
 *  [HomeWxMonitorService] doesn't need starting here separately - MainActivity.onCreate already
 *  starts it. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
