package com.example.androidfotobox.ui

import android.Manifest
import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.menuAnchor
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.androidfotobox.R
import com.example.androidfotobox.canon.CanonConnectionState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Size

private data class ResolutionOption(val label: String, val size: Size?)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FotoboxScreen(
    controller: LifecycleCameraController,
    captureTrigger: SharedFlow<Unit>,
    isListening: Boolean,
    voiceMessage: String?,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onManualCapture: () -> Unit,
    canonState: CanonConnectionState,
    canonEnabled: Boolean,
    onCanonToggle: (Boolean) -> Unit,
    onCanonCapture: () -> Unit,
    onCanonRetry: () -> Unit
) {
    val permissionsState = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var useFrontCamera by remember { mutableStateOf(true) }
    var resolutionExpanded by remember { mutableStateOf(false) }
    var countdownExpanded by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf(3) }
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
    val latestCanonState by rememberUpdatedState(canonState)
    val latestCanonEnabled by rememberUpdatedState(canonEnabled)
    val latestCanonCapture by rememberUpdatedState(onCanonCapture)

    DisposableCameraBinding(
        controller = controller,
        lifecycleOwner = lifecycleOwner,
        useFrontCamera = useFrontCamera
    )

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
            showPermissionHint = true
        } else {
            showPermissionHint = false
        }
    }

    LaunchedEffect(canonEnabled) {
        if (canonEnabled) {
            resolutionExpanded = false
            countdownExpanded = false
        }
    }

    var countdownJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(captureTrigger) {
        captureTrigger.collect {
            countdownJob?.cancel()
            countdownJob = triggerCountdown(
                scope = coroutineScope,
                seconds = countdownSeconds.coerceAtLeast(0),
                onTick = { countdownRemaining = it },
                onComplete = {
                    countdownRemaining = null
                    if (latestCanonEnabled) {
                        if (latestCanonState is CanonConnectionState.Ready) {
                            latestCanonCapture()
                        } else {
                            Toast.makeText(context, context.getString(R.string.canon_not_ready), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        capturePhoto(context, controller, selectedResolution)
                    }
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
                            .padding(16.dp)
                    ) {
                        val statusText: String
                        val statusColor: Color
                        when (val state = canonState) {
                            is CanonConnectionState.Disconnected -> {
                                statusText = stringResource(R.string.canon_status_disconnected)
                                statusColor = Color.White
                            }
                            is CanonConnectionState.RequestingPermission -> {
                                statusText = stringResource(R.string.canon_status_permission)
                                statusColor = Color.White
                            }
                            is CanonConnectionState.Connecting -> {
                                statusText = stringResource(R.string.canon_status_connecting)
                                statusColor = Color.White
                            }
                            is CanonConnectionState.Ready -> {
                                statusText = stringResource(R.string.canon_status_ready)
                                statusColor = Color.Green
                            }
                            is CanonConnectionState.Capturing -> {
                                statusText = stringResource(R.string.canon_status_capturing)
                                statusColor = Color.Yellow
                            }
                            is CanonConnectionState.Error -> {
                                statusText = stringResource(R.string.canon_status_error, state.message)
                                statusColor = Color.Red
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.canon_enable_label),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = statusText,
                                    color = statusColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Switch(
                                checked = canonEnabled,
                                onCheckedChange = { enabled -> onCanonToggle(enabled) }
                            )
                        }

                        if (canonEnabled) {
                            Spacer(modifier = Modifier.size(12.dp))
                            OutlinedButton(onClick = onCanonCapture, enabled = canonState is CanonConnectionState.Ready) {
                                Text(text = stringResource(R.string.capture_button))
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.canon_resolution_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                            if (canonState is CanonConnectionState.Error) {
                                Spacer(modifier = Modifier.size(8.dp))
                                OutlinedButton(onClick = onCanonRetry) {
                                    Text(text = stringResource(R.string.canon_retry))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.size(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { if (!canonEnabled) countdownExpanded = true },
                            enabled = !canonEnabled
                        ) {
                            Text(
                                text = stringResource(R.string.countdown_label) + ": ${countdownSeconds}s",
                                color = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = countdownExpanded && !canonEnabled,
                            onDismissRequest = { countdownExpanded = false }
                        ) {
                            (0..10).forEach { seconds ->
                                DropdownMenuItem(
                                    text = { Text("${seconds}s") },
                                    onClick = {
                                        countdownSeconds = seconds
                                        countdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        Button(
                            onClick = { if (!canonEnabled) resolutionExpanded = true },
                            enabled = !canonEnabled
                        ) {
                            Text(selectedResolution.label)
                        }
                        DropdownMenu(
                            expanded = resolutionExpanded && !canonEnabled,
                            onDismissRequest = { resolutionExpanded = false }
                        ) {
                            resolutionOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        selectedResolution = option
                                        resolutionExpanded = false
                                    },
                                    enabled = !canonEnabled
                                )
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
                        onClick = { useFrontCamera = !useFrontCamera },
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
                        onClick = { if (isListening) onStopListening() else onStartListening() },
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
        androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
    } else {
        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
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
    context: Context,
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
