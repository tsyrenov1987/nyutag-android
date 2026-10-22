// Нютаг. Микрофон и динамик телефона для звонка: AudioRecord с источником VOICE_COMMUNICATION (система включает
// эхоподавление), AudioTrack для разговора. Два своих потока: захват и воспроизведение; воспроизведение идёт в
// темпе звуковой карты и само тянет кадры из конвейера. Без микрофона (не разрешён) шлём тишину в том же темпе:
// собеседник по ней видит, что связь жива.

package chat.nyutag.messenger.calls

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import chat.nyutag.core.calls.VoiceFormat
import chat.nyutag.core.calls.VoiceIO
import chat.nyutag.core.calls.VoicePipe
import kotlin.concurrent.thread

class DeviceVoice(context: Context) : VoiceIO {
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var running = false
    private var capture: Thread? = null
    private var playback: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(pipe: VoicePipe) {
        if (running) return
        running = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route(speakerOn)
        val frame = VoiceFormat.FRAME_SAMPLES
        val record = runCatching {
            val min = AudioRecord.getMinBufferSize(VoiceFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, VoiceFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 4))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        if (record == null) Log.w("calls", "микрофон не открылся, шлём тишину")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(VoiceFormat.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(VoiceFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                                                                   AudioFormat.ENCODING_PCM_FLOAT), frame * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (record != null) {
            val session = record.audioSessionId
            val effects = listOfNotNull(
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null,
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null,
            ).onEach { runCatching { it.enabled = true } }
            capture = thread(name = "call-capture") {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val shorts = ShortArray(frame)
                val samples = FloatArray(frame)
                record.startRecording()
                while (running) {
                    val read = record.read(shorts, 0, frame)
                    if (read <= 0) continue
                    for (i in 0 until read) samples[i] = shorts[i] / 32768f
                    pipe.capture(samples, read)
                }
                runCatching { record.stop() }
                record.release()
                effects.forEach { it.release() }
            }
        }
        playback = thread(name = "call-playback") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val out = FloatArray(frame)
            val silence = FloatArray(frame)
            track.play()
            while (running) {
                pipe.render(out, frame)
                if (record == null) pipe.capture(silence, frame)
                track.write(out, 0, frame, AudioTrack.WRITE_BLOCKING)
            }
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Ждёт свои потоки: после возврата звонок закрывает кодеки, а поток звука их больше не трогает. */
    override fun stop() {
        if (!running) return
        running = false
        capture?.join(1_000)
        playback?.join(1_000)
        capture = null
        playback = null
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = false }
        audio.mode = AudioManager.MODE_NORMAL
    }

    @Volatile var speakerOn = false
        private set

    /** Громкая связь или динамик у уха. */
    fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (running) route(on)
    }

    private fun route(speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audio.availableCommunicationDevices.firstOrNull { it.type == type }
            if (device != null) audio.setCommunicationDevice(device) else audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = speaker
        }
    }
}
