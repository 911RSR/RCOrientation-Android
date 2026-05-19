package com.example.rc_orientation_app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.rc_orientation_app.ui.theme.RC_orientation_appTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var absoluteRollDeg = 0f
    private var absolutePitchDeg = 0f
    private var absoluteYawDeg = 0f
    private var zeroRollDeg = 0f
    private var zeroPitchDeg = 0f
    private var zeroYawDeg = 0f
    private var displayRollDeg by mutableStateOf(0f)
    private var displayPitchDeg by mutableStateOf(0f)
    private var displayYawDeg by mutableStateOf(0f)
    private var sensorAvailable by mutableStateOf(true)
    private var permissionsGranted by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)
    private var showDeviceSelection by mutableStateOf(true)
    private var showNearbyDevices by mutableStateOf(false)
    private var sequenceNumber = 0
    private var lastSensorEventNs = 0L
    private var lastCommandSendNs = 0L
    private var latestSensorPeriodMs: Float? = null
    private var latestCommandPeriodMs: Float? = null
    private var displaySensorPeriodMs by mutableStateOf<Float?>(null)
    private var displayCommandPeriodMs by mutableStateOf<Float?>(null)
    private var lastTelemetryUiUpdateNs = 0L

    private val pairedDevices = mutableStateListOf<BleDeviceItem>()
    private val discoveredDevices = mutableStateListOf<BleDeviceItem>()
    private val connectedDevices = mutableStateMapOf<String, ConnectedBleDevice>()
    private val bluetoothGatts = mutableMapOf<String, BluetoothGatt>()
    private val orientationChars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val servoFeedbackChars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val latestFeedbackByAddress = mutableMapOf<String, ServoFeedbackSnapshot>()
    private val manuallyDisconnectedAddresses = mutableSetOf<String>()
    private val reconnectAttemptsByAddress = mutableMapOf<String, Int>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = hasBlePermissions()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            val advertisedName = result.scanRecord?.deviceName
            val cachedName = try {
                if (hasConnectPermission()) device.name else null
            } catch (_: SecurityException) {
                null
            }
            val name = advertisedName ?: cachedName ?: "Unnamed BLE device"
            val advertisesOrientationService = result.scanRecord?.serviceUuids
                ?.any { it.uuid == ORIENTATION_SERVICE_UUID } == true
            val existingIndex = discoveredDevices.indexOfFirst { it.address == address }
            val item = BleDeviceItem(name, address, device, advertisesOrientationService)
            if (existingIndex >= 0) {
                discoveredDevices[existingIndex] = item
            } else {
                discoveredDevices.add(item)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorAvailable = rotationVectorSensor != null
        permissionsGranted = hasBlePermissions()
        refreshPairedDevices()
        setContent {
            RC_orientation_appTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        rollDeg = displayRollDeg,
                        pitchDeg = displayPitchDeg,
                        yawDeg = displayYawDeg,
                        sensorPeriodMs = displaySensorPeriodMs,
                        commandPeriodMs = displayCommandPeriodMs,
                        sensorAvailable = sensorAvailable,
                        permissionsGranted = permissionsGranted,
                        isScanning = isScanning,
                        showDeviceSelection = showDeviceSelection,
                        showNearbyDevices = showNearbyDevices,
                        pairedDevices = visiblePairedDevices(),
                        discoveredDevices = visibleDiscoveredDevices(),
                        connectedDevices = connectedDevices.values.sortedBy { it.name },
                        onZeroClick = ::zeroCurrentOrientation,
                        onRequestPermissionsClick = ::requestBlePermissions,
                        onToggleDeviceSelectionClick = ::toggleDeviceSelection,
                        onAddReceiverClick = ::toggleAddReceiver,
                        onDeviceClick = ::connectToDevice,
                        onDisconnectClick = ::disconnectDevice,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPairedDevices()
        rotationVectorSensor?.also { sensor -> sensorManager.registerListener(this, sensor, SENSOR_PERIOD_US) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        bluetoothGatts.values.forEach { gatt -> try { gatt.close() } catch (_: SecurityException) {} }
        bluetoothGatts.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (lastSensorEventNs != 0L) {
            latestSensorPeriodMs = (event.timestamp - lastSensorEventNs) / 1_000_000f
        }
        lastSensorEventNs = event.timestamp
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        absoluteYawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        absolutePitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        absoluteRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
        broadcastOrientationPacket()
        maybePublishTelemetryUi()
    }

    private fun zeroCurrentOrientation() {
        zeroRollDeg = absoluteRollDeg
        zeroPitchDeg = absolutePitchDeg
        zeroYawDeg = absoluteYawDeg
        publishTelemetryToUi()
    }

    private fun currentRollDeg() = relativeAngle(absoluteRollDeg, zeroRollDeg)
    private fun currentPitchDeg() = relativeAngle(absolutePitchDeg, zeroPitchDeg)
    private fun currentYawDeg() = relativeAngle(absoluteYawDeg, zeroYawDeg)

    private fun requestBlePermissions() = permissionLauncher.launch(requiredBlePermissions())

    private fun toggleDeviceSelection() {
        if (showDeviceSelection) {
            stopScan()
            showNearbyDevices = false
            showDeviceSelection = false
        } else {
            refreshPairedDevices()
            showDeviceSelection = true
        }
    }

    private fun toggleAddReceiver() {
        if (!permissionsGranted) {
            requestBlePermissions()
            return
        }
        if (showNearbyDevices) {
            stopScan()
            showNearbyDevices = false
        } else {
            showDeviceSelection = true
            showNearbyDevices = true
            startScan()
        }
    }

    private fun startScan() {
        if (!hasBlePermissions()) return
        discoveredDevices.clear()
        try {
            bleScanner.startScan(scanCallback)
            isScanning = true
        } catch (_: SecurityException) {
            permissionsGranted = false
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        try { bleScanner.stopScan(scanCallback) } catch (_: SecurityException) {}
        isScanning = false
    }

    private fun refreshPairedDevices() {
        pairedDevices.clear()
        if (!hasConnectPermission()) return
        try {
            bluetoothAdapter.bondedDevices.forEach { device ->
                val advertisesOrientationService = discoveredDevices
                    .firstOrNull { it.address == device.address }
                    ?.advertisesOrientationService == true
                pairedDevices.add(BleDeviceItem(device.name ?: "Unnamed paired device", device.address, device, advertisesOrientationService))
            }
        } catch (_: SecurityException) {}
    }

    private fun visiblePairedDevices(): List<BleDeviceItem> = pairedDevices
        .filter { isRcOrientationCandidate(it) }
        .filter { !connectedDevices.containsKey(it.address) }
        .sortedWith(compareBy<BleDeviceItem> { it.name.lowercase() }.thenBy { it.address })

    private fun visibleDiscoveredDevices(): List<BleDeviceItem> = discoveredDevices
        .filter { isRcOrientationCandidate(it) }
        .filter { !connectedDevices.containsKey(it.address) }
        .filter { pairedDevices.none { paired -> paired.address == it.address } }
        .sortedWith(
            compareBy<BleDeviceItem> { it.name == "Unnamed BLE device" }
                .thenBy { it.name.lowercase() }
                .thenBy { it.address }
        )

    private fun isRcOrientationCandidate(item: BleDeviceItem): Boolean =
        item.advertisesOrientationService || item.name.startsWith(RC_DEVICE_NAME, ignoreCase = true)

    private fun connectToDevice(item: BleDeviceItem) {
        if (!hasConnectPermission()) {
            requestBlePermissions()
            return
        }
        if (bluetoothGatts.containsKey(item.address)) return
        manuallyDisconnectedAddresses.remove(item.address)
        reconnectAttemptsByAddress.remove(item.address)
        connectedDevices[item.address] = ConnectedBleDevice(item.name, item.address, "Connecting...")
        showDeviceSelection = false
        showNearbyDevices = false
        stopScan()
        try {
            bluetoothGatts[item.address] = item.device.connectGatt(this, false, createGattCallback(item.address))
        } catch (_: SecurityException) {
            permissionsGranted = false
            connectedDevices.remove(item.address)
        }
    }

    private fun createGattCallback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttemptsByAddress.remove(address)
                    runOnUiThread {
                        connectedDevices[address] = connectedDevices[address]?.copy(status = "Connected, discovering service...")
                            ?: return@runOnUiThread
                    }
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.discoverServices()
                    } catch (_: SecurityException) {}
                }

                BluetoothProfile.STATE_CONNECTING -> runOnUiThread {
                    connectedDevices[address] = connectedDevices[address]?.copy(status = "Connecting...")
                        ?: return@runOnUiThread
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    orientationChars.remove(address)
                    servoFeedbackChars.remove(address)
                    bluetoothGatts.remove(address)
                    try { gatt.close() } catch (_: SecurityException) {}
                    runOnUiThread {
                        latestFeedbackByAddress.remove(address)
                        if (manuallyDisconnectedAddresses.remove(address) || !connectedDevices.containsKey(address)) {
                            return@runOnUiThread
                        }
                        connectedDevices[address] = connectedDevices[address]?.copy(status = "Reconnecting...")
                            ?: return@runOnUiThread
                        scheduleReconnect(address, gatt.device)
                    }
                }

                else -> runOnUiThread {
                    connectedDevices[address] = connectedDevices[address]?.copy(status = "State $newState")
                        ?: return@runOnUiThread
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(ORIENTATION_SERVICE_UUID)
            val orientationChar = service?.getCharacteristic(ORIENTATION_COMMAND_CHAR_UUID)
            val servoChar = service?.getCharacteristic(SERVO_FEEDBACK_CHAR_UUID)
            if (service == null || orientationChar == null || servoChar == null) {
                runOnUiThread { connectedDevices[address] = connectedDevices[address]?.copy(status = "Service not found") ?: return@runOnUiThread }
                return
            }
            orientationChars[address] = orientationChar
            servoFeedbackChars[address] = servoChar
            enableServoNotifications(gatt, servoChar)
            runOnUiThread { connectedDevices[address] = connectedDevices[address]?.copy(status = "Ready") ?: return@runOnUiThread }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != SERVO_FEEDBACK_CHAR_UUID || value.size < 10) return
            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            val servo0Us = buffer.short.toInt()
            val servo1Us = buffer.short.toInt()
            val servo2Us = buffer.short.toInt()
            val servo3Us = buffer.short.toInt()
            val sequence = buffer.short.toInt() and 0xFFFF
            val feedbackNowNs = System.nanoTime()
            runOnUiThread {
                val previousFeedback = latestFeedbackByAddress[address]
                val feedbackPeriodMs = previousFeedback?.lastFeedbackNs?.let { (feedbackNowNs - it) / 1_000_000f }
                val sequenceStep = previousFeedback?.lastSequence?.let { (sequence - it + 65536) and 0xFFFF }
                latestFeedbackByAddress[address] = ServoFeedbackSnapshot(
                    servo0Us = servo0Us,
                    servo1Us = servo1Us,
                    servo2Us = servo2Us,
                    servo3Us = servo3Us,
                    lastSequence = sequence,
                    lastFeedbackNs = feedbackNowNs,
                    feedbackPeriodMs = feedbackPeriodMs,
                    sequenceStep = sequenceStep
                )
                maybePublishTelemetryUi(feedbackNowNs)
            }
        }
    }

    private fun enableServoNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        } catch (_: SecurityException) {}
    }

    private fun broadcastOrientationPacket() {
        if (!hasConnectPermission() || orientationChars.isEmpty()) return
        val nowNs = System.nanoTime()
        if (lastCommandSendNs != 0L) {
            latestCommandPeriodMs = (nowNs - lastCommandSendNs) / 1_000_000f
        }
        lastCommandSendNs = nowNs
        val sequence = sequenceNumber++ and 0xFFFF
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(degreesToCentiDegrees(currentRollDeg()).toShort())
            .putShort(degreesToCentiDegrees(currentPitchDeg()).toShort())
            .putShort(degreesToCentiDegrees(currentYawDeg()).toShort())
            .putShort(sequence.toShort())
            .array()
        orientationChars.forEach { (address, characteristic) ->
            val gatt = bluetoothGatts[address] ?: return@forEach
            try {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
            } catch (_: SecurityException) {}
        }
    }

    private fun scheduleReconnect(address: String, device: BluetoothDevice) {
        val attempt = (reconnectAttemptsByAddress[address] ?: 0) + 1
        reconnectAttemptsByAddress[address] = attempt
        val delayMs = (attempt * 1_000L).coerceAtMost(5_000L)
        mainHandler.postDelayed({
            if (!connectedDevices.containsKey(address) || bluetoothGatts.containsKey(address)) return@postDelayed
            if (!hasConnectPermission()) {
                permissionsGranted = false
                connectedDevices[address]?.let { connectedDevices[address] = it.copy(status = "BLE permission needed") }
                return@postDelayed
            }
            val selectedDevice = connectedDevices[address] ?: return@postDelayed
            connectedDevices[address] = selectedDevice.copy(status = "Reconnecting...")
            try {
                bluetoothGatts[address] = device.connectGatt(this, false, createGattCallback(address))
            } catch (_: SecurityException) {
                permissionsGranted = false
                connectedDevices[address]?.let { connectedDevices[address] = it.copy(status = "BLE permission needed") }
            }
        }, delayMs)
    }

    private fun disconnectDevice(address: String) {
        manuallyDisconnectedAddresses.add(address)
        reconnectAttemptsByAddress.remove(address)
        val gatt = bluetoothGatts.remove(address)
        orientationChars.remove(address)
        servoFeedbackChars.remove(address)
        latestFeedbackByAddress.remove(address)
        connectedDevices.remove(address)
        refreshPairedDevices()
        showDeviceSelection = true
        showNearbyDevices = false
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {}
    }

    private fun maybePublishTelemetryUi(nowNs: Long = System.nanoTime()) {
        if (lastTelemetryUiUpdateNs != 0L && nowNs - lastTelemetryUiUpdateNs < UI_UPDATE_INTERVAL_NS) return
        lastTelemetryUiUpdateNs = nowNs
        publishTelemetryToUi()
    }

    private fun publishTelemetryToUi() {
        displayRollDeg = currentRollDeg()
        displayPitchDeg = currentPitchDeg()
        displayYawDeg = currentYawDeg()
        displaySensorPeriodMs = latestSensorPeriodMs
        displayCommandPeriodMs = latestCommandPeriodMs
        latestFeedbackByAddress.forEach { (address, feedback) ->
            val previous = connectedDevices[address] ?: return@forEach
            connectedDevices[address] = previous.copy(
                servo0Us = feedback.servo0Us,
                servo1Us = feedback.servo1Us,
                servo2Us = feedback.servo2Us,
                servo3Us = feedback.servo3Us,
                lastSequence = feedback.lastSequence,
                lastFeedbackNs = feedback.lastFeedbackNs,
                feedbackPeriodMs = feedback.feedbackPeriodMs,
                sequenceStep = feedback.sequenceStep
            )
        }
    }

    private fun hasBlePermissions(): Boolean = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private val ORIENTATION_SERVICE_UUID = UUID.fromString("7b5d0000-6a2e-4d6b-9e6f-8d2b7c5a1100")
        private val ORIENTATION_COMMAND_CHAR_UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
        private val SERVO_FEEDBACK_CHAR_UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RC_DEVICE_NAME = "RCOrient"
        private const val SENSOR_PERIOD_US = 10_000
        private const val UI_UPDATE_INTERVAL_NS = 100_000_000L
    }
}

