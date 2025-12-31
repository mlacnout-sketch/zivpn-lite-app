package com.example.zivpnlite

import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.ArrayList
import java.util.concurrent.CopyOnWriteArrayList

class HysteriaService : VpnService() {

    companion object {
        const val TAG = "ZIVPN_Service"
        const val ACTION_CONNECT = "com.example.zivpnlite.CONNECT"
        const val ACTION_DISCONNECT = "com.example.zivpnlite.DISCONNECT"
        const val ACTION_LOG = "com.example.zivpnlite.LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }

    private fun logToUI(msg: String) {
        Log.d(TAG, msg)
        val intent = Intent(ACTION_LOG)
        intent.putExtra(EXTRA_LOG_MESSAGE, msg)
        sendBroadcast(intent)
    }

    // Update logging usage below
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            stopVpn()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra("HOST") ?: return START_NOT_STICKY
        val pass = intent?.getStringExtra("PASS") ?: return START_NOT_STICKY
        // Opsional: ambil obfs dari intent, fallback ke default
        OBFS_KEY = intent?.getStringExtra("OBFS") ?: OBFS_KEY

        logToUI("Starting VPN Service for Host: $host")

        Thread {
            try {
                // Pastikan bersih sebelum mulai
                stopVpnInternal()
                cleanUpFiles()
                
                // Resolve IP agar routing table tidak bingung dengan domain
                logToUI("Resolving IP for $host")
                val resolvedIP = InetAddress.getByName(host).hostAddress
                logToUI("Resolved IP: $resolvedIP")
                
                // 1. Start Hysteria Core (4 Instances)
                startHysteriaCore(resolvedIP, pass)
                
                // 2. Start Load Balancer
                startLoadBalancer()
                
                // 3. Start Tun2Socks
                startTun2Socks(resolvedIP)
                
                logToUI("All services started successfully.")
            } catch (e: Exception) {
                logToUI("Error starting VPN: ${e.message}")
                Log.e(TAG, "Error starting VPN", e)
                stopVpn()
            }
        }.start()

        return START_STICKY
    }

    private fun cleanUpFiles() {
        val filesToDelete = listOf("process_log.txt", "tun.sock")
        filesToDelete.forEach { File(filesDir, it).delete() }
        File(cacheDir, "tun.sock").delete()
    }

    // --- Helper Copy Binary ---
    private fun getExecutablePath(name: String): String {
        val file = File(filesDir, name)
        // Jika file belum ada atau ukurannya 0, copy dari native lib
        // Atau force copy setiap start untuk memastikan update
        copyBinaryFromNative(name, file)
        return file.absolutePath
    }

    private fun copyBinaryFromNative(libName: String, destFile: File) {
        try {
            // Binary di nativeLibraryDir namanya biasanya "libnama.so"
            // Kita harus cari file aslinya.
            val soName = if (libName.startsWith("lib") && libName.endsWith(".so")) libName else "lib$libName.so"
            val sourceFile = File(nativeLibDir, soName)
            
            if (!sourceFile.exists()) {
                logToUI("Native library not found: ${sourceFile.absolutePath}")
                return
            }

            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Set executable permission
            destFile.setExecutable(true, false)
            logToUI("Copied $libName to ${destFile.absolutePath}")
        } catch (e: Exception) {
            logToUI("Failed to copy binary $libName: ${e.message}")
            Log.e(TAG, "Failed to copy binary $libName", e)
        }
    }

    private fun startTun2Socks(serverIp: String) {
        // Gunakan fungsi helper untuk path
        val libtun = getExecutablePath("tun2socks")
        // ... (sisanya sama)
        // PENTING: Update cmd.add(libtun) di bawah
        
        logToUI("Starting Tun2Socks...")
        val builder = Builder()
        builder.setSession("ZIVPN Lite")
        builder.addAddress(VPN_ADDRESS, 24)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")
        builder.setMtu(1500)

        logToUI("Calculating routes to exclude $serverIp")
        val routes = calculateRoutes(serverIp)
        for (route in routes) {
            builder.addRoute(route.address, route.prefix)
        }
        
        vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN Interface")
        val tunFd = vpnInterface!!.fd
        logToUI("VPN Interface established. FD: $tunFd")

        val sockFile = File(cacheDir, "tun.sock")
        if (sockFile.exists()) sockFile.delete()

        val cmd = ArrayList<String>()
        cmd.add(libtun) // Gunakan path dari filesDir
        cmd.add("--netif-ipaddr"); cmd.add(TUN2SOCKS_ADDRESS)
        cmd.add("--netif-netmask"); cmd.add("255.255.255.0")
        cmd.add("--socks-server-addr"); cmd.add("127.0.0.1:$LOAD_BALANCER_PORT")
        cmd.add("--tunmtu"); cmd.add("1500")
        cmd.add("--tunfd"); cmd.add(tunFd.toString())
        cmd.add("--sock"); cmd.add(sockFile.absolutePath)
        cmd.add("--loglevel"); cmd.add("3")
        cmd.add("--udpgw-transparent-dns")
        cmd.add("--udpgw-remote-server-addr"); cmd.add("127.0.0.1:$LOAD_BALANCER_PORT")

        startBinary("tun2socks", cmd)
        
        try { Thread.sleep(500) } catch (e: InterruptedException) {}
        
        if (!sendFdToSocket(vpnInterface!!, sockFile)) {
             // Coba kirim lagi jika gagal pertama kali (Tun2Socks butuh waktu init)
             logToUI("Retrying send FD...")
             Thread.sleep(1000)
             if (!sendFdToSocket(vpnInterface!!, sockFile)) {
                 throw IOException("Failed to send FD to tun2socks socket!")
             }
        }
    }

