package io.agora.ktvdemo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import io.agora.ktvdemo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object{
        const val TAG = "MainActivity"

        val PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ActivityCompat.requestPermissions(this, PERMISSIONS, 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            var granted = true
            for (result in grantResults) {
                granted = result == PackageManager.PERMISSION_GRANTED
                if (!granted) break
            }
            if (granted) {
                Log.d(TAG,"get premission...")
            } else {
                Log.d(TAG,"no premission...")
            }
        }
    }
}