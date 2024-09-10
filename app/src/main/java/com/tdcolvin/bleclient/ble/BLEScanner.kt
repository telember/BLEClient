package com.tdcolvin.bleclient.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.pow

class BLEScanner(private val context: Context) {

    companion object {
        private const val SERVICE_UUID = "0000fe9a-0000-1000-8000-00805f9b34fb"
        private const val DEFAULT_TX_POWER = -65 // Default Tx Power if 127 is encountered
        private const val COLLECTION_INTERVAL = 2000L // 2 seconds interval
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw Exception("Bluetooth is not supported by this device")

    private val scanner: BluetoothLeScanner
        get() = bluetoothManager.adapter.bluetoothLeScanner

    private val scanFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid.fromString(SERVICE_UUID))
        .build()

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val isScanning = MutableStateFlow(false)
    val foundDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())

    private var lastCollectionTime = 0L // Track the last collection time
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // Scope for coroutines

    // Map to track EMA filters for each beacon ID
    private val filters = mutableMapOf<String, ExponentialMovingAverage>()

    // Scan callback
    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                handleScanResult(it)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            isScanning.value = false
            println("Scan failed with error code: $errorCode")
        }
    }

    // Function to handle scan results
    private fun handleScanResult(result: ScanResult) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCollectionTime >= COLLECTION_INTERVAL) {
            lastCollectionTime = currentTime

            // Launch coroutine to process the scan result
            scope.launch {
                processScanResult(result)
            }
        }
    }

    // Process each scan result to extract data and calculate distance
    private suspend fun processScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord
        val byteArray = scanRecord?.serviceData?.get(ParcelUuid.fromString(SERVICE_UUID))

        if (byteArray != null && byteArray.isNotEmpty()) {
            val frameType = byteArray[0].toInt() and 0b00001111
            if (frameType == 0x00 || frameType == 0x01) {
                val id = byteArray.sliceArray(1 until 17).joinToString("") { "%02x".format(it) }
                val estimatedTxPower = if (result.txPower == 127) DEFAULT_TX_POWER else result.txPower
                val distance = calculateDistance(result.rssi, estimatedTxPower)

                // Get or create a filter for the current beacon ID
                val filter = filters.getOrPut(id) { ExponentialMovingAverage(alpha = 0.2) }

                // Apply the EMA filter to reduce noise for this beacon ID
                val smoothedDistance = filter.filter(distance)

                // Emit the smoothed distance data
                println("Beacon $id and Smoothed Distance: $smoothedDistance")

                // Update found devices state
                foundDevices.update { currentDevices ->
                    currentDevices.toMutableList().apply {
                        if (result.device !in this) add(result.device)
                    }
                }
            }
        } else {
            println("Service Data not found or empty")
        }
    }

    // Calculate the distance based on RSSI and Tx Power
    private fun calculateDistance(rssi: Int, txPower: Int, pathLossExponent: Double = 2.0): Double {
        return 10.0.pow((txPower - rssi) / (10 * pathLossExponent))
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun startScanning() {
        if (isScanning.value) return
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        isScanning.value = true
        println("Started scanning...")
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun stopScanning() {
        if (!isScanning.value) return
        scanner.stopScan(scanCallback)
        isScanning.value = false
        scope.cancel() // Cancel all coroutines when stopping the scan
        println("Stopped scanning.")
    }
}

// Exponential Moving Average Filter to reduce noise in distance measurements
class ExponentialMovingAverage(private val alpha: Double) {
    private var average: Double? = null

    fun filter(newMeasurement: Double): Double {
        average = if (average == null) {
            newMeasurement // First measurement sets the initial average
        } else {
            alpha * newMeasurement + (1 - alpha) * average!! // EMA calculation
        }
        return average!!
    }
}