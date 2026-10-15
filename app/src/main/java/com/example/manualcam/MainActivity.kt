package com.example.manualcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.camera2.*
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var textureView: AutoFitTextureView
    private lateinit var isoSeek: SeekBar
    private lateinit var shutterSeek: SeekBar
    private lateinit var brightSeek: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var brightLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var autoBtn: Button
    private lateinit var thumb: ImageView
    private var lastUri: Uri? = null
    private val amber = Color.parseColor("#FFB300")

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val ui = Handler(Looper.getMainLooper())

    private var cameraReady = false
    private var cameraId = ""
    private var sensorOrientation = 90
    private var previewSize = Size(1280, 960)
    private var jpegSize = Size(1280, 960)
    private var isoMin = 100
    private var isoMax = 800
    private var expMin = 1_000_000L
    private var expMax = 1_000_000_000L

    @Volatile private var iso = 100
    @Volatile private var exposureNs = 16_666_666L
    @Volatile private var brightness = 0 // -100..+100  => -2..+2 EV

    // Zoom / déplacement (recadrage numérique du capteur)
    private var activeRect = Rect(0, 0, 4000, 3000)
    private var maxZoom = 1f
    @Volatile private var zoom = 1f
    @Volatile private var centerX = 2000f
    @Volatile private var centerY = 1500f
    private var lastX = 0f
    private var lastY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector

    // Mode automatique
    private var autoOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.texture)
        isoSeek = findViewById(R.id.isoSeek)
        shutterSeek = findViewById(R.id.shutterSeek)
        brightSeek = findViewById(R.id.brightSeek)
        isoLabel = findViewById(R.id.isoLabel)
        shutterLabel = findViewById(R.id.shutterLabel)
        brightLabel = findViewById(R.id.brightLabel)
        zoomLabel = findViewById(R.id.zoomLabel)
        autoBtn = findViewById(R.id.autoBtn)
        thumb = findViewById(R.id.thumb)
        val shutter = findViewById<View>(R.id.shutterBtn)
        shutter.setOnClickListener { takePhoto() }
        autoBtn.setOnClickListener { toggleAuto() }
        thumb.setOnClickListener { openLastPhoto() }

        // Look moderne : barres noires, écran toujours allumé, éléments arrondis
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val r = dp(28).toFloat()
        findViewById<View>(R.id.panel).background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2121212"))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        zoomLabel.background = rounded(Color.parseColor("#99000000"), 16f)
        thumb.background = rounded(Color.parseColor("#222222"), 14f, Color.parseColor("#66FFFFFF"), 2)
        thumb.clipToOutline = true
        shutter.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shutterDrawable(amber))
            addState(intArrayOf(), shutterDrawable(Color.WHITE))
        }
        styleAuto()
        loadLastThumbnail()

        cameraReady = try { loadCharacteristics() } catch (e: Exception) { false }
        if (!cameraReady) {
            toast("Caméra manuelle non disponible sur cet appareil")
            return
        }
        textureView.setAspectRatio(previewSize.height, previewSize.width)

        isoSeek.max = isoMax - isoMin
        shutterSeek.max = 1000
        brightSeek.max = 200

        iso = 100.coerceIn(isoMin, isoMax)
        exposureNs = 16_666_666L.coerceIn(expMin, expMax)
        isoSeek.progress = iso - isoMin
        shutterSeek.progress = shutterToProgress(exposureNs)
        brightSeek.progress = 100
        updateLabels()

        isoSeek.onChange { iso = isoMin + it; updateLabels(); updatePreview() }
        shutterSeek.onChange {
            exposureNs = (expMin * (expMax.toDouble() / expMin).pow(it / 1000.0)).toLong()
            updateLabels(); updatePreview()
        }
        brightSeek.onChange { brightness = it - 100; updateLabels(); applyBrightnessPreview() }
        applyBrightnessPreview()

        // Pincer = zoom, un doigt = déplacer la zone zoomée
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    zoom = (zoom * d.scaleFactor).coerceIn(1f, maxZoom)
                    updateLabels(); updatePreview()
                    return true
                }
            })
        textureView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = ev.x; lastY = ev.y }
                MotionEvent.ACTION_MOVE ->
                    if (ev.pointerCount == 1 && !scaleDetector.isInProgress && zoom > 1f) {
                        pan(ev.x - lastX, ev.y - lastY)
                        lastX = ev.x; lastY = ev.y
                    }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = if (ev.actionIndex == 0) 1 else 0
                    lastX = ev.getX(i); lastY = ev.getY(i)
                }
            }
            true
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Permission caméra refusée")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!cameraReady) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        thread = HandlerThread("camera").also { it.start() }
        handler = Handler(thread!!.looper)
        if (textureView.isAvailable) openCamera() else textureView.surfaceTextureListener = surfaceListener
    }

    override fun onPause() {
        if (autoOn) toggleAuto()
        closeCamera()
        thread?.quitSafely()
        try { thread?.join() } catch (_: InterruptedException) {}
        thread = null
        handler = null
        super.onPause()
    }

    // ---------- Réglages ----------

    private fun shutterToProgress(ns: Long): Int =
        (ln(ns.toDouble() / expMin) / ln(expMax.toDouble() / expMin) * 1000).roundToInt().coerceIn(0, 1000)

    private fun updateLabels() {
        isoLabel.text = "$iso"
        shutterLabel.text = fmtShutter(exposureNs)
        brightLabel.text = String.format(Locale.US, "%+.1f EV", brightness / 50f)
        zoomLabel.text = String.format(Locale.US, "🔍 x%.1f", zoom)
    }

    private fun fmtShutter(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.5) String.format(Locale.US, "%.1f s", s) else "1/${(1 / s).roundToInt()} s"
    }

    private fun gain() = 2.0.pow(brightness / 50.0).toFloat()

    private fun applyBrightnessPreview() {
        val g = gain()
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(g, g, g, 1f) })
        textureView.setLayerPaint(paint)
    }

    private fun SeekBar.onChange(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = block(p)
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun cropRect(): Rect {
        val w = (activeRect.width() / zoom).toInt()
        val h = (activeRect.height() / zoom).toInt()
        val l = (centerX - w / 2f).toInt().coerceIn(activeRect.left, activeRect.right - w)
        val t = (centerY - h / 2f).toInt().coerceIn(activeRect.top, activeRect.bottom - h)
        centerX = l + w / 2f
        centerY = t + h / 2f
        return Rect(l, t, l + w, t + h)
    }

    private fun pan(dx: Float, dy: Float) {
        val r = cropRect()
        val s = if (sensorOrientation == 270) -1f else 1f
        centerY += s * dx * r.height() / textureView.width
        centerX -= s * dy * r.width() / textureView.height
        updatePreview()
    }

    private fun applyManual(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        b.set(CaptureRequest.SCALER_CROP_REGION, cropRect())
    }

    private fun updatePreview() {
        val b = previewBuilder ?: return
        val s = session ?: return
        try {
            applyManual(b)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: Exception) {}
    }

    // ---------- Mode AUTO (soleil + filtre) ----------

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!autoOn) return
            autoStep()
            ui.postDelayed(this, maxOf(350L, exposureNs / 1_000_000L * 2 + 250L))
        }
    }

    private fun toggleAuto() {
        autoOn = !autoOn
        autoBtn.text = if (autoOn) "AUTO ☀ ON" else "AUTO ☀"
        styleAuto()
        for (sb in listOf(isoSeek, shutterSeek, brightSeek)) {
            sb.isEnabled = !autoOn
            sb.alpha = if (autoOn) 0.4f else 1f
        }
        if (autoOn) {
            brightSeek.progress = 100 // luminosité neutre
            ui.post(autoRunnable)
        } else {
            ui.removeCallbacks(autoRunnable)
        }
    }

    /** Mesure les pixels les plus brillants (le disque solaire) et ajuste ISO/vitesse
     *  pour les amener à ~78 % sans les brûler. ISO au minimum tant que possible. */
    private fun autoStep() {
        val w = 160
        val h = 120
        val bmp = textureView.getBitmap(w, h) ?: return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val lum = FloatArray(px.size) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        }
        lum.sort()
        val n = 12
        var sum = 0f
        for (i in lum.size - n until lum.size) sum += lum[i]
        val measured = (sum / n).coerceAtLeast(1f)

        val ratio = (200f / measured).toDouble().pow(2.2) // vers le linéaire
        val step = ratio.pow(0.7).coerceIn(0.2, 5.0)       // amorti
        if (abs(ln(step)) < 0.06) return                   // assez proche : on ne bouge plus

        val e = iso.toDouble() * exposureNs * step          // "quantité de lumière" ISO x temps
        var newIso = isoMin
        var t = e / newIso
        if (t > expMax) {
            t = expMax.toDouble()
            newIso = (e / t).roundToInt().coerceIn(isoMin, isoMax)
        }
        t = t.coerceIn(expMin.toDouble(), expMax.toDouble())
        iso = newIso
        exposureNs = t.toLong()
        isoSeek.progress = iso - isoMin
        shutterSeek.progress = shutterToProgress(exposureNs)
        updateLabels(); updatePreview()
    }

    // ---------- Caméra ----------

    private fun pickSize(sizes: Array<Size>, maxArea: Int, maxWidth: Int): Size {
        val ok = sizes.filter { it.width * it.height <= maxArea && it.width <= maxWidth }
        val c = ok.filter { it.width * 3 == it.height * 4 }.ifEmpty { ok }
        return c.maxByOrNull { it.width * it.height } ?: sizes.first()
    }

    private fun loadCharacteristics(): Boolean {
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        for (id in mgr.cameraIdList) {
            val c = mgr.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val isoR = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: continue
            val expR = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: continue
            cameraId = id
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            isoMin = isoR.lower; isoMax = isoR.upper
            expMin = expR.lower; expMax = minOf(expR.upper, 1_000_000_000L)
            activeRect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
            maxZoom = minOf(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f, 10f)
            centerX = activeRect.exactCenterX()
            centerY = activeRect.exactCenterY()
            previewSize = pickSize(map.getOutputSizes(SurfaceTexture::class.java), Int.MAX_VALUE, 1920)
            jpegSize = pickSize(map.getOutputSizes(ImageFormat.JPEG), 9_000_000, Int.MAX_VALUE)
            return true
        }
        return false
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (imageReader != null) return
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            img.close()
            save(bytes, brightness)
        }, handler)
        imageReader = reader
        try {
            (getSystemService(CAMERA_SERVICE) as CameraManager).openCamera(cameraId, stateCallback, handler)
        } catch (e: Exception) {
            toast("Impossible d'ouvrir la caméra")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) { cameraDevice = cam; createSession(cam) }
        override fun onDisconnected(cam: CameraDevice) { cam.close(); cameraDevice = null }
        override fun onError(cam: CameraDevice, error: Int) { cam.close(); cameraDevice = null }
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: CameraDevice) {
        val tex = textureView.surfaceTexture ?: return
        val reader = imageReader ?: return
        tex.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(tex)
        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        previewBuilder = builder
        cam.createCaptureSession(listOf(surface, reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; updatePreview() }
                override fun onConfigureFailed(s: CameraCaptureSession) { toast("Échec de la configuration caméra") }
            }, handler)
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        previewBuilder = null
    }

    // ---------- Prise de photo ----------

    private fun takePhoto() {
        val cam = cameraDevice ?: return
        val s = session ?: return
        val r = imageReader ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            applyManual(b)
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            s.capture(b.build(), null, handler)
        } catch (e: Exception) {
            toast("Erreur de capture : ${e.message}")
        }
    }

    /** Applique la luminosité (gain en EV) sur le JPEG, en respectant l'orientation EXIF. */
    private fun applyBrightness(jpeg: ByteArray, b: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val orientation = ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val swap = deg == 90f || deg == 270f
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        val m = Matrix()
        m.postRotate(deg, src.width / 2f, src.height / 2f)
        m.postTranslate((w - src.width) / 2f, (h - src.height) / 2f)

        val g = 2.0.pow(b / 50.0).toFloat()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(g, g, g, 1f) })

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, paint)
        src.recycle()
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        out.recycle()
        return bos.toByteArray()
    }

    private fun save(jpeg: ByteArray, b: Int) {
        try {
            val data = if (b == 0) jpeg else applyBrightness(jpeg, b)
            val name = "MANUAL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ManualCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("insertion impossible")
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            lastUri = uri
            showThumb(uri)
            toast("Photo enregistrée")
        } catch (e: Exception) {
            toast("Erreur d'enregistrement : ${e.message}")
        }
    }

    // ---------- Interface : aides ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun oval(fill: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun shutterDrawable(inner: Int) = LayerDrawable(arrayOf(
        oval(Color.TRANSPARENT, Color.WHITE, 4),
        InsetDrawable(oval(inner), dp(8))
    ))

    private fun styleAuto() {
        if (autoOn) {
            autoBtn.background = rounded(amber, 24f)
            autoBtn.setTextColor(Color.BLACK)
        } else {
            autoBtn.background = rounded(Color.parseColor("#26FFFFFF"), 24f, Color.parseColor("#55FFFFFF"), 1)
            autoBtn.setTextColor(Color.WHITE)
        }
    }

    // ---------- Miniature de la dernière photo ----------

    private fun showThumb(uri: Uri) {
        Thread {
            try {
                val bmp = contentResolver.loadThumbnail(uri, Size(240, 240), null)
                runOnUiThread { thumb.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun loadLastThumbnail() {
        Thread {
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
                    arrayOf("Pictures/ManualCam%"),
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                        lastUri = uri
                        showThumb(uri)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun openLastPhoto() {
        val u = lastUri
        if (u == null) { toast("Aucune photo pour le moment"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("Aucune application pour ouvrir la photo")
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
