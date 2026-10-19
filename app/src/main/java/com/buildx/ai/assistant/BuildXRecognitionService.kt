package com.buildx.ai.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Declared only because the assistant declaration asks for one. It never recognizes anything. */
class BuildXRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
