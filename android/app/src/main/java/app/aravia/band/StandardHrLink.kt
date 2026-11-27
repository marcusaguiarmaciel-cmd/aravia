package app.aravia.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import app.aravia.health.HealthConnectStore
import app.aravia.protocol.HrGattParser
import app.aravia.training.WorkoutController
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Universal Bluetooth SIG Heart Rate Profile client (GATT 0x180D / 0x2A37 + Battery 0x180F / 0x2A19).
 * Works with ANY standard chest strap (Polar H10/H9, Garmin HRM, Coospo, Magene, Wahoo) and ANY
 * smartwatch broadcasting Heart Rate over BLE — zero proprietary key, zero vendor app required.
 *
 * Streams live heart rate to WorkoutController (gym/run/treadmill) and supports a 60-second
 * seated Spot HRV (RMSSD) check-in from beat-to-beat RR intervals.
 */
@SuppressLint("MissingPermission")
object StandardHrLink {
    data class HrDevice(val name: String, val address: String, val rssi: Int?, val connected: Boolean = false)

    enum class Status(val label: String) {
        IDLE("Desconectado"),
        SCANNING("Procurando sensores cardíacos…"),
        CONNECTING("Conectando…"),
        CONNECTED("Conectado ao vivo"),
        FAILED("Falha na conexão"),
    }

    data class LiveState(
        val status: Status = Status.IDLE,
        val deviceName: String? = null,
        val bpm: Int? = null,
        val battery: Int? = null,
        val contact: HrGattParser.SensorContact = HrGattParser.SensorContact.UNSUPPORTED,
        val hasRr: Boolean = false,
        val spotHrvRunning: Boolean = false,
        val spotHrvElapsedSec: Int = 0,
        val spotHrvRmssdMs: Double? = null,
        val error: String? = null,
    )

