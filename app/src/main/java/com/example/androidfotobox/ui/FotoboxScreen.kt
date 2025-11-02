package com.example.androidfotobox.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Size
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

private data class ResolutionOption(val labelRes: Int, val size: Size?)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
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
    var useFrontCamera by rememberSaveable { mutableStateOf(true) }
    var resolutionExpanded by remember { mutableStateOf(false) }
    var countdownExpanded by remember { mutableStateOf(false) }
    var countdownSeconds by rememberSaveable { mutableIntStateOf(3) }
    var countdownRemaining by remember { mutableStateOf<Int?>(null) }
    var showPermissionHint by remember { mutableStateOf(false) }

    val resolutionOptions = remember {
        listOf(
            ResolutionOption(R.string.resolution_option_max, null),
            ResolutionOption(R.string.resolution_option_12mp, Size(4000, 3000)),
            ResolutionOption(R.string.resolution_option_8mp, Size(3264, 2448)),
            ResolutionOption(R.string.resolution_option_5mp, Size(2592, 1944))
        )
    }
    var selectedResolutionIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedResolution = resolutionOptions[selectedResolutionIndex]

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

    val backgroundColor = MaterialTheme.colorScheme.background
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
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
                    color = onBackgroundColor,
                    textAlign = TextAlign.Center
                )
                voiceMessage?.let {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBackgroundColor,
                        textAlign = TextAlign.Center
                    )
                }
                if (showPermissionHint) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.permission_required),
                        color = MaterialTheme.colorScheme.error,
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
                        color = onBackgroundColor,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.size(16.dp))

                CanonStatusCard(
                    canonState = canonState,
                    canonEnabled = canonEnabled,
                    onCanonToggle = onCanonToggle,
                    onCanonCapture = onCanonCapture,
                    onCanonRetry = onCanonRetry
                )

                Spacer(modifier = Modifier.size(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CountdownSelector(
                        expanded = countdownExpanded,
                        onExpandedChange = { countdownExpanded = it },
                        selectedSeconds = countdownSeconds,
                        enabled = !canonEnabled,
                        onSelect = { value ->
                            countdownSeconds = value
                            countdownExpanded = false
                        }
                    )

                    ResolutionSelector(
                        expanded = resolutionExpanded,
                        onExpandedChange = { resolutionExpanded = it },
                        selectedOption = selectedResolution,
                        resolutionOptions = resolutionOptions,
                        enabled = !canonEnabled,
                        onSelect = { index ->
                            selectedResolutionIndex = index
                            resolutionExpanded = false
                        }
                    )
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
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = stringResource(R.string.flip_camera_content_desc),
                            tint = onBackgroundColor
                        )
                    }

                    Button(
                        onClick = onManualCapture,
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
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = stringResource(
                                if (isListening) R.string.stop_listening_desc else R.string.start_listening_desc
                            ),
                            tint = if (isListening) MaterialTheme.colorScheme.error else onBackgroundColor
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountdownSelector(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedSeconds: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    val labelText = if (selectedSeconds == 0) {
        stringResource(R.string.countdown_off)
    } else {
        stringResource(R.string.countdown_value_format, selectedSeconds)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                onExpandedChange(!expanded)
            }
        }
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(),
            value = labelText,
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.countdown_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            colors = TextFieldDefaults.outlinedTextFieldColors()
        )

        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            (0..10).forEach { seconds ->
                DropdownMenuItem(
                    text = {
                        val optionText = if (seconds == 0) {
                            stringResource(R.string.countdown_off)
                        } else {
                            stringResource(R.string.countdown_value_format, seconds)
                        }
                        Text(optionText)
                    },
                    onClick = { onSelect(seconds) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionSelector(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedOption: ResolutionOption,
    resolutionOptions: List<ResolutionOption>,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                onExpandedChange(!expanded)
            }
        }
    ) {
        OutlinedTextField(
            modifier = Modifier.menuAnchor(),
            value = stringResource(id = selectedOption.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.resolution_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            colors = TextFieldDefaults.outlinedTextFieldColors()
        )

        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            resolutionOptions.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = option.labelRes)) },
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun CanonStatusCard(
    canonState: CanonConnectionState,
    canonEnabled: Boolean,
    onCanonToggle: (Boolean) -> Unit,
    onCanonCapture: () -> Unit,
    onCanonRetry: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val statusText: String
            val statusColor = when (val state = canonState) {
                is CanonConnectionState.Disconnected -> {
                    statusText = stringResource(R.string.canon_status_disconnected)
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                is CanonConnectionState.RequestingPermission -> {
                    statusText = stringResource(R.string.canon_status_permission)
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                is CanonConnectionState.Connecting -> {
                    statusText = stringResource(R.string.canon_status_connecting)
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                is CanonConnectionState.Ready -> {
                    statusText = stringResource(R.string.canon_status_ready)
                    MaterialTheme.colorScheme.tertiary
                }
                is CanonConnectionState.Capturing -> {
                    statusText = stringResource(R.string.canon_status_capturing)
                    MaterialTheme.colorScheme.primary
                }
                is CanonConnectionState.Error -> {
                    statusText = stringResource(R.string.canon_status_error, state.message)
                    MaterialTheme.colorScheme.error
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
                    onCheckedChange = onCanonToggle,
                    colors = SwitchDefaults.colors()
                )
            }

            if (canonEnabled) {
                Spacer(modifier = Modifier.size(12.dp))
                OutlinedButton(
                    onClick = onCanonCapture,
                    enabled = canonState is CanonConnectionState.Ready
                ) {
                    Text(text = stringResource(R.string.capture_button))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.canon_resolution_note),
                    style = MaterialTheme.typography.bodySmall
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
