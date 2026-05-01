package com.glassapp.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.*
import kotlin.random.Random

class NeuralEngine {
    private val sampleRate = 44100
    private val isRunning = AtomicBoolean(false)
    private var thread: Thread? = null
    private val lock = ReentrantLock()

    // Mode state
    enum class SystemMode { OFF, FOCUS, HEALING, SLEEP_HEALING }
    @Volatile var activeMode = SystemMode.OFF

    // Thread-safe state
    @Volatile var currentPreset = ADVANCED_PRESETS[0]
    @Volatile var baseFreq = currentPreset.base
    @Volatile var beatFreq = currentPreset.beat
    @Volatile var isIsochronic = currentPreset.iso
    
    // Custom Tuning State
    @Volatile var customBaseFreq = 150.0
    @Volatile var customBeatFreq = 10.0
    @Volatile var customIso = false
    
    // Gain Stages
    @Volatile var masterVolume = 0.1f
    @Volatile var dopamineVolume = 0.08f
    
    @Volatile var dopamineActive = false
    @Volatile var aiFocusEnabled = false

    // Safety Guardrails
    @Volatile var sessionStartTime: Long = 0
    @Volatile var totalSessionMillis: Long = 0
    @Volatile var cooldownEndTime: Long = 0
    private val maxSessionMillis: Long = 60 * 60 * 1000 // 60 minutes
    private val cooldownMillis: Long = 15 * 60 * 1000 // 15 minutes

    // Target vs Current for smoothing
    private var curBase = baseFreq
    private var curBeat = beatFreq
    
    // Auto-Tuning State
    @Volatile var isAutoTuning = false
    @Volatile var tuningOffset = 0.0
    private var driftVelocity = 0.0 
    
    // Phase accumulators
    private var phaseBase = 0.0
    private var phaseBeat = 0.0
    private var phaseDopamine = 0.0
    
    private val twoPi = 2.0 * PI
    
    // Crossfade state
    private val crossfadeSamplesTotal = (sampleRate * 0.1).toInt()
    private var crossfadeSamplesLeft = 0
    private var oldBase = baseFreq
    private var oldBeat = beatFreq
    private var oldIso = isIsochronic
    private var phaseOldBase = 0.0
    private var phaseOldBeat = 0.0
    
    // Dopamine filter state
    private var dopamineFilterState = 0.0
    private val dopamineFilterCutoff = 120.0

    // Bio-Feedback tracking
    private val interactions = AtomicInteger(0)
    @Volatile var restlessnessScore = 0.0 
    @Volatile var focusScore = 1.0 
    @Volatile var kpmValue = 0.0
    
    val focusHistory = mutableListOf<Double>()

    fun recordInteraction() {
        interactions.incrementAndGet()
    }

    fun isCoolingDown(): Boolean = System.currentTimeMillis() < cooldownEndTime
    fun getRemainingCooldownMillis(): Long = (cooldownEndTime - System.currentTimeMillis()).coerceAtLeast(0)

    fun setMode(mode: SystemMode) {
        lock.withLock {
            if (mode == SystemMode.FOCUS && isCoolingDown()) return
            
            if (activeMode != mode) {
                markCrossfade()
                activeMode = mode
                if (mode == SystemMode.OFF) {
                    stop()
                } else {
                    when (mode) {
                        SystemMode.HEALING -> {
                            baseFreq = 120.0
                            beatFreq = 5.5 // Deep Theta
                            isIsochronic = false
                        }
                        SystemMode.SLEEP_HEALING -> {
                            // Target: Frontal Cortex Delta
                            // 100Hz Carrier is optimal for frontal resonance
                            baseFreq = 100.0
                            beatFreq = 1.0 // Slow Delta for deep restorative sleep
                            isIsochronic = false
                        }
                        else -> {
                            applyPreset(currentPreset)
                        }
                    }
                    if (!isRunning.get()) start()
                }
            }
        }
    }