data class BleDeviceItem(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    val advertisesOrientationService: Boolean = false
)
data class ServoFeedbackSnapshot(
    val servo0Us: Int,
    val servo1Us: Int,
    val servo2Us: Int,
    val servo3Us: Int,
    val lastSequence: Int,
    val lastFeedbackNs: Long,
    val feedbackPeriodMs: Float?,
    val sequenceStep: Int?
)

data class ConnectedBleDevice(
    val name: String,
    val address: String,
    val status: String,
    val servo0Us: Int? = null,
    val servo1Us: Int? = null,
    val servo2Us: Int? = null,
    val servo3Us: Int? = null,
    val lastSequence: Int? = null,
    val lastFeedbackNs: Long? = null,
    val feedbackPeriodMs: Float? = null,
    val sequenceStep: Int? = null
)

@Composable
fun MainScreen(
    rollDeg: Float,
    pitchDeg: Float,
    yawDeg: Float,
    sensorPeriodMs: Float?,
    commandPeriodMs: Float?,
    sensorAvailable: Boolean,
    permissionsGranted: Boolean,
    isScanning: Boolean,
    showDeviceSelection: Boolean,
    showNearbyDevices: Boolean,
    pairedDevices: List<BleDeviceItem>,
    discoveredDevices: List<BleDeviceItem>,
    connectedDevices: List<ConnectedBleDevice>,
    onZeroClick: () -> Unit,
    onRequestPermissionsClick: () -> Unit,
    onToggleDeviceSelectionClick: () -> Unit,
    onAddReceiverClick: () -> Unit,
    onDeviceClick: (BleDeviceItem) -> Unit,
    onDisconnectClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Top, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("RC orientation app", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        if (!sensorAvailable) {
            Text("Rotation-vector sensor not available on this phone.", modifier = Modifier.padding(top = 24.dp))
            return
        }
        AngleRow("Roll", rollDeg, modifier = Modifier.padding(top = 24.dp))
        AngleRow("Pitch", pitchDeg, modifier = Modifier.padding(top = 8.dp))
        AngleRow("Yaw", yawDeg, modifier = Modifier.padding(top = 8.dp))
        TimingRow(sensorPeriodMs, commandPeriodMs, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onZeroClick, modifier = Modifier.padding(top = 20.dp)) { Text("Zero") }
        Text("Connected boards", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp))
        if (connectedDevices.isEmpty()) {
            Text("None", modifier = Modifier.padding(top = 8.dp))
        } else {
            connectedDevices.forEach { item ->
                Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text("${item.address} • ${item.status}", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { onDisconnectClick(item.address) }) {
                            Text(if (item.status == "Ready") "Disconnect" else "De-select")
                        }
                    }
                    if (item.hasServoFeedback()) {
                        ServoRow(item, modifier = Modifier.padding(top = 8.dp))
                        FeedbackRow(item, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        Text("Waiting for servo feedback...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        if (!permissionsGranted) {
            Button(onClick = onRequestPermissionsClick, modifier = Modifier.padding(top = 20.dp)) { Text("Allow BLE") }
        } else {
            Button(onClick = onToggleDeviceSelectionClick, modifier = Modifier.padding(top = 20.dp)) {
                Text(if (showDeviceSelection) "Close device selection" else "Select devices")
            }
        }
        if (showDeviceSelection && permissionsGranted) {
            Text("Paired RCOrientation receivers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            if (pairedDevices.isEmpty()) {
                Text("None", modifier = Modifier.padding(top = 8.dp))
            } else {
                pairedDevices.forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onDeviceClick(item) }.padding(vertical = 10.dp)) {
                        Text(item.name, fontWeight = FontWeight.Bold)
                        Text(item.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = onAddReceiverClick, modifier = Modifier.padding(top = 12.dp)) {
                Text(if (showNearbyDevices) "Close add receiver" else "Add receiver")
            }
        }
        if (showDeviceSelection && showNearbyDevices) {
            Text("Nearby RCOrientation receivers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            Text(if (isScanning) "Scanning..." else "Scan stopped", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(discoveredDevices, key = { it.address }) { item ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onDeviceClick(item) }.padding(vertical = 10.dp)) {
                        Text(item.name, fontWeight = FontWeight.Bold)
                        Text(item.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NumericText(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val baseStyle = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall
    Row(modifier = modifier, horizontalArrangement = Arrangement.End) {
        text.forEach { character ->
            Text(
                text = character.toString(),
                style = baseStyle.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(if (large) 11.dp else 8.dp)
            )
        }
    }
}

@Composable
private fun AngleRow(label: String, valueDeg: Float, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(56.dp))
        NumericText(
            text = formatDegrees(valueDeg),
            large = true,
            modifier = Modifier.width(82.dp)
        )
    }
}

@Composable
private fun TimingRow(sensorPeriodMs: Float?, commandPeriodMs: Float?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimingMetric("Sensor", sensorPeriodMs)
        TimingMetric("Send", commandPeriodMs, modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
private fun TimingMetric(label: String, valueMs: Float?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
        NumericText(formatMs(valueMs), modifier = Modifier.width(80.dp))
    }
}

@Composable
private fun ServoRow(item: ConnectedBleDevice, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ServoValue("S0", item.servo0Us)
            ServoValue("S1", item.servo1Us, modifier = Modifier.padding(start = 18.dp))
        }
        Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ServoValue("S2", item.servo2Us)
            ServoValue("S3", item.servo3Us, modifier = Modifier.padding(start = 18.dp))
        }
    }
}

@Composable
private fun ServoValue(label: String, valueUs: Int?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(22.dp))
        NumericText(formatUs(valueUs), modifier = Modifier.width(38.dp))
        Text(" us", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(24.dp))
    }
}

@Composable
private fun FeedbackRow(item: ConnectedBleDevice, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Feedback", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        NumericText(formatMs(item.feedbackPeriodMs), modifier = Modifier.width(80.dp))
        Text("Seq", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(28.dp))
        NumericText(formatStep(item.sequenceStep), modifier = Modifier.width(28.dp))
    }
}

