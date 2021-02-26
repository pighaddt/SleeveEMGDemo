
package com.itri.sleeveemgdemo

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.itri.sleeveemgdemo.BleScanner.BleScannerAPI18
import com.itri.sleeveemgdemo.BleScanner.BleScannerAPI21
import java.util.*

/**
 * Created by Haba on 2019/2/27.
 * © Ingee.com.tw All Rights Reserved
 */

/**
 * 此 project 不預先加入 permission 以方便使用管理
 *
 * 建議加入以下權限以確保使用藍芽
 *
 * BLUETOOTH、BLUETOOTH_ADMIN、ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION
 *
 * 使用 [BleScannerAPI18] 目標為 API 18 ~ API 21 之間裝置
 *
 * 使用 [BleScannerAPI21] 目標為 API 21 以上裝置，並且須對使用者要求定位權限，以使用 [ScanCallback] 得到更詳細的藍芽資訊
 */
sealed class BleScanner(val context: Context) {

    protected val tag = javaClass.simpleName

    companion object {
        const val PermissionRequestCode = 1
        const val BluetoothEnableRequestCode = 87
    }

    open class Device {
        var lastScannedMillis = 0L

        fun as18(): BleScannerAPI18.Device = this as BleScannerAPI18.Device
        fun as21(): BleScannerAPI21.Device = this as BleScannerAPI21.Device
    }

    /**
     * 裝置搜尋 callback for API 18 + API 21 (藍芽會限制以 API 18 為主)
     */
    var onDeviceFoundDefault: (device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?) -> Unit =
        { _, _, _ -> }

    /**
     * 裝置搜尋 callback for API 18
     */
    var onDeviceFoundAPI18: (device: BleScannerAPI18.Device) -> Unit = { _ -> }

    /**
     * 裝置搜尋 callback for API 21
     */
    var onDeviceFoundAPI21: (device: BleScannerAPI21.Device) -> Unit = { _ -> }

    /**
     * 藍芽開啟 callback
     */
    var handleBluetoothEnableCallback: (permissionGranted: Boolean) -> Unit = {}

    /**
     * 權限要求 callback
     */
    var handleRequestPermissionCallback: (permissionGranted: Boolean) -> Unit = {}

    /**
     * 是否開啟 debug 輸出
     */
    var debug = false

    /**
     * 搜尋中與否
     */
    var scanning = false

    /**
     * 搜尋清單
     */
    var devices: HashMap<String, Any> = HashMap()

    /**
     * 開始搜尋
     */
    abstract fun startScan()

    /**
     * 僅作用於 [BleScannerAPI21], 否則效果同 [startScan]
     *  @param filters 搜尋篩選條件，例如指定搜尋裝置名稱
     *  @param settings 搜尋屬性設定，例如搜尋頻率、功耗
     *  @throws LocationPermissionNotGrantedException 因為 API 21 需要定位權限才能使用 [ScanCallback]，如果沒有提供定位權限將會丟出此例外
     */
    abstract fun startScan(filters: List<ScanFilter>?, settings: ScanSettings?)

    /**
     * 停止搜尋
     */
    abstract fun stopScan()

    /**
     * 取得全部裝置搜尋紀錄
     *
     * 如果使用 [BleScannerAPI21], 型別使用 [BleScannerAPI21.Device] 轉換
     *
     * 如果使用 [BleScannerAPI18], 型別使用 [BleScannerAPI18.Device] 轉換
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Device> allFoundDevices(): Iterable<T> = devices.values.map { device -> device as T }

    /**
     * enable debug output with tag what name is it's simple name of class
     */
    fun enableDebug() {
        debug = true
    }

    fun enableBluetooth(
        requestCode: Int = BluetoothEnableRequestCode,
        callback: (permissionGranted: Boolean) -> Unit = {}
    ) {
        handleBluetoothEnableCallback = callback

        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        if (context is Activity) {
            context.startActivityForResult(intent, requestCode)
        } else {
            context.startActivity(intent)
        }
    }

    fun requestLocationPermission(
        requestActivity: Activity,
        requestCode: Int = PermissionRequestCode,
        callback: (permissionGranted: Boolean) -> Unit = {}
    ) {
        handleRequestPermissionCallback = callback
        ActivityCompat.requestPermissions(
            requestActivity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            requestCode
        )
    }

