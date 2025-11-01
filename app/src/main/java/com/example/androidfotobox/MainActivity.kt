package com.example.androidfotobox

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.androidfotobox.ui.theme.AndroidFotoboxTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import android.util.Size
import androidx.compose.ui.draw.clip
import androidx.camera.core.ImageCaptureException

class MainActivity : ComponentActivity() {

    private lateinit var cameraController: LifecycleCameraController
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
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        }

        setupSpeechRecognizer()

        setContent {
            AndroidFotoboxTheme {
                val listening by listeningState.collectAsState()
                val message by voiceMessage.collectAsState()

                FotoboxScreen(
                    controller = cameraController,
                    captureTrigger = captureTrigger,
                    isListening = listening,
                    voiceMessage = message,
                    onStartListening = ::startListening,
                    onStopListening = ::stopListening,
                    onManualCapture = { captureTrigger.tryEmit(Unit) }
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
        super.onDestroy()
        speechRecognizer?.destroy()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
}

private data class ResolutionOption(val label: String, val size: Size?)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun FotoboxScreen(
    controller: LifecycleCameraController,
    captureTrigger: SharedFlow<Unit>,
    isListening: Boolean,
    voiceMessage: String?,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onManualCapture: () -> Unit
) {
    val permissionsState = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var useFrontCamera by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf(3f) }
    var countdownRemaining by remember { mutableStateOf<Int?>(null) }
    var showPermissionHint by remember { mutableStateOf(false) }

    val resolutionOptions = remember {
        listOf(
            ResolutionOption("Maximal", null),
            ResolutionOption("12 MP", Size(4000, 3000)),
            ResolutionOption("8 MP", Size(3264, 2448)),
            ResolutionOption("5 MP", Size(2592, 1944))
        )
    }
    var selectedResolution by remember { mutableStateOf(resolutionOptions.first()) }

    val coroutineScope = rememberCoroutineScope()

    DisposableCameraBinding(controller = controller, lifecycleOwner = lifecycleOwner, useFrontCamera = useFrontCamera)

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
            showPermissionHint = true
        } else {
            showPermissionHint = false
        }
    }

    var countdownJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(captureTrigger) {
        captureTrigger.collect {
            countdownJob?.cancel()
            countdownJob = triggerCountdown(
                scope = coroutineScope,
                seconds = countdownSeconds.roundToInt().coerceAtLeast(0),
                onTick = { countdownRemaining = it },
                onComplete = {
                    countdownRemaining = null
                    capturePhoto(context, controller, selectedResolution)
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (permissionsState.allPermissionsGranted) {
            CameraPreview(
                controller = controller,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            onManualCapture()
                        }
                    }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.bluetooth_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                voiceMessage?.let {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                if (showPermissionHint) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.permission_required),
                        color = Color.Red,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                countdownRemaining?.let { remaining ->
                    Text(
                        text = remaining.toString(),
                        color = Color.White,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.size(16.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.countdown_label) + ": ${countdownSeconds.roundToInt()}s",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = countdownSeconds,
                            onValueChange = { countdownSeconds = it.roundToInt().toFloat() },
                            valueRange = 0f..10f,
                            steps = 9
                        )

                        Spacer(modifier = Modifier.size(12.dp))

                        Box {
                            Button(onClick = { expanded = true }) {
                                Text(selectedResolution.label)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                resolutionOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            selectedResolution = option
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.size(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            useFrontCamera = !useFrontCamera
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.DarkGray.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }

                    Button(
                        onClick = { onManualCapture() },
                        modifier = Modifier
                            .height(72.dp)
                            .width(160.dp)
                    ) {
                        Text(text = stringResource(R.string.capture_button))
                    }

                    IconButton(
                        onClick = {
                            if (isListening) onStopListening() else onStartListening()
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.DarkGray.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isListening) Color.Red else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(controller: LifecycleCameraController, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            androidx.camera.view.PreviewView(context).apply {
                scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        }
    )
}

@Composable
private fun DisposableCameraBinding(
    controller: LifecycleCameraController,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    useFrontCamera: Boolean
) {
    val cameraSelector = if (useFrontCamera) {
        CameraSelector.DEFAULT_FRONT_CAMERA
    } else {
        CameraSelector.DEFAULT_BACK_CAMERA
    }

    DisposableEffect(lifecycleOwner, useFrontCamera) {
        controller.cameraSelector = cameraSelector
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
        }
    }
}

private fun triggerCountdown(
    scope: CoroutineScope,
    seconds: Int,
    onTick: (Int?) -> Unit,
    onComplete: () -> Unit
): Job {
    return scope.launch {
        if (seconds <= 0) {
            onTick(null)
            onComplete()
            return@launch
        }
        for (time in seconds downTo 1) {
            onTick(time)
            delay(1000)
        }
        onTick(null)
        onComplete()
    }
}

private fun capturePhoto(
    context: android.content.Context,
    controller: LifecycleCameraController,
    resolutionOption: ResolutionOption
) {
    controller.imageCaptureTargetSize = resolutionOption.size?.let { size ->
        CameraController.OutputSize(size)
    }

    val resolver = context.contentResolver
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Foto_$timestamp.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Fotobox")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        resolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    controller.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                Toast.makeText(context, context.getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(
                    context,
                    context.getString(R.string.photo_error) + ": ${exception.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    )
}
