package com.local.mangatranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MangaBubble(
    val boundingBox: Rect,
    val originalJapanese: String,
    val translatedEnglish: String,
    val isVertical: Boolean
)

class MangaTranslationEngine private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        private var INSTANCE: MangaTranslationEngine? = null

        fun getInstance(context: Context): MangaTranslationEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MangaTranslationEngine(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val llmMutex = Mutex()

    // 1. Offline Bundled Japanese OCR V2
    private val japaneseOcr = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    // 2. Offline NMT Fallback
    private val nmtTranslator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    // 3. Heavy On-Device LLM (MediaPipe GenAI)
    private var localLlm: LlmInference? = null
    private var loadedLlmPath: String? = null

    @Volatile
    var activeEngineName: String = "Offline NMT (Ready)"
        private set

    // 4. Offline Original Japanese TTS
    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false

    init {
        initJapaneseTts()
    }

    private fun initJapaneseTts() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(Locale.JAPAN)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                // Bind directly to an installed offline Japanese voice (no internet required)
                val offlineJapaneseVoice = engine.voices?.firstOrNull { voice ->
                    voice.locale.language == Locale.JAPAN.language && !voice.isNetworkConnectionRequired
                }
                if (offlineJapaneseVoice != null) {
                    engine.voice = offlineJapaneseVoice
                }
                engine.setSpeechRate(0.92f)
            }
        }
    }

    /**
     * Checks the app's local storage folder for a heavy MediaPipe LLM (.task or .bin)
     * on Dispatchers.IO so the UI thread never blocks.
     */
    suspend fun initHeavyLocalLlmIfPresent(): String = withContext(Dispatchers.IO) {
        llmMutex.withLock {
            val modelDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val modelFile = modelDir.listFiles()?.firstOrNull {
                it.isFile && (it.name.endsWith(".bin") || it.name.endsWith(".task"))
            }

            if (modelFile == null || !modelFile.exists()) {
                if (localLlm == null) {
                    activeEngineName = "Offline NMT (No .bin/.task LLM in folder)"
                }
                return@withLock activeEngineName
            }

            if (loadedLlmPath == modelFile.absolutePath && localLlm != null) {
                return@withLock activeEngineName
            }

            try {
                localLlm?.close()
                localLlm = null

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    .build()

                localLlm = LlmInference.createFromOptions(appContext, options)
                loadedLlmPath = modelFile.absolutePath
                activeEngineName = "Heavy Local LLM (${modelFile.name})"
            } catch (e: Throwable) {
                localLlm = null
                loadedLlmPath = null
                activeEngineName = "Offline NMT (LLM Load Failed: ${e.javaClass.simpleName})"
            }
            activeEngineName
        }
    }

    suspend fun ensureOfflineModelsReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conditions = DownloadConditions.Builder().build()
            nmtTranslator.downloadModelIfNeeded(conditions).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Speaks the ORIGINAL Japanese text aloud using the bound offline voice.
     */
    fun speakOriginalJapanese(japaneseText: String) {
        if (!isTtsReady) return
        val cleanText = japaneseText.replace("\n", " ").trim()
        if (cleanText.isEmpty()) return

        tts?.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "jp_utt_${System.currentTimeMillis()}"
        )
    }

    suspend fun processScreenFrame(bitmap: Bitmap): List<MangaBubble> = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = japaneseOcr.process(inputImage).await()

        val rawLines = visionText.textBlocks.flatMap { it.lines }
            .filter { it.boundingBox != null && containsJapaneseCharacters(it.text) }

        if (rawLines.isEmpty()) return@withContext emptyList()

        val clusteredBubbles = clusterAndSortMangaBubbles(rawLines)
        val results = mutableListOf<MangaBubble>()

        for (cluster in clusteredBubbles) {
            val translated = translateJapaneseContextAware(cluster.japaneseText)
            results.add(
                MangaBubble(
                    boundingBox = cluster.bounds,
                    originalJapanese = cluster.japaneseText,
                    translatedEnglish = translated,
                    isVertical = cluster.isVertical
                )
            )
        }
        results
    }

    private data class BubbleCluster(
        val bounds: Rect,
        val japaneseText: String,
        val isVertical: Boolean
    )

    private fun clusterAndSortMangaBubbles(lines: List<Text.Line>): List<BubbleCluster> {
        val visited = BooleanArray(lines.size)
        val clusters = mutableListOf<BubbleCluster>()

        for (i in lines.indices) {
            if (visited[i]) continue
            visited[i] = true
            val group = mutableListOf(lines[i])
            val groupRect = Rect(lines[i].boundingBox!!)

            var merged: Boolean
            do {
                merged = false
                for (j in lines.indices) {
                    if (visited[j]) continue
                    val candidateRect = lines[j].boundingBox!!
                    if (areLinesInSameSpeechBubble(groupRect, candidateRect)) {
                        visited[j] = true
                        group.add(lines[j])
                        groupRect.union(candidateRect)
                        merged = true
                    }
                }
            } while (merged)

            // Determine if this speech bubble is vertical (Tategaki)
            val verticalCount = group.count { line ->
                val b = line.boundingBox!!
                b.height() > b.width() * 1.15f && line.text.trim().length >= 2
            }
            val isVertical = verticalCount * 2 >= group.size

            // Filter narrow Furigana columns in multi-column vertical speech bubbles
            val filteredGroup = if (isVertical && group.size >= 2) {
                val widths = group.map { it.boundingBox!!.width() }.sorted()
                val medianWidth = widths[widths.size / 2]
                group.filter { line ->
                    val w = line.boundingBox!!.width()
                    val clean = line.text.trim()
                    val isPureKana = clean.all { ch ->
                        ch in '\u3040'..'\u309F' ||
                        ch in '\u30A0'..'\u30FF' ||
                        ch == 'ー'
                    }
                    !(w < medianWidth * 0.52f && isPureKana)
                }.ifEmpty { group }
            } else {
                group
            }

            // 2D Reading Order Sort:
            // Handles both multi-column Tategaki AND columns split into top/bottom segments by OCR
            val sortedLines = if (isVertical) {
                filteredGroup.sortedWith { a, b ->
                    val boxA = a.boundingBox!!
                    val boxB = b.boundingBox!!
                    val sameColumnThreshold = (min(boxA.width(), boxB.width()) * 0.6f).toInt().coerceAtLeast(12)
                    if (abs(boxA.centerX() - boxB.centerX()) <= sameColumnThreshold) {
                        // Same vertical column -> Read Top-to-Bottom
                        boxA.top.compareTo(boxB.top)
                    } else {
                        // Different vertical columns -> Read Right-to-Left
                        boxB.centerX().compareTo(boxA.centerX())
                    }
                }
            } else {
                filteredGroup.sortedWith { a, b ->
                    val boxA = a.boundingBox!!
                    val boxB = b.boundingBox!!
                    val sameRowThreshold = (min(boxA.height(), boxB.height()) * 0.6f).toInt().coerceAtLeast(12)
                    if (abs(boxA.centerY() - boxB.centerY()) <= sameRowThreshold) {
                        // Same horizontal row -> Read Left-to-Right
                        boxA.left.compareTo(boxB.left)
                    } else {
                        // Different horizontal rows -> Read Top-to-Bottom
                        boxA.centerY().compareTo(boxB.centerY())
                    }
                }
            }

            val combinedJapanese = sortedLines.joinToString(separator = "") { it.text.trim() }
            if (combinedJapanese.isNotBlank()) {
                clusters.add(BubbleCluster(groupRect, combinedJapanese, isVertical))
            }
        }

        // Order speech bubbles in Manga page order (Top-to-Bottom, Right-to-Left)
        return clusters.sortedWith { a, b ->
            val yDiff = a.bounds.centerY() - b.bounds.centerY()
            if (abs(yDiff) > max(a.bounds.height(), b.bounds.height()) / 2) {
                yDiff
            } else {
                b.bounds.centerX() - a.bounds.centerX()
            }
        }
    }

    private fun areLinesInSameSpeechBubble(clusterRect: Rect, lineRect: Rect): Boolean {
        val isLineVertical = lineRect.height() > lineRect.width() * 1.15f
        val charThickness = if (isLineVertical) lineRect.width() else lineRect.height()
        val safeThickness = charThickness.coerceAtLeast(16)

        val horizontalGap = max(0, max(clusterRect.left - lineRect.right, lineRect.left - clusterRect.right))
        val verticalGap = max(0, max(clusterRect.top - lineRect.bottom, lineRect.top - clusterRect.bottom))
        val horizontalOverlap = min(clusterRect.right, lineRect.right) - max(clusterRect.left, lineRect.left)
        val verticalOverlap = min(clusterRect.bottom, lineRect.bottom) - max(clusterRect.top, lineRect.top)

        if (isLineVertical) {
            val minHeight = min(clusterRect.height(), lineRect.height()).coerceAtLeast(1)
            val minWidth = min(clusterRect.width(), lineRect.width()).coerceAtLeast(1)
            val isAdjacentColumn = horizontalGap <= (safeThickness * 1.75f).toInt() &&
                    (verticalOverlap.toFloat() / minHeight) >= 0.22f
            val isSplitSameColumn = verticalGap <= (safeThickness * 1.4f).toInt() &&
                    (horizontalOverlap.toFloat() / minWidth) >= 0.4f
            return isAdjacentColumn || isSplitSameColumn
        } else {
            val minWidth = min(clusterRect.width(), lineRect.width()).coerceAtLeast(1)
            val minHeight = min(clusterRect.height(), lineRect.height()).coerceAtLeast(1)
            val isStackedRow = verticalGap <= (safeThickness * 1.5f).toInt() &&
                    (horizontalOverlap.toFloat() / minWidth) >= 0.22f
            val isSplitSameRow = horizontalGap <= (safeThickness * 1.4f).toInt() &&
                    (verticalOverlap.toFloat() / minHeight) >= 0.4f
            return isStackedRow || isSplitSameRow
        }
    }

    private fun containsJapaneseCharacters(text: String): Boolean {
        return text.any { ch ->
            ch in '\u3040'..'\u309F' || // Hiragana
            ch in '\u30A0'..'\u30FF' || // Katakana
            ch in '\u4E00'..'\u9FAF' || // Kanji
            ch in '\u3400'..'\u4DBF'    // CJK Extension A
        }
    }

    private suspend fun translateJapaneseContextAware(japaneseText: String): String =
        withContext(Dispatchers.IO) {
            llmMutex.withLock {
                val llm = localLlm
                if (llm != null) {
                    try {
                        val prompt = """
                            <start_of_turn>user
                            You are an expert Japanese-to-English manga translator. Translate the following Japanese dialogue into natural, accurate English. Output ONLY the English translation without explanations or quotes:
                            $japaneseText<end_of_turn>
                            <start_of_turn>model
                        """.trimIndent()
                        val response = llm.generateResponse(prompt).trim()
                        if (response.isNotEmpty()) return@withContext response
                    } catch (_: Throwable) {
                        // Fall through to offline NMT fallback
                    }
                }
            }

            return@withContext try {
                nmtTranslator.translate(japaneseText).await()
            } catch (e: Exception) {
                "[Offline Model Needed: Connect once to download 30MB NMT]"
            }
        }
}