    fun startLocationSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    /**
     * 是否為本程序開啟藍芽的 Callback，若是則處理後呼叫 [handleBluetoothEnableCallback]
     */
    open fun handleBluetoothEnableResult(requestCode: Int, resultCode: Int): Boolean {
        val handled =
            requestCode == BluetoothEnableRequestCode

        if (handled) {
            handleBluetoothEnableCallback(resultCode == Activity.RESULT_OK)
        }

        return handled
    }

    /**
     * 是否為本程序要求的權限，若是則處理後呼叫 [handleRequestPermissionCallback]
     */
    fun handleRequestPermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        val handled =
            requestCode == PermissionRequestCode
                    && permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION)

        if (handled) {
            val granted = grantResults.isNotEmpty()
                    && permissions[0] == Manifest.permission.ACCESS_FINE_LOCATION
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED

            handleRequestPermissionCallback(granted)
        }

        return handled
    }

    fun asAPI18() = this@BleScanner as BleScannerAPI18

    fun asAPI21() = this@BleScanner as BleScannerAPI21

    /**
     * BLeScan 需要定位權限才能使用 [ScanCallback]，只要對定位權限 Group 要求任一權限即可，例如
     *
     * [android.Manifest.permission.ACCESS_FINE_LOCATION] 或
     * [android.Manifest.permission.ACCESS_COARSE_LOCATION]
     *
     * 這裡我們使用 [android.Manifest.permission.ACCESS_FINE_LOCATION]
     */
    protected fun requestedLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * BleScannerAPI21 used for API 21+
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    class BleScannerAPI21(context: Context) : BleScanner(context) {

        data class Device(val callbackType: Int, val scanResult: ScanResult) : BleScanner.Device() {
            override fun equals(other: Any?): Boolean {
                return other is Device && other.scanResult.device.address == scanResult.device.address
            }

            override fun hashCode(): Int {
                return scanResult.hashCode()
            }
        }

        private var mBluetoothAdapter: BluetoothAdapter? = null
        private var bleScanCallback: ScanCallback? = null

        /**
         * 裝置搜尋 callback for API 21
         */
        var onDeviceFound: (device: Device) -> Unit = { device ->
            device.lastScannedMillis = System.currentTimeMillis()

            val scanResult = device.scanResult
            onDeviceFoundDefault(scanResult.device, scanResult.rssi, scanResult.scanRecord?.bytes)
            onDeviceFoundAPI21(device)
        }

        init {
            mBluetoothAdapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

            bleScanCallback = object : ScanCallback() {

                override fun onScanResult(callbackType: Int, scanResult: ScanResult?) {

                    scanResult?.let { result ->
                        val device = Device(callbackType, result)
                        devices[result.device.address] = device

                        onDeviceFound(device)
                    }
                }
            }
        }

        /**
         *  @throws LocationPermissionNotGrantedException 因為 API 21 需要定位權限才能使用 [ScanCallback]，如果沒有提供定位權限將會丟出此例外
         */
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH])
        @Throws(BluetoothNotEnabledException::class, LocationPermissionNotGrantedException::class, LocationDisabledException::class)
        override fun startScan() {
            if (mBluetoothAdapter == null) {
                return
            } else if (!mBluetoothAdapter!!.isEnabled) {
                throw BluetoothNotEnabledException("Bluetooth is not enabled")
            } else if (!requestedLocationPermission()) {
                throw LocationPermissionNotGrantedException("at least one of location providers need to be enable so we can use ScanCallback")
            } else if (!isLocationServiceEnabled()) {
                throw LocationDisabledException("above Android OS 6.0, we need to enable location service to scan device nearby")
            } else if (!scanning) {
                scanning = true

                mBluetoothAdapter?.bluetoothLeScanner?.startScan(bleScanCallback)

                if (debug) {
                    Log.d(tag, "startScan")
                }
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH])
        @Throws(LocationPermissionNotGrantedException::class, BluetoothNotEnabledException::class)
        override fun startScan(filters: List<ScanFilter>?, settings: ScanSettings?) {
            if (mBluetoothAdapter == null) {
                return
            } else if (!mBluetoothAdapter!!.isEnabled) {
                throw BluetoothNotEnabledException("Bluetooth is not enabled")
            } else if (!requestedLocationPermission()) {
                throw LocationPermissionNotGrantedException("at least one of location providers need to be enable so we can use ScanCallback")
            } else if (!isLocationServiceEnabled()) {
                throw LocationDisabledException("above Android OS 6.0, we need to enable location service to scan device nearby")
            } else if (!scanning) {
                scanning = true

                mBluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, bleScanCallback)

                if (debug) {
                    Log.d(tag, "startScan")
                }
            }
        }

        /**
         * API 21 需要開啟任一種定位方式 (GPS or WIFI or Passive 等等)
         */
        private fun isLocationServiceEnabled(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                val locationManager =
                    context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return locationManager.isLocationEnabled
                } else {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) or
                            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
            } else {
                true
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
        override fun stopScan() {
            if (scanning) {
                scanning = false

                try {
                    mBluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)

                    if (debug) {
                        Log.d(tag, "stopScan")
                    }
                } catch (e: IllegalStateException) {
                    // BT Adapter is not turned ON or it just turn off before shutdown the scanner
                }
            }
        }

        override fun handleBluetoothEnableResult(requestCode: Int, resultCode: Int): Boolean {
            val handled = super.handleBluetoothEnableResult(requestCode, resultCode)

            if (handled)
                mBluetoothAdapter =
                    (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            return handled
        }
    }

    /**
     * BleScannerAPI18 used for API 18-21 compatible
     */
    @Suppress("DEPRECATION")
    class BleScannerAPI18(context: Context) : BleScanner(context) {

        data class Device(val device: BluetoothDevice, val rssi: Int, val scanRecord: ByteArray?) :
            BleScanner.Device() {
            override fun equals(other: Any?): Boolean {
                return other is Device && other.device.address == device.address
            }

            override fun hashCode(): Int {
                return device.hashCode()
            }
        }

        private var mBluetoothAdapter: BluetoothAdapter? = null
        private var bleScanCallback: BluetoothAdapter.LeScanCallback? = null

        /**
         * 裝置搜尋 callback for API 18
         */
        var onDeviceFound: (device: Device) -> Unit = { device ->
            device.lastScannedMillis = System.currentTimeMillis()

            onDeviceFoundDefault(device.device, device.rssi, device.scanRecord)
            onDeviceFoundAPI18(device)
        }

        init {
            mBluetoothAdapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

            bleScanCallback = BluetoothAdapter.LeScanCallback { bleDevice, rssi, scanRecord ->
                val device = Device(bleDevice, rssi, scanRecord)
                devices[bleDevice.address] = device
                onDeviceFound(device)
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH])
        @Throws(BluetoothNotEnabledException::class)
        override fun startScan() {
            if (mBluetoothAdapter == null) {
                return
            } else if (!mBluetoothAdapter!!.isEnabled) {
                throw BluetoothNotEnabledException("Bluetooth is not enabled")
            } else if (!scanning) {
                scanning = true

                mBluetoothAdapter?.startLeScan(bleScanCallback)

                if (debug) {
                    Log.d(tag, "startScan")
                }
            }
        }

        /**
         * 此 method 僅提供給 API21，將忽略所有參數，呼叫等同 [startScan]
         */
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH])
        @Throws(BluetoothNotEnabledException::class)
        override fun startScan(filters: List<ScanFilter>?, settings: ScanSettings?) {
            startScan()
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_ADMIN)
        override fun stopScan() {
            if (scanning) {
                scanning = false

                try {
                    mBluetoothAdapter?.stopLeScan(bleScanCallback)

                    if (debug) {
                        Log.d(tag, "stopScan")
                    }
                } catch (e: IllegalStateException) {
                    // BT Adapter is not turned ON or it just turn off before shutdown the scanner
                }
            }
        }
    }

    class LocationDisabledException(message: String) : Exception(message)

    class LocationPermissionNotGrantedException(message: String) : Exception(message)

    class BluetoothNotEnabledException(message: String) : Exception(message)
}