    fun applyPreset(preset: Preset) {
        lock.withLock {
            if (activeMode != SystemMode.FOCUS) return
            markCrossfade()
            currentPreset = preset
            if (preset.name == "CUSTOM") {
                baseFreq = customBaseFreq
                beatFreq = customBeatFreq
                isIsochronic = customIso
            } else {
                baseFreq = preset.base
                beatFreq = preset.beat
                isIsochronic = preset.iso
            }
            tuningOffset = 0.0
            driftVelocity = 0.0
        }
    }

    fun updateCustom(base: Double, beat: Double, iso: Boolean) {
        lock.withLock {
            customBaseFreq = base
            customBeatFreq = beat
            customIso = iso
            if (activeMode == SystemMode.FOCUS && currentPreset.name == "CUSTOM") {
                markCrossfade()
                baseFreq = base
                beatFreq = beat
                isIsochronic = iso
            }
        }
    }

    private fun markCrossfade() {
        oldBase = curBase
        oldBeat = curBeat
        oldIso = isIsochronic
        phaseOldBase = phaseBase
        phaseOldBeat = phaseBeat
        crossfadeSamplesLeft = crossfadeSamplesTotal
    }

    private fun softClip(x: Double): Double {
        return if (x > 1.0) 1.0 else if (x < -1.0) -1.0 else x * (1.5 - 0.5 * x * x)
    }

    private fun tukeyPulse(phase: Double, alpha: Double = 0.3): Double {
        val duty = 0.5
        if (phase >= duty) return 0.0
        val x = phase / duty
        val taper = alpha / 2.0
        return when {
            x < taper -> 0.5 * (1.0 + cos(PI * ((2.0 * x / alpha) - 1.0)))
            x > (1.0 - taper) -> 0.5 * (1.0 + cos(PI * ((2.0 * x / alpha) - (2.0 / alpha) + 1.0)))
            else -> 1.0
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        sessionStartTime = System.currentTimeMillis()
        totalSessionMillis = 0
        
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(2048 * 4)
            
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM).build()

            audioTrack.play()
            
            val buffer = FloatArray(1024 * 2) 
            val random = Random(System.currentTimeMillis())
            val smoothingFactor = 0.0005 

            while (isRunning.get()) {
                val frames = buffer.size / 2
                
                if (activeMode == SystemMode.FOCUS) {
                    val currentMillis = System.currentTimeMillis()
                    totalSessionMillis = currentMillis - sessionStartTime
                    if (totalSessionMillis >= maxSessionMillis) {
                        cooldownEndTime = currentMillis + cooldownMillis
                        activeMode = SystemMode.OFF
                        isRunning.set(false)
                        break
                    }
                }

                // Snapshots
                val targetBase: Double
                val targetBeat: Double
                val iso: Boolean
                val dopa: Boolean
                val dVol: Float
                val mVol: Float

                lock.withLock {
                    if (aiFocusEnabled && isAutoTuning && activeMode == SystemMode.FOCUS) {
                        driftVelocity += (random.nextDouble() - 0.5) * 0.00001
                        driftVelocity *= 0.99
                        tuningOffset += driftVelocity
                        if (abs(tuningOffset) > 2.5) {
                            tuningOffset *= 0.95
                            driftVelocity *= -0.5
                        }
                    }

                    targetBase = baseFreq + tuningOffset
                    targetBeat = beatFreq
                    iso = isIsochronic
                    dopa = (activeMode == SystemMode.FOCUS && dopamineActive)
                    dVol = dopamineVolume
                    mVol = masterVolume
                }

                for (i in 0 until frames) {
                    curBase += (targetBase - curBase) * smoothingFactor
                    curBeat += (targetBeat - curBeat) * smoothingFactor

                    phaseBase = (phaseBase + curBase / sampleRate) % 1.0
                    phaseBeat = (phaseBeat + curBeat / sampleRate) % 1.0
                    
                    val leftMain = sin(twoPi * phaseBase)
                    val rightMain = sin(twoPi * (phaseBase + phaseBeat))
                    val leftHarm = 0.15 * sin(twoPi * (phaseBase * 2.0 % 1.0))
                    val rightHarm = 0.15 * sin(twoPi * ((phaseBase + phaseBeat) * 2.0 % 1.0))
                    
                    var left = (leftMain + leftHarm) * 0.4
                    var right = (rightMain + rightHarm) * 0.4
                    
                    if (iso) {
                        val pulse = tukeyPulse(phaseBeat)
                        left *= pulse
                        right *= pulse
                    }

                    if (crossfadeSamplesLeft > 0) {
                        val fade = 1.0 - (crossfadeSamplesLeft.toDouble() / crossfadeSamplesTotal)
                        phaseOldBase = (phaseOldBase + oldBase / sampleRate) % 1.0
                        phaseOldBeat = (phaseOldBeat + oldBeat / sampleRate) % 1.0
                        var lOld = sin(twoPi * phaseOldBase) + 0.15 * sin(twoPi * (phaseOldBase * 2.0 % 1.0))
                        var rOld = sin(twoPi * (phaseOldBase + phaseOldBeat)) + 0.15 * sin(twoPi * ((phaseOldBase + phaseOldBeat) * 2.0 % 1.0))
                        lOld *= 0.4
                        rOld *= 0.4
                        if (oldIso) {
                            val oldPulse = tukeyPulse(phaseOldBeat)
                            lOld *= oldPulse
                            rOld *= oldPulse
                        }
                        left = (1.0 - fade) * lOld + fade * left
                        right = (1.0 - fade) * rOld + fade * right
                        crossfadeSamplesLeft--
                    }
                    
                    if (dopa) {
                        phaseDopamine = (phaseDopamine + 15.0 / sampleRate) % 1.0
                        val rawDopamine = sin(twoPi * phaseDopamine) * dVol
                        val dt = 1.0 / sampleRate
                        val rc = 1.0 / (twoPi * dopamineFilterCutoff)
                        val a = dt / (rc + dt)
                        dopamineFilterState += a * (rawDopamine - dopamineFilterState)
                        left += dopamineFilterState; right += dopamineFilterState
                    }
                    
                    val noise = (random.nextFloat() * 2.0 - 1.0) * 0.001
                    val outL = (left + noise) * mVol
                    val outR = (right + noise) * mVol
                    
                    buffer[i * 2] = softClip(outL).toFloat()
                    buffer[i * 2 + 1] = softClip(outR).toFloat()
                }
                audioTrack.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
            audioTrack.stop()
            audioTrack.release()
            isRunning.set(false)
            activeMode = SystemMode.OFF
        }
        thread?.start()
        
        // Monitoring Loop
        Thread {
            while (isRunning.get()) {
                Thread.sleep(2000)
                if (!isRunning.get()) break
                
                val currentInteractions = interactions.getAndSet(0)
                kpmValue = (currentInteractions / 2.0) * 60.0
                
                val restlessnessPenalty = (restlessnessScore * 2.5).coerceIn(0.0, 1.0)
                val interactionPenalty = (kpmValue / 40.0).coerceIn(0.0, 1.0)
                
                focusScore = (1.0 - (restlessnessPenalty * 0.75 + interactionPenalty * 0.25)).coerceIn(0.0, 1.0)
                
                lock.withLock {
                    if (focusHistory.size > 20) focusHistory.removeAt(0)
                    focusHistory.add(focusScore)
                }

                if (aiFocusEnabled && activeMode == SystemMode.FOCUS) {
                    isAutoTuning = focusScore < 0.85 
                    if (focusScore < 0.35) {
                        if (beatFreq != 40.0) {
                            markCrossfade()
                            beatFreq = 40.0
                            isIsochronic = true
                        }
                    } else if (focusScore > 0.75) {
                        if (beatFreq != 14.0) {
                            markCrossfade()
                            beatFreq = 14.0
                            isIsochronic = false
                        }
                    }
                }
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        activeMode = SystemMode.OFF
    }
}
