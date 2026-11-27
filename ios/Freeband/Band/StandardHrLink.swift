import CoreBluetooth
import Foundation

/// Universal Bluetooth SIG Heart Rate Service (`0x180D` / `0x2A37`) & Battery (`0x180F` / `0x2A19`)
/// client for iOS: connects in 1 tap to any BLE chest strap (Polar H10/H9, Garmin HRM, Coospo,
/// Magene, Wahoo) or smartwatch broadcasting HR over BLE.
///
/// Works on ALL iPhones (including free-Apple-ID Sideloadly builds, zero extra entitlements):
/// - Feeds live BPM directly to `WorkoutController.shared`
/// - Runs a 60-second resting Spot HRV (RMSSD) check-in saved to `ExternalHealthStore.shared`
@MainActor
final class StandardHrLink: NSObject, ObservableObject {
    static let shared = StandardHrLink()

    enum Status: Equatable {
        case idle, scanning, connecting, connected

        var label: String {
            switch self {
            case .idle: "Desconectado"
            case .scanning: "Procurando sensores cardíacos…"
            case .connecting: "Conectando ao sensor…"
            case .connected: "Conectado"
            }
        }
    }

    struct DiscoveredDevice: Identifiable, Equatable {
        let id: UUID
        let name: String
        let rssi: Int?
        let alreadyConnected: Bool
    }

    @Published private(set) var status: Status = .idle
    @Published private(set) var devices: [DiscoveredDevice] = []
    @Published private(set) var deviceName: String?
    @Published private(set) var bpm: Int?
    @Published private(set) var battery: Int?
    @Published private(set) var hasRr = false
    @Published private(set) var spotHrvRunning = false
    @Published private(set) var spotHrvElapsedSec = 0
    @Published private(set) var spotHrvRmssdMs: Double?
    @Published private(set) var error: String?

    private static let hrServiceUUID = CBUUID(string: HrGattParser.serviceHeartRate)
    private static let hrCharUUID = CBUUID(string: HrGattParser.charHeartRateMeasurement)
    private static let batteryServiceUUID = CBUUID(string: HrGattParser.serviceBattery)
    private static let batteryCharUUID = CBUUID(string: HrGattParser.charBatteryLevel)

    private var central: CBCentralManager?
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var activePeripheral: CBPeripheral?
    private var pendingScan = false
    private let spotSession = HrGattParser.SpotHrvSession()

    private override init() {
        super.init()
        if let today = ExternalHealthStore.shared.latestSpotHrv {
            spotHrvRmssdMs = today.valueMs
        }
    }

    private func ensureCentral() -> CBCentralManager {
        if let central { return central }
        let mgr = CBCentralManager(delegate: self, queue: .main)
        central = mgr
        return mgr
    }

    func startScan() {
        error = nil
        let mgr = ensureCentral()
        if mgr.state != .poweredOn {
            pendingScan = true
            status = .scanning
            if mgr.state == .poweredOff {
                error = "Ligue o Bluetooth do iPhone para procurar sensores cardíacos."
                status = .idle
            } else if mgr.state == .unauthorized {
                error = "Permita o acesso ao Bluetooth em Ajustes › Aravia."
                status = .idle
            }
            return
        }
        beginScan(mgr)
    }

    private func beginScan(_ mgr: CBCentralManager) {
        pendingScan = false
        devices = []
        peripherals = [:]
        status = .scanning

        // Include any HR strap/watch already connected to iOS at the OS level!
        let connected = mgr.retrieveConnectedPeripherals(withServices: [Self.hrServiceUUID])
        for p in connected {
            peripherals[p.identifier] = p
            let name = p.name ?? "Sensor cardíaco"
            devices.append(DiscoveredDevice(id: p.identifier, name: name, rssi: nil, alreadyConnected: true))
        }

        mgr.scanForPeripherals(withServices: [Self.hrServiceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false,
        ])
    }

    func stopScan() {
        pendingScan = false
        central?.stopScan()
        if status == .scanning { status = .idle }
    }

