package com.glassapp.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class NeuralService : Service(), AudioManager.OnAudioFocusChangeListener, SensorEventListener {

    private val binder = LocalBinder()
    val engine = NeuralEngine()
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private lateinit var mediaSession: MediaSessionCompat
    private var focusRequest: AudioFocusRequest? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    
    private var noiseFloor = 0.05f
    private var calibrationSamples = 0
    private val maxCalibrationSamples = 50

    inner class LocalBinder : Binder() {
        fun getService(): NeuralService = this@NeuralService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mediaSession = MediaSessionCompat(this, "NeuralService")
        
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_FOCUS" -> startFocus()
            "START_HEALING" -> startHealing()
            "START_SLEEP" -> startSleep()
            "STOP" -> stopEngine()
        }
        return START_STICKY
    }

    fun isHeadphonesConnected(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true
            }
        }
        return false
    }

    private fun startFocus() {
        if (!isHeadphonesConnected()) {
            sendBroadcast(Intent("com.glassapp.app.HEADPHONES_REQUIRED"))
            return
        }
        if (engine.isCoolingDown()) return
        
        if (requestAudioFocus()) {
            engine.setMode(NeuralEngine.SystemMode.FOCUS)
            val notification = createNotification()
            startForeground(1, notification)
            mediaSession.isActive = true
        }
    }

    private fun startHealing() {
        if (!isHeadphonesConnected()) {
            sendBroadcast(Intent("com.glassapp.app.HEADPHONES_REQUIRED"))
            return
        }
        if (requestAudioFocus()) {
            engine.setMode(NeuralEngine.SystemMode.HEALING)
            val notification = createNotification()
            startForeground(1, notification)
            mediaSession.isActive = true
        }
    }

    private fun startSleep() {
        if (!isHeadphonesConnected()) {
            sendBroadcast(Intent("com.glassapp.app.HEADPHONES_REQUIRED"))
            return
        }
        if (requestAudioFocus()) {
            engine.setMode(NeuralEngine.SystemMode.SLEEP_HEALING)
            val notification = createNotification()
            startForeground(1, notification)
            mediaSession.isActive = true
        }
    }

    private fun stopEngine() {
        engine.setMode(NeuralEngine.SystemMode.OFF)
        mediaSession.isActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine.stop()
            AudioManager.AUDIOFOCUS_GAIN -> engine.start()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val delta = sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ))
            if (calibrationSamples < maxCalibrationSamples) {
                noiseFloor = (noiseFloor * 0.9f) + (delta * 0.1f)
                calibrationSamples++
            } else {
                val sensitivity = 3.5f
                val score = ((delta - noiseFloor) / sensitivity).toDouble().coerceIn(0.0, 1.0)
                engine.restlessnessScore = score
            }
            lastX = x; lastY = y; lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotification(): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val title = when (engine.activeMode) {
            NeuralEngine.SystemMode.FOCUS -> "Focus Mode Active"
            NeuralEngine.SystemMode.HEALING -> "Healing Mode Active"
            NeuralEngine.SystemMode.SLEEP_HEALING -> "Sleep Healing Active"
            else -> "Neural Engine"
        }

        val text = when (engine.activeMode) {
            NeuralEngine.SystemMode.FOCUS -> engine.currentPreset.name
            NeuralEngine.SystemMode.HEALING -> "Deep Regeneration"
            NeuralEngine.SystemMode.SLEEP_HEALING -> "Delta Sleep Entrainment"
            else -> "Standby"
        }

        return NotificationCompat.Builder(this, "NEURAL_CHANNEL")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "NEURAL_CHANNEL",
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        engine.stop()
        mediaSession.release()
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }
}