    private val HR_SERVICE = UUID.fromString(HrGattParser.SERVICE_HEART_RATE)
    private val HR_MEASUREMENT = UUID.fromString(HrGattParser.CHAR_HEART_RATE_MEASUREMENT)
    private val BATTERY_SERVICE = UUID.fromString(HrGattParser.SERVICE_BATTERY)
    private val BATTERY_LEVEL = UUID.fromString(HrGattParser.CHAR_BATTERY_LEVEL)
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null
    private val foundMap = LinkedHashMap<String, HrDevice>()
    private val spotSession = HrGattParser.SpotHrvSession()

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<HrDevice>>(emptyList())
    val devices: StateFlow<List<HrDevice>> = _devices.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val bluetoothManager: BluetoothManager? get() = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun startScan() {
        if (!BandPermissions.granted(app)) return
        val a = adapter ?: return
        if (!a.isEnabled) {
            _state.value = _state.value.copy(status = Status.FAILED, error = "Ligue o Bluetooth para buscar sensores.")
            return
        }
        stopScan()
        foundMap.clear()
        // Include already-connected GATT devices so active straps/broadcasting watches appear immediately.
        runCatching { bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() }
            .getOrDefault(emptyList())
            .forEach { d ->
                val addr = d.address ?: return@forEach
                val name = d.name.orEmpty().trim()
                if (name.isNotEmpty()) foundMap[addr] = HrDevice(name, addr, null, connected = true)
            }
        _devices.value = foundMap.values.toList()

        val scanner = a.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    val addr = result.device.address ?: return@post
                    val name = (result.scanRecord?.deviceName ?: result.device.name).orEmpty().trim()
                        .ifEmpty { "Sensor cardíaco BLE" }
                    foundMap[addr] = HrDevice(name, addr, result.rssi, foundMap[addr]?.connected == true)
                    _devices.value = foundMap.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
                }
            }
        }
        scanCb = cb
        _state.value = _state.value.copy(status = Status.SCANNING, error = null)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, cb) }.onFailure { scanner.startScan(cb) }
        main.postDelayed({ if (scanCb === cb) stopScan() }, 15_000L)
    }

    fun stopScan() {
        scanCb?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCb = null
        if (_state.value.status == Status.SCANNING) {
            _state.value = _state.value.copy(status = Status.IDLE)
        }
    }

    fun connect(address: String, name: String) {
        if (!BandPermissions.granted(app)) return
        val a = adapter ?: return
        stopScan()
        disconnect()
        _state.value = LiveState(status = Status.CONNECTING, deviceName = name)
        val dev = a.getRemoteDevice(address)
        gatt = dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        spotSession.reset()
        _state.value = LiveState(status = Status.IDLE)
    }

    fun startSpotHrv() {
        if (_state.value.status != Status.CONNECTED) return
        spotSession.reset()
        _state.value = _state.value.copy(spotHrvRunning = true, spotHrvElapsedSec = 0, spotHrvRmssdMs = null)
    }

    fun cancelSpotHrv() {
        spotSession.reset()
        _state.value = _state.value.copy(spotHrvRunning = false, spotHrvElapsedSec = 0)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt?.close(); gatt = null
                _state.value = _state.value.copy(
                    status = Status.IDLE,
                    bpm = null,
                    spotHrvRunning = false,
                    error = if (status != 0) "Conexão encerrada (erro $status)." else null,
                )
            }
        }.let {}

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main.post {
            if (g != gatt) return@post
            val hrSvc = g.getService(HR_SERVICE)
            val hrChar = hrSvc?.getCharacteristic(HR_MEASUREMENT)
            if (hrChar == null) {
                disconnect()
                _state.value = LiveState(status = Status.FAILED, error = "Este dispositivo não transmite batimentos no padrão Bluetooth (0x180D).")
                return@post
            }
            g.setCharacteristicNotification(hrChar, true)
            val cccd = hrChar.getDescriptor(CCCD)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
            _state.value = _state.value.copy(status = Status.CONNECTED, error = null)
        }.let {}

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = main.post {
            if (g != gatt) return@post
            // Read battery level after subscribing to heart rate.
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { bat ->
                runCatching { g.readCharacteristic(bat) }
            }
        }.let {}

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = main.post {
            if (g != gatt || c.uuid != BATTERY_LEVEL) return@post
            HrGattParser.parseBatteryLevel(value)?.let { pct ->
                _state.value = _state.value.copy(battery = pct)
            }
        }.let {}

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33 || c.uuid != BATTERY_LEVEL) return
            @Suppress("DEPRECATION") val copy = c.value?.copyOf() ?: return
            main.post {
                if (g == gatt) HrGattParser.parseBatteryLevel(copy)?.let { _state.value = _state.value.copy(battery = it) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { if (g == gatt && c.uuid == HR_MEASUREMENT) handleMeasurement(copy) }
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33 || c.uuid != HR_MEASUREMENT) return
            @Suppress("DEPRECATION") val copy = c.value?.copyOf() ?: return
            main.post { if (g == gatt) handleMeasurement(copy) }
        }
    }

    private fun handleMeasurement(bytes: ByteArray) {
        val m = HrGattParser.parseHeartRate(bytes) ?: return
        if (m.bpm in 30..220) WorkoutController.onLive(m.bpm, 0L)
        var spotRunning = _state.value.spotHrvRunning
        var spotElapsed = _state.value.spotHrvElapsedSec
        var spotRmssd = _state.value.spotHrvRmssdMs

        if (spotRunning) {
            spotSession.add(m)
            spotElapsed = spotSession.elapsedSeconds.toInt()
            if (spotElapsed >= 60) {
                val computed = spotSession.computeRmssd()
                if (computed != null) {
                    spotRmssd = computed
                    HealthConnectStore.saveSpotHrv(LocalDate.now(), computed, spotSession.meanHeartRate())
                }
                spotRunning = false
            }
        }
        _state.value = _state.value.copy(
            status = Status.CONNECTED,
            bpm = m.bpm,
            contact = m.sensorContact,
            hasRr = _state.value.hasRr || m.rrIntervalsMs.isNotEmpty(),
            spotHrvRunning = spotRunning,
            spotHrvElapsedSec = spotElapsed,
            spotHrvRmssdMs = spotRmssd,
        )
    }
}
