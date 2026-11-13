package com.phoneinteraction.free.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

// Two distinct start failures so the service can show the right toast; values are not UI strings.
enum class VoiceStartError { MODEL_LOAD_FAILED, AUDIO_INIT_FAILED }

// Keyword spotting uses the sherpa-onnx KWS model: wake words and command words share one
// keywords file, chosen by UI language (VoiceKeywords.assetPath); a wake word revives the app,
// a command word emits a VoiceCommandEvent. Inline keywords can only be appended, never
// replaced, so a language switch must rebuild the spotter (restart).
class VoicePipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onKeyword: (String) -> Unit
) {

    private var kws: KeywordSpotter? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val startMutex = Mutex()

    // Start-failure channel: StateFlow instead of SharedFlow(replay=0); the service attaches its
    // collector after pipeline creation, so early emissions must be retained. Reset via consumeStartError.
    private val _startErrors = MutableStateFlow<VoiceStartError?>(null)
    val startErrors: StateFlow<VoiceStartError?> = _startErrors

    @Volatile
    private var running = false

    // Language actually loaded: decides which keywords file createSpotter uses, regardless of
    // AppCompat locale overrides; the service reads it via isChinese for wake-word decisions.
    @Volatile
    private var chinese = true

    // Desired language, kept separate from the loaded one so rapid switching always converges on
    // the latest target instead of a stale loaded value.
    @Volatile
    private var targetChinese = true

    // The user's real on/off intent: a language restart is a background rebuild and must not flip
    // it, so startInternal skips the rebuild if voice was turned off while a restart was queued.
    @Volatile
    private var desiredRunning = false

    // Keeps voiceRunning from dropping to false mid-restart, so the service's all-off watcher does
    // not mistake a language switch for idle and stopSelf; a counter handles overlapping restarts.
    private val restartCount = AtomicInteger(0)

    // Keyword-list language actually loaded by the engine; wake-word matching must follow it
    val isChinese: Boolean get() = chinese

    // Expected keyword-list language; the service restart guard uses it to decide whether a restart is needed
    val targetIsChinese: Boolean get() = targetChinese

    // Clears the error after the service has shown it; compareAndSet never clobbers an error
    // written during consumption.
    fun consumeStartError(expected: VoiceStartError): Boolean =
        _startErrors.compareAndSet(expected, null)

    fun start(chinese: Boolean) {
        desiredRunning = true
        targetChinese = chinese
        scope.launch { startInternal(chinese) }
    }

    // Rebuilds the spotter with the new language's keywords file; stop and start must run
    // sequentially here, or a concurrent start could finish with the old file.
    fun restart(chinese: Boolean) {
        targetChinese = chinese
        scope.launch {
            restartCount.incrementAndGet()
            try {
                stopInternal(keepState = true)
                // Read the latest target so rapid switching converges on the last choice.
                startInternal(targetChinese)
            } finally {
                // Fallback state write-back when rebuild fails (running stays false) so the UI never shows a false running state
                if (restartCount.decrementAndGet() == 0 && !running) {
                    ServiceState.setVoiceRunning(false)
                }
            }
        }
    }

    // stop and startInternal serialize on startMutex: set running=false to unblock read, then join
    // the capture loop before releasing; kws.release() would natively crash an in-flight decode.
    fun stop() {
        desiredRunning = false
        scope.launch { stopInternal() }
    }

    private suspend fun stopInternal(keepState: Boolean = false) {
        startMutex.withLock {
            running = false
            // Restart keeps the running state so the service's idle self-stop is not triggered.
            if (!keepState) ServiceState.setVoiceRunning(false)
            captureJob?.cancelAndJoin()
            captureJob = null
            audioRecord?.let { r ->
                runCatching { r.stop() }
                runCatching { r.release() }
            }
            audioRecord = null
            kws?.release()
            kws = null
        }
    }

    private suspend fun startInternal(chinese: Boolean) {
        startMutex.withLock {
            // Skip the rebuild if the user turned voice off while a restart was queued.
            if (running || !desiredRunning) return@withLock
            val spotter = try {
                // Model load must not block the main thread.
                withContext(Dispatchers.Default) { createSpotter(chinese) }
            } catch (e: CancellationException) {
                // Cancellation must propagate; do not let catch(Exception) swallow it.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KWS model load failed", e)
                _startErrors.value = VoiceStartError.MODEL_LOAD_FAILED
                return@withLock
            }
            val record = try {
                createAudioRecord()
            } catch (e: CancellationException) {
                spotter.release()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord create failed", e)
                _startErrors.value = VoiceStartError.AUDIO_INIT_FAILED
                spotter.release()
                return@withLock
            }
            kws = spotter
            audioRecord = record
            // Confirm the loaded language only after a successful load; on failure keep the old
            // value so isChinese stays accurate.
            this.chinese = chinese
            running = true
            ServiceState.setVoiceRunning(true)
            captureJob = scope.launch(Dispatchers.Default) {
                captureLoop(record, spotter)
            }
        }
    }

    private fun createSpotter(chinese: Boolean): KeywordSpotter {
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                modelType = "zipformer2"
            ),
            keywordsFile = VoiceKeywords.assetPath(chinese)
        )
        return KeywordSpotter(context.assets, config)
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        // 16 kHz mono PCM16; buffer at least 2x the minimum to avoid underruns.
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 3200)
        )
        record.startRecording()
        // AudioRecord construction fails silently (STATE_UNINITIALIZED); unchecked, read loops forever.
        if (record.state != AudioRecord.STATE_INITIALIZED ||
            record.recordingState != AudioRecord.RECORDSTATE_RECORDING
        ) {
            runCatching { record.stop() }
            runCatching { record.release() }
            throw IllegalStateException("AudioRecord init failed or cannot start recording")
        }
        return record
    }

    // Capture loop on Dispatchers.Default: reset after a hit so the same audio cannot re-trigger;
    // release the stream explicitly (GC would delay it) and clear running on exit so restart works.
    private fun captureLoop(record: AudioRecord, spotter: KeywordSpotter) {
        val stream = spotter.createStream()
        val buf = ShortArray(SAMPLE_RATE / 10)
        var consecutiveErrors = 0
        try {
            while (running) {
                val len = record.read(buf, 0, buf.size)
                if (len <= 0) {
                    consecutiveErrors++
                    // Five consecutive failures (~500ms) mean the audio channel is dead; exit to avoid busy-spinning.
                    if (consecutiveErrors >= 5) {
                        Log.e(TAG, "AudioRecord read failed repeatedly, exiting capture loop")
                        running = false
                        break
                    }
                    continue
                }
                consecutiveErrors = 0
                val samples = FloatArray(len) { buf[it] / 32768f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword.trim()
                    if (keyword.isNotBlank()) {
                        spotter.reset(stream)
                        onKeyword(keyword)
                        break
                    }
                }
            }
        } catch (_: IllegalStateException) {
            // After stop() an in-flight read/decode throws ISE; swallow it.
            running = false
        } finally {
            // Always publish the final state so the home toggle matches reality, except during a
            // language restart, where restart() owns the state (see restartCount).
            if (restartCount.get() == 0) ServiceState.setVoiceRunning(false)
            runCatching { record.stop() }
            runCatching { stream.release() }
        }
    }

    companion object {
        private const val TAG = "VoicePipeline"
        const val MODEL_DIR = "kws-zh-en-3M"
        const val SAMPLE_RATE = 16000
    }
}
