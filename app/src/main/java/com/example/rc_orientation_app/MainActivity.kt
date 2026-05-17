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
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.rc_orientation_app.ui.theme.RC_orientation_appTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val bluetoothManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var absoluteRollDeg by mutableStateOf(0f)
    private var absolutePitchDeg by mutableStateOf(0f)
    private var absoluteYawDeg by mutableStateOf(0f)
    private var zeroRollDeg by mutableStateOf(0f)
    private var zeroPitchDeg by mutableStateOf(0f)
    private var zeroYawDeg by mutableStateOf(0f)
    private var sensorAvailable by mutableStateOf(true)
    private var permissionsGranted by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)
    private var showAvailableDevices by mutableStateOf(true)
    private var sequenceNumber = 0

    private val discoveredDevices = mutableStateListOf<BleDeviceItem>()
    private val connectedDevices = mutableStateMapOf<String, ConnectedBleDevice>()
    private val bluetoothGatts = mutableMapOf<String, BluetoothGatt>()
    private val orientationChars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val servoFeedbackChars = mutableMapOf<String, BluetoothGattCharacteristic>()

    private val sendOrientationRunnable = object : Runnable {
        override fun run() {
            broadcastOrientationPacket()
            mainHandler.postDelayed(this, ORIENTATION_SEND_PERIOD_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = hasBlePermissions()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            val name = try {
                if (hasConnectPermission()) device.name ?: "Unnamed BLE device" else "Unnamed BLE device"
            } catch (_: SecurityException) {
                "Unnamed BLE device"
            }
            if (discoveredDevices.none { it.address == address }) {
                discoveredDevices.add(BleDeviceItem(name, address, device))
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
        setContent {
            RC_orientation_appTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        rollDeg = currentRollDeg(),
                        pitchDeg = currentPitchDeg(),
                        yawDeg = currentYawDeg(),
                        sensorAvailable = sensorAvailable,
                        permissionsGranted = permissionsGranted,
                        isScanning = isScanning,
                        showAvailableDevices = showAvailableDevices,
                        discoveredDevices = visibleDiscoveredDevices(),
                        connectedDevices = connectedDevices.values.sortedBy { it.name },
                        onZeroClick = ::zeroCurrentOrientation,
                        onRequestPermissionsClick = ::requestBlePermissions,
                        onScanClick = ::toggleScan,
                        onToggleAvailableDevicesClick = { showAvailableDevices = !showAvailableDevices },
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
        rotationVectorSensor?.also { sensor -> sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) }
        mainHandler.post(sendOrientationRunnable)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopScan()
        mainHandler.removeCallbacks(sendOrientationRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        mainHandler.removeCallbacks(sendOrientationRunnable)
        bluetoothGatts.values.forEach { gatt -> try { gatt.close() } catch (_: SecurityException) {} }
        bluetoothGatts.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        absoluteYawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        absolutePitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
        absoluteRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
    }

    private fun zeroCurrentOrientation() {
        zeroRollDeg = absoluteRollDeg
        zeroPitchDeg = absolutePitchDeg
        zeroYawDeg = absoluteYawDeg
    }

    private fun currentRollDeg() = relativeAngle(absoluteRollDeg, zeroRollDeg)
    private fun currentPitchDeg() = relativeAngle(absolutePitchDeg, zeroPitchDeg)
    private fun currentYawDeg() = relativeAngle(absoluteYawDeg, zeroYawDeg)

    private fun requestBlePermissions() = permissionLauncher.launch(requiredBlePermissions())

    private fun toggleScan() {
        if (!permissionsGranted) {
            requestBlePermissions()
            return
        }
        if (isScanning) stopScan() else {
            showAvailableDevices = true
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

    private fun visibleDiscoveredDevices(): List<BleDeviceItem> = discoveredDevices
        .filter { !connectedDevices.containsKey(it.address) }
        .sortedWith(
            compareBy<BleDeviceItem> { it.name == "Unnamed BLE device" }
                .thenBy { it.name.lowercase() }
                .thenBy { it.address }
        )

    private fun connectToDevice(item: BleDeviceItem) {
        if (!hasConnectPermission()) {
            requestBlePermissions()
            return
        }
        if (bluetoothGatts.containsKey(item.address)) return
        connectedDevices[item.address] = ConnectedBleDevice(item.name, item.address, "Connecting...")
        showAvailableDevices = false
        try {
            bluetoothGatts[item.address] = item.device.connectGatt(this, false, createGattCallback(item.address))
        } catch (_: SecurityException) {
            permissionsGranted = false
            connectedDevices.remove(item.address)
        }
    }

    private fun createGattCallback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                connectedDevices[address] = connectedDevices[address]?.copy(
                    status = when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> "Connected, discovering service..."
                        BluetoothProfile.STATE_CONNECTING -> "Connecting..."
                        BluetoothProfile.STATE_DISCONNECTED -> "Disconnected"
                        else -> "State $newState"
                    }
                ) ?: return@runOnUiThread
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { gatt.discoverServices() } catch (_: SecurityException) {}
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                orientationChars.remove(address)
                servoFeedbackChars.remove(address)
                bluetoothGatts.remove(address)
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
            if (characteristic.uuid != SERVO_FEEDBACK_CHAR_UUID || value.size < 8) return
            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            val rollUs = buffer.short.toInt()
            val pitchUs = buffer.short.toInt()
            val yawUs = buffer.short.toInt()
            val sequence = buffer.short.toInt() and 0xFFFF
            runOnUiThread {
                connectedDevices[address] = connectedDevices[address]?.copy(
                    servoRollUs = rollUs,
                    servoPitchUs = pitchUs,
                    servoYawUs = yawUs,
                    lastSequence = sequence
                ) ?: return@runOnUiThread
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

    private fun disconnectDevice(address: String) {
        val gatt = bluetoothGatts[address]
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {}
        bluetoothGatts.remove(address)
        orientationChars.remove(address)
        servoFeedbackChars.remove(address)
        connectedDevices.remove(address)
        showAvailableDevices = true
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
        private const val ORIENTATION_SEND_PERIOD_MS = 50L
    }
}

data class BleDeviceItem(val name: String, val address: String, val device: BluetoothDevice)
data class ConnectedBleDevice(val name: String, val address: String, val status: String, val servoRollUs: Int? = null, val servoPitchUs: Int? = null, val servoYawUs: Int? = null, val lastSequence: Int? = null)

@Composable
fun MainScreen(
    rollDeg: Float,
    pitchDeg: Float,
    yawDeg: Float,
    sensorAvailable: Boolean,
    permissionsGranted: Boolean,
    isScanning: Boolean,
    showAvailableDevices: Boolean,
    discoveredDevices: List<BleDeviceItem>,
    connectedDevices: List<ConnectedBleDevice>,
    onZeroClick: () -> Unit,
    onRequestPermissionsClick: () -> Unit,
    onScanClick: () -> Unit,
    onToggleAvailableDevicesClick: () -> Unit,
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
        Text("Roll: ${formatDegrees(rollDeg)}", modifier = Modifier.padding(top = 24.dp))
        Text("Pitch: ${formatDegrees(pitchDeg)}", modifier = Modifier.padding(top = 8.dp))
        Text("Yaw: ${formatDegrees(yawDeg)}", modifier = Modifier.padding(top = 8.dp))
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
                        Button(onClick = { onDisconnectClick(item.address) }) { Text("Disconnect") }
                    }
                    Text("Servo: R ${item.servoRollUs ?: "—"} µs, P ${item.servoPitchUs ?: "—"} µs, Y ${item.servoYawUs ?: "—"} µs", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (!permissionsGranted) {
            Button(onClick = onRequestPermissionsClick, modifier = Modifier.padding(top = 20.dp)) { Text("Allow BLE") }
        } else {
            Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onScanClick) { Text(if (isScanning) "Stop scan" else "Scan") }
                Button(onClick = onToggleAvailableDevicesClick) { Text(if (showAvailableDevices) "Hide devices" else "Show devices") }
            }
        }
        if (showAvailableDevices) {
            Text("Available devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
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

private fun relativeAngle(valueDeg: Float, zeroDeg: Float): Float {
    var angle = valueDeg - zeroDeg
    while (angle > 180f) angle -= 360f
    while (angle < -180f) angle += 360f
    return angle
}
private fun degreesToCentiDegrees(value: Float): Int = (value * 100f).roundToInt().coerceIn(-32768, 32767)
private fun formatDegrees(value: Float): String = "${(value * 10).roundToInt() / 10.0}°"

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    RC_orientation_appTheme {
        MainScreen(
            rollDeg = 12.3f,
            pitchDeg = -4.5f,
            yawDeg = 87.6f,
            sensorAvailable = true,
            permissionsGranted = true,
            isScanning = false,
            showAvailableDevices = false,
            discoveredDevices = emptyList(),
            connectedDevices = listOf(ConnectedBleDevice("STM32WB #1", "AA:BB:CC:DD:EE:01", "Ready", 1500, 1500, 1500)),
            onZeroClick = {},
            onRequestPermissionsClick = {},
            onScanClick = {},
            onToggleAvailableDevicesClick = {},
            onDeviceClick = {},
            onDisconnectClick = {}
        )
    }
}
