package com.sherpaonnxofflinetts

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.k2fsa.sherpa.onnx.*
import android.content.res.AssetManager
import kotlin.concurrent.thread // This import is now used
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONObject
import android.os.Environment

// ModelLoader class (unchanged, assumed to be above)
// ...

class TTSManagerModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private var tts: OfflineTts? = null
    private var realTimeAudioPlayer: AudioPlayer? = null
    private val modelLoader = ModelLoader(reactContext) // This is unused, but fine

    override fun getName(): String {
        return "TTSManager"
    }

    // ---------- (All your file naming and WAV writing helpers are great) ----------

    private fun ensureWav(name: String) =
        if (name.lowercase().endsWith(".wav")) name else "$name.wav"

    private fun sanitize(name: String) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

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
            out.write(intLE(16))           // PCM chunk size
            out.write(shortLE(1))          // audio format = PCM
            out.write(shortLE(1))          // channels = 1
            out.write(intLE(sampleRate))   // sample rate
            out.write(intLE(sampleRate * 2)) // byte rate = sr * ch * bps/8
            out.write(shortLE(2))          // block align = ch * bps/8
            out.write(shortLE(16))         // bits per sample
            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            out.write(intLE(data.size))
            out.write(data)
        }
    }

    // Initialize TTS (Unchanged)
    @ReactMethod
    fun initializeTTS(sampleRate: Double, channels: Int, modelId: String) {
        // ... (This function is fine as-is) ...
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

    // --- ⬇️ FIXED FUNCTION ⬇️ ---
    // Generate and Play method (Wrapped in a background thread)
    @ReactMethod
    fun generateAndPlay(text: String, sid: Int, speed: Double, promise: Promise) {
        // Run on a background thread
        thread {
            val trimmedText = text.trim()
            if (trimmedText.isEmpty()) {
                promise.reject("EMPTY_TEXT", "Input text is empty")
                return@thread // Exit thread
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
    }

    // --- ⬇️ FIXED FUNCTION ⬇️ ---
    // Generate to file (Wrapped in a background thread)
    @ReactMethod
    fun generate(text: String, sid: Int, speed: Double, options: ReadableMap?, promise: Promise) {
        // Run on a background thread
        thread {
            try {
                val engine = tts ?: run {
                    promise.reject("E_TTS_NOT_INIT", "TTS not initialized")
                    return@thread // Exit thread
                }
                val trimmedText = text.trim()
                if (trimmedText.isEmpty()) {
                    promise.reject("EMPTY_TEXT", "Input text is empty")
                    return@thread // Exit thread
                }

                // (All your excellent options parsing logic is unchanged)
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

                // (All your audio generation logic is unchanged)
                val sentences = splitText(trimmedText, 15)
                val all = ArrayList<Float>()
                var sr = 24000 // Default, will be updated

                for (sentence in sentences) {
                    val processed = if (sentence.endsWith(".")) sentence else "$sentence."
                    val audio = engine.generate(processed, sid, speed.toFloat())
                    if (audio != null) {
                        if (all.isEmpty()) sr = audio.sampleRate // Get sample rate from first chunk
                        all.addAll(audio.samples.toList())
                    }
                }

                if (all.isEmpty()) {
                    promise.reject("GENERATION_ERROR", "No audio produced")
                    return@thread // Exit thread
                }

                val arr = FloatArray(all.size)
                for (i in all.indices) arr[i] = all[i]

                // (Your WAV writing and validation is unchanged)
                writeWav16Mono(outFile, arr, sr)

                if (!outFile.exists() || outFile.length() <= 44L) {
                    throw RuntimeException("Empty WAV at ${outFile.absolutePath}")
                }

                promise.resolve(outFile.absolutePath)
            } catch (t: Throwable) {
                promise.reject("E_TTS_GENERATE", t.message, t)
            }
        }
    }

    // Deinitialize method (Unchanged)
    @ReactMethod
    fun deinitialize() {
        // ... (This function is fine as-is) ...
    }

    // Helper: split text (Unchanged)
    private fun splitText(text: String, maxWords: Int): List<String> {
        // ... (This function is fine as-is) ...
    }

    // Helper: generateAudio (Unchanged)
    private fun generateAudio(text: String, sid: Int, speed: Float) {
        // ... (This function is fine as-is) ...
    }

    // Helper: sendVolumeUpdate (Unchanged)
    private fun sendVolumeUpdate(volume: Float) {
        // ... (This function is fine as-is) ...
    }
}