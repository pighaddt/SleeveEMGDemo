package com.itri.sleeveemgdemo

import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Created by HabaCo on 2020/5/21.
 */
class FileIO(private val mContext: Context) {

    /**
     * for test 測資
     */
    fun loadRecords(): List<ByteArray> = ArrayList<ByteArray>().also { dataList ->
        mContext.getExternalFilesDir("")?.run {
            val file = listFiles()?.first()

            var headerCounter = 0
            file?.forEachLine { line ->
                if (headerCounter < 5) {
                    headerCounter ++
                } else {
                    val recordDataArray = line.split(",")
                    val value = "1${recordDataArray[1].trim()}"
                    dataList.add(value.toByteArray())
                }
            }
        }
    }

    fun saveFile(fileName: String?, dataList: List<Record>) {
        if (fileName?.isNotEmpty() == true) {
            val file = File(mContext.getExternalFilesDir(""), fileName)
            if (file.exists()) {
                file.delete()
            }
            val outputStream = FileOutputStream(file).bufferedWriter()
            try {
                outputStream.appendln("#,#")   // 1
                outputStream.appendln("#,#")   // 2
                outputStream.appendln("#,#")   // 3
                outputStream.appendln("#,#")   // 4
                outputStream.appendln("TimeInMillis,Ampl") // 5

                //// data store index start at 6
                dataList.forEach { record ->
                    outputStream.appendln("${record.time},${record.value}")
                }
                outputStream.close()
                Toast.makeText(mContext, "檔案儲存位置: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            Toast.makeText(mContext, "請輸入檔名", Toast.LENGTH_SHORT).show()
        }
    }

}