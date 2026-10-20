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
        val translations = translateBubblesBatch(clusteredBubbles.map { it.japaneseText })

        clusteredBubbles.mapIndexed { index, cluster ->
            MangaBubble(
                boundingBox = cluster.bounds,
                originalJapanese = cluster.japaneseText,
                translatedEnglish = translations.getOrElse(index) { "" },
                isVertical = cluster.isVertical
            )
        }
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

            val verticalCount = group.count { line ->
                val b = line.boundingBox!!
                b.height() > b.width() * 1.15f && line.text.trim().length >= 2
            }
            val isVertical = verticalCount * 2 >= group.size

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

            // Transitive bucket-based 2D sort (prevents TimSort IllegalArgumentException)
            val sortedLines = if (isVertical) {
                val columns = mutableListOf<MutableList<Text.Line>>()
                for (line in filteredGroup.sortedByDescending { it.boundingBox!!.centerX() }) {
                    val box = line.boundingBox!!
                    val matchingCol = columns.firstOrNull { col ->
                        val refBox = col.first().boundingBox!!
                        val threshold = (min(box.width(), refBox.width()) * 0.6f).toInt().coerceAtLeast(12)
                        abs(box.centerX() - refBox.centerX()) <= threshold
                    }
                    if (matchingCol != null) matchingCol.add(line) else columns.add(mutableListOf(line))
                }
                columns.flatMap { col -> col.sortedBy { it.boundingBox!!.top } }
            } else {
                val rows = mutableListOf<MutableList<Text.Line>>()
                for (line in filteredGroup.sortedBy { it.boundingBox!!.centerY() }) {
                    val box = line.boundingBox!!
                    val matchingRow = rows.firstOrNull { row ->
                        val refBox = row.first().boundingBox!!
                        val threshold = (min(box.height(), refBox.height()) * 0.6f).toInt().coerceAtLeast(12)
                        abs(box.centerY() - refBox.centerY()) <= threshold
                    }
                    if (matchingRow != null) matchingRow.add(line) else rows.add(mutableListOf(line))
                }
                rows.flatMap { row -> row.sortedBy { it.boundingBox!!.left } }
            }

            val combinedJapanese = sortedLines.joinToString(separator = "") { it.text.trim() }
            if (combinedJapanese.isNotBlank()) {
                clusters.add(BubbleCluster(groupRect, combinedJapanese, isVertical))
            }
        }

        // Group bubbles into horizontal bands first, then sort bands top-to-bottom and right-to-left
        val bands = mutableListOf<MutableList<BubbleCluster>>()
        for (cluster in clusters.sortedBy { it.bounds.centerY() }) {
            val matchingBand = bands.firstOrNull { band ->
                val ref = band.first()
                abs(cluster.bounds.centerY() - ref.bounds.centerY()) <=
                        max(cluster.bounds.height(), ref.bounds.height()) / 2
            }
            if (matchingBand != null) matchingBand.add(cluster) else bands.add(mutableListOf(cluster))
        }
        return bands.flatMap { band -> band.sortedByDescending { it.bounds.centerX() } }
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

    private suspend fun translateBubblesBatch(japaneseTexts: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (japaneseTexts.isEmpty()) return@withContext emptyList()

            llmMutex.withLock {
                val llm = localLlm
                if (llm != null) {
                    try {
                        val numberedInput = japaneseTexts.mapIndexed { i, text -> "${i + 1}. $text" }
                            .joinToString("\n")
                        val prompt = "<start_of_turn>user\n" +
                            "You are an expert Japanese-to-English manga translator. Translate each numbered Japanese line below into natural, accurate English. Keep the exact numbering (1., 2., etc.) and output ONLY the numbered translations:\n" +
                            "$numberedInput<end_of_turn>\n" +
                            "<start_of_turn>model\n"
                        val response = llm.generateResponse(prompt).trim()
                        val lineRegex = Regex("""^\d+[\.\)]\s*(.+)$""")
                        val parsed = response.lines()
                            .mapNotNull { line ->
                                lineRegex.find(line.trim())?.groupValues?.get(1)?.trim()
                            }
                        if (parsed.size == japaneseTexts.size) {
                            return@withContext parsed
                        }
                    } catch (_: Throwable) {
                        // Fall through to offline NMT fallback
                    }
                }
            }

            japaneseTexts.map { text ->
                try {
                    nmtTranslator.translate(text).await()
                } catch (_: Exception) {
                    "[Offline Model Needed: Connect once to download 30MB NMT]"
                }
            }
        }
}
