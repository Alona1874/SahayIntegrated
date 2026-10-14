package com.example.pathfinderindoor.indoor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeechCapture(
    private val context: Context
) {

    private var recognizer: SpeechRecognizer? = null

    var onResult: ((String) -> Unit)? = null

    var onError: ((String) -> Unit)? = null

    var onErrorCode: ((Int, String) -> Unit)? = null

    var onListeningStarted: (() -> Unit)? = null

    @Volatile
    private var isListeningActive = false
    val isListening: Boolean get() = isListeningActive

    private val mainHandler =
        Handler(Looper.getMainLooper())

    /*
     * Create the recognizer only once.
     */
    private fun createRecognizer() {

        if (recognizer != null) {
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {

            onError?.invoke(
                "Speech recognition is not available on this phone."
            )

            return
        }

        recognizer =
            SpeechRecognizer.createSpeechRecognizer(context)

        recognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {
                    isListeningActive = true
                    mainHandler.post {
                        onListeningStarted?.invoke()
                    }
                }

                override fun onBeginningOfSpeech() {
                }

                override fun onRmsChanged(
                    rmsdB: Float
                ) {
                }

                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {
                }

                override fun onEndOfSpeech() {
                }

                override fun onResults(
                    results: Bundle?
                ) {

                    val matches =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                    val best =
                        matches?.firstOrNull()

                    mainHandler.post {
                        isListeningActive = false
                        if (!best.isNullOrBlank()) {
                            onResult?.invoke(best)
                        } else {
                            onError?.invoke(
                                "No speech was recognized. Please try again."
                            )
                        }
                    }
                }

                override fun onError(
                    error: Int
                ) {
                    val message =
                        when (error) {
                            SpeechRecognizer.ERROR_AUDIO ->
                                "Microphone audio error."

                            SpeechRecognizer.ERROR_CLIENT ->
                                "Speech recognition client error."

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Microphone permission is required."

                            SpeechRecognizer.ERROR_NETWORK ->
                                "Network error. Check your internet connection."

                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Network timeout. Check your internet connection."

                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "I could not understand the speech. Please try again."

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Speech recognizer is busy. Please try again."

                            SpeechRecognizer.ERROR_SERVER ->
                                "Speech recognition server error."

                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                                "Speech recognition service disconnected."

                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "I did not hear anything. Please speak again."

                            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                                "Too many speech requests. Please wait a moment."

                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                                "The selected speech language is not supported."

                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                "The selected speech language is unavailable."

                            else ->
                                "Speech recognition error: $error"
                        }

                    mainHandler.post {
                        isListeningActive = false
                        onErrorCode?.invoke(error, message)
                        onError?.invoke(message)
                    }
                }

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {
                }
            }
        )
    }

    /*
     * Start listening.
     *
     * Everything is deliberately executed on
     * Android's main thread.
     */
    fun startListening() {

        mainHandler.post {

            createRecognizer()

            val speechRecognizer =
                recognizer ?: return@post

            /*
             * Make sure an old recognition session
             * is not still running.
             */
            try {
                speechRecognizer.cancel()
            } catch (_: Exception) {
            }

            val intent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    val lang = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        lang
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        lang
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        false
                    )
                }

            try {

                speechRecognizer.startListening(intent)

            } catch (e: Exception) {

                onError?.invoke(
                    "Could not start speech recognition."
                )
            }
        }
    }

    fun stopListening() {
        isListeningActive = false
        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
        }
    }

    fun cancelListening() {
        isListeningActive = false
        mainHandler.post {
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
            }
        }
    }

    fun destroy() {
        isListeningActive = false
        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
            }

            recognizer = null
        }
    }
}
