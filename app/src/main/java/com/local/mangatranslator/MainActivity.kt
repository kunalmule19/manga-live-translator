package com.local.mangatranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusTextView: TextView
    private lateinit var engine: MangaTranslationEngine

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestScreenCapturePermission()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, LiveOverlayService::class.java).apply {
                putExtra(LiveOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(LiveOverlayService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            moveTaskToBack(true)
        } else {
            Toast.makeText(
                this,
                "Screen capture permission is required for Live Mode",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = MangaTranslationEngine.getInstance(this)

        val modelFolder = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            setBackgroundColor(Color.parseColor("#FF0F172A"))
        }

        val titleView = TextView(this).apply {
            text = "Manga Live Translator (100% Offline)"
            textSize = 22f
            setTextColor(Color.WHITE)
        }

        statusTextView = TextView(this).apply {
            text = "Initializing offline Japanese OCR, TTS & Translation models..."
            textSize = 14f
            setTextColor(Color.parseColor("#FF94A3B8"))
            setPadding(0, 24, 0, 36)
        }

        val startOverlayBtn = Button(this).apply {
            text = "1. Launch Live Floating Translator"
            setOnClickListener { startLiveTranslatorOverlay() }
        }

        val testJapaneseTtsBtn = Button(this).apply {
            text = "2. Test Offline Japanese Audio (TTS)"
            setOnClickListener {
                engine.speakOriginalJapanese("お前はもう死んでいる。日本語の音声テストです。")
            }
        }

        val reloadHeavyLlmBtn = Button(this).apply {
            text = "3. Scan for Heavy Local LLM (.bin / .task)"
            setOnClickListener {
                statusTextView.text = "Scanning and loading Local LLM in background..."
                activityScope.launch {
                    val active = engine.initHeavyLocalLlmIfPresent()
                    updateStatusDisplay(modelFolder, active)
                }
            }
        }

        rootLayout.addView(titleView)
        rootLayout.addView(statusTextView)
        rootLayout.addView(startOverlayBtn)
        rootLayout.addView(testJapaneseTtsBtn)
        rootLayout.addView(reloadHeavyLlmBtn)

        setContentView(ScrollView(this).apply { addView(rootLayout) })

        activityScope.launch {
            val ready = engine.ensureOfflineModelsReady()
            val active = engine.initHeavyLocalLlmIfPresent()
            updateStatusDisplay(
                modelFolder,
                if (ready) active else "$active (Connect once for 30MB NMT fallback)"
            )
        }
    }

    private fun updateStatusDisplay(modelFolder: String, engineStatus: String) {
        statusTextView.text = """
            • Active Translation Engine: $engineStatus
            • Vertical Manga Sorter (Tategaki): ENABLED (Right-to-Left + Furigana Filter)
            • Audio Output: Original Japanese (Locale.JAPAN Offline Voice)

            HOW TO USE HEAVY LOCAL LLM (OPTIONAL UPGRADE):
            Place a quantized MediaPipe LLM file (e.g., gemma-2-2b-jpn-it-gpu-int4.bin) into:
            $modelFolder
            Then tap "Scan for Heavy Local LLM".

            HOW TO USE IN MANGA APPS:
            1. Tap "Launch Live Floating Translator" below.
            2. Drag the floating bar anywhere using "⠿ Move".
            3. Tap "Scan" (or toggle "Live: ON" for automatic translation when turning pages).
            4. Tap any translated white speech bubble to hear the original Japanese spoken aloud!
            5. Tap anywhere outside a bubble to dismiss the overlay and turn the page.
        """.trimIndent()
    }

    private fun startLiveTranslatorOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Please grant 'Display over other apps' permission first",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
