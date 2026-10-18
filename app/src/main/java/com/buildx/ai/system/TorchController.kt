package com.buildx.ai.system

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** Camera flash as a torch. No permission needed; works from the background service and with the screen off. */
object TorchController {

    private var manager: CameraManager? = null
    private var torchId: String? = null
    private val states = HashMap<String, Boolean>()
    private var registered = false

    /** the phone's own report of the torch, null until the first report arrives */
    val isOn: Boolean? get() = torchId?.let { states[it] }

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            states[cameraId] = enabled
        }
    }

    private fun init(ctx: Context): Boolean {
        if (torchId != null) return true
        val m = ctx.applicationContext.getSystemService(CameraManager::class.java) ?: return false
        manager = m
        var fallback: String? = null
        for (id in m.cameraIdList) {
            val c = m.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                torchId = id
                break
            }
            if (fallback == null) fallback = id
        }
        if (torchId == null) torchId = fallback
        if (torchId != null && !registered) {
            m.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
        }
        return torchId != null
    }

    /** null = ok, otherwise a short reason */
    fun set(ctx: Context, on: Boolean): String? {
        return try {
            if (!init(ctx)) return "no_flash"
            manager!!.setTorchMode(torchId!!, on)
            null
        } catch (e: CameraAccessException) {
            "in_use"
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
    }

    /** current state for "toggle"; asks the camera service once if no report has arrived yet */
    fun current(ctx: Context): Boolean? {
        init(ctx)
        return isOn
    }
}
