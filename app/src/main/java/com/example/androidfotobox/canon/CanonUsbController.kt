package com.example.androidfotobox.canon

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.androidfotobox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.jvm.Volatile

class CanonUsbController(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()

    private val _state = MutableStateFlow<CanonConnectionState>(CanonConnectionState.Disconnected)
    val state: StateFlow<CanonConnectionState> = _state

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    @Volatile
    private var shouldReconnect = false
    @Volatile
    private var currentDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var cameraInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    @Volatile
    private var sessionOpen = false
    private var transactionId = 1

    private val permissionIntent: PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION),
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && isSupportedDevice(device)) {
                        if (granted) {
                            openDevice(device)
                        } else {
                            _state.value = CanonConnectionState.Error(context.getString(R.string.canon_status_permission_denied))
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device == currentDevice) {
                        scope.launch {
                            cleanupConnection()
                            _state.value = CanonConnectionState.Disconnected
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && isSupportedDevice(device) && shouldReconnect) {
                        connectToDevice(device)
                    }
                }
            }
        }
    }

    private var receiverRegistered = false

    init {
        registerReceiver()
    }

    fun setEnabled(enabled: Boolean) {
        shouldReconnect = enabled
        if (_isEnabled.value == enabled) {
            if (enabled && !sessionOpen) {
                connect()
            }
            return
        }
        _isEnabled.value = enabled
        if (enabled) {
            connect()
        } else {
            scope.launch {
                cleanupConnection()
                _state.value = CanonConnectionState.Disconnected
            }
        }
    }

    fun connect() {
        if (!shouldReconnect) return
        if (sessionOpen && currentDevice != null) {
            _state.value = CanonConnectionState.Ready
            return
        }
        val device = findSupportedDevice()
        if (device != null) {
            connectToDevice(device)
        } else {
            _state.value = CanonConnectionState.Error(context.getString(R.string.canon_status_not_found))
        }
    }

    suspend fun capturePhoto(): Result<Unit> {
        if (!sessionOpen) {
            return Result.failure(IllegalStateException(context.getString(R.string.canon_not_ready)))
        }
        _state.value = CanonConnectionState.Capturing
        val result = runCatching {
            val response = sendCommand(PTP_OPERATION_INITIATE_CAPTURE, 0, 0)
            if (response != PTP_RESPONSE_OK) {
                throw IOException("PTP response 0x${response.toString(16)}")
            }
        }
        _state.value = if (result.isSuccess) {
            CanonConnectionState.Ready
        } else {
            CanonConnectionState.Error(
                result.exceptionOrNull()?.localizedMessage ?: context.getString(R.string.canon_capture_unknown)
            )
        }
        return result
    }

    fun shutdown() {
        shouldReconnect = false
        _isEnabled.value = false
        scope.launch {
            cleanupConnection()
            _state.value = CanonConnectionState.Disconnected
        }
        unregisterReceiver()
        scope.cancel()
    }

    private fun connectToDevice(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            _state.value = CanonConnectionState.RequestingPermission
            usbManager.requestPermission(device, permissionIntent)
            return
        }
        openDevice(device)
    }

    private fun openDevice(device: UsbDevice) {
        if (_state.value is CanonConnectionState.Connecting || _state.value is CanonConnectionState.Capturing) {
            return
        }
        _state.value = CanonConnectionState.Connecting
        scope.launch {
            runCatching {
                establishConnection(device)
            }.onSuccess {
                val sessionEstablished = establishSession()
                if (sessionEstablished) {
                    _state.value = CanonConnectionState.Ready
                } else {
                    cleanupConnection()
                    _state.value = CanonConnectionState.Error(context.getString(R.string.canon_status_session_failed))
                }
            }.onFailure { throwable ->
                cleanupConnection()
                _state.value = CanonConnectionState.Error(throwable.localizedMessage ?: context.getString(R.string.canon_status_generic_error))
            }
        }
    }

    private fun findSupportedDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device -> isSupportedDevice(device) }
    }

    private fun isSupportedDevice(device: UsbDevice): Boolean {
        return device.vendorId == CANON_VENDOR_ID && SUPPORTED_PRODUCT_IDS.contains(device.productId)
    }

    private suspend fun establishConnection(device: UsbDevice) {
        withContext(Dispatchers.IO) {
            val stillImageInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: throw IOException(context.getString(R.string.canon_status_interface_missing))

            val bulkIn = (0 until stillImageInterface.endpointCount)
                .map { stillImageInterface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: throw IOException(context.getString(R.string.canon_status_endpoint_missing))

            val bulkOut = (0 until stillImageInterface.endpointCount)
                .map { stillImageInterface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: throw IOException(context.getString(R.string.canon_status_endpoint_missing))

            val deviceConnection = usbManager.openDevice(device)
                ?: throw IOException(context.getString(R.string.canon_status_open_failed))

            if (!deviceConnection.claimInterface(stillImageInterface, true)) {
                deviceConnection.close()
                throw IOException(context.getString(R.string.canon_status_claim_failed))
            }

            connectionMutex.withLock {
                currentDevice = device
                connection = deviceConnection
                cameraInterface = stillImageInterface
                inEndpoint = bulkIn
                outEndpoint = bulkOut
                transactionId = 1
                sessionOpen = false
            }
        }
    }

    private suspend fun establishSession(): Boolean {
        return withContext(Dispatchers.IO) {
            val response = sendCommand(PTP_OPERATION_OPEN_SESSION, SESSION_ID)
            if (response == PTP_RESPONSE_OK) {
                sessionOpen = true
                val remoteResponse = sendCommand(PTP_OPERATION_CANON_SET_REMOTE, 1)
                if (remoteResponse != PTP_RESPONSE_OK) {
                    Log.w(TAG, "Failed to enable remote mode: 0x${remoteResponse.toString(16)}")
                }
                val eventModeResponse = sendCommand(PTP_OPERATION_CANON_SET_EVENT_MODE, 1)
                if (eventModeResponse != PTP_RESPONSE_OK) {
                    Log.w(TAG, "Failed to enable event mode: 0x${eventModeResponse.toString(16)}")
                }
                true
            } else {
                sessionOpen = false
                false
            }
        }
    }

    private suspend fun cleanupConnection() {
        connectionMutex.withLock {
            if (sessionOpen) {
                runCatching { sendCommandLocked(PTP_OPERATION_CLOSE_SESSION) }
            }
            sessionOpen = false
            currentDevice = null
            inEndpoint = null
            outEndpoint = null
            cameraInterface?.let { iface ->
                connection?.releaseInterface(iface)
            }
            cameraInterface = null
            connection?.close()
            connection = null
        }
    }

    private suspend fun sendCommand(operationCode: Int, vararg params: Int): Int {
        return withContext(Dispatchers.IO) {
            connectionMutex.withLock {
                sendCommandLocked(operationCode, *params)
            }
        }
    }

    private fun sendCommandLocked(operationCode: Int, vararg params: Int): Int {
        val deviceConnection = connection ?: throw IOException(context.getString(R.string.canon_status_open_failed))
        val output = outEndpoint ?: throw IOException(context.getString(R.string.canon_status_endpoint_missing))
        val input = inEndpoint ?: throw IOException(context.getString(R.string.canon_status_endpoint_missing))

        val length = 12 + params.size * 4
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(PTP_CONTAINER_TYPE_COMMAND.toShort())
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId++)
        params.forEach { parameter -> buffer.putInt(parameter) }

        val written = deviceConnection.bulkTransfer(output, buffer.array(), length, USB_TIMEOUT_MS)
        if (written != length) {
            throw IOException("Bulk transfer wrote $written of $length bytes")
        }

        val response = ByteArray(USB_RESPONSE_BUFFER_SIZE)
        val read = deviceConnection.bulkTransfer(input, response, response.size, USB_TIMEOUT_MS)
        if (read < 12) {
            throw IOException("Invalid PTP response length: $read")
        }

        val responseBuffer = ByteBuffer.wrap(response, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        val totalLength = responseBuffer.int
        val containerType = responseBuffer.short.toInt() and 0xFFFF
        val responseCode = responseBuffer.short.toInt() and 0xFFFF
        responseBuffer.int
        if (containerType != PTP_CONTAINER_TYPE_RESPONSE) {
            throw IOException("Unexpected container type: $containerType (length=$totalLength)")
        }
        return responseCode
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        receiverRegistered = false
    }

    companion object {
        private const val TAG = "CanonUsbController"
        private const val ACTION_USB_PERMISSION = "com.example.androidfotobox.action.USB_PERMISSION"
        private const val CANON_VENDOR_ID = 0x04A9
        private val SUPPORTED_PRODUCT_IDS = setOf(0x325A)
        private const val USB_TIMEOUT_MS = 4000
        private const val USB_RESPONSE_BUFFER_SIZE = 512
        private const val SESSION_ID = 1

        private const val PTP_CONTAINER_TYPE_COMMAND = 1
        private const val PTP_CONTAINER_TYPE_RESPONSE = 3

        private const val PTP_OPERATION_OPEN_SESSION = 0x1002
        private const val PTP_OPERATION_CLOSE_SESSION = 0x1003
        private const val PTP_OPERATION_INITIATE_CAPTURE = 0x100E

        private const val PTP_OPERATION_CANON_SET_REMOTE = 0x9114
        private const val PTP_OPERATION_CANON_SET_EVENT_MODE = 0x9115

        private const val PTP_RESPONSE_OK = 0x2001
    }
}

sealed class CanonConnectionState {
    object Disconnected : CanonConnectionState()
    object RequestingPermission : CanonConnectionState()
    object Connecting : CanonConnectionState()
    object Ready : CanonConnectionState()
    object Capturing : CanonConnectionState()
    data class Error(val message: String) : CanonConnectionState()
}
