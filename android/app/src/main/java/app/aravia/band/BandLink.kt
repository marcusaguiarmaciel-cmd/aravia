package app.aravia.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import app.aravia.protocol.ActivityAssembler
import app.aravia.protocol.ActivityFileID
import app.aravia.protocol.ActivitySyncPlan
import app.aravia.protocol.AssemblerEvent
import app.aravia.protocol.MiAuth
import app.aravia.protocol.MiBind
import app.aravia.protocol.MiCrypto
import app.aravia.protocol.MiFrame
import app.aravia.protocol.MiMonitoring
import app.aravia.protocol.MiV1Channel
import app.aravia.protocol.MiV1Cipher
import app.aravia.protocol.PbMessage
import app.aravia.training.WorkoutController
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Bluetooth session with the watch, the Android twin of BandLink.swift on iOS. The same steps,
 * in the same order, with Android's GATT in place of CoreBluetooth:
 *
 * 1. Find the band: straight by its address (it came in the QR code or local bind), else by a scan
 *    for its name suffix or the address in its MiBeacon advert.
 * 2. Connect, ask for a large MTU, find the FE95 service: V2 (005E notify, 005F write) or the older
 *    V1 (0051/0052/0053, 0055 when present).
 * 3. V2: SessionConfig, then the two-step login (MiAuth) — or local bind / bind probe (MiBind).
 *    V1: login once every subscription is on.
 * 4. After login: clock, device info and state, battery, live stats; for a sync, today's and the
 *    past file list, then one file at a time (ActivitySyncPlan decides what to fetch).
 *
 * Stay connected (`Purpose.HOLD`) keeps an authenticated session open whenever no user session is
 * running, so the band can reach the phone (Find Phone). It leaves `phase`, `lastOutcome` and
 * `onFinish` untouched; user sessions pause it and resume it afterwards.
 */
@SuppressLint("MissingPermission")
object BandLink {
    sealed class Purpose(val label: String) {
        object SYNC : Purpose("sync")
        object LIVE : Purpose("live")
        class Send(val commands: List<ByteArray>) : Purpose("send")
        object HOLD : Purpose("hold")
        object BIND_PROBE : Purpose("bind probe")
        class Bind(val config: MiBind.Config, val deviceAddress: String?, val deviceName: String) : Purpose("bind")
    }

    enum class HoldState { OFF, CONNECTING, CONNECTED }

    sealed class Phase(val label: String) {
        object Idle : Phase("")
        object Searching : Phase("Procurando o relógio…")
        object Connecting : Phase("Conectando…")
        object Authenticating : Phase("Verificando a chave…")
        object Reading : Phase("Lendo o relógio…")
        object Live : Phase("Ao vivo")
        object Done : Phase("Sincronizado")
        class Partial(reason: String) : Phase(reason)
        class Failed(message: String) : Phase(message)

        val isBusy: Boolean get() = this is Searching || this is Connecting || this is Authenticating || this is Reading || this is Live
    }

    sealed class Outcome {
        object Complete : Outcome()
        class Partial(val reason: String) : Outcome()
        class Failed(val message: String) : Outcome()

        val label: String
            get() = when (this) {
                is Complete -> "completo"
                is Partial -> "parcial: $reason"
                is Failed -> "falhou: $message"
            }
    }

    data class Live(val heartRate: Int, val steps: Long, val calories: Long)

    enum class BandSource(val label: String) {
        CONNECTED("Conectado ao celular"),
        BONDED("Pareado no Bluetooth"),
        NEARBY("Próximo (Bluetooth)"),
    }

    /** A Bluetooth device discovered via active GATT connection, system pairing, or BLE scan. */
    data class ScannedBand(
        val name: String,
        val address: String,
        val rssi: Int?,
        val hasMiBeacon: Boolean,
        val source: BandSource = BandSource.NEARBY,
        val likelyWatch: Boolean = false,
    ) {
        val shortName: String get() = name.replace(Regex("\\s+[0-9A-Fa-f]{4}$"), "").ifBlank { name }
    }

