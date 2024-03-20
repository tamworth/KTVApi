package io.agora.ktvdemo

import android.app.Application
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.Exception

class MyApplication : Application() {

    companion object {
        private lateinit var sApp: Application

        fun app(): Application = sApp
    }

    override fun onCreate() {
        super.onCreate()
        sApp = this
        try {
            initFile("不如跳舞.mp4")
            initFile("不如跳舞.xml")
        }catch (e:Exception){
            e.printStackTrace()
        }

    }

        @Throws(IOException::class)
        open fun initFile(fileName: String) {
            val inputStream = assets.open(fileName)
            val out = File(filesDir.absolutePath + File.separator + fileName)
            val outputStream: OutputStream = FileOutputStream(out)
            val buffer = ByteArray(10240)
            while (true) {
                val len = inputStream.read(buffer)
                if (len < 0) break
                outputStream.write(buffer, 0, len)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
    }
}