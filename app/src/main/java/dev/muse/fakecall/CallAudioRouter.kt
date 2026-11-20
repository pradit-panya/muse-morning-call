package dev.muse.fakecall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Manages real phone-call audio routing (Bluetooth > Earpiece / Speakerphone)
 * and the proximity sensor screen-off wake lock when holding the phone to ear.
 */
class CallAudioRouter(context: Context) {

    private val appCtx = context.applicationContext
    private val audioManager = appCtx.getSystemService(AudioManager::class.java)
    private val powerManager = appCtx.getSystemService(PowerManager::class.java)

    private var active = false
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var proximityLock: PowerManager.WakeLock? = null

    @Volatile var isSpeakerOn: Boolean = false
        private set

    fun enterCall(startWithSpeaker: Boolean) {
        if (active) {
            setSpeaker(startWithSpeaker)
            return
        }
        active = true
        val am = audioManager ?: return
        try {
            previousMode = am.mode
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { am.requestAudioFocus(it) }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            Log.w("FakeCallDbg", "CallAudioRouter.enterCall audio setup error", e)
        }
        setSpeaker(startWithSpeaker)
    }

    fun setSpeaker(on: Boolean) {
        isSpeakerOn = on
        CallEventBus.updateState(speaker = on)
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val target = if (on) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    // Prefer Bluetooth headset if connected, else built-in earpiece.
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                if (target != null) {
                    am.setCommunicationDevice(target)
                    Log.d("FakeCallDbg", "CallAudioRouter: routed to type=${target.type} speaker=$on")
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
            Log.w("FakeCallDbg", "CallAudioRouter.setSpeaker($on) error", e)
        }
        updateProximityLock()
    }

    fun toggleSpeaker(): Boolean {
        setSpeaker(!isSpeakerOn)
        return isSpeakerOn
    }

    private fun updateProximityLock() {
        if (!active || isSpeakerOn) {
            releaseProximityLock()
            return
        }
        try {
            val pm = powerManager ?: return
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                if (proximityLock == null) {
                    proximityLock = pm.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "dev.muse.fakecall:proximity"
                    )
                }
                if (proximityLock?.isHeld != true) {
                    proximityLock?.acquire(30 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.w("FakeCallDbg", "CallAudioRouter: proximity lock failed", e)
        }
    }

    private fun releaseProximityLock() {
        try {
            if (proximityLock?.isHeld == true) {
                proximityLock?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        } catch (e: Exception) {
            try {
                if (proximityLock?.isHeld == true) proximityLock?.release()
            } catch (ignored: Exception) { }
        }
    }

    fun exitCall() {
        if (!active) return
        active = false
        releaseProximityLock()
        proximityLock = null
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (e: Exception) { }
        try {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } catch (e: Exception) { }
        focusRequest = null
        try {
            am.mode = previousMode
        } catch (e: Exception) { }
    }
}
