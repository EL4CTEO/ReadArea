package com.readarea.qa

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.Locale

@Implements(TextToSpeech::class)
class QuietTts {
    lateinit var init: TextToSpeech.OnInitListener
    var listener: UtteranceProgressListener? = null
    val spoken = mutableListOf<Pair<String, String>>()
    var stops = 0

    @Implementation
    fun __constructor__(context: Context, listener: TextToSpeech.OnInitListener, engine: String?, packageName: String?, useFallback: Boolean) {
        init = listener
        last = this
    }

    @Implementation
    fun initTts(): Int = TextToSpeech.SUCCESS

    @Implementation
    fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        spoken += text.toString() to utteranceId
        return TextToSpeech.SUCCESS
    }

    @Implementation
    fun setOnUtteranceProgressListener(l: UtteranceProgressListener): Int {
        listener = l
        return TextToSpeech.SUCCESS
    }

    @Implementation
    fun stop(): Int {
        stops++
        return TextToSpeech.SUCCESS
    }

    @Implementation
    fun shutdown() {}

    @Implementation
    fun isLanguageAvailable(loc: Locale): Int = TextToSpeech.LANG_AVAILABLE

    @Implementation
    fun setLanguage(loc: Locale): Int = TextToSpeech.LANG_AVAILABLE

    @Implementation
    fun setSpeechRate(rate: Float): Int = TextToSpeech.SUCCESS

    @Implementation
    fun setPitch(pitch: Float): Int = TextToSpeech.SUCCESS

    companion object {
        var last: QuietTts? = null
    }
}
