package com.carstage.playbacktap

import android.annotation.SuppressLint
import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Hands what the other apps play to CarStage, for an emulator without the MediaProjection consent
 * dialog (docs/05 "재생 소리 캡처 확인"). Runs as the shell user through app_process
 * (scripts/playback-tap.sh), which holds MODIFY_AUDIO_ROUTING and CAPTURE_AUDIO_OUTPUT: it
 * registers the same kind of audio policy an app's AudioPlaybackCapture registers (a loopback mix
 * that keeps the sound playing) and streams it over a socket on 127.0.0.1 only.
 *
 * One client at a time. The client sends one line, `usages=1,0,14 exclude=<uid>` (the usages to
 * hear, and an app whose sound to leave out), and gets `ok rate=48000 channels=2` followed by
 * 16-bit little-endian PCM, or `error <reason>`. The capture runs exactly while the client stays
 * connected. Nothing is stored.
 */
object Main {
    private const val DEFAULT_PORT = 47_123
    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
        Looper.prepareMainLooper()
        ShellContext.install()
        thread(name = "playback-tap-server") {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                log("listening on 127.0.0.1:$port")
                while (true) {
                    val client = server.accept()
                    try {
                        client.use(::serve)
                    } catch (e: Exception) {
                        log("client ended: ${e::class.java.simpleName}: ${e.message}")
                    }
                }
            }
        }
        Looper.loop()
    }

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        val request = BufferedReader(InputStreamReader(client.getInputStream())).readLine() ?: return
        val words = request.split(' ').mapNotNull { word -> word.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val usages = words["usages"]?.split(',')?.mapNotNull(String::toIntOrNull)?.takeIf { it.isNotEmpty() } ?: listOf(AudioAttributes.USAGE_MEDIA)
        val exclude = words["exclude"]?.toIntOrNull()
        val out = client.getOutputStream()
        val capture = try {
            PlaybackCapture(usages, exclude, SAMPLE_RATE, CHANNELS)
        } catch (e: Exception) {
            // Reflection wraps what the framework threw; say that, not the wrapper.
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log("capture failed: ${cause::class.java.simpleName}: ${cause.message}")
            out.write("error ${cause::class.java.simpleName}: ${cause.message}\n".toByteArray())
            return
        }
        try {
            out.write("ok rate=$SAMPLE_RATE channels=$CHANNELS\n".toByteArray())
            out.flush()
            log("capturing usages $usages${exclude?.let { " without uid $it" } ?: ""}")
            val pcm = ShortArray(SAMPLE_RATE / 50 * CHANNELS)
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var total = 0L
            while (true) {
                val read = capture.record.read(pcm, 0, pcm.size)
                if (read < 0) {
                    log("read returned $read")
                    break
                }
                bytes.clear()
                bytes.asShortBuffer().put(pcm, 0, read)
                out.write(bytes.array(), 0, read * 2)
                total += read
            }
            log("sent ${total / CHANNELS} frames")
        } finally {
            capture.close()
            log("capture stopped")
        }
    }

    fun log(line: String) {
        println("${System.currentTimeMillis()} playback-tap: $line")
        System.out.flush()
    }
}

/**
 * A loopback mix of the other apps' playback that keeps it playing (`ROUTE_FLAG_LOOP_BACK_RENDER`,
 * what AudioPlaybackCapture registers), through the system APIs AudioPlaybackCapture is built on.
 */
@SuppressLint("BlockedPrivateApi")
private class PlaybackCapture(usages: List<Int>, excludeUid: Int?, sampleRate: Int, channels: Int) {
    private val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
    private val policy: Any
    val record: AudioRecord

    init {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        ruleBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(ruleBuilder, MIX_ROLE_PLAYERS)
        val addMixRule = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in usages) {
            addMixRule.invoke(ruleBuilder, RULE_MATCH_ATTRIBUTE_USAGE, AudioAttributes.Builder().setUsage(usage).build())
        }
        // As AudioPlaybackCaptureConfiguration.excludeUid does: addMixRule refuses exclusion values
        // ("Illegal rule value 32772" on the Pleos emulator, docs/05), excludeMixRule takes the match rule.
        if (excludeUid != null) {
            ruleBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(ruleBuilder, RULE_MATCH_UID, excludeUid)
        }
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
            .build()
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        val loopBackRender = mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, loopBackRender)
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ShellContext.context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!
        val registered = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass).apply { isAccessible = true }.invoke(null, policy) as Int
        check(registered == 0) { "registerAudioPolicyStatic returned $registered" }
        record = try {
            policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as AudioRecord
        } catch (e: Exception) {
            unregister()
            throw e
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            close()
            error("startRecording failed")
        }
    }

    fun close() {
        runCatching { record.stop() }
        record.release()
        unregister()
    }

    private fun unregister() {
        runCatching { AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass).apply { isAccessible = true }.invoke(null, policy) }
            .onFailure { Main.log("unregister failed: ${it::class.java.simpleName}: ${it.message}") }
    }

    companion object {
        private const val MIX_ROLE_PLAYERS = 0
        private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
        private const val RULE_MATCH_UID = 0x1 shl 2
    }
}

/**
 * Makes the process look like the shell app to the audio framework: app_process starts no
 * application, so the package name AudioRecord and the audio policy report would be null.
 */
@SuppressLint("BlockedPrivateApi")
private object ShellContext {
    private const val PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2_000
    lateinit var context: Context
        private set

    fun install() {
        val threadClass = Class.forName("android.app.ActivityThread")
        val activityThread = threadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        threadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, activityThread)
        threadClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(activityThread, true)

        val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
        val bindData = bindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        bindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bindData, ApplicationInfo().apply { packageName = PACKAGE })
        threadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(activityThread, bindData)

        val system = threadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(activityThread) as Context
        val shell = object : ContextWrapper(system) {
            override fun getPackageName() = PACKAGE
            override fun getOpPackageName() = PACKAGE
            override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(SHELL_UID).setPackageName(PACKAGE).build()
            override fun getApplicationContext(): Context = this
        }
        context = shell
        val application = Application()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }.invoke(application, shell)
        threadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(activityThread, application)
    }
}
