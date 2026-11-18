package com.micronizationlab.glafira

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.net.InetAddress

/**
 * Explicitly owner-started LAN host. It is never started from boot and is not sticky.
 * The persistent notification makes the active network listener visible and stoppable.
 */
class GlafiraBridgeHostService : Service() {
    private var host: GlafiraBridgeTlsHttpServer? = null
    private var approvalStore: AndroidKeystoreBridgePairingApprovalStore? = null
    private var replayGuard: GlafiraBridgeReplayGuard? = null
    private var pairingTicketStore: AndroidKeystoreBridgePairingTicketStore? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHost()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (host != null) {
            updateNotification("Защищённый локальный канал уже запущен.")
            return START_NOT_STICKY
        }

        getSharedPreferences(HOST_STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(HOST_RUNNING_KEY, false).putInt(HOST_PID_KEY, 0).commit()
        val address = intent.getStringExtra(EXTRA_IPV4_ADDRESS)
        val port = intent.getIntExtra(EXTRA_PORT, GlafiraBridgeTlsHttpServer.DEFAULT_PORT)
        startAsForeground()
        return try {
            val selectedHost = BridgeServerHost.ipv4(address ?: "")
            val bindAddress = InetAddress.getByName(selectedHost.value)
            approvalStore = AndroidKeystoreBridgePairingApprovalStore(this)
            pairingTicketStore = AndroidKeystoreBridgePairingTicketStore(this)
            replayGuard = GlafiraBridgeReplayGuard(this)
            val identity = AndroidKeystoreBridgeServerIdentityStore(this).getOrCreate(selectedHost)
            val limiter = GlafiraBridgeRateLimiter(
                maxTrackedIdentities = 256,
                bucketCapacity = 20,
                refillTokensPerSecond = 0.33,
                idleTimeoutNanos = 600_000_000_000L
            )
            val deviceLimiter = GlafiraBridgeRateLimiter(
                maxTrackedIdentities = 512,
                bucketCapacity = 30,
                refillTokensPerSecond = 0.5,
                idleTimeoutNanos = 600_000_000_000L
            )
            host = GlafiraBridgeTlsHttpServer(
                identity = identity,
                admission = GlafiraBridgeRequestAdmission(approvalStore!!, replayGuard!!),
                enrollmentCoordinator = GlafiraBridgeEnrollmentCoordinator(
                    GlafiraBridgePairingProtocol(pairingTicketStore!!),
                    GlafiraBridgeOwnerApprovalService(approvalStore!!)
                ),
                operationDispatcher = GlafiraBridgeOperationDispatcher(this),
                addressRateLimiter = limiter,
                deviceRateLimiter = deviceLimiter
            ).also { it.start(bindAddress, port) }
            getSharedPreferences(HOST_STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(HOST_RUNNING_KEY, true)
                .putInt(HOST_PID_KEY, android.os.Process.myPid())
                .commit()
            updateNotification("Защищённый локальный канал активен: $address:$port")
            START_NOT_STICKY
        } catch (_: Exception) {
            stopHost()
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopHost()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification("Запуск защищённого локального канала…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, GlafiraBridgeHostService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("GLAFIRA AI HUB")
                .setContentText(message)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Остановить", stopPendingIntent).build())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("GLAFIRA AI HUB")
                .setContentText(message)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GLAFIRA Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус защищённого локального соединения"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopHost() {
        getSharedPreferences(HOST_STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(HOST_RUNNING_KEY, false)
            .putInt(HOST_PID_KEY, 0)
            .commit()
        runCatching { host?.close() }
        host = null
        runCatching { pairingTicketStore?.close() }
        pairingTicketStore = null
        runCatching { approvalStore?.close() }
        approvalStore = null
        runCatching { replayGuard?.close() }
        replayGuard = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "com.micronizationlab.glafira.bridge.START"
        const val ACTION_STOP = "com.micronizationlab.glafira.bridge.STOP"
        const val EXTRA_IPV4_ADDRESS = "ipv4_address"
        const val EXTRA_PORT = "port"
        private const val NOTIFICATION_ID = 47321
        private const val STOP_REQUEST_CODE = 47322
        private const val NOTIFICATION_CHANNEL_ID = "glafira_bridge_host"
        const val HOST_STATE_PREFS = "glafira_bridge_host_state"
        const val HOST_RUNNING_KEY = "running"
        const val HOST_PID_KEY = "process_id"

        /** Call only from an explicit owner action after selecting the device's LAN IPv4. */
        fun startFromOwnerAction(context: Context, ipv4Address: String, port: Int = GlafiraBridgeTlsHttpServer.DEFAULT_PORT) {
            val validated = BridgeServerHost.ipv4(ipv4Address)
            require(port in 1024..65_535)
            val intent = Intent(context, GlafiraBridgeHostService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_IPV4_ADDRESS, validated.value)
                .putExtra(EXTRA_PORT, port)
            context.startForegroundService(intent)
        }

        fun stopFromOwnerAction(context: Context) {
            context.startService(Intent(context, GlafiraBridgeHostService::class.java).setAction(ACTION_STOP))
        }
    }
}
