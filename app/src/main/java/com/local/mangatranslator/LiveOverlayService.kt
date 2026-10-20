package com.local.mangatranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class LiveOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "MangaLiveTranslatorChannel"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var engine: MangaTranslationEngine

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var overlayCanvasView: MangaOverlayView? = null
    private var overlayCanvasParams: WindowManager.LayoutParams? = null
    private var controlPillLayout: LinearLayout? = null

    private var autoLiveJob: Job? = null
    private var isAutoLiveEnabled = false
    private var lastFrameSignature: Long = 0L
    private var isProcessingFrame = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        engine = MangaTranslationEngine.getInstance(this)
        serviceScope.launch {
            engine.ensureOfflineModelsReady()
            engine.initHeavyLocalLlmIfPresent()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        setupMediaProjection(resultCode, resultData)

        if (overlayCanvasView == null && controlPillLayout == null) {
            setupFloatingUi()
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dynamically resize capture surface when user rotates screen for double-page manga spreads
        if (mediaProjection != null && virtualDisplay != null) {
            val (realWidth, realHeight, densityDpi) = getRealScreenDimensions()
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                realWidth,
                realHeight,
                PixelFormat.RGBA_8888,
                2
            )
            virtualDisplay?.resize(realWidth, realHeight, densityDpi)
            virtualDisplay?.surface = imageReader?.surface
            overlayCanvasParams?.let { updateOverlayBubbles(emptyList(), it) }
        }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live Manga Translator",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline Manga Translator Active")
            .setContentText("Tap Scan or toggle Live Mode on the floating bar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notification)
        }
    }

    private fun releaseProjectionResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projectionCallback?.let { cb ->
            runCatching { mediaProjection?.unregisterCallback(cb) }
        }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun getRealScreenDimensions(): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        releaseProjectionResources()

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
                stopSelf()
            }
        }
        projectionCallback = callback
        projection?.registerCallback(callback, Handler(Looper.getMainLooper()))

        val (realWidth, realHeight, densityDpi) = getRealScreenDimensions()

        imageReader = ImageReader.newInstance(
            realWidth,
            realHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "MangaLiveCapture",
            realWidth,
            realHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun setupFloatingUi() {
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        overlayCanvasView = MangaOverlayView(
            context = this,
            onBubbleTapped = { tappedBubble ->
                engine.speakOriginalJapanese(tappedBubble.originalJapanese)
            },
            onDismissRequested = {
                overlayCanvasParams?.let { updateOverlayBubbles(emptyList(), it) }
            }
        )

        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        overlayCanvasParams = canvasParams
        windowManager.addView(overlayCanvasView, canvasParams)

        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }

        controlPillLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EE1E293B"))
            setPadding(16, 8, 16, 8)

            val dragHandle = TextView(context).apply {
                text = "⠿ Move "
                textSize = 13f
                setTextColor(Color.parseColor("#FF94A3B8"))
                setPadding(12, 12, 16, 12)
            }

            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            dragHandle.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = pillParams.x
                        initialY = pillParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        pillParams.x = initialX + (event.rawX - touchX).toInt()
                        pillParams.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(this@apply, pillParams)
                        true
                    }
                    else -> false
                }
            }

            val translateBtn = Button(context).apply {
                text = "Scan"
                textSize = 12f
                setOnClickListener {
                    captureAndTranslateNow(force = true)
                }
            }

            val liveBtn = Button(context).apply {
                text = "Live: OFF"
                textSize = 12f
                setOnClickListener {
                    isAutoLiveEnabled = !isAutoLiveEnabled
                    text = if (isAutoLiveEnabled) "Live: ON" else "Live: OFF"
                    toggleAutoLiveLoop(isAutoLiveEnabled)
                }
            }

            val clearBtn = Button(context).apply {
                text = "Clear"
                textSize = 12f
                setOnClickListener {
                    overlayCanvasParams?.let { updateOverlayBubbles(emptyList(), it) }
                }
            }

            val closeBtn = Button(context).apply {
                text = "X"
                textSize = 12f
                setOnClickListener { stopSelf() }
            }

            addView(dragHandle)
            addView(translateBtn)
            addView(liveBtn)
            addView(clearBtn)
            addView(closeBtn)
        }

        windowManager.addView(controlPillLayout, pillParams)
    }

    private fun toggleAutoLiveLoop(enabled: Boolean) {
        autoLiveJob?.cancel()
        if (!enabled) return

        autoLiveJob = serviceScope.launch {
            while (isActive && isAutoLiveEnabled) {
                captureAndTranslateNow(force = false)
                delay(1600L)
            }
        }
    }

    private fun captureAndTranslateNow(force: Boolean) {
        if (isProcessingFrame) return
        isProcessingFrame = true

        serviceScope.launch {
            try {
                overlayCanvasView?.visibility = View.INVISIBLE
                controlPillLayout?.visibility = View.INVISIBLE
                delay(85L)

                var bitmap = acquireScreenBitmap()
                if (bitmap == null && force) {
                    repeat(3) {
                        if (bitmap == null) {
                            delay(45L)
                            bitmap = acquireScreenBitmap()
                        }
                    }
                }

                overlayCanvasView?.visibility = View.VISIBLE
                controlPillLayout?.visibility = View.VISIBLE

                val captured = bitmap
                if (captured != null) {
                    try {
                        val signature = computeFastFrameSignature(captured)
                        val hasPageChanged = abs(signature - lastFrameSignature) > 1800L

                        if (force || hasPageChanged) {
                            lastFrameSignature = signature
                            val bubbles = engine.processScreenFrame(captured)
                            overlayCanvasParams?.let { updateOverlayBubbles(bubbles, it) }
                        }
                    } finally {
                        captured.recycle()
                    }
                }
            } finally {
                isProcessingFrame = false
            }
        }
    }

    private fun updateOverlayBubbles(
        bubbles: List<MangaBubble>,
        params: WindowManager.LayoutParams
    ) {
        overlayCanvasView?.setBubbles(bubbles)
        if (bubbles.isNotEmpty()) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        overlayCanvasView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun acquireScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            val fullBitmap = Bitmap.createBitmap(
                paddedWidth,
                height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            if (paddedWidth == width) {
                fullBitmap
            } else {
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                fullBitmap.recycle()
                cropped
            }
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }
    }

    private fun computeFastFrameSignature(bitmap: Bitmap): Long {
        var sum = 0L
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                sum += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel))
            }
        }
        return sum
    }

    override fun onDestroy() {
        autoLiveJob?.cancel()
        serviceScope.cancel()
        overlayCanvasView?.let {
            runCatching { windowManager.removeView(it) }
        }
        controlPillLayout?.let {
            runCatching { windowManager.removeView(it) }
        }
        overlayCanvasView = null
        controlPillLayout = null
        releaseProjectionResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Custom Overlay View that auto-expands narrow 1-column vertical Manga boxes
 * so horizontal English translations never wrap every 2 letters, and stores drawn
 * bounds so tapping any part of a rendered card speaks the original Japanese.
 */
class MangaOverlayView(
    context: Context,
    private val onBubbleTapped: (MangaBubble) -> Unit,
    private val onDismissRequested: () -> Unit
) : View(context) {

    private data class RenderedBubble(
        val bubble: MangaBubble,
        val drawnRect: RectF
    )

    private var bubbles: List<MangaBubble> = emptyList()
    private val renderedBubbles = mutableListOf<RenderedBubble>()
    private val windowOffset = IntArray(2)

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4FFFFFF")
        style = Paint.Style.FILL
    }

    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2563EB")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0F172A")
        textSize = 34f
    }

    private val speakerBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1D4ED8")
        textSize = 24f
        isFakeBoldText = true
    }

    fun setBubbles(newBubbles: List<MangaBubble>) {
        bubbles = newBubbles
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderedBubbles.clear()
        if (bubbles.isEmpty()) return

        getLocationOnScreen(windowOffset)
        val offsetX = windowOffset[0].toFloat()
        val offsetY = windowOffset[1].toFloat()
        val viewW = width.toFloat().coerceAtLeast(360f)

        for (bubble in bubbles) {
            val rectF = RectF(
                bubble.boundingBox.left - offsetX,
                bubble.boundingBox.top - offsetY,
                bubble.boundingBox.right - offsetX,
                bubble.boundingBox.bottom - offsetY
            )
            rectF.inset(-10f, -10f)

            // Expand narrow 1-column vertical Japanese boxes to at least 220px wide
            val minReadableWidth = 220f
            if (rectF.width() < minReadableWidth) {
                val cx = rectF.centerX()
                var newLeft = cx - minReadableWidth / 2f
                var newRight = cx + minReadableWidth / 2f
                if (newLeft < 8f) {
                    newRight += (8f - newLeft)
                    newLeft = 8f
                }
                if (newRight > viewW - 8f) {
                    newLeft -= (newRight - (viewW - 8f))
                    newRight = viewW - 8f
                }
                rectF.left = newLeft.coerceAtLeast(4f)
                rectF.right = newRight.coerceAtMost(viewW - 4f)
            }

            val availableWidth = (rectF.width() - 20f).toInt().coerceAtLeast(120)
            val targetHeight = (rectF.height() - 36f).toInt().coerceAtLeast(48)

            var currentTextSize = 32f
            var staticLayout: StaticLayout
            do {
                textPaint.textSize = currentTextSize
                staticLayout = StaticLayout.Builder
                    .obtain(
                        bubble.translatedEnglish,
                        0,
                        bubble.translatedEnglish.length,
                        textPaint,
                        availableWidth
                    )
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
                currentTextSize -= 2f
            } while (staticLayout.height > targetHeight && currentTextSize >= 18f)

            // Ensure card bottom always fits the full translated English text
            val requiredHeight = staticLayout.height + 42f
            if (rectF.height() < requiredHeight) {
                rectF.bottom = rectF.top + requiredHeight
            }

            canvas.drawRoundRect(rectF, 16f, 16f, boxFillPaint)
            canvas.drawRoundRect(rectF, 16f, 16f, boxBorderPaint)
            canvas.drawText("🔊 JP", rectF.right - 72f, rectF.top + 26f, speakerBadgePaint)

            canvas.save()
            val textTop = rectF.top + 32f + max(0f, (rectF.height() - 36f - staticLayout.height) / 2f)
            canvas.translate(rectF.left + 10f, textTop)
            staticLayout.draw(canvas)
            canvas.restore()

            renderedBubbles.add(RenderedBubble(bubble, RectF(rectF)))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val viewX = event.x
            val viewY = event.y

            val hit = renderedBubbles.firstOrNull { it.drawnRect.contains(viewX, viewY) }
            if (hit != null) {
                onBubbleTapped(hit.bubble)
                return true
            } else {
                onDismissRequested()
                return false
            }
        }
        return super.onTouchEvent(event)
    }
}