    // UUIDs (16-bit, on the Bluetooth base)
    private fun uuid16(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    private val FE95 = uuid16("fe95")
    private val RX = uuid16("005e")
    private val TX = uuid16("005f")
    private val V1_READ = uuid16("0051")
    private val V1_WRITE = uuid16("0052")
    private val V1_ACTIVITY = uuid16("0053")
    private val V1_UPLOAD = uuid16("0055")
    private val V1_REQUIRED = listOf(V1_READ, V1_WRITE, V1_ACTIVITY)
    private val V1_ORDER = listOf(V1_WRITE, V1_READ, V1_ACTIVITY, V1_UPLOAD)
    private val CCCD = uuid16("2902")

    private const val LIVE_LIMIT_MS = 120_000L
    private const val FILE_SYNC_LIMIT_MS = 120_000L
    private const val LOG_LIMIT = 600
    private val HOLD_RETRY_DELAYS_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _holdState = MutableStateFlow(HoldState.OFF)
    val holdState: StateFlow<HoldState> = _holdState.asStateFlow()
    private val _lastOutcome = MutableStateFlow<Outcome?>(null)
    val lastOutcome: StateFlow<Outcome?> = _lastOutcome.asStateFlow()
    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()
    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()
    private val _deviceState = MutableStateFlow<MiAuth.DeviceState?>(null)
    val deviceState: StateFlow<MiAuth.DeviceState?> = _deviceState.asStateFlow()
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _bindStatus = MutableStateFlow<String?>(null)
    val bindStatus: StateFlow<String?> = _bindStatus.asStateFlow()
    private val _lastBindFailure = MutableStateFlow<MiBind.Failure?>(null)
    val lastBindFailure: StateFlow<MiBind.Failure?> = _lastBindFailure.asStateFlow()
    private val _lastBindTarget = MutableStateFlow<ScannedBand?>(null)
    val lastBindTarget: StateFlow<ScannedBand?> = _lastBindTarget.asStateFlow()
    private val _nearby = MutableStateFlow<List<ScannedBand>>(emptyList())
    val nearby: StateFlow<List<ScannedBand>> = _nearby.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    /** A complete, CRC-checked activity file, filed under the id it carries. */
    var onActivityFile: ((ActivityFileID, ByteArray) -> Unit)? = null
    /** When the copy of a file on the phone was fetched (Unix seconds); null when there is none. */
    var storedAt: ((ActivityFileID) -> Long?)? = null
    /** Whether a stored file may be acknowledged, which makes the band delete it. */
    var mayAcknowledge: ((ActivityFileID) -> Boolean)? = null
    /** Once per user session. */
    var onFinish: ((Outcome) -> Unit)? = null
    /** Firmware, serial and hardware model, from Command{2,2}. */
    var onDeviceInfo: ((MiAuth.DeviceInfo) -> Unit)? = null
    /** Bluetooth MAC learned when connecting to a watch whose MAC wasn't saved yet. */
    var onMacResolved: ((String) -> Unit)? = null
    /** Health monitoring setting bytes returned by Command{8,8/10/14}. */
    var onMonitoring: ((MiMonitoring.Setting, ByteArray) -> Unit)? = null
    /** Find Phone from the band (Command{2,17}): true to start ringing, false to stop. */
    var onFindPhone: ((String, Boolean) -> Unit)? = null
    /** Local bind succeeded (docs/BIND.md): auth key, session keys, appDeviceID and band info. */
    var onBound: ((MiBind.Bound, String, String?) -> Unit)? = null
    /** Which candidate key matched at login. */
    var onKeyMatched: ((Int) -> Unit)? = null

    /** How long a live session runs; a workout keeps live heart rate for hours. */
    var liveWindowMs: Long? = null

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var target: BandVault.Band? = null
    private var holdTarget: BandVault.Band? = null
    private var holdFailures = 0
    var purpose: Purpose = Purpose.SYNC; private set
    private var stage: Phase = Phase.Idle
        set(value) {
            field = value
            if (purpose != Purpose.HOLD) _phase.value = value
        }
    private var run = 0
    private var active = false
    private var authed = false
    private var finishing = false
    private var realtimeOn = false
    private var keyIndex = 0
    private var gattRetried = false
    private var btReceiverRegistered = false

    private var gatt: BluetoothGatt? = null
    private var scanning: ScanCallback? = null
    private var discoveryCallback: ScanCallback? = null
    private val discoveredMap = LinkedHashMap<String, ScannedBand>()
    private var mtu = 23
    private var v1 = false
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val v1Chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private val v1Channels = HashMap<UUID, MiV1Channel>()
    private val v1Subscribed = HashSet<UUID>()
    private val sliceTimers = HashMap<UUID, Int>()
    private var sliceSerial = 0
    private val buffer = mutableListOf<Byte>()
    private var seq = 0
    private val sentFrames = HashMap<Int, ByteArray>()
    private var phoneNonce = ByteArray(0)
    private var keys: MiAuth.SessionKeys? = null
    private val factoryDumps = HashSet<String>()

    // Bind / Bind probe
    private var bindMachine: MiBind.Machine? = null
    private var bindAnswered = false
    private var bindTimerSerial = 0

    /** One GATT operation at a time: a write, or a subscription (CCCD write). */
    private sealed class Op {
        class Write(val c: BluetoothGattCharacteristic, val value: ByteArray) : Op()
        class Subscribe(val c: BluetoothGattCharacteristic) : Op()
    }
    private val ops = ArrayDeque<Op>()
    private var opBusy = false

    // Activity files
    private enum class FileStage { IDLE, LISTING_TODAY, LISTING_PAST, FETCHING, DONE }
    private var fileStage = FileStage.IDLE
    private var todayIds: List<ActivityFileID> = emptyList()
    private val fileQueue = ArrayDeque<ActivityFileID>()
    private var currentFile: ActivityFileID? = null
    private var currentRetried = false
    private var assembler = ActivityAssembler()
    private var fileToken = 0
    private val receivedFiles = HashSet<ActivityFileID>()
    private var requestedFiles: Set<ActivityFileID> = emptySet()
    private var fileListMissing = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            main.post {
                if (state == BluetoothAdapter.STATE_ON) {
                    resumeHold()
                } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (active) fail("O Bluetooth foi desligado.", bluetoothOff = true)
                }
            }
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        if (!btReceiverRegistered) {
            btReceiverRegistered = true
            runCatching { app.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
        }
    }

    private val bluetoothManager: BluetoothManager? get() = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun bluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun clearBindState() {
        _bindStatus.value = null
        _lastBindFailure.value = null
        _lastBindTarget.value = null
        if (_phase.value is Phase.Failed) _phase.value = Phase.Idle
    }

    // Control

    fun start(band: BandVault.Band, purpose: Purpose) {
        if (_phase.value.isBusy || purpose == Purpose.HOLD) return
        if (!BandPermissions.granted(app)) { _phase.value = Phase.Failed("Permita o Bluetooth para o Aravia nos ajustes do Android."); return }
        val a = adapter
        if (a == null) { _phase.value = Phase.Failed("Este celular não tem Bluetooth LE."); return }
        if (!a.isEnabled) { _phase.value = Phase.Failed("O Bluetooth está desligado. Ligue e tente de novo."); return }
        stopDiscovery()
        var delay = 0L
        if (active && this.purpose == Purpose.HOLD) {
            endHold()
            _holdState.value = HoldState.CONNECTING
            delay = 1_000L
        }
        begin(band, purpose, delay)
    }

    /** Starts a local pairing (bind) session with a discovered watch, without Mi Fitness (docs/BIND.md). */
    fun startBind(deviceAddress: String, deviceName: String, userID: String, dynamicCode: String = "") {
        if (_phase.value.isBusy) return
        if (!BandPermissions.granted(app)) { _phase.value = Phase.Failed("Permita o Bluetooth para o Aravia nos ajustes do Android."); return }
        val a = adapter
        if (a == null) { _phase.value = Phase.Failed("Este celular não tem Bluetooth LE."); return }
        if (!a.isEnabled) { _phase.value = Phase.Failed("O Bluetooth está desligado. Ligue e tente de novo."); return }
        stopDiscovery()
        _lastBindFailure.value = null
        _lastBindTarget.value = discoveredMap[deviceAddress]
            ?: ScannedBand(
                name = deviceName.ifBlank { "Relógio" },
                address = deviceAddress,
                rssi = null,
                hasMiBeacon = false,
                source = BandSource.NEARBY,
                likelyWatch = true,
            )
        val idBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val appDeviceID = idBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val config = MiBind.Config(userID = userID.ifBlank { "aravia-user" }, appDeviceID = appDeviceID, deviceName = "Aravia", dynamicCode = dynamicCode)
        val placeholder = BandVault.Band(name = deviceName.ifBlank { "Relógio" }, mac = deviceAddress, model = "", keys = emptyList())
        var delay = 0L
        if (active && this.purpose == Purpose.HOLD) {
            endHold()
            _holdState.value = HoldState.CONNECTING
            delay = 1_000L
        }
        begin(placeholder, Purpose.Bind(config, deviceAddress, deviceName), delay)
    }

    /** Stay connected (docs/STAY-CONNECTED.md): keeps an authenticated session open while idle. */
    fun hold(band: BandVault.Band?) {
        val holding = active && purpose == Purpose.HOLD
        holdTarget = band
        holdFailures = 0
        if (band == null) {
            _holdState.value = HoldState.OFF
            if (holding) endHold()
            return
        }
        if (holding && target?.mac == band.mac && target?.name == band.name) return
        _holdState.value = HoldState.CONNECTING
        if (holding) {
            endHold()
            begin(band, Purpose.HOLD, 1_000L)
        } else if (!active) {
            begin(band, Purpose.HOLD, 0L)
        }
    }

