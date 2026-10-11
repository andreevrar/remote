package app.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Запускает фоновую службу при включении ТВ. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, ControlService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
