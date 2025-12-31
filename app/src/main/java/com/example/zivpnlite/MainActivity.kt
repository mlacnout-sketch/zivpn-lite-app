package com.example.zivpnlite

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var inputHost: EditText
    private lateinit var inputPass: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnStop: Button
    private lateinit var tvLog: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HysteriaService.ACTION_LOG) {
                val msg = intent.getStringExtra(HysteriaService.EXTRA_LOG_MESSAGE)
                appendLog(msg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputHost = findViewById(R.id.inputHost)
        inputPass = findViewById(R.id.inputPass)
        btnConnect = findViewById(R.id.btnConnect)
        btnStop = findViewById(R.id.btnStop)
        tvLog = findViewById(R.id.tvLog)

        // Set default values jika kosong (memudahkan testing)
        if (inputHost.text.isEmpty()) inputHost.setText("202.10.48.173")
        if (inputPass.text.isEmpty()) inputPass.setText("asd63")

        btnConnect.setOnClickListener {
            tvLog.text = "Initializing...\n"
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 1)
            } else {
                onActivityResult(1, Activity.RESULT_OK, null)
            }
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, HysteriaService::class.java)
            intent.action = HysteriaService.ACTION_DISCONNECT
            startService(intent)
            updateUI(false)
            appendLog("Stopping VPN...")
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(HysteriaService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(logReceiver)
    }

    private fun appendLog(msg: String?) {
        if (msg == null) return
        runOnUiThread {
            tvLog.append("$msg\n")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            val host = inputHost.text.toString().trim()
            val pass = inputPass.text.toString().trim()

            if (host.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Host and Password cannot be empty!", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(this, HysteriaService::class.java)
            intent.putExtra("HOST", host)
            intent.putExtra("PASS", pass)
            intent.action = HysteriaService.ACTION_CONNECT
            startService(intent)
            updateUI(true)
        } else {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(isConnected: Boolean) {
        if (isConnected) {
            btnConnect.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
            inputHost.isEnabled = false
            inputPass.isEnabled = false
        } else {
            btnConnect.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
            inputHost.isEnabled = true
            inputPass.isEnabled = true
        }
    }
}
