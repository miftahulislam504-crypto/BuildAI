package com.buildx.ai.photo

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaActionSound
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.buildx.ai.LogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes one photo: shows the camera for 3 seconds (time to aim), counts down, shoots, saves to Pictures/BuildX.
 * Appears over the lock screen and turns the screen on (like a secure camera); it never unlocks anything.
 * Portrait only. Must be started while the screen can show it (the launcher helper wakes the screen).
 */
class PhotoActivity : Activity(), TextureView.SurfaceTextureListener {

    private val ui = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("buildx-photo").also { it.start() }
    private val bg = Handler(thread.looper)

    private lateinit var textureView: TextureView
    private lateinit var countdown: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var sensorOrientation = 90
    private var front = false
    private var bn = true
    @Volatile private var done = false

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        front = intent.getStringExtra("camera") == "front"
        bn = intent.getBooleanExtra("bn", true)

        val dm = resources.displayMetrics
        textureView = TextureView(this)
        countdown = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
        }
        val hint = TextView(this).apply {
            text = t("ক্যামেরার দিকে তাকান…", "Look at the camera…")
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(textureView, FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels, Gravity.CENTER))
            addView(countdown, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = (48 * dm.density).toInt() })
        }
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            end(false, t("ক্যামেরার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন", "No camera permission. Open the app and allow it"))
            return
        }
        textureView.surfaceTextureListener = this
        ui.postDelayed({ end(false, t("ছবি তোলা শেষ করা গেল না (সময় শেষ)", "Couldn't finish the photo (timed out)")) }, 20_000)
    }

    // ---------- camera ----------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        try {
            openCamera(st)
        } catch (e: Exception) {
            LogStore.add(this, "PHOTO খোলা যায়নি: ${e.javaClass.simpleName}: ${e.message}")
            end(false, t("ক্যামেরা খোলা যায়নি", "Couldn't open the camera"))
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun pickCamera(mgr: CameraManager): String? {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: mgr.cameraIdList.firstOrNull()
    }

    private fun area(s: Size) = s.width.toLong() * s.height

    private fun pickJpeg(sizes: Array<Size>): Size =
        sizes.filter { area(it) <= 12_000_000L }.maxByOrNull { area(it) } ?: sizes.minByOrNull { area(it) } ?: sizes[0]

    private fun pickPreview(sizes: Array<Size>, jpeg: Size): Size {
        val target = jpeg.width.toDouble() / jpeg.height
        val small = sizes.filter { area(it) <= 1280L * 960 }
        return small.filter { Math.abs(it.width.toDouble() / it.height - target) < 0.02 }.maxByOrNull { area(it) }
            ?: small.maxByOrNull { area(it) } ?: sizes[0]
    }

    private fun openCamera(st: SurfaceTexture) {
        val mgr = getSystemService(CameraManager::class.java)
        val id = pickCamera(mgr) ?: return end(false, t("এই ফোনে ক্যামেরা পাইনি", "No camera found"))
        val chars = mgr.getCameraCharacteristics(id)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return end(false, t("ক্যামেরার তথ্য পাইনি", "No camera configuration"))
        val jpeg = pickJpeg(map.getOutputSizes(ImageFormat.JPEG))
        val preview = pickPreview(map.getOutputSizes(SurfaceTexture::class.java), jpeg)

        // portrait screen: the sensor is landscape, so the view's shape is the preview's height:width
        val w = resources.displayMetrics.widthPixels
        val h = (w * preview.width.toFloat() / preview.height).toInt()
        textureView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        st.setDefaultBufferSize(preview.width, preview.height)
        previewSurface = Surface(st)

        reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 1).also {
            it.setOnImageAvailableListener({ r -> onImage(r) }, bg)
        }
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cd: CameraDevice) {
                camera = cd
                startSession(cd)
            }

            override fun onDisconnected(cd: CameraDevice) {
                cd.close()
                end(false, t("ক্যামেরা বিচ্ছিন্ন হয়ে গেল", "The camera disconnected"))
            }

            override fun onError(cd: CameraDevice, error: Int) {
                cd.close()
                end(false, if (error == ERROR_CAMERA_IN_USE) t("ক্যামেরা অন্য অ্যাপ ব্যবহার করছে", "The camera is in use by another app") else t("ক্যামেরা খোলা যায়নি", "Couldn't open the camera"))
            }
        }, bg)
    }

    @Suppress("DEPRECATION")
    private fun startSession(cd: CameraDevice) {
        val surfaces = listOfNotNull(previewSurface, reader?.surface)
        try {
            cd.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface!!)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        s.setRepeatingRequest(req.build(), null, bg)
                        ui.post { tick(3) }
                    } catch (e: CameraAccessException) {
                        end(false, t("ক্যামেরা চালু করা যায়নি", "Couldn't start the camera"))
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    end(false, t("ক্যামেরা সেট করা যায়নি", "Couldn't set up the camera"))
                }
            }, bg)
        } catch (e: Exception) {
            end(false, t("ক্যামেরা সেট করা যায়নি", "Couldn't set up the camera"))
        }
    }

    /** 3, 2, 1, shoot: time for the person to aim (the preview is on screen) */
    private fun tick(n: Int) {
        if (done) return
        if (n == 0) {
            countdown.text = ""
            shoot()
        } else {
            countdown.text = if (bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()
            ui.postDelayed({ tick(n - 1) }, 1000)
        }
    }

    private fun shoot() {
        val cd = camera
        val s = session
        val r = reader
        if (cd == null || s == null || r == null) return end(false, t("ছবি তোলা যায়নি", "Couldn't take the photo"))
        try {
            val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation) // portrait screen
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            s.capture(req.build(), null, bg)
        } catch (e: Exception) {
            end(false, t("ছবি তোলা যায়নি", "Couldn't take the photo"))
        }
    }

    private fun onImage(r: ImageReader) {
        if (done) return
        val image = r.acquireLatestImage() ?: return
        val bytes = try {
            val buf = image.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            image.close()
        }
        val name = save(bytes)
        ui.post { MediaActionSound().play(MediaActionSound.SHUTTER_CLICK) }
        if (name != null) end(true, t("ছবি তোলা হয়েছে, গ্যালারির BuildX ফোল্ডারে", "Photo taken, saved in the BuildX folder"))
        else end(false, t("ছবি সেভ করা যায়নি", "Couldn't save the photo"))
    }

    private fun save(bytes: ByteArray): String? = try {
        val name = "BuildX_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BuildX")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            null
        } else {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            LogStore.add(this, "PHOTO সেভ: Pictures/BuildX/$name (${bytes.size / 1024} KB)")
            name
        }
    } catch (e: Exception) {
        LogStore.add(this, "PHOTO সেভ ব্যর্থ: ${e.message}")
        null
    }

    // ---------- finishing ----------

    private fun end(ok: Boolean, message: String) {
        if (done) return
        done = true
        PhotoBridge.finish(ok, message)
        ui.postDelayed({ finish() }, if (ok) 700 else 200)
    }

    override fun onStop() {
        super.onStop()
        if (!done) end(false, t("ছবি তোলা বাতিল হয়েছে", "The photo was cancelled"))
    }

    override fun onDestroy() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        thread.quitSafely()
        super.onDestroy()
    }
}
