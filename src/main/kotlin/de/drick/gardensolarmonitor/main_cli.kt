package de.drick.gardensolarmonitor

import com.welie.blessed.*
import de.drick.bmsmonitor.bluetooth.BluetoothLeService
import de.drick.bmsmonitor.bluetooth.KBluetoothManager
import de.drick.bmsmonitor.bms_adapter.jk_bms.JKBmsAdapter
import de.drick.log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

val serviceUUID =
    checkNotNull(UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"))

fun main() {
    println("Hello World")

    //central.scanForPeripheralsWithServices(arrayOf(serviceUUID))
    runBlocking {
        val bleManager = KBluetoothManager()
        log("Connecting")
        val service = bleManager.connect("C8:47:8C:E2:9A:ED")
        log("Connected")
        val jkBms = JKBmsAdapter(service)
        log("Start")
        launch {
            jkBms.deviceInfoState.collect {
                log(it.toString())
            }
        }
        launch {
            jkBms.cellInfoState.collect {
                log(it.toString())
            }
        }
        jkBms.start()
        delay(20000)
        log("stop")
        jkBms.stop()
        delay(5000)
        log("Disconnect")
        bleManager.disconnect(service)
        bleManager.shutdown()
        //val jkBmsPeripheral = central.getPeripheral("C8:47:8C:E2:9A:ED")
    }
}