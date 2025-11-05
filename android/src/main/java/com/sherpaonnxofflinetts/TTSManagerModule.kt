package com.sherpaonnxofflinetts

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.k2fsa.sherpa.onnx.*
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class TTSManagerModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private var tts: OfflineTts? = null
    private var realTimeAudioPlayer: AudioPlayer? = null

    override fun getName(): String = "TTSManager"

    // ---------- helpers for file naming ----------
    private fun ensureWav(name: String) =
        if (name.lowercase().endsWith(".wav")) name else "$name.wav"

    private fun sanitize(name: String) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    // ---------- helpers for WAV writing (PCM16 mono) ----------
    private fun intLE(i: Int) = byteArrayOf(
        (i and 0xFF).toByte(),
        ((i shr 8) and 0xFF).toByte(),
        ((i shr 16) and 0xFF).toByte(),
        ((i shr 24) and 0xFF).toByte()
    )

    private fun shortLE(s: Int) = byteArrayOf(
        (s and 0xFF).toByte(),
        ((s shr 8) and 0xFF).toByte()
    )

    private fun floatsToPcm16LE(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (f in samples) {
            val v = (f.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun writeWav16Mono(file: File, samples: FloatArray, sampleRate: Int) {
        val data = floatsToPcm16LE(samples)
        FileOutputStream(file).use { out ->
            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            out.write(intLE(36 + data.size))
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            // fmt chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            out.write(intLE(16))             // PCM chunk size
            out.write(shortLE(1))            // audio format = PCM
            out.write(shortLE(1))            // channels = 1
            out.write(intLE(sampleRate))     // sample rate
            out.write(intLE(sampleRate * 2)) // byte rate = sr * ch * bps/8
            out.write(shortLE(2))            // block align = ch * bps/8
            out.write(shortLE(16))           // bits per sample
            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intLE(data.size))
            out.write(data)
        }
    }

    // Initialize TTS and Audio Player
    @ReactMethod
    fun initializeTTS(sampleRate: Double, channels: Int, modelId: String) {
        realTimeAudioPlayer = AudioPlayer(sampleRate.toInt(), channels, object : AudioPlayerDelegate {
            override fun didUpdateVolume(volume: Float) {
                sendVolumeUpdate(volume)
            }
        })

        val jsonObject = JSONObject(modelId)
        val modelPath = jsonObject.getString("modelPath")
        val tokensPath = jsonObject.getString("tokensPath")
        val dataDirPath = jsonObject.getString("dataDirPath")

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    dataDir = dataDirPath,
                ),
                numThreads = 1,
                debug = true,
            )
        )

        tts = OfflineTts(config = config)
        realTimeAudioPlayer?.start()
    }

    // Generate and Play method exposed to React Native
    @ReactMethod
    fun generateAndPlay(text: String, sid: Int, speed: Double, promise: Promise) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            promise.reject("EMPTY_TEXT", "Input text is empty")
            return
        }

        val sentences = splitText(trimmedText, 15)
        try {
            for (sentence in sentences) {
                val processedSentence = if (sentence.endsWith(".")) sentence else "$sentence."
                generateAudio(processedSentence, sid, speed.toFloat())
            }
            promise.resolve("Audio generated and played successfully")
        } catch (e: Exception) {
            promise.reject("GENERATION_ERROR", "Error during audio generation: ${e.message}")
        }
    }

    // NEW: Generate to file (returns absolute path). options: { isSaving?: boolean|number, fileName?: string }
    @ReactMethod
    fun generate(text: String, sid: Int, speed: Double, options: ReadableMap?, promise: Promise) {
        try {
            val engine = tts ?: run {
                promise.reject("E_TTS_NOT_INIT", "TTS not initialized")
                return
            }
            val trimmedText = text.trim()
            if (trimmedText.isEmpty()) {
                promise.reject("EMPTY_TEXT", "Input text is empty")
                return
            }

            val isSaving = when {
                options?.hasKey("isSaving") == true && options.getType("isSaving").name == "Number" ->
                    options.getDouble("isSaving") != 0.0
                options?.hasKey("isSaving") == true && options.getType("isSaving").name == "Boolean" ->
                    options.getBoolean("isSaving")
                else -> false
            }
            val userFileName = if (options?.hasKey("fileName") == true) options.getString("fileName") else null

            val baseDir: File =
                if (isSaving) {
                    reactContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                        ?: reactContext.filesDir
                } else {
                    reactContext.cacheDir
                }
            if (!baseDir.exists()) baseDir.mkdirs()

            val safeName = ensureWav(sanitize(userFileName ?: "tts-${System.currentTimeMillis()}"))
            val outFile = File(baseDir, safeName)

            // Synthesize full text by concatenating sentence chunks
            val sentences = splitText(trimmedText, 15)
            val all = ArrayList<Float>()
            var sr = 24000

            for (sentence in sentences) {
                val processed = if (sentence.endsWith(".")) sentence else "$sentence."
                val audio = engine.generate(processed, sid, speed.toFloat())
                if (audio != null) {
                    if (all.isEmpty()) sr = audio.sampleRate
                    all.addAll(audio.samples.toList())
                }
            }

            if (all.isEmpty()) {
                promise.reject("GENERATION_ERROR", "No audio produced")
                return
            }

            val arr = FloatArray(all.size)
            for (i in all.indices) arr[i] = all[i]

            writeWav16Mono(outFile, arr, sr)

            if (!outFile.exists() || outFile.length() <= 44L) {
                throw RuntimeException("Empty WAV at ${outFile.absolutePath}")
            }

            promise.resolve(outFile.absolutePath)
        } catch (t: Throwable) {
            promise.reject("E_TTS_GENERATE", t.message, t)
        }
    }

    // Deinitialize method exposed to React Native
    @ReactMethod
    fun deinitialize() {
        realTimeAudioPlayer?.stopPlayer()
        realTimeAudioPlayer = null
        tts?.release()
        tts = null
    }

    // Helper: split text into manageable chunks
    private fun splitText(text: String, maxWords: Int): List<String> {
        val sentences = mutableListOf<String>()
        val words = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        var currentIndex = 0
        val totalWords = words.size

        while (currentIndex < totalWords) {
            val endIndex = (currentIndex + maxWords).coerceAtMost(totalWords)
            val chunk = words.subList(currentIndex, endIndex).joinToString(" ")

            val lastPeriod = chunk.lastIndexOf('.')
            val lastComma = chunk.lastIndexOf(',')

            when {
                lastPeriod != -1 -> {
                    val sentence = chunk.substring(0, lastPeriod + 1).trim()
                    sentences.add(sentence)
                    currentIndex += sentence.split("\\s+".toRegex()).size
                }
                lastComma != -1 -> {
                    val sentence = chunk.substring(0, lastComma + 1).trim()
                    sentences.add(sentence)
                    currentIndex += sentence.split("\\s+".toRegex()).size
                }
                else -> {
                    sentences.add(chunk.trim())
                    currentIndex += maxWords
                }
            }
        }

        return sentences
    }

    private fun generateAudio(text: String, sid: Int, speed: Float) {
        val startTime = System.currentTimeMillis()
        val audio = tts?.generate(text, sid, speed)
        val endTime = System.currentTimeMillis()
        val generationTime = (endTime - startTime) / 1000.0
        println("Time taken for TTS generation: $generationTime seconds")

        if (audio == null) {
            println("Error: TTS was never initialized or audio generation failed")
            return
        }
        realTimeAudioPlayer?.enqueueAudioData(audio.samples, audio.sampleRate)
    }

    private fun sendVolumeUpdate(volume: Float) {
        if (reactContext.hasActiveCatalystInstance()) {
            val params = Arguments.createMap()
            params.putDouble("volume", volume.toDouble())
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("VolumeUpdate", params)
        }
    }
}
