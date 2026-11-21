package com.example.tripodcamera

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripodService : Service() {

    private var camera: TripodIntervalCamera? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var startRunnable: Runnable? = null
    private var endRunnable: Runnable? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("tripod", "Съёмка", NotificationManager.IMPORTANCE_LOW)
        )

        val interval = intent?.getLongExtra(EXTRA_INTERVAL, 5L) ?: 5L
        val manual = intent?.getBooleanExtra(MainActivity.EXTRA_MANUAL, false) ?: false
        val denom = intent?.getIntExtra(MainActivity.EXTRA_SHUTTER_DENOM, 500) ?: 500
        val iso = intent?.getIntExtra(MainActivity.EXTRA_ISO, 400) ?: 400
        val startAt = intent?.getLongExtra(MainActivity.EXTRA_START_AT, 0L) ?: 0L
        val endAt = intent?.getLongExtra(MainActivity.EXTRA_END_AT, 0L) ?: 0L

        // Повторный «Старт»: сбрасываем прежние таймеры и камеру.
        cancelPending()
        camera?.stop()
        camera = null

        val now = System.currentTimeMillis()
        val startDelay = (startAt - now).coerceAtLeast(0L)
        val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

        val firstText = if (startDelay > 0L) "Ожидание старта: ${timeFmt.format(Date(startAt))}"
        else "Интервальная съёмка идёт"
        startForeground(1, buildNotification(firstText), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)

        // Во время ожидания процессор не должен засыпать, иначе таймер сработает с опозданием.
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalCamera:service").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val launch = Runnable {
            camera = TripodIntervalCamera(
                context = this,
                outputDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "tripod"),
                jpegSink = { name, bytes -> saveToGallery(name, bytes) }
            ).also {
                it.setManualExposure(manual, denom, iso)
                it.startCamera(interval)
            }
            val endText = if (endAt > 0L) ", до ${timeFmt.format(Date(endAt))}" else ""
            getSystemService(NotificationManager::class.java)
                .notify(1, buildNotification("Интервальная съёмка идёт$endText"))
        }
        startRunnable = launch
        if (startDelay > 0L) handler.postDelayed(launch, startDelay) else launch.run()

        if (endAt > 0L) {
            val end = Runnable {
                camera?.stop()
                camera = null
                stopSelf()
            }
            endRunnable = end
            handler.postDelayed(end, (endAt - now).coerceAtLeast(0L))
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, "tripod")
            .setContentTitle("Tripod Camera")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    private fun cancelPending() {
        startRunnable?.let { handler.removeCallbacks(it) }
        endRunnable?.let { handler.removeCallbacks(it) }
        startRunnable = null
        endRunnable = null
    }

    /** Сохраняет JPEG в Pictures/TripodCamera, видно в галерее. Разрешение на память не нужно. */
    private fun saveToGallery(name: String, bytes: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TripodCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e("TripodService", "Ошибка сохранения: $e")
        }
    }

    override fun onDestroy() {
        cancelPending()
        camera?.stop()
        camera = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_INTERVAL = "interval"
    }
}
