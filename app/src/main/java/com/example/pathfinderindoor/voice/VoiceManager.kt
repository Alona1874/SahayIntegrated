package com.example.pathfinderindoor.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared voice output layer that wraps Android's TextToSpeech.
 * Provides reliable queueing, initialization safety, and audio gating to prevent mic collisions.
 */
class VoiceManager(context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile
    private var isReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val pendingQueue = mutableListOf<Triple<String, Int, (() -> Unit)?>>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
                setupListener()
                isReady = true
                mainHandler.post {
                    flushPendingQueue()
                }
            }
        }
    }

    var onSpeechStarted: (() -> Unit)? = null
    @Volatile
    private var isSpeakingNow = false

    val isSpeaking: Boolean get() = isSpeakingNow || tts?.isSpeaking == true

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeakingNow = true
                mainHandler.post {
                    onSpeechStarted?.invoke()
                }
            }

            override fun onDone(utteranceId: String?) {
                isSpeakingNow = false
                if (utteranceId != null) {
                    val callback = callbacks.remove(utteranceId)
                    if (callback != null) {
                        // 250ms audio gating delay: gives the physical phone speaker time to finish
                        // audio playback before the microphone opens, preventing acoustic echo collisions.
                        mainHandler.postDelayed({
                            callback()
                        }, 250)
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                isSpeakingNow = false
                if (utteranceId != null) {
                    val callback = callbacks.remove(utteranceId)
                    if (callback != null) {
                        mainHandler.post {
                            callback()
                        }
                    }
                }
            }
        })
    }

    private fun flushPendingQueue() {
        synchronized(pendingQueue) {
            for (item in pendingQueue) {
                speakInternal(item.first, item.second, item.third)
            }
            pendingQueue.clear()
        }
    }

    /**
     * Speak routine text, queued normally (QUEUE_ADD).
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        speakInternal(text, TextToSpeech.QUEUE_ADD, onDone)
    }

    /**
     * Speak urgent text (e.g. hazard alerts), interrupting current speech (QUEUE_FLUSH).
     */
    fun speakUrgent(text: String, onDone: (() -> Unit)? = null) {
        callbacks.clear()
        speakInternal(text, TextToSpeech.QUEUE_FLUSH, onDone)
    }

    private fun speakInternal(text: String, queueMode: Int, onDone: (() -> Unit)?) {
        if (!isReady) {
            synchronized(pendingQueue) {
                if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                    pendingQueue.clear()
                }
                pendingQueue.add(Triple(text, queueMode, onDone))
            }
            return
        }

        val utteranceId = "voice_${System.currentTimeMillis()}_${(0..999).random()}"
        if (onDone != null) {
            callbacks[utteranceId] = onDone
        }

        tts?.speak(text, queueMode, null, utteranceId)
    }

    fun stop() {
        callbacks.clear()
        tts?.stop()
    }

    fun shutdown() {
        callbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
