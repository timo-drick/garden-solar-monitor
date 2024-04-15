package de.drick.bmsmonitor.bluetooth

import com.welie.blessed.*
import de.drick.log
import kotlinx.coroutines.flow.*
import java.util.*

data class ConnectionEvent(
    val state: State,
    val peripheral: BluetoothPeripheral,
    val status: BluetoothCommandStatus?
) {
    enum class State {
        Discovered, Connected, ConnectionFailed, Disconnected
    }
}

class KBluetoothManager {
    private val deviceConnectionStateChanged = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 1)
    private val connectedServiceList = mutableMapOf<BluetoothPeripheral, BluetoothLeService>()
    private val callback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            println("Discovered: $scanResult")
            val event = ConnectionEvent(ConnectionEvent.State.Discovered, peripheral, null)
            val result = deviceConnectionStateChanged.tryEmit(event)
            log("Emitted: $result")
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            log("connected: ${peripheral.name}")
            connectedServiceList[peripheral]?.setState(BluetoothLeService.State.Connected)
            val event = ConnectionEvent(ConnectionEvent.State.Connected, peripheral, null)
            deviceConnectionStateChanged.tryEmit(event)
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: BluetoothCommandStatus) {
            log("Connection failed: ${peripheral.name} status: $status")
            val event = ConnectionEvent(ConnectionEvent.State.ConnectionFailed, peripheral, status)
            deviceConnectionStateChanged.tryEmit(event)
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: BluetoothCommandStatus) {
            log("Disconnected: ${peripheral.name} status: $status")
            connectedServiceList[peripheral]?.setState(BluetoothLeService.State.Disconnected)
            val event = ConnectionEvent(ConnectionEvent.State.Disconnected,peripheral, status)
            deviceConnectionStateChanged.tryEmit(event)
        }
    }
    private val central = BluetoothCentralManager(callback)
    fun scanForPeripheralsWithServices(serviceUUID: Array<UUID>) {
        central.scanForPeripheralsWithServices(serviceUUID)
    }
    fun stopScan() {
        central.stopScan()
    }

    fun disconnect(service: BluetoothLeService) {
        central.cancelConnection(service.gatt)
    }

    suspend fun connect(address: String): BluetoothLeService {
        central.stopScan()
        central.connectedPeripherals.forEach {
            log("Connected: $it")
        }
        log("Scan for device: $address")
        central.scanForPeripheralsWithAddresses(arrayOf(address))
        //central.scanForPeripherals()
        log("Wait until device found")
        val peripheral = deviceConnectionStateChanged
            .filter { it.state == ConnectionEvent.State.Discovered }
            .first { it.peripheral.address == address }
            .peripheral
        log("Device found")
        central.stopScan()
        val service = BluetoothLeService(peripheral)
        connectedServiceList[peripheral] = service
        service.setState(BluetoothLeService.State.Connecting)
        log("connecting...")
        central.connectPeripheral(peripheral, service.bluetoothGattCallback)
        //Wait until connected
        deviceConnectionStateChanged
            .filter { it.peripheral == peripheral }
            .filter { it.state == ConnectionEvent.State.Connected }
            .first()
        log("Connected")
        return service
    }

    fun shutdown() {
        central.shutdown()
    }
}