    func connect(_ id: UUID) {
        guard let mgr = central, let p = peripherals[id] else { return }
        mgr.stopScan()
        error = nil
        status = .connecting
        deviceName = p.name ?? "Sensor cardíaco"
        activePeripheral = p
        p.delegate = self
        mgr.connect(p, options: nil)
    }

    func disconnect() {
        pendingScan = false
        spotHrvRunning = false
        spotSession.reset()
        central?.stopScan()
        if let p = activePeripheral {
            central?.cancelPeripheralConnection(p)
        }
        activePeripheral = nil
        status = .idle
        bpm = nil
        battery = nil
        hasRr = false
        spotHrvElapsedSec = 0
    }

    func startSpotHrv() {
        guard status == .connected, hasRr else { return }
        spotSession.reset()
        spotHrvRunning = true
        spotHrvElapsedSec = 0
        error = nil
    }

    func cancelSpotHrv() {
        spotHrvRunning = false
        spotSession.reset()
        spotHrvElapsedSec = 0
    }

    private func handleMeasurement(_ data: Data) {
        guard let m = HrGattParser.parseHeartRate(data) else { return }
        if (30 ... 220).contains(m.bpm) {
            bpm = m.bpm
            WorkoutController.shared.receivedExternalBpm(m.bpm)
        }
        if !m.rrIntervalsMs.isEmpty {
            hasRr = true
        }
        if spotHrvRunning {
            spotSession.add(m)
            spotHrvElapsedSec = Int(spotSession.elapsedSeconds.rounded(.down))
            if spotSession.elapsedSeconds >= 60.0 {
                if let rmssd = spotSession.computeRmssd() {
                    spotHrvRmssdMs = rmssd
                    spotHrvRunning = false
                    ExternalHealthStore.shared.saveSpotHrv(rmssdMs: rmssd, sourceName: deviceName ?? "BLE HR")
                    AppModel.shared?.rescoreAll()
                } else {
                    spotHrvRunning = false
                    error = "Poucos batimentos limpos para calcular HRV. Fique parado e tente de novo."
                }
            }
        }
    }
}

extension StandardHrLink: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn:
                if self.pendingScan { self.beginScan(central) }
            case .poweredOff:
                self.status = .idle
                self.error = "Bluetooth desligado."
            case .unauthorized:
                self.status = .idle
                self.error = "Bluetooth sem permissão em Ajustes › Aravia."
            default:
                break
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? "Sensor cardíaco"
        let rssiVal = RSSI.intValue
        Task { @MainActor in
            self.peripherals[peripheral.identifier] = peripheral
            let item = DiscoveredDevice(id: peripheral.identifier, name: name, rssi: rssiVal, alreadyConnected: false)
            if let idx = self.devices.firstIndex(where: { $0.id == peripheral.identifier }) {
                self.devices[idx] = item
            } else {
                self.devices.append(item)
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.status = .connected
            self.deviceName = peripheral.name ?? self.deviceName ?? "Sensor cardíaco"
            peripheral.discoverServices([Self.hrServiceUUID, Self.batteryServiceUUID])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            self.status = .idle
            self.activePeripheral = nil
            self.error = error?.localizedDescription ?? "Não foi possível conectar ao sensor."
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            if self.activePeripheral?.identifier == peripheral.identifier {
                self.activePeripheral = nil
                self.status = .idle
                self.bpm = nil
                self.spotHrvRunning = false
            }
        }
    }
}

extension StandardHrLink: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for s in services {
            if s.uuid == Self.hrServiceUUID {
                peripheral.discoverCharacteristics([Self.hrCharUUID], for: s)
            } else if s.uuid == Self.batteryServiceUUID {
                peripheral.discoverCharacteristics([Self.batteryCharUUID], for: s)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        for c in chars {
            if c.uuid == Self.hrCharUUID {
                peripheral.setNotifyValue(true, for: c)
            } else if c.uuid == Self.batteryCharUUID {
                peripheral.readValue(for: c)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        Task { @MainActor in
            if characteristic.uuid == Self.hrCharUUID {
                self.handleMeasurement(data)
            } else if characteristic.uuid == Self.batteryCharUUID {
                self.battery = HrGattParser.parseBatteryLevel(data)
            }
        }
    }
}
