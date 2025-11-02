package com.example.androidfotobox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.ImageCapture
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.androidfotobox.canon.CanonUsbController
import com.example.androidfotobox.ui.FotoboxScreen
import com.example.androidfotobox.ui.theme.AndroidFotoboxTheme
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var cameraController: LifecycleCameraController
    private lateinit var canonController: CanonUsbController

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent

    private val captureTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val listeningState = MutableStateFlow(false)
    private val voiceMessage = MutableStateFlow<String?>(null)
    private var shouldRestartVoice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraController = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        canonController = CanonUsbController(this)
        setupSpeechRecognizer()

        setContent {
            AndroidFotoboxTheme {
                val listening by listeningState.collectAsStateWithLifecycle()
                val message by voiceMessage.collectAsStateWithLifecycle()
                val canonState by canonController.state.collectAsStateWithLifecycle()
                val canonEnabled by canonController.isEnabled.collectAsStateWithLifecycle()

                FotoboxScreen(
                    controller = cameraController,
                    captureTrigger = captureTrigger,
                    isListening = listening,
                    voiceMessage = message,
                    onStartListening = ::startListening,
                    onStopListening = ::stopListening,
                    onManualCapture = { captureTrigger.tryEmit(Unit) },
                    canonState = canonState,
                    canonEnabled = canonEnabled,
                    onCanonToggle = canonController::setEnabled,
                    onCanonCapture = ::captureWithCanon,
                    onCanonRetry = canonController::connect
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                captureTrigger.tryEmit(Unit)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        canonController.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceMessage.value = getString(R.string.voice_error)
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    voiceMessage.value = getString(R.string.voice_prompt)
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    listeningState.value = false
                }

                override fun onError(error: Int) {
                    listeningState.value = false
                    voiceMessage.value = getString(R.string.voice_error)
                    if (shouldRestartVoice) {
                        startListening()
                    }
                }

                override fun onResults(results: Bundle?) {
                    listeningState.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognized = matches?.firstOrNull { match ->
                        match.contains("cheese", ignoreCase = true) ||
                            match.contains("foto", ignoreCase = true) ||
                            match.contains("photo", ignoreCase = true)
                    }
                    if (recognized != null) {
                        voiceMessage.value = getString(R.string.capture_button)
                        captureTrigger.tryEmit(Unit)
                    } else {
                        voiceMessage.value = getString(R.string.voice_prompt)
                    }
                    if (shouldRestartVoice) {
                        startListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
    }

    private fun startListening() {
        if (listeningState.value) return
        if (speechRecognizer == null) {
            Toast.makeText(this, R.string.voice_error, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.startListening(speechIntent)
        listeningState.value = true
        voiceMessage.value = getString(R.string.voice_prompt)
        shouldRestartVoice = true
    }

    private fun stopListening() {
        if (!listeningState.value) return
        speechRecognizer?.stopListening()
        listeningState.value = false
        shouldRestartVoice = false
    }

    private fun captureWithCanon() {
        lifecycleScope.launch {
            val result = canonController.capturePhoto()
            if (result.isSuccess) {
                Toast.makeText(this@MainActivity, getString(R.string.canon_capture_success), Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull()?.localizedMessage ?: getString(R.string.canon_capture_unknown)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.canon_capture_failed, message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

