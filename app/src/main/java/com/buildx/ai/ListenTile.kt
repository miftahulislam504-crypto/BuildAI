package com.buildx.ai

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile. Android 14 does not let an app start a microphone foreground service from the
 * background, so a tap on the tile opens a transparent Activity that starts the service from the
 * foreground. Stopping is done directly.
 */
class ListenTile : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (WakeService.running) {
            startService(Intent(this, WakeService::class.java).setAction(WakeService.ACTION_STOP))
        } else {
            launchStarter()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (WakeService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "BuildX"
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun launchStarter() {
        val intent = Intent(this, StartListeningActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
