package br.jorge.tradutorhe

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reconhecimento de hebraico 100% offline: Zipformer2 streaming (66M) treinado pelo ivrit.ai /
 * Yakov Kolani. Precisão próxima do Whisper large-v3-turbo do ivrit.ai, mas leve para celular.
 *
 * Duas fontes de áudio:
 *  - microfone (pessoa falando, TV na sala)
 *  - áudio interno do celular (vídeo/canal tocando em outro app), via captura de reprodução
 */
class AsrEngine(assets: AssetManager) {

    interface Listener {
        fun onPartial(he: String)
        fun onFinal(he: String)
        fun onLevel(level: Float)
        fun onError(msg: String)
        fun onSourceUsed(source: Int)
    }

    private val online = OnlineRecognizer(
        assetManager = assets,
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder.int8.onnx",
                    decoder = "$MODEL_DIR/decoder.int8.onnx",
                    joiner = "$MODEL_DIR/joiner.int8.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                modelType = "zipformer2",
                numThreads = 2,
            ),
            // fecha a frase após 0,8 s de silêncio; força corte em frases de 12 s (noticiário fala sem parar)
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.0f, 0.0f),
                rule2 = EndpointRule(true, 0.8f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 12.0f),
            ),
            enableEndpoint = true,
        ),
    )

    @Volatile var gain = 1.0f
    /** Quando true (TTS falando), o áudio é descartado para não traduzir a voz do próprio app. */
    @Volatile var isMuted: () -> Boolean = { false }
    @Volatile private var running = false
    private var thread: Thread? = null

    fun isRunning() = running

    /** Microfone. */
    fun startMic(source: Int, device: AudioDeviceInfo?, noiseSuppressor: Boolean, l: Listener) {
        start(l) { openMic(source, device, noiseSuppressor, l) }
    }

    /** Áudio que está tocando em outros apps do celular. */
    fun startInternal(projection: MediaProjection, l: Listener) {
        start(l) { openPlayback(projection) }
    }

    private fun start(l: Listener, open: () -> Opened?) {
        if (running) return
        running = true
        thread = Thread({ loop(open, l) }, "he-asr").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    fun release() { stop(); online.release() }

    private class Opened(val rec: AudioRecord, val effects: List<android.media.audiofx.AudioEffect>, val highPass: Boolean)

    @SuppressLint("MissingPermission")
    private fun newRecord(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            val r = AudioRecord(source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SR * 2))
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) { null }
    }

    private fun openMic(requested: Int, device: AudioDeviceInfo?, useNs: Boolean, l: Listener): Opened? {
        var source = requested
        var rec = newRecord(source)
        if (rec == null && source != MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            rec = newRecord(source)
        }
        rec ?: return null
        l.onSourceUsed(source)
        if (device != null) rec.preferredDevice = device
        val fx = ArrayList<android.media.audiofx.AudioEffect>()
        try {
            if (useNs && NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }?.let { fx.add(it) }
            // o AGC do sistema "bombeia" o ruído entre frases e piora o reconhecimento
            if (AutomaticGainControl.isAvailable())
                AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = false }?.let { fx.add(it) }
        } catch (_: Exception) { }
        return Opened(rec, fx, highPass = true)
    }

    @SuppressLint("MissingPermission")
    private fun openPlayback(projection: MediaProjection): Opened? = try {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(android.os.Process.myUid()) // não capta a voz de leitura do próprio app
            .build()
        val rec = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(SR * 2)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (rec.state == AudioRecord.STATE_INITIALIZED) Opened(rec, emptyList(), highPass = false)
        else { rec.release(); null }
    } catch (e: Exception) { null }

    private fun loop(open: () -> Opened?, l: Listener) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val o = open()
        if (o == null) { running = false; l.onError("Não foi possível abrir a fonte de áudio."); return }
        val rec = o.rec

        val stream = online.createStream()
        val buf = ShortArray(SR / 10) // blocos de 100 ms
        var lastPartial = ""
        // passa-altas ~80 Hz (só no microfone): tira ronco de ar-condicionado, vento e batidas na mesa
        var xPrev = 0f; var yPrev = 0f
        val a = 0.969f

        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue

                val g = gain
                val samples = FloatArray(n)
                var sum = 0.0
                for (i in 0 until n) {
                    var x = buf[i] / 32768f
                    if (o.highPass) { val y = x - xPrev + a * yPrev; xPrev = x; yPrev = y; x = y }
                    val v = (x * g).coerceIn(-1f, 1f)
                    samples[i] = v
                    sum += (v * v).toDouble()
                }
                l.onLevel((sqrt(sum / n).toFloat() * 5f).coerceAtMost(1f))
                if (isMuted()) continue

                stream.acceptWaveform(samples, SR)
                while (online.isReady(stream)) online.decode(stream)

                val text = clean(online.getResult(stream).text)
                if (text != lastPartial) { lastPartial = text; l.onPartial(text) }

                if (online.isEndpoint(stream)) {
                    online.reset(stream)
                    if (text.isNotEmpty()) l.onFinal(text)
                    lastPartial = ""
                }
            }
            // termina a frase que estava em andamento
            stream.inputFinished()
            while (online.isReady(stream)) online.decode(stream)
            val last = clean(online.getResult(stream).text)
            if (last.isNotEmpty()) l.onFinal(last)
        } catch (e: Exception) {
            l.onError("Falha na captura: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
            o.effects.forEach { it.release() }
            stream.release()
            running = false
        }
    }

    /** Hebraico usa espaços entre palavras: só normaliza. */
    private fun clean(s: String) = s.replace('▁', ' ').replace(Regex("\\s+"), " ").trim()

    companion object {
        const val SR = 16000
        const val MODEL_DIR = "zipformer2-66m-streaming-he"
    }
}