    /** The phone stopped ringing on its own: tells the watch so it closes its Find Phone screen. */
    fun stopFindPhone() {
        if (!active || !authed) return
        val cmd = MiAuth.findPhoneStopCommand()
        note("→ find phone: stop ${hexPrefix(cmd)}")
        sendCommand(cmd, encrypted = true)
    }

    /**
     * Immediately populates connected GATT devices and system-paired (bonded) devices — watches
     * already connected to Android do not advertise over BLE, so scanning alone never sees them.
     */
    fun refreshKnownDevices() {
        if (!BandPermissions.granted(app)) return
        val a = adapter ?: return
        if (!a.isEnabled) return
        seedKnownDevices()
        publishNearby()
    }

    /** Scans nearby Bluetooth LE devices AND includes already-connected / system-paired watches. */
    fun startDiscovery() {
        if (!BandPermissions.granted(app)) return
        val a = adapter ?: return
        if (!a.isEnabled) return
        stopDiscovery()
        discoveredMap.clear()
        seedKnownDevices()
        publishNearby()
        val scanner = a.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    val name = (result.scanRecord?.deviceName ?: result.device.name).orEmpty().trim()
                    val beacon = result.scanRecord?.getServiceData(ParcelUuid(FE95)) != null
                    val hasFe95 = beacon || result.scanRecord?.serviceUuids?.any { it.uuid == FE95 } == true
                    if (!hasFe95 && name.isEmpty()) return@post
                    val addr = result.device.address ?: return@post
                    val prev = discoveredMap[addr]
                    val label = name.ifEmpty { prev?.name.orEmpty().ifEmpty { "Dispositivo Xiaomi" } }
                    val fe95 = hasFe95 || prev?.hasMiBeacon == true
                    val src = prev?.source?.takeIf { it != BandSource.NEARBY } ?: BandSource.NEARBY
                    val likely = prev?.likelyWatch == true || isLikelyWatch(label, result.device, fe95)
                    discoveredMap[addr] = ScannedBand(label, addr, result.rssi, fe95, src, likely)
                    publishNearby()
                }
            }
        }
        discoveryCallback = cb
        _discovering.value = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, cb) }.onFailure { scanner.startScan(cb) }
        main.postDelayed({ if (discoveryCallback === cb) stopDiscovery() }, 15_000L)
    }

    fun stopDiscovery() {
        discoveryCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        discoveryCallback = null
        _discovering.value = false
    }

    private fun seedKnownDevices() {
        val mgr = bluetoothManager
        val a = adapter ?: return
        val connected = runCatching { mgr?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() }.getOrDefault(emptyList())
        for (dev in connected) {
            val addr = dev.address ?: continue
            val label = dev.name.orEmpty().trim().ifEmpty { discoveredMap[addr]?.name.orEmpty().ifEmpty { "Relógio conectado" } }
            val prev = discoveredMap[addr]
            discoveredMap[addr] = ScannedBand(
                name = label,
                address = addr,
                rssi = prev?.rssi,
                hasMiBeacon = prev?.hasMiBeacon == true,
                source = BandSource.CONNECTED,
                likelyWatch = true.takeIf { prev?.likelyWatch == true } ?: isLikelyWatch(label, dev, prev?.hasMiBeacon == true),
            )
        }
        val bonded = runCatching { a.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        for (dev in bonded) {
            val addr = dev.address ?: continue
            if (discoveredMap[addr]?.source == BandSource.CONNECTED) continue
            val label = dev.name.orEmpty().trim()
            if (label.isEmpty()) continue
            val prev = discoveredMap[addr]
            discoveredMap[addr] = ScannedBand(
                name = label,
                address = addr,
                rssi = prev?.rssi,
                hasMiBeacon = prev?.hasMiBeacon == true,
                source = BandSource.BONDED,
                likelyWatch = prev?.likelyWatch == true || isLikelyWatch(label, dev, prev?.hasMiBeacon == true),
            )
        }
    }

    private fun isLikelyWatch(name: String, device: BluetoothDevice?, hasFe95: Boolean): Boolean {
        if (hasFe95) return true
        val major = runCatching { device?.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major == BluetoothClass.Device.Major.WEARABLE) return true
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains("redmi") ||
            lower.contains("xiaomi") ||
            lower.contains("mi band") ||
            lower.contains("mi watch") ||
            lower.contains("smart band") ||
            lower.contains("poco watch") ||
            lower.contains("watch") ||
            lower.contains("band")
    }

    private fun publishNearby() {
        _nearby.value = discoveredMap.values.sortedWith(
            compareByDescending<ScannedBand> { it.likelyWatch || it.hasMiBeacon }
                .thenBy {
                    when (it.source) {
                        BandSource.CONNECTED -> 0
                        BandSource.BONDED -> 1
                        BandSource.NEARBY -> if (it.hasMiBeacon) 1 else 2
                    }
                }
                .thenByDescending { it.hasMiBeacon }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
        )
    }

    private fun begin(band: BandVault.Band, purpose: Purpose, delayMs: Long) {
        if (purpose == Purpose.HOLD) {
            if (!BandPermissions.granted(app) || adapter?.isEnabled != true) {
                note("hold: waiting for Bluetooth")
                _holdState.value = HoldState.CONNECTING
                return
            }
        }
        run++
        val thisRun = run
        target = band
        this.purpose = purpose
        keyIndex = 0
        gattRetried = false
        active = true
        authed = false
        finishing = false
        realtimeOn = false
        bindMachine = null
        bindAnswered = false
        factoryDumps.clear()
        if (purpose != Purpose.HOLD) {
            _battery.value = null
            _live.value = null
            _deviceState.value = null
            _progress.value = null
            _bindStatus.value = if (purpose is Purpose.Bind) "Conectando ao relógio…" else null
            if (purpose is Purpose.Bind) _lastBindFailure.value = null
        }
        fileStage = FileStage.IDLE
        todayIds = emptyList()
        fileQueue.clear()
        currentFile = null
        receivedFiles.clear()
        requestedFiles = emptySet()
        fileListMissing = false
        resetConnection()
        note("— ${purpose.label}: ${band.shortName}, ${band.keys.size} key(s)")
        BandService.start(app)
        if (purpose != Purpose.HOLD) {
            after(25_000, thisRun) {
                if (active && !authed && !bindAnswered && bindMachine == null) {
                    val timeoutMsg = if (purpose is Purpose.Bind) {
                        "O relógio não respondeu ao pareamento. Se o Mi Fitness estiver rodando em segundo plano, feche-o e tente de novo."
                    } else {
                        "O relógio não respondeu. Feche o Mi Fitness e tente de novo."
                    }
                    if (purpose is Purpose.Bind) _bindStatus.value = timeoutMsg
                    fail(timeoutMsg)
                }
            }
        }
        stage = Phase.Searching
        if (delayMs > 0) after(delayMs, thisRun) { locate(thisRun) } else locate(thisRun)
    }

    fun stop() {
        if (!active || purpose == Purpose.HOLD) return
        note("stopped")
        finish()
    }

    // Held session (docs/STAY-CONNECTED.md)

    private fun endHold() {
        note("hold: letting go")
        release()
        run++
    }

    private fun resumeHold() {
        if (holdTarget == null || active) return
        _holdState.value = HoldState.CONNECTING
        scheduleHold(2_000L)
    }

    private fun scheduleHold(delayMs: Long) {
        if (holdTarget == null) return
        after(delayMs, run) {
            val ht = holdTarget
            if (!active && ht != null) begin(ht, Purpose.HOLD, 0L)
        }
    }

    private fun holdFailed(bluetoothOff: Boolean, wrongKey: Boolean) {
        val wasOpen = authed
        release()
        run++
        if (holdTarget == null) return
        _holdState.value = HoldState.CONNECTING
        if (bluetoothOff || !BandPermissions.granted(app) || adapter?.isEnabled != true) {
            note("hold: waiting for Bluetooth")
            return
        }
        val delay = when {
            wasOpen -> 1_000L
            wrongKey -> 300_000L
            else -> {
                val d = HOLD_RETRY_DELAYS_MS[minOf(holdFailures, HOLD_RETRY_DELAYS_MS.lastIndex)]
                holdFailures++
                d
            }
        }
        note("hold: again in ${delay / 1000} s")
        scheduleHold(delay)
    }

    // Locating the band

    private fun locate(thisRun: Int) {
        if (run != thisRun || !active) return
        stage = Phase.Searching
        val rawAddr = (purpose as? Purpose.Bind)?.deviceAddress ?: target?.mac
        val hex = rawAddr?.uppercase()?.filter { it in "0123456789ABCDEF" }
        val mac = hex?.takeIf { it.length == 12 }?.chunked(2)?.joinToString(":")
        if (mac != null && BluetoothAdapter.checkBluetoothAddress(mac)) {
            note("connecting by address")
            connect(adapter!!.getRemoteDevice(mac))
            return
        }
        // Check already-connected GATT and system-bonded watches before scanning (connected watches don't advertise).
        findKnownTargetDevice()?.let { dev ->
            note("found already connected/paired watch")
            connect(dev)
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: return fail("O Bluetooth não está pronto. Tente de novo.")
        note("scanning for adverts")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post { scanResult(result) }
            }
        }
        scanning = cb
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, cb) }.onFailure { scanner.startScan(cb) }
        after(12_000, thisRun) {
            if (stage is Phase.Searching) { stopScan(); fail("Relógio não encontrado. Ele está no pulso, perto e com bateria?") }
        }
    }

    private fun findKnownTargetDevice(): BluetoothDevice? {
        val a = adapter ?: return null
        val connected = runCatching { bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() }.getOrDefault(emptyList())
        val bonded = runCatching { a.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        val candidates = (connected + bonded).distinctBy { it.address }
        candidates.firstOrNull { nameMatches(it.name) }?.let { return it }
        val tName = target?.shortName?.lowercase(Locale.ROOT).orEmpty()
        if (tName.isNotEmpty()) {
            candidates.firstOrNull { d -> d.name?.lowercase(Locale.ROOT)?.contains(tName) == true }?.let { return it }
        }
        val connectedWatches = connected.filter { isLikelyWatch(it.name.orEmpty(), it, false) }
        if (connectedWatches.size == 1) return connectedWatches.first()
        return null
    }

    private fun scanResult(r: ScanResult) {
        if (stage !is Phase.Searching) return
        val name = r.scanRecord?.deviceName ?: r.device.name
        var match = nameMatches(name)
        val beacon = r.scanRecord?.getServiceData(ParcelUuid(FE95))
        val wanted = target?.mac?.uppercase()?.filter { it.isLetterOrDigit() }
        if (!match && beacon != null && wanted != null) match = beaconMac(beacon) == wanted
        if (!match) return
        note("found advertising (${r.rssi} dBm)")
        stopScan()
        connect(r.device)
    }

    private fun nameMatches(name: String?): Boolean {
        val suffix = target?.name?.takeLast(4)?.uppercase() ?: return false
        return name != null && suffix.all { it.isLetterOrDigit() } && name.uppercase().endsWith(suffix)
    }

    /** MiBeacon service data under FE95 carries the MAC (reversed) when frame-control bit 4 is set. */
    fun beaconMac(b: ByteArray): String? {
        if (b.size < 11 || (b[0].toInt() and 0x10) == 0) return null
        return (10 downTo 5).joinToString("") { "%02X".format(b[it].toInt() and 0xFF) }
    }

    private fun stopScan() {
        scanning?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanning = null
    }

    private fun connect(device: BluetoothDevice) {
        stage = Phase.Connecting
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    // GATT callbacks, posted to the main thread

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main.post {
            if (!active || g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                note("connected")
                if (purpose == Purpose.HOLD) {
                    val thisRun = run
                    after(25_000, thisRun) { if (active && !authed) fail("Tempo esgotado ao autenticar.") }
                }
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (status == 133 && !authed && bindMachine == null && !gattRetried && purpose != Purpose.HOLD) {
                    gattRetried = true
                    note("GATT 133; retrying once")
                    reconnect()
                    return@post
                }
                val errMsg = if (status == 0) {
                    "A conexão caiu. Aproxime o relógio e tente de novo."
                } else {
                    "Não deu para conectar ao relógio (erro $status). Desligue e ligue o Bluetooth e tente de novo."
                }
                if (purpose is Purpose.Bind) _bindStatus.value = errMsg
                fail(errMsg)
            }
        }.let {}

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) = main.post {
            if (!active || g != gatt) return@post
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            note("MTU $mtu")
            g.discoverServices()
        }.let {}

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main.post { if (active && g == gatt) servicesFound(g) }.let {}

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = main.post {
            if (!active || g != gatt) return@post
            subscribed(d.characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS)
            opDone()
        }.let {}

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = main.post {
            if (active && g == gatt) opDone()
        }.let {}

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { if (active && g == gatt) changed(c.uuid, copy) }
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION") val copy = c.value?.copyOf() ?: return
            main.post { if (active && g == gatt) changed(c.uuid, copy) }
        }
    }

    private fun servicesFound(g: BluetoothGatt) {
        val service = g.getService(FE95) ?: return fail("Este relógio não oferece o canal Bluetooth que o Aravia usa.")
        val found = service.characteristics
        if (!authed) {
            note("FE95 has ${found.size} characteristic(s)")
            for (c in found) note("  ${c.uuid.toString().substring(4, 8)}: props ${c.properties}")
        }
        rx = service.getCharacteristic(RX)
        tx = service.getCharacteristic(TX)
        if (rx != null && tx != null) {
            v1 = false
            enqueue(Op.Subscribe(rx!!))
            return
        }
        if (!V1_REQUIRED.all { u -> found.any { it.uuid == u } }) return fail("Este relógio não oferece o canal Bluetooth que o Aravia usa.")
        v1 = true
        if (purpose == Purpose.BIND_PROBE || purpose is Purpose.Bind) {
            note("bind: this watch uses BLE V1; local bind is written for V2 watches")
            if (purpose == Purpose.BIND_PROBE) { bindAnswered = true; finish() }
            else fail("Este relógio usa o canal Bluetooth V1; o pareamento direto só está disponível para relógios V2.")
            return
        }
        val maxWrite = mtu - 3
        note("BLE V1 channel, $maxWrite-byte writes")
        for (u in V1_ORDER) {
            val c = service.getCharacteristic(u) ?: continue
            v1Chars[u] = c
            v1Channels[u] = MiV1Channel(maxWrite)
            enqueue(Op.Subscribe(c))
        }
    }

    private fun subscribed(u: UUID, ok: Boolean) {
        if (!v1) {
            if (u != RX) return
            if (!ok) return fail("O relógio não abriu o canal de dados. Tente de novo.")
            if (keys == null) startSession()
            return
        }
        if (v1Chars[u] == null) return
        if (!ok) {
            note("${short(u)} notifications refused")
            if (u != V1_UPLOAD) return fail("O relógio não abriu o canal de dados. Tente de novo.")
            v1Chars.remove(V1_UPLOAD); v1Channels.remove(V1_UPLOAD)
        } else v1Subscribed += u
        if (v1Chars.keys.all { it in v1Subscribed } && keys == null && phoneNonce.isEmpty()) {
            stage = Phase.Authenticating
            startAuth()
        }
    }

    private fun changed(u: UUID, value: ByteArray) {
        if (!v1) { if (u == RX) receive(value); return }
        if (v1Chars[u] == null) return
        if (!authed) note("← ${short(u)} ${hexPrefix(value)}")
        v1Step(u) { it.receive(value) }
    }

    // GATT operation queue

    private fun enqueue(op: Op) { ops.addLast(op); pump() }

    private fun opDone() { opBusy = false; pump() }

    private fun pump() {
        val g = gatt ?: return
        while (!opBusy && ops.isNotEmpty()) {
            val op = ops.removeFirst()
            val started = when (op) {
                is Op.Subscribe -> {
                    g.setCharacteristicNotification(op.c, true)
                    val d = op.c.getDescriptor(CCCD)
                    if (d == null) { subscribed(op.c.uuid, false); false } else writeDescriptor(g, d)
                }
                is Op.Write -> writeCharacteristic(g, op.c, op.value)
            }
            if (started) opBusy = true
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; c.value = value; g.writeCharacteristic(c) }

    // Session

    private fun startSession() {
        stage = Phase.Authenticating
        note("→ SessionConfig")
        sendFrame(MiFrame.encode(MiFrame.KIND_SESSION_CONFIG, 0, MiFrame.startSessionPayload))
    }

    // Bind probe & local bind (docs/BIND.md)

    private fun startBindProbe() {
        bindAnswered = false
        val m = MiBind.Machine.create(MiBind.Config("aravia-probe", "aravia-probe", "")) ?: return finish()
        val first = m.start()
        bindMachine = m
        stage = Phase.Reading
        note("→ bind probe 1/17 ${hexPrefix(first, 80)}")
        sendCommand(first, encrypted = false)
        after(6_000, run) {
            if (active && purpose == Purpose.BIND_PROBE) {
                note("bind probe: no answer")
                finish()
            }
        }
    }

    private fun startLocalBind(bind: Purpose.Bind) {
        bindAnswered = false
        val m = MiBind.Machine.create(bind.config) ?: return fail("Configuração de pareamento inválida.")
        val first = m.start()
        bindMachine = m
        stage = Phase.Authenticating
        _bindStatus.value = "Iniciando pareamento local…"
        note("→ bind 1/17")
        sendCommand(first, encrypted = false)
        armBindTimer(20_000L)
    }

    private fun armBindTimer(ms: Long) {
        val serial = ++bindTimerSerial
        after(ms, run) {
            if (active && bindTimerSerial == serial && bindMachine != null) {
                fail("O relógio não respondeu ao pareamento a tempo.")
            }
        }
    }

    private fun handleBindReply(body: ByteArray) {
        val m = bindMachine ?: return
        val step = m.receive(body)
        if (purpose == Purpose.BIND_PROBE) {
            when (step) {
                is MiBind.Step.Send -> note("bind probe: the band offers local pairing${if (step.confirmOnBand) ", confirmed on the band" else ""}; stopping here")
                is MiBind.Step.Failed -> note("bind probe: ${step.failure}")
                is MiBind.Step.BoundResult, is MiBind.Step.Ignored -> {
                    note("bind probe: a reply of ${body.size} bytes that the probe doesn't read")
                    return
                }
            }
            bindMachine = null
            bindAnswered = true
            finish()
            return
        }
        val bindPurpose = purpose as? Purpose.Bind ?: return
        when (step) {
            is MiBind.Step.Ignored -> return
            is MiBind.Step.Send -> {
                if (step.confirmOnBand) {
                    _bindStatus.value = "Confirme o pareamento na tela do relógio…"
                    note("bind: waiting for confirmation on watch")
                    armBindTimer(35_000L)
                } else {
                    _bindStatus.value = "Trocando chaves com o relógio…"
                    armBindTimer(20_000L)
                }
                sendCommand(step.data, encrypted = false)
            }
            is MiBind.Step.BoundResult -> {
                bindTimerSerial++
                bindMachine = null
                bindAnswered = true
                _lastBindFailure.value = null
                val b = step.bound
                note("bind: paired (${b.info.model.ifBlank { "watch" }}, oob ${b.info.oobMode})")
                _bindStatus.value = "Relógio pareado com sucesso!"
                onBound?.invoke(b, bindPurpose.deviceName, bindPurpose.deviceAddress)
                finish()
            }
            is MiBind.Step.Failed -> {
                bindTimerSerial++
                bindMachine = null
                _lastBindFailure.value = step.failure
                step.tellBand?.let { sendCommand(it, encrypted = false) }
                note("bind failed: ${step.failure}")
                val msg = when (val f = step.failure) {
                    MiBind.Failure.AlreadyBound -> "Este relógio ainda está vinculado ao Mi Fitness. Desvincule/redefina nos ajustes do relógio para parear direto no Aravia, ou insira a chave de 32 caracteres abaixo."
                    MiBind.Failure.ServerBindRequired -> "O firmware deste modelo exige uma chave de autenticação prévia no primeiro pareamento. Insira a chave de 32 caracteres abaixo ou transfira de outro celular com Aravia."
                    MiBind.Failure.RejectedOnBand -> "Pareamento recusado ou expirou na tela do relógio."
                    MiBind.Failure.BandProofMismatch -> "A verificação criptográfica do relógio falhou. Tente de novo."
                    is MiBind.Failure.UnsupportedVerifyMode -> "Modo de verificação não suportado (${f.mode})."
                    is MiBind.Failure.UnsupportedOOB -> "Modo de confirmação não suportado (${f.mode})."
                    is MiBind.Failure.Band -> "O relógio recusou o pareamento (código ${f.code}, passo ${f.step})."
                    is MiBind.Failure.UnexpectedReply -> "Resposta inesperada do relógio no passo ${f.step}."
                }
                _bindStatus.value = msg
                fail(msg)
            }
        }
    }

    private fun startAuth() {
        phoneNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        note("→ auth 1/2")
        sendCommand(MiAuth.nonceCommand(phoneNonce, target?.appDeviceID), encrypted = false)
    }

    private fun didAuthenticate() {
        authed = true
        onKeyMatched?.invoke(keyIndex)
        if (target?.mac.isNullOrBlank()) {
            gatt?.device?.address?.takeIf { it.isNotBlank() }?.let { onMacResolved?.invoke(it) }
        }
        sendCommand(MiAuth.clockCommand(System.currentTimeMillis(), java.util.TimeZone.getDefault(),
            android.text.format.DateFormat.is24HourFormat(app)), encrypted = true)
        sendCommand(MiAuth.systemGet(2), encrypted = true)
        sendCommand(MiAuth.systemGet(78), encrypted = true)
        if (purpose == Purpose.HOLD) {
            stage = Phase.Reading
            _holdState.value = HoldState.CONNECTED
            holdFailures = 0
            note("auth ok → holding the session")
            return
        }
        val p = purpose
        if (p is Purpose.Send) {
            stage = Phase.Reading
            note("auth ok → sending ${p.commands.size} command(s)")
            for (cmd in p.commands) sendCommand(cmd, encrypted = true)
            after(2_500, run) { finish() }
            return
        }
        stage = if (p == Purpose.LIVE) Phase.Live else Phase.Reading
        val resuming = p == Purpose.SYNC && fileStage == FileStage.FETCHING
        note(if (resuming) "auth ok → next file" else "auth ok → battery + live stats")
        sendCommand(MiAuth.batteryCommand(), encrypted = true)
        sendCommand(MiAuth.realtimeCommand(start = true), encrypted = true)
        realtimeOn = true
        if (resuming) { fetchNextFile(); return }
        val thisRun = run
        if (p == Purpose.SYNC) {
            fileStage = FileStage.LISTING_TODAY
            note("→ today's file list")
            sendCommand(MiAuth.activityListCommand(today = true), encrypted = true)
            after(10_000, thisRun) {
                if (fileStage == FileStage.LISTING_TODAY || fileStage == FileStage.LISTING_PAST) {
                    note("no file list, continuing without files")
                    fileListMissing = true
                    fileStage = FileStage.DONE
                    finishSyncIfDone()
                }
            }
        }
        val window = if (p == Purpose.LIVE) (liveWindowMs ?: LIVE_LIMIT_MS) else FILE_SYNC_LIMIT_MS
        after(window, thisRun) { if (active) { note("time limit reached (${window / 1000} s)"); finish() } }
    }

    private fun handle(c: PbMessage, body: ByteArray) {
        val type = c.varints[1] ?: 0L
        val subtype = c.varints[2] ?: 0L
        when {
            type == MiAuth.COMMAND_TYPE_AUTH && subtype == MiAuth.SUBTYPE_NONCE -> {
                val verify = c.message(3)?.message(31)
                val watchNonce = verify?.blobs?.get(1)
                val watchHmac = verify?.blobs?.get(2)
                if (watchNonce == null || watchHmac == null) return fail("O relógio recusou a conexão. Feche o Mi Fitness e tente de novo.")
                val t = target ?: return
                if (keyIndex >= t.keys.size) return
                val result = MiAuth.confirmCommand(t.keys[keyIndex].unhexKey(), phoneNonce, watchNonce, watchHmac, "Aravia")
                if (result == null) {
                    if (keyIndex + 1 < t.keys.size) retryWithNextKey()
                    else fail("A chave não confere com este relógio. Pareie novamente ou atualize a chave na aba Relógios.", wrongKey = true)
                    return
                }
                keys = result.keys
                for (ch in v1Channels.values) ch.cipher = MiV1Cipher(result.keys)
                note("→ auth 2/2")
                sendCommand(result.command, encrypted = false)
            }
            type == MiAuth.COMMAND_TYPE_AUTH && subtype == MiAuth.SUBTYPE_AUTH -> {
                for (ch in v1Channels.values) ch.authenticated()
                didAuthenticate()
            }
            type == 2L && subtype == 1L -> {
                note("← 2/1 battery ${hexPrefix(body)}")
                MiAuth.batteryCharging(c)?.let { charging ->
                    val prev = _deviceState.value
                    _deviceState.value = MiAuth.DeviceState(charging, prev?.worn ?: true, prev?.asleep ?: false)
                }
                MiAuth.batteryLevel(c)?.let { _battery.value = it.toInt() }
            }
            type == 8L && subtype == 47L -> {
                val s = MiAuth.liveStats(c) ?: return
                if (_live.value == null) note("← 8/47 live ${hexPrefix(body)}")
                _live.value = Live(s.heartRate.toInt(), s.steps, s.calories)
                WorkoutController.onLive(s.heartRate.toInt(), s.steps)
                if (purpose == Purpose.SYNC && s.heartRate > 10) finishSyncIfDone()
            }
            type == 8L && subtype == 1L && fileStage == FileStage.LISTING_TODAY -> {
                todayIds = ActivityFileID.list(MiAuth.activityFileIDBytes(c))
                note("← today ${todayIds.size} file(s); → past list")
                fileStage = FileStage.LISTING_PAST
                sendCommand(MiAuth.activityListCommand(today = false), encrypted = true)
            }
            type == 8L && subtype == 2L && fileStage == FileStage.LISTING_PAST -> {
                val past = ActivityFileID.list(MiAuth.activityFileIDBytes(c))
                val plan = ActivitySyncPlan.make(todayIds, past, System.currentTimeMillis() / 1000,
                    storedAt = { storedAt?.invoke(it) }, mayAcknowledge = { mayAcknowledge?.invoke(it) ?: false })
                for (f in plan.acknowledge) sendCommand(MiAuth.activityAckCommand(f.bytes), encrypted = true)
                if (plan.acknowledge.isNotEmpty()) note("→ acknowledged ${plan.acknowledge.size} stored file(s); the band deletes them")
                requestedFiles = plan.fetch.toSet()
                fileQueue.clear(); fileQueue.addAll(plan.fetch)
                note("← past ${past.size} file(s); ${fileQueue.size} to request")
                _progress.value = 0 to fileQueue.size
                fileStage = FileStage.FETCHING
                fetchNextFile()
            }
            type == 2L && subtype == 2L -> {
                MiAuth.deviceInfo(c)?.let { info ->
                    note("← device: ${info.model}, firmware ${info.firmware}")
                    onDeviceInfo?.invoke(info)
                }
            }
            type == 2L && subtype == 3L -> note("← clock set (status ${c.varints[100] ?: 0})")
            type == 2L && (subtype == 78L || subtype == 79L) -> {
                note("← 2/$subtype state ${hexPrefix(body)}")
                MiAuth.deviceStateChange(c)?.let { ch ->
                    val prev = _deviceState.value
                    _deviceState.value = MiAuth.DeviceState(
                        charging = ch.charging ?: prev?.charging ?: false,
                        worn = ch.worn ?: prev?.worn ?: true,
                        asleep = ch.asleep ?: prev?.asleep ?: false,
                    )
                }
            }
            type == 2L && subtype == 17L -> {
                val ring = MiAuth.findPhoneRequest(c) ?: return
                note("← find phone: ${if (ring) "ring" else "stop"} ${hexPrefix(body)}")
                onFindPhone?.invoke(target?.shortName ?: "Seu relógio", ring)
            }
            type == 2L && subtype == 110L -> {
                note("← 2/110 ${body.size} bytes: ${hexPrefix(body, body.size)}")
            }
            type == 8L && (subtype == 8L || subtype == 10L || subtype == 14L) -> {
                val setting = MiMonitoring.Setting.fromGetSubtype(subtype)
                val settingBody = setting?.let { MiMonitoring.reply(c, it) }
                if (setting == null || settingBody == null) {
                    note("← 8/$subtype without its setting: ${hexPrefix(body)}")
                } else {
                    note("← 8/$subtype setting ${hexPrefix(settingBody, 64)}")
                    onMonitoring?.invoke(setting, settingBody)
                }
            }
            type == 8L && (subtype == 9L || subtype == 11L || subtype == 15L) -> {
                note("← 8/$subtype setting written (status ${c.varints[100] ?: 0})")
            }
            type == 8L && (subtype == 3L || subtype == 5L) -> {}
            type == 13L && subtype == 2L -> {
                val name = c.message(15)?.message(3)?.blobs?.get(1)?.toString(Charsets.UTF_8)
                val label = name?.let { "\"$it\"" } ?: "fields ${c.fieldNumbers}"
                if (factoryDumps.add(label)) note("← 13/2 factory file dump $label, not used")
            }
            else -> {
                val known = mapOf(
                    "16/3" to "the band asks for the phone's location",
                    "23/0" to "the band asks for the phone's status",
                    "23/17" to "the band registers for phone usage",
                    "4/1" to "the band changed its watch face",
                    "2/18" to "the band acknowledged Find",
                    "7/16" to "the band asks for the app's icon",
                    // Carries the band's weather place name: logged without its bytes.
                    "10/3" to "the band asks for the weather",
                )
                val what = known["$type/$subtype"]
                if (what != null) note("← $type/$subtype $what (not answered)")
                else note("← $type/$subtype not handled: ${hexPrefix(body, 240)}")
            }
        }
    }

    // Activity files

    private fun fetchNextFile() {
        if (!active || fileStage != FileStage.FETCHING) return
        val file = fileQueue.removeFirstOrNull()
        if (file == null) {
            fileStage = FileStage.DONE
            currentFile = null
            note("files done")
            finishSyncIfDone()
            return
        }
        currentFile = file
        currentRetried = false
        requestCurrentFile()
    }

    private fun requestCurrentFile() {
        val file = currentFile ?: return
        assembler = ActivityAssembler()
        sendCommand(MiAuth.activityFetchCommand(file.bytes), encrypted = true)
        armFileTimer(first = true)
    }

    /** 15 s for the first chunk, then 5 s per chunk (as on iOS); one retry, then the file is skipped
     *  for this sync and the rest get a fresh connection. */
    private fun armFileTimer(first: Boolean = false) {
        val token = ++fileToken
        after(if (first) 15_000 else 5_000, run) {
            val file = currentFile
            if (fileToken != token || fileStage != FileStage.FETCHING || file == null) return@after
            if (!currentRetried) { currentRetried = true; note("file ${file.hex} did not answer, retrying"); requestCurrentFile(); return@after }
            note("file ${file.hex} skipped")
            advanceProgress()
            if (fileQueue.isEmpty()) fetchNextFile() else { note("reconnecting for the ${fileQueue.size} remaining file(s)"); reconnectForNextFile() }
        }
    }

    private fun receiveActivityChunk(packet: ByteArray) {
        val file = currentFile
        if (fileStage != FileStage.FETCHING || file == null) return
        when (val e = assembler.add(packet)) {
            is AssemblerEvent.NeedMore -> armFileTimer()
            is AssemblerEvent.Complete -> {
                fileToken++
                val got = ActivityFileID.of(e.file) ?: file
                note("← file ${got.hex}, ${e.file.size} bytes")
                val queued = fileQueue.contains(got)
                fileQueue.remove(got)
                if (receivedFiles.add(got)) onActivityFile?.invoke(got, e.file)
                if (got == file || queued) advanceProgress()
                if (got == file) fetchNextFile() else {
                    note("← file ${got.hex} arrived while waiting for ${file.hex}")
                    assembler = ActivityAssembler()
                    armFileTimer()
                }
            }
            is AssemblerEvent.Failed -> {
                fileToken++
                note("file ${file.hex} corrupt: ${e.reason}")
                advanceProgress()
                fetchNextFile()
            }
        }
    }

    private fun advanceProgress() { _progress.value?.let { _progress.value = (it.first + 1) to it.second } }

    /** A sync ends once its files are in and a heart-rate reading came (or 8 s after the files). */
    private fun finishSyncIfDone() {
        if (purpose != Purpose.SYNC) return
        val filesDone = fileStage == FileStage.DONE || fileStage == FileStage.IDLE
        if (filesDone && (_live.value?.heartRate ?: 0) > 10) { finish(); return }
        if (filesDone && fileStage == FileStage.DONE) after(8_000, run) { if (active && purpose == Purpose.SYNC) finish() }
    }

    private fun retryWithNextKey() {
        keyIndex++
        note("key did not match, trying key ${keyIndex + 1}")
        reconnect()
    }

    private fun reconnectForNextFile() {
        val token = ++fileToken
        currentFile = null
        realtimeOn = false
        reconnect()
        after(20_000, run) { if (fileToken == token) fail("O relógio não respondeu. Feche o Mi Fitness e tente de novo.") }
    }

    /** Drops the connection and opens a new one in the same run; the sync's state is kept. */
    private fun reconnect() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        resetConnection()
        val thisRun = run
        after(1_000, thisRun) { locate(thisRun) }
    }

    private fun finish() {
        if (!active || finishing) return
        finishing = true
        if (authed && realtimeOn) sendCommand(MiAuth.realtimeCommand(start = false), encrypted = true)
        val thisRun = run
        after(400, thisRun) {
            val outcome = if (authed || bindAnswered) completion() else Outcome.Failed("Não deu para ler o relógio.")
            release()
            stage = when (outcome) {
                is Outcome.Complete -> Phase.Done
                is Outcome.Partial -> Phase.Partial(outcome.reason)
                is Outcome.Failed -> Phase.Failed(outcome.message)
            }
            note("result: ${outcome.label}")
            _lastOutcome.value = outcome
            onFinish?.invoke(outcome)
            resumeHold()
        }
    }

    private fun completion(): Outcome {
        if (purpose != Purpose.SYNC) return Outcome.Complete
        if (fileListMissing || fileStage == FileStage.IDLE || fileStage == FileStage.LISTING_TODAY || fileStage == FileStage.LISTING_PAST) {
            return Outcome.Partial("Não veio a lista de arquivos. Os registros chegam na próxima sincronização.")
        }
        val total = requestedFiles.size
        val received = receivedFiles.count { it in requestedFiles }
        return if (received < total) Outcome.Partial("$received de $total arquivos recebidos. O resto chega na próxima sincronização.") else Outcome.Complete
    }

    private fun fail(message: String, bluetoothOff: Boolean = false, wrongKey: Boolean = false) {
        if (!active) return
        note("error: $message")
        if (purpose == Purpose.HOLD) {
            holdFailed(bluetoothOff, wrongKey)
            return
        }
        if (authed && purpose == Purpose.SYNC) { realtimeOn = false; finish(); return }
        release()
        stage = Phase.Failed(message)
        val outcome = Outcome.Failed(message)
        _lastOutcome.value = outcome
        onFinish?.invoke(outcome)
        resumeHold()
    }

    /** Lets go of the band, so Mi Fitness or another session can have it. */
    private fun release() {
        active = false
        bindMachine = null
        stopScan()
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        ops.clear()
        opBusy = false
        note("channel released")
        BandService.stop(app)
    }

    private fun resetConnection() {
        rx = null; tx = null; buffer.clear(); ops.clear(); opBusy = false; seq = 0; sentFrames.clear()
        keys = null; phoneNonce = ByteArray(0); v1 = false; mtu = 23
        v1Chars.clear(); v1Channels.clear(); v1Subscribed.clear(); sliceTimers.clear()
    }

    // Frames (V2)

    private fun sendCommand(protobuf: ByteArray, encrypted: Boolean) {
        if (v1) { v1Step(V1_WRITE) { it.send(protobuf) }; return }
        val k = keys
        val body = if (encrypted && k != null) MiCrypto.ctr(k.encryptionKey, protobuf) else protobuf
        val payload = byteArrayOf(0x01, if (encrypted) 0x02 else 0x01) + body
        val frame = MiFrame.encode(MiFrame.KIND_DATA, seq, payload)
        sentFrames[seq] = frame
        sendFrame(frame)
        seq = (seq + 1) and 0xFF
    }

    private fun sendFrame(frame: ByteArray) {
        val c = tx ?: return
        val size = maxOf(20, mtu - 3)
        var offset = 0
        while (offset < frame.size) {
            enqueue(Op.Write(c, frame.copyOfRange(offset, minOf(offset + size, frame.size))))
            offset += size
        }
    }

    private fun receive(chunk: ByteArray) {
        buffer.addAll(chunk.toList())
        for (p in MiFrame.drain(buffer)) {
            when (p.kind) {
                MiFrame.KIND_SESSION_CONFIG -> when (val cur = purpose) {
                    Purpose.BIND_PROBE -> if (bindMachine == null) startBindProbe()
                    is Purpose.Bind -> if (bindMachine == null) startLocalBind(cur)
                    else -> if (phoneNonce.isEmpty()) startAuth()
                }
                MiFrame.KIND_DATA -> { sendFrame(MiFrame.encode(MiFrame.KIND_ACK, p.seq, ByteArray(0))); decode(p.payload) }
                MiFrame.KIND_NACK -> {
                    val resend = MiFrame.resendSeqs(seq, p.seq)
                    note(if (resend.isEmpty()) "← NACK ${p.seq}, nothing pending" else "← NACK ${p.seq}, resending ${resend.size}")
                    for (s in resend) sentFrames[s]?.let { sendFrame(it) }
                }
            }
        }
    }

    private fun decode(payload: ByteArray) {
        if (payload.size < 2) return
        val channel = payload[0].toInt() and 0x0F
        val opcode = payload[1].toInt() and 0xFF
        var body = payload.copyOfRange(2, payload.size)
        if (opcode == 2) { val k = keys ?: return; body = MiCrypto.ctr(k.decryptionKey, body) }
        if (channel == 5) { receiveActivityChunk(body); return }
        if (channel != 1) return
        commandBytes(body)
    }

    private fun commandBytes(body: ByteArray) {
        val c = PbMessage.parse(body) ?: return
        if ((purpose == Purpose.BIND_PROBE || purpose is Purpose.Bind) && c.varints[1] == MiBind.COMMAND_TYPE) {
            handleBindReply(body)
            return
        }
        if (purpose == Purpose.HOLD && authed) {
            note("← held ${c.varints[1] ?: 0}/${c.varints[2] ?: 0}, ${body.size} bytes")
        }
        handle(c, body)
    }

    // V1 (docs/BLE-V1.md)

    private fun v1Step(u: UUID, step: (MiV1Channel) -> MiV1Channel.Output) {
        val ch = v1Channels[u] ?: return
        val c = v1Chars[u] ?: return
        ch.maxWrite = mtu - 3
        val out = step(ch)
        for (w in out.writes) { if (!authed) note("→ ${short(u)} ${hexPrefix(w)}"); enqueue(Op.Write(c, w)) }
        armSliceTimer(u)
        for (e in out.events) {
            if (!active) break
            when (e) {
                is MiV1Channel.Event.Message -> when (u) {
                    V1_ACTIVITY -> receiveActivityChunk(e.data)
                    V1_READ, V1_WRITE -> commandBytes(e.data)
                    else -> note("← ${short(u)} ${e.data.size} bytes, not used")
                }
                is MiV1Channel.Event.Acknowledged -> if (!e.accepted) note("← ${short(u)} refused a message")
                is MiV1Channel.Event.Undecryptable -> note("← ${short(u)} ${e.count} bytes didn't decrypt")
                is MiV1Channel.Event.Unexpected -> note("← ${short(u)} ${e.what}")
            }
        }
    }

    /** A chunked V1 message that stops partway: ask for the missing slices every 5 s. */
    private fun armSliceTimer(u: UUID) {
        if (v1Channels[u]?.isReceiving != true) { sliceTimers.remove(u); return }
        val serial = ++sliceSerial
        sliceTimers[u] = serial
        after(5_000, run) {
            if (active && sliceTimers[u] == serial) { note("← ${short(u)} slices missing, asking again"); v1Step(u) { it.requestMissing() } }
        }
    }

    // Helpers

    private fun after(ms: Long, thisRun: Int, work: () -> Unit) {
        main.postDelayed({ if (run == thisRun) work() }, ms)
    }

    private fun short(u: UUID) = u.toString().substring(4, 8)

    fun hexPrefix(b: ByteArray, limit: Int = 48): String {
        val shown = b.take(limit).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return if (b.size > limit) "$shown… (${b.size} bytes)" else shown
    }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** The log shown in Diagnostics; it has the band's traffic, never its key or address. */
    fun note(text: String) {
        _log.value = (_log.value + "${clock.format(Date())}  $text").takeLast(LOG_LIMIT)
    }

    private fun String.unhexKey(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
