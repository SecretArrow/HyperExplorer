/*
 * Hyper Explorer - a free and open source file manager for Android.
 * Copyright (C) 2025-2026 Hyper Explorer contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.hyperexplorer.feature.transfer.ftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) yang menjalankan [FtpServer].
 *
 * Kebijakan keamanan: server TIDAK pernah start sendiri (tidak ada receiver
 * boot, [onStartCommand] selalu [Service.START_NOT_STICKY]); hanya hidup
 * saat pengguna menekan tombol Mulai dan selalu ditampilkan lewat notifikasi
 * foreground.
 */
class FtpService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: FtpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            server?.stop()
            stopSelf()
        } else {
            launchServer(intent)
        }
        // START_NOT_STICKY: sistem tidak boleh menghidupkan ulang server
        // setelah proses mati — kebijakan keamanan (tanpa autostart).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun launchServer(intent: Intent?) {
        if (server?.isRunning == true) return
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val username = intent?.getStringExtra(EXTRA_USERNAME).orEmpty()
        val password = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val rootPath = intent?.getStringExtra(EXTRA_ROOT).orEmpty()
        val idleMinutes = intent?.getIntExtra(EXTRA_IDLE_MINUTES, DEFAULT_IDLE_MINUTES) ?: DEFAULT_IDLE_MINUTES
        val config = FtpConfig(
            port = port,
            username = username,
            password = password,
            rootDir = File(rootPath),
            idleTimeoutMinutes = idleMinutes,
        )

        ensureNotificationChannel()
        // Segera tampilkan notifikasi foreground sesuai ketentuan
        // startForegroundService.
        startForegroundCompat(buildNotification(config.port, config.username))

        val ftpServer = FtpServer(config, onStopped = { reason ->
            _running.value = false
            _error.value = reason
            stopSelf()
        })
        server = ftpServer
        serviceScope.launch {
            try {
                ftpServer.start()
            } catch (e: IllegalStateException) {
                _error.value = e.message
                stopSelf()
                return@launch
            }
            _error.value = null
            _port.value = ftpServer.boundPort()
            _running.value = true
            refreshNotification(ftpServer.boundPort(), config.username)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Transfer FTP", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Status server FTP untuk transfer berkas"
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(port: Int, username: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply { setPackage(null) }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val host = localIp() ?: "127.0.0.1"
        val portText = if (port > 0) port.toString() else "…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Server FTP aktif")
            .setContentText("$host:$portText — pengguna $username")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification(port: Int, username: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(port, username))
    }

    companion object {
        private const val ACTION_START = "com.hyperexplorer.feature.transfer.ftp.action.START"
        private const val ACTION_STOP = "com.hyperexplorer.feature.transfer.ftp.action.STOP"
        private const val EXTRA_PORT = "com.hyperexplorer.feature.transfer.ftp.extra.PORT"
        private const val EXTRA_USERNAME = "com.hyperexplorer.feature.transfer.ftp.extra.USERNAME"
        private const val EXTRA_PASSWORD = "com.hyperexplorer.feature.transfer.ftp.extra.PASSWORD"
        private const val EXTRA_ROOT = "com.hyperexplorer.feature.transfer.ftp.extra.ROOT"
        private const val EXTRA_IDLE_MINUTES = "com.hyperexplorer.feature.transfer.ftp.extra.IDLE_MINUTES"
        private const val CHANNEL_ID = "ftp_transfer"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_PORT = 2121
        private const val DEFAULT_IDLE_MINUTES = 15

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        private val _port = MutableStateFlow(0)
        val port: StateFlow<Int> = _port.asStateFlow()

        /** Mulai layanan foreground server FTP dengan [config]. */
        fun start(context: Context, config: FtpConfig) {
            val intent = Intent(context, FtpService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_USERNAME, config.username)
                putExtra(EXTRA_PASSWORD, config.password)
                putExtra(EXTRA_ROOT, config.rootDir.absolutePath)
                putExtra(EXTRA_IDLE_MINUTES, config.idleTimeoutMinutes)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Minta layanan berhenti: server ditutup dan notifikasi dilepas. */
        fun stop(context: Context) {
            val intent = Intent(context, FtpService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /** Alamat IP LAN untuk ditampilkan ke pengguna, bila tersedia. */
        fun localIpText(): String = localIp() ?: "tidak diketahui"

        private fun localIp(): String? {
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces()
            } catch (_: Exception) {
                null
            } ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                try {
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address.isSiteLocalAddress) {
                            return address.hostAddress
                        }
                    }
                } catch (_: Exception) {
                    // Lewati antarmuka yang bermasalah.
                }
            }
            return null
        }
    }
}
