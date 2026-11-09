package com.walter.obdgarage.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Procura dispositivos BLE por perto. Os resultados chegam em [devices], do sinal mais forte ao mais fraco. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val found = LinkedHashMap<String, BleDevice>()
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices

    private var callback: ScanCallback? = null

    /** Começa a busca. Devolve uma mensagem de erro, ou null se começou. */
    fun start(): String? {
        if (callback != null) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return "Este aparelho não tem Bluetooth"
        if (!adapter.isEnabled) return "O Bluetooth está desligado"
        val scanner = adapter.bluetoothLeScanner ?: return "Não foi possível iniciar a busca por Bluetooth"

        found.clear()
        _devices.value = emptyList()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val name = result.scanRecord?.deviceName ?: try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }
                found[address] = BleDevice(name?.takeIf { it.isNotBlank() }, address, result.rssi)
                _devices.value = found.values.sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w("DashBle", "scan falhou: $errorCode")
            }
        }
        return try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            callback = cb
            null
        } catch (e: SecurityException) {
            "Sem permissão de Bluetooth"
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: SecurityException) {
        }
    }
}