    private fun sendFdToSocket(vpnInterface: ParcelFileDescriptor, socketFile: File): Boolean {
        for (i in 0..10) { 
            try {
                if (!socketFile.exists()) {
                    Thread.sleep(200)
                    continue
                }
                val localSocket = LocalSocket()
                localSocket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                localSocket.setFileDescriptorsForSend(arrayOf(vpnInterface.fileDescriptor))
                localSocket.outputStream.write(42) // Kirim dummy byte
                localSocket.shutdownOutput()
                localSocket.close()
                return true
            } catch (e: Exception) {
                // Log.w(TAG, "Retry $i sending FD: ${e.message}") // Too noisy for UI
                try { Thread.sleep(500) } catch (inter: InterruptedException) {}
            }
        }
        return false
    }

    private fun startHysteriaCore(serverIp: String, pass: String) {
        // Copy libuz -> hysteria
        val libuz = getExecutablePath("uz") // Akan cari libuz.so, simpan sebagai 'uz'
        logToUI("Starting 4 Hysteria Cores...")

        for (i in PORT_RANGES.indices) {
            val range = PORT_RANGES[i]
            val localPort = LOCAL_PORTS[i]
            
            val configContent = """
            {
              "server": "$serverIp:$range",
              "obfs": "$OBFS_KEY",
              "auth": "$pass",
              "socks5": {
                "listen": "127.0.0.1:$localPort"
              },
              "insecure": true,
              "recvwindowconn": 131072,
              "recvwindow": 327680
            }
            """.trimIndent()

            val cmd = arrayListOf(
                libuz,
                "-s", OBFS_KEY,
                "--config", configContent
            )
            
            startBinary("hysteria_core_$i", cmd)
        }
    }

    private fun startLoadBalancer() {
        val libload = getExecutablePath("load") // Cari libload.so -> load
        logToUI("Starting Load Balancer...")

        val cmd = ArrayList<String>()
        cmd.add(libload)
        cmd.add("-lport"); cmd.add(LOAD_BALANCER_PORT.toString())
        cmd.add("-tunnel")
        for (port in LOCAL_PORTS) {
            cmd.add("127.0.0.1:$port")
        }

        startBinary("load_balancer", cmd)
    }

    private fun startBinary(name: String, command: List<String>) {
        try {
            val pb = ProcessBuilder(command)
            pb.directory(filesDir)
            pb.redirectErrorStream(true) // Gabung stdout dan stderr
            
            // Set environment variable jika perlu
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = nativeLibDir

            val process = pb.start()
            processList.add(process)
            
            // Logger thread: Baca output binary dan tulis ke Logcat/File
            Thread {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                         if (line != null && (line!!.contains("Error") || line!!.contains("Fail") || name.contains("tun2socks"))) {
                             logToUI("[$name] $line")
                         }
                    }
                }
            }.start()

            logToUI("Binary $name started.")

        } catch (e: Exception) {
            logToUI("Failed to start binary $name: ${e.message}")
            Log.e(TAG, "Failed to start binary $name", e)
            throw e
        }
    }
    
    // Helper untuk mengambil PID (hanya untuk debug/info, sejak Java 9 bisa process.pid())
    private fun getPid(p: Process): Long {
        // Hapus implementasi p.pid() karena menyebabkan error build jika target Java < 9
        // Kita return -1 saja, tidak krusial.
        return -1
    }

    // --- Helper Routing ---
    data class CidrRoute(val address: String, val prefix: Int)

    private fun calculateRoutes(excludeIpStr: String): List<CidrRoute> {
        val excludeIp = ipToLong(excludeIpStr)
        val routes = ArrayList<CidrRoute>()
        addRouteRecursive(routes, 0L, 0, excludeIp)
        return routes
    }

    private fun addRouteRecursive(routes: ArrayList<CidrRoute>, currentIp: Long, currentPrefix: Int, excludeIp: Long) {
        val blockSize = 1L shl (32 - currentPrefix)
        val endIp = currentIp + blockSize - 1
        if (excludeIp < currentIp || excludeIp > endIp) {
            routes.add(CidrRoute(longToIp(currentIp), currentPrefix))
            return
        }
        if (currentPrefix == 32) return
        val nextPrefix = currentPrefix + 1
        val leftIp = currentIp
        val rightIp = currentIp + (1L shl (32 - nextPrefix))
        addRouteRecursive(routes, leftIp, nextPrefix, excludeIp)
        addRouteRecursive(routes, rightIp, nextPrefix, excludeIp)
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        return (parts[0].toLong() shl 24) + (parts[1].toLong() shl 16) + (parts[2].toLong() shl 8) + parts[3].toLong()
    }

    private fun longToIp(ip: Long): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    // --- Cleanup ---

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN Service...")
        stopVpnInternal()
        stopSelf()
    }
    
    private fun stopVpnInternal() {
        for (process in processList) {
            if (process.isAlive) {
                process.destroy()
            }
        }
        processList.clear()
        
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }
}
