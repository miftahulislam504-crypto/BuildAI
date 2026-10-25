package com.buildx.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.buildx.ai.wake.ModelManager

/** Invisible Activity opened by the Quick Settings tile. Starts WakeService, then closes itself. */
class StartListeningActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasModel = ModelManager.isReady(this)
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (hasModel && hasMic) {
            startForegroundService(Intent(this, WakeService::class.java))
        } else {
            LogStore.add(this, "Could not start from the tile: open the app, download the model and allow the microphone")
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