private fun relativeAngle(valueDeg: Float, zeroDeg: Float): Float {
    var angle = valueDeg - zeroDeg
    while (angle > 180f) angle -= 360f
    while (angle < -180f) angle += 360f
    return angle
}
private fun ConnectedBleDevice.hasServoFeedback(): Boolean =
    servo0Us != null || servo1Us != null || servo2Us != null || servo3Us != null

private fun degreesToCentiDegrees(value: Float): Int = (value * 100f).roundToInt().coerceIn(-32768, 32767)
private fun formatDegrees(value: Float): String = String.format(Locale.US, "% 6.1f°", value)
private fun formatMs(value: Float?): String = value?.let { String.format(Locale.US, "%6.1f ms", it) } ?: "    -- ms"
private fun formatUs(value: Int?): String = value?.let { String.format(Locale.US, "%4d", it) } ?: "  --"
private fun formatStep(value: Int?): String = value?.let { String.format(Locale.US, "%2d", it) } ?: "--"

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    RC_orientation_appTheme {
        MainScreen(
            rollDeg = 12.3f,
            pitchDeg = -4.5f,
            yawDeg = 87.6f,
            sensorPeriodMs = 20f,
            commandPeriodMs = 10f,
            sensorAvailable = true,
            permissionsGranted = true,
            isScanning = false,
            showDeviceSelection = false,
            showNearbyDevices = false,
            pairedDevices = emptyList(),
            discoveredDevices = emptyList(),
            connectedDevices = listOf(ConnectedBleDevice("STM32WB #1", "AA:BB:CC:DD:EE:01", "Ready", 1500, 1500, 1500, 1500)),
            onZeroClick = {},
            onRequestPermissionsClick = {},
            onToggleDeviceSelectionClick = {},
            onAddReceiverClick = {},
            onDeviceClick = {},
            onDisconnectClick = {}
        )
    }
}