class BluetoothLeService(
    val gatt: BluetoothPeripheral
) {
    enum class State {
        Connected, Connecting, Disconnecting, Disconnected
    }
    private val _connectionState = MutableStateFlow(State.Disconnected)
    val connectionState: StateFlow<State> = _connectionState

    private val discoveryResult = MutableStateFlow<List<BluetoothGattService>?>(null)
    private val clientCharacteristicConfigurationDescriptor = checkNotNull(UUID.fromString("2902-0000-1000-8000-00805f9b34fb"))

    internal fun setState(state: State) {
        _connectionState.value = state
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun writeCharacteristic(serviceUUID: UUID, characteristicUUID: UUID, value: ByteArray) {
        val characteristic = getCharacteristic(serviceUUID, characteristicUUID)
        if (characteristic != null) {
            log("is readable : ${characteristic.isReadable()}")
            log("is writeable: ${characteristic.isWritable()}")
            log("Write values: ${value.toHexString()}")
            if (characteristic.isWritable()) {
                val result = gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE)
                log("Result: $result")
            } else {
                log("Characteristic not writeable!")
            }
        } else {
            log("Unable to find characteristic!")
        }
    }

    data class CharacteristicSubscription(
        val uuid: UUID,
        val callback: (ByteArray) -> Unit
    )

    private val subscriptionMap = mutableMapOf<UUID, CharacteristicSubscription>()
    suspend fun subscribeForNotification(
        serviceUUID: UUID, characteristicUUID: UUID, callback: (ByteArray) -> Unit
    ) {
        val subscriptionData = CharacteristicSubscription(characteristicUUID, callback)
        subscriptionMap[subscriptionData.uuid] = subscriptionData
        unSubscribeForNotificationInternal(true, serviceUUID, characteristicUUID)
    }
    suspend fun unSubscribeForNotification(serviceUUID: UUID, characteristicUUID: UUID) {
        unSubscribeForNotificationInternal(false, serviceUUID, characteristicUUID)
        subscriptionMap.remove(characteristicUUID)
    }

    val ENABLE_NOTIFICATION_VALUE: ByteArray = byteArrayOf(0x01, 0x00)
    val DISABLE_NOTIFICATION_VALUE: ByteArray = byteArrayOf(0x00, 0x00)

    private suspend fun unSubscribeForNotificationInternal(
        subscribe: Boolean,
        serviceUUID: UUID,
        characteristicUUID: UUID
    ) {
        val characteristic = getCharacteristic(serviceUUID, characteristicUUID)
        if (characteristic != null) {
            log("is readable   : ${characteristic.isReadable()}")
            log("is writeable  : ${characteristic.isWritable()}")
            log("is indicatable: ${characteristic.isIndicatable()}")
            log("is notifiable : ${characteristic.isNotifiable()}")
            val success = gatt.setNotify(characteristic, true)
            log("result: $success")
            characteristic.getDescriptor(clientCharacteristicConfigurationDescriptor)
                ?.let { cccDescriptor ->
                    val value = if (subscribe) ENABLE_NOTIFICATION_VALUE else DISABLE_NOTIFICATION_VALUE
                    val result = gatt.writeDescriptor(cccDescriptor, value)
                    log("write ccc descriptor: $result")
                }
        } else {
            log("Unable to find service or characteristc!")
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    internal val bluetoothGattCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(
            peripheral: BluetoothPeripheral,
            services: MutableList<BluetoothGattService>
        ) {
            //val services = gatt.services
            services.forEach { gattService ->
                val uuid = gattService.uuid.toString()
                val gattCharacteristics = gattService.characteristics
                val serviceName = AllGattServices.lookup(uuid)
                log("Service: $uuid name: $serviceName")
                gattCharacteristics.forEach { gattCharacteristic ->
                    val characteristicUuid = gattCharacteristic.uuid.toString()
                    val characteristicName = AllGattCharacteristics.lookup(characteristicUuid)
                    log("   Characteristics: $characteristicUuid $characteristicName")
                }
            }
            discoveryResult.value = services
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray?,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            log("onWrite: $status")
        }

        override fun onDescriptorWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray?,
            descriptor: BluetoothGattDescriptor,
            status: BluetoothCommandStatus
        ) {
            log("descriptor write status: $status")
        }

        override fun onDescriptorRead(
            peripheral: BluetoothPeripheral,
            value: ByteArray?,
            descriptor: BluetoothGattDescriptor,
            status: BluetoothCommandStatus
        ) {
            log("descriptor read")
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            log("Characteristic status: $status")
            log("Characteristic update:\n${value.toHexString()}")
            subscriptionMap[characteristic.uuid]?.callback?.invoke(value)
        }

        override fun onNotificationStateUpdate(
            peripheral: BluetoothPeripheral,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            log("Notification state update for ${peripheral.name} status: $status")
        }

        override fun onReadRemoteRssi(peripheral: BluetoothPeripheral, rssi: Int, status: BluetoothCommandStatus) {
            log("Remote rssi: $rssi $status")
        }

        override fun onBondingStarted(peripheral: BluetoothPeripheral) {
            log("Bonding started: ${peripheral.name}")
        }
        override fun onBondLost(peripheral: BluetoothPeripheral) {
            log("Bond lost: ${peripheral.name}")
        }

        override fun onBondingSucceeded(peripheral: BluetoothPeripheral) {
            log("Bond succeeded")
        }

        override fun onBondingFailed(peripheral: BluetoothPeripheral) {
            log("Bond failed")
        }
    }

    private suspend fun getCharacteristic(
        serviceUUID: UUID,
        characteristicUUID: UUID
    ): BluetoothGattCharacteristic? {
        //Wait until discovery is ready
        val discoveryResult = discoveryResult.filterNotNull().first()
        // Search characteristic in discovered result
        val service = discoveryResult.find { it.uuid == serviceUUID }
        if (service != null) {
            val characteristic = service.characteristics.find { it.uuid == characteristicUUID }
            if (characteristic != null) {
                return characteristic
            }
        }

        // Not found in discoverd services. Check if we can connect directly
        gatt.getService(serviceUUID)?.let { service ->
            service.getCharacteristic(characteristicUUID)?.let { characteristic ->
                log("is readable   : ${characteristic.isReadable()}")
                log("is writeable  : ${characteristic.isWritable()}")
                log("is indicatable: ${characteristic.isIndicatable()}")
                log("is notifiable : ${characteristic.isNotifiable()}")
                return characteristic
            } ?: log("Characteristics not found!")
        } ?: log("Service not found!")
        return null
    }
}

fun BluetoothGattCharacteristic.isReadable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

fun BluetoothGattCharacteristic.isWritable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
    return properties and property != 0
}