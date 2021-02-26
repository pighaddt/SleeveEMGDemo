package com.itri.sleeveemgdemo

import android.app.ProgressDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.itri.sleeveemgdemo.fft.Complex
import com.itri.sleeveemgdemo.fft.FFT
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.timer

class MainActivity : AppCompatActivity() {

    //// 節省時間，使用過時的 ProgressDialog
    private var progressDialog: ProgressDialog? = null

    //// 藍芽掃描 tool
    private val bleScanner: BleScanner by lazy {
        if (Build.VERSION.SDK_INT >= 21)
            BleScanner.BleScannerAPI21(this).apply {
                enableDebug()
            }
        else
            BleScanner.BleScannerAPI18(this).apply {
                enableDebug()
            }
    }

    //// 藍芽掃描計時
    private var stopScanningCounter = 0

    //// 藍芽掃描裝置清單
    private var bleList: java.util.ArrayList<BluetoothDevice> = java.util.ArrayList()
    private var bleListDialogAdapter: ArrayAdapter<BluetoothDevice>? = null

    //// 藍芽連線 tool
    private val bleConnector: BleConnector by lazy {
        BleConnector(this)
    }

    //// 測量資料
    private val dataList = LinkedList<Record>()
    private val showRange = 1024    // L
    private val frequency = 1000    // Fs
    private var stopped = false

    //// 藍芽資料串接 Buffer
    private var serialAsciiBuffer = StringBuilder()

    //// 開始記錄時間
    private var timeStartRecord = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleScanner.onDeviceFoundDefault = { device, _, _ ->
            if (device.name?.contains("LAIRD") == true) {
                if (!bleList.contains(device)) {
                    runOnUiThread {
                        bleList.add(device)
                        bleListDialogAdapter?.notifyDataSetChanged()
                    }
                }
            }
            Log.i(
                ">>>",
                "scan device(${device.name ?: "N/A"}): ${device.address}"
            )
        }

        bleConnector.run {
            onConnecting = {
                toggleRecord?.isChecked = false
                toggleRecord?.isEnabled = false
                progressDialog?.dismiss()
                progressDialog = ProgressDialog.show(this@MainActivity, null, "連線中，請稍候")
            }
            onServiceDiscovering = {
                runOnUiThread {
                    bleConnector.selectedDevice()?.run {
                        textDeviceInfo?.text = String.format("%s\n%s", name, address)
                        textDeviceInfo?.visibility = View.VISIBLE
                    }
                    toggleRecord?.isChecked = false
                    toggleRecord?.isEnabled = false
                    progressDialog?.dismiss()
                    progressDialog = ProgressDialog.show(this@MainActivity, null, "正在搜尋服務 UUID")
                }
            }
            onServiceDiscoverCompleted = {
                runOnUiThread {
                    toggleRecord?.isChecked = false
                    toggleRecord?.isEnabled = true
                    progressDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "搜尋完成", Toast.LENGTH_SHORT).show()
                }
            }
            onDisconnected = {
                runOnUiThread {
                    textDeviceInfo?.visibility = View.INVISIBLE
                    toggleRecord?.isChecked = false
                    toggleRecord?.isEnabled = false
                    progressDialog?.dismiss()
                }
            }
            onNotificationChanged = {
                runOnUiThread {
                    toggleRecord?.isEnabled = true
                }

                if (toggleRecord?.isChecked == false) {
                    //// 關閉接收資料，顯示圖表
                    showChart(true)

                    chartOrigin?.run {
                        post {
                            setScaleEnabled(true)
                        }
                    }

                } else {
                    // 資料收集中禁用拖拉
                    chartOrigin?.run {
                        post {
                            setScaleEnabled(false)
                        }
                    }

                }
            }

            onDataIn = { bytes ->
                // data bytes in to string
                val bytes2Text = String(bytes)

                val textArr = bytes2Text.split("\n")
                if (serialAsciiBuffer.isNotEmpty()) {
                    serialAsciiBuffer.append(textArr[0])
                }
                val preText = serialAsciiBuffer.toString()

                if (textArr.size > 1) {
                    //// 檢測至分隔符號
                    serialAsciiBuffer.clear()
                    serialAsciiBuffer.append(textArr.last())

                    val tempList = java.util.ArrayList<Record>()

                    if (preText.isNotEmpty() && preText.length < 6) {
                        val dataValue = getData(preText)

                        if (dataValue[0] == 1) {
                            //// manage value of channel 1 only
                            tempList.add(
                                Record(timeStartRecord + dataList.size, dataValue[1])
                            )
                        }
                    }
                    for (i in 1 until textArr.size - 1) {
                        if (textArr[i].length < 6) {
                            val dataValue = getData(textArr[i])

                            if (dataValue[0] == 1) {
                                //// manage value of channel 1 only
                                tempList.add(
                                    Record(
                                        timeStartRecord + dataList.size + tempList.size,
                                        dataValue[1]
                                    )
                                )
                            }
                        }
                    }
                    dataList.addAll(tempList)
                    showChart()
                }
            }
        }

        initChart()

        toggleRecord?.setOnCheckedChangeListener { buttonView, isChecked ->
            buttonView.isEnabled = false

            if (isChecked) {
                dataList.clear()
                bleConnector.enableDataIncome()
                buttonSaveFile.isEnabled = false
            } else {
                bleConnector.disableDataIncome()
                buttonSaveFile.isEnabled = true
            }
        }

        buttonSaveFile?.setOnClickListener {
            saveFile()
        }

        buttonEmail?.setOnClickListener {
            email()
        }

        buttonToggle?.setOnClickListener {
            textConsole?.visibility = if (textConsole?.isShown == true) View.GONE else View.VISIBLE
        }

        textConsole?.movementMethod = ScrollingMovementMethod()
        textConsole?.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        rootScroller.setOnTouchListener{ v, event ->
            v.parent.requestDisallowInterceptTouchEvent(false)
            false
        }

//        // 讀檔
//        thread {
//            FileIO(this).loadRecords().forEach { bytes ->
//                Thread.sleep(10)
//                val bytes2Text = String(bytes)
//
//                textConsole?.run {
//                    post {
//                        text ="${text ?: ""}${String(bytes)}"
//                    }
//                }
//                if (bytes2Text.length == 5) {
//
//                    val dataValue = getData(bytes2Text)
//
//                    Log.i(">>>", "${dataValue[0]} - ${dataValue[1]}")
//                    if (dataValue[0] == 1) {
//                        //// manage channel 1 only
//                        val record = Record(
//                            dataList.size.toLong(),
//                            dataValue[1]
//                        )
//
//                        dataList.add(record)
//
//                        showChart()
//                    }
//                }
//            }
//        }
    }

    /**
     * 分析訊號
     * @param dataInText 訊號資料 (5)
     * @return IntArray of [0]= channel(1), [1]= value(4)
     */
    private fun getData(dataInText: String): IntArray {
        val channel = dataInText[0].toInt() - 48    // ascii value
        val value = dataInText.substring(1).toInt()
        return intArrayOf(channel, value)
    }

    /**
     * 初始化 Chart 屬性
     */
    private fun initChart() {
        //// 圖表屬性
        chartOrigin?.run {
            isAutoScaleMinMaxEnabled = false
            setDrawBorders(false)
            setPinchZoom(false)
            isHighlightPerTapEnabled = false

            legend.isEnabled = false

            xAxis.run {
                position = XAxis.XAxisPosition.BOTTOM
                gridColor = Color.TRANSPARENT
                isEnabled = true
                setLabelCount(11, true)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return String.format("%.0f", value)
                    }
                }
            }

            axisLeft.run {
                isEnabled = true
                axisMaximum = 3.5f
                axisMinimum = 0f
                gridColor = Color.TRANSPARENT
                setLabelCount(11, true)
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return String.format("%.1f", value)
                    }
                }
            }

            axisRight.isEnabled = false

            description = null

            data = LineData(generateLineDataSet(java.util.ArrayList(), "v"))
        }
    }

    private fun generateLineDataSet(entries: java.util.ArrayList<Entry>, label: String) =
        LineDataSet(entries, label).apply {
            color = Color.parseColor("#44bb66")
            this.setDrawCircles(false)
            this.setDrawValues(false)
            this.isHighlightEnabled = false
        }

    private fun showChart(forceUpdate: Boolean = false) {
        if (!forceUpdate && stopped || dataList.size == 0)
            return

        val entries = java.util.ArrayList<Entry>()
        val subList: List<Record> =
            if (dataList.size <= showRange) {
                with(dataList) {
                    ArrayList<Record>().also { list ->
                        val record = this.first()
                        repeat(showRange - this.size) {
                            list.add(record)
                        }
                        list.addAll(this)
                    }
                }
            } else
                dataList.subList(dataList.size - showRange, dataList.size)
        subList.forEachIndexed { index, record ->
            entries.add(Entry(index.toFloat() + 1, record.value.toFloat() / 1000f))
        }
        chartOrigin?.run {
            data = LineData(generateLineDataSet(entries, "v"))
            post {
                invalidate()
            }
        }

        fft(subList)
    }

    private fun fft(dataList: List<Record>) {
        val fftBuffer
        // 原始資料
                = ArrayList(dataList.map { Complex(it.value.toDouble() / 1000, 0.0) })
            .also {
                // 補 0 至 4096
                while (it.size < showRange) {
                    it.add(Complex(0.0, 0.0))
                }
            }.toTypedArray()
        val Y = FFT.fft(fftBuffer)      // fft
        val P2 = java.util.ArrayList<Double>()    // P2 list
        for (i in Y.indices) {

            // complex from Y divide L
            val complex = Complex(Y[i].re() / showRange, Y[i].im() / showRange)

            // complex abs and add in P2
            P2.add(complex.abs())
        }
        // 取一半
        val P1 = P2.subList(0, P2.size/2 + 1)

        // 圖表 data
        val entries = java.util.ArrayList<Entry>()
        P1.forEachIndexed { index, value ->
            entries.add(
                Entry(
                    1f * index * frequency / showRange  // f
                    , value.toFloat() * 2f              // P1 value
                )
            )
        }
    }

    /**
     * 存檔
     */
    private fun saveFile() {
        FileIO(this).saveFile(editFileName?.text?.toString(), dataList)
    }

    private fun email() {
        val mail = editEmail.text.toString()
        if (mail.isEmpty()) {
            Toast.makeText(this, "請輸入信箱", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = editFileName.text.toString()
        val file = File(getExternalFilesDir(""), fileName)
        if (fileName.isNotEmpty()) {
            if (!file.exists()) {
                Toast.makeText(this, "找不到該檔案", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            Toast.makeText(this, "請輸入檔名指定附件", Toast.LENGTH_SHORT).show()
            return
        }

        val uri =
            if (Build.VERSION.SDK_INT >= 24)
                FileProvider.getUriForFile(this, "$packageName.provider", file)
            else
                Uri.fromFile(file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(mail))
            putExtra(Intent.EXTRA_SUBJECT, "監測記錄")
            putExtra(Intent.EXTRA_TEXT, "監測記錄")
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        startActivityForResult(intent, 123)
    }

    override fun onDestroy() {
        super.onDestroy()

        bleConnector.disconnect()
    }

    /**
     * # 掃描按鈕點擊
     *
     *      1. 取消目前連線
     *      2. 開始掃描
     *
     * @throws BleScanner.BluetoothNotEnabledException 藍芽未開啟
     * @throws BleScanner.LocationPermissionNotGrantedException 未給予定位權限
     * @throws BleScanner.LocationDisabledException 未開啟定位
     */
    fun scan(button: View) {
        try {
            bleConnector.disconnect()
            bleScanner.startScan()

            button.isEnabled = false
            progressScanning.visibility = View.VISIBLE
            stopScanningCounter = 15

            bleList.clear()
            bleListDialogAdapter = object : ArrayAdapter<BluetoothDevice>(
                this,
                android.R.layout.simple_list_item_1,
                bleList
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)

                    val device = getItem(position)!!

                    (view as TextView).text = String.format("%s\n%s", device.name, device.address)

                    return view
                }
            }
            val dialog = AlertDialog.Builder(this)
                .setAdapter(bleListDialogAdapter) { dialog, index ->
                    bleListDialogAdapter?.getItem(index)?.run {
                        dialog.dismiss()
                        bleConnector.selectDevice(this)
                        bleConnector.connect()
                    }
                }.show()

            timer(null, true, 0, 1000) {
                if (bleConnector.selectedDevice() != null) {
                    cancel()

                    runOnUiThread {
                        bleScanner.stopScan()
                        button.isEnabled = true
                        progressScanning.visibility = View.GONE
                    }
                } else if (--stopScanningCounter <= 0) {
                    cancel()

                    runOnUiThread {
                        bleScanner.stopScan()
                        button.isEnabled = true
                        progressScanning.visibility = View.GONE
                        if (bleList.size == 0) {
                            dialog.dismiss()
                            Toast.makeText(this@MainActivity, "未發現裝置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: BleScanner.BluetoothNotEnabledException) {
            Log.i(">>>", "try enable bluetooth")
            bleScanner.enableBluetooth { enabled ->
                Log.i(">>>", "bluetooth enabled? $enabled")
                if (enabled) scan(button)
            }
        } catch (e: BleScanner.LocationPermissionNotGrantedException) {
            Log.i(">>>", "try to ask location permission")
            bleScanner.requestLocationPermission(this) { permissionGranted ->
                Log.i(">>>", "permission granted? $permissionGranted")
                if (permissionGranted) scan(button)
            }
        } catch (e: BleScanner.LocationDisabledException) {
            Log.i(">>>", "try to ask location on")
            Toast.makeText(this, "請開啟 GPS 定位以掃描附近裝置", Toast.LENGTH_SHORT).show()
            bleScanner.startLocationSettings(this)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (bleScanner.handleBluetoothEnableResult(requestCode, resultCode)) {
            return
        }

        Log.i(">>>", "req:$requestCode result:$resultCode")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        bleScanner.handleRequestPermissionResult(requestCode, permissions, grantResults)
    }
}