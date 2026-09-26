package com.vocabularybooster.platform

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vocabularybooster.playback.AudioPlayer
import com.vocabularybooster.playback.TrackDescriptor
import com.vocabularybooster.speech.SegmentResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AudioPlayer 的 Android 实现（AUDIO_ENGINE_SPEC §8 平台 actual 契约）：
 * Media3 ExoPlayer 单 track 顺序播放；`playAt(offsetMs)` = `seekTo + play`；
 * `pause()` 返回真实 `currentPosition`（§6 Offset/Pause——不模拟、不自带秒表）；
 * 完成/失败经 Player.Listener 回调驱动 CompletableDeferred（不依赖 UI 轮询/固定 sleep）。
 *
 * - **失败契约**：prepare / 播放失败以异常抛出（编排器 portCall 统一转 TTS 兜底，AUDIO §9）。
 * - **音频焦点（§8 / TC-AE-16）**：只在平台层处理，不上漏端口——播放前请求（拒绝 = 播放失败），
 *   停止/完成后放弃；transient 丢失 → 自动暂停（位置保留）；焦点回归**不**自动恢复播放。
 *   ExoPlayer 内建 focus 处理（regain 自动续播）与此语义冲突，故关闭自行管理。
 * - **线程契约**：ExoPlayer 访问钉在主线程——suspend 入口内部 `withContext(Main.immediate)`；
 *   `pause()/stop()/release()` 须从主线程调用（DI scope = Dispatchers.Main.immediate 保证）。
 *   构造亦应在主线程（Application.onCreate 预装配；无 Looper 线程构造时 ExoPlayer 回落主 Looper）。
 * - **release 后**：prepare/playAt 抛异常，pause/stop 幂等 no-op——`release()` 后不再触碰 player。
 *
 * @param context 任意 Context；内部取 applicationContext，不持有 Activity（防泄漏）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
public class Media3AudioPlayer(
    context: Context,
) : AudioPlayer {

    private val appContext: Context = context.applicationContext
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(EXO_AUDIO_ATTRIBUTES, /* handleAudioFocus = */ false)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _progressMs = MutableStateFlow<Long?>(null)
    override val progressMs: StateFlow<Long?> = _progressMs

    // —— 主线程限定的可变状态 ——
    private var released = false
    private var prepared: TrackDescriptor? = null
    private var activeTrackId: String = ""
    private var prepareAwaiter: CompletableDeferred<Unit>? = null
    private var playbackAwaiter: CompletableDeferred<SegmentResult>? = null
    private var progressJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 测试/冒烟观测：ExoPlayer 是否正在出声（focus 自动暂停后 = false）。主线程限定。 */
    public val isPlaying: Boolean
        get() {
            ensureOnMainThread()
            return player.isPlaying
        }

    private val stateListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> prepareAwaiter?.complete(Unit)
                Player.STATE_ENDED -> {
                    stopProgressTicker()
                    _progressMs.value = null
                    abandonAudioFocus()
                    val duration = player.duration
                    playbackAwaiter?.complete(
                        SegmentResult(
                            utteranceId = activeTrackId,
                            completed = true,
                            durationMs = if (duration == C.TIME_UNSET) null else duration,
                        )
                    )
                }
                else -> Unit // IDLE（stop 后）/ BUFFERING：无终结语义
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            stopProgressTicker()
            abandonAudioFocus()
            val failure = IllegalStateException("Media3 播放失败：${error.errorCodeName}", error)
            prepareAwaiter?.completeExceptionally(failure)
            playbackAwaiter?.completeExceptionally(failure)
        }
    }

    init {
        player.addListener(stateListener)
    }

    override suspend fun prepare(track: TrackDescriptor): Unit = withContext(Dispatchers.Main.immediate) {
        ensureNotReleased()
        resetForNewTrack()
        prepared = track
        activeTrackId = track.trackId
        val awaiter = CompletableDeferred<Unit>()
        prepareAwaiter = awaiter
        try {
            player.setMediaItem(MediaItem.fromUri(resolveUri(track.audioUri)))
            player.prepare()
            awaiter.await() // STATE_READY 或 onPlayerError（异常向上抛 = §9 兜底触发）
        } catch (e: CancellationException) {
            player.stop()
            throw e
        } finally {
            if (prepareAwaiter === awaiter) prepareAwaiter = null
        }
    }

    override suspend fun playAt(offsetMs: Long): SegmentResult = withContext(Dispatchers.Main.immediate) {
        ensureNotReleased()
        val track = prepared ?: throw IllegalStateException("playAt 前必须先 prepare（track 缺失）")
        val awaiter = CompletableDeferred<SegmentResult>()
        playbackAwaiter = awaiter
        try {
            if (!requestAudioFocus()) {
                throw IllegalStateException("音频焦点请求被拒绝，无法播放：${track.audioUri}")
            }
            if (offsetMs > 0L) player.seekTo(offsetMs)
            player.play()
            startProgressTicker()
            awaiter.await() // STATE_ENDED（完成）或 onPlayerError（异常）
        } catch (e: CancellationException) {
            // 编排器控制操作（Pause/Next/Exit）取消驱动协程：保持位置暂停（§3 pause 记 offset）。
            // 防迟到取消：CE 经主线程队列派发，可能晚于下一段 playAt 接管（resume 快速续播场景）——
            // 仅当自己仍是活跃 awaiter 且未 release 才触碰播放状态，绝不误停/误清后继段。
            if (!released && playbackAwaiter === awaiter) {
                player.pause()
                stopProgressTicker()
                _progressMs.value = player.currentPosition
            }
            throw e
        } finally {
            if (playbackAwaiter === awaiter) playbackAwaiter = null
        }
    }

    /** 暂停并返回真实 currentPosition（主线程限定）。 */
    override fun pause(): Long {
        ensureOnMainThread()
        if (released) return 0L // release 后幂等 no-op（dispose 次序防御）
        player.pause()
        stopProgressTicker()
        val position = player.currentPosition
        _progressMs.value = position
        return position
    }

    override fun stop() {
        ensureOnMainThread()
        if (released) return
        stopProgressTicker()
        playbackAwaiter?.cancel()
        prepareAwaiter?.cancel()
        player.stop()
        player.clearMediaItems()
        prepared = null
        _progressMs.value = null
        abandonAudioFocus()
    }

    /** 释放（宿主销毁）：release 后不再触碰 player；重复调用幂等。主线程限定。 */
    public fun release() {
        ensureOnMainThread()
        if (released) return
        released = true
        stopProgressTicker()
        playbackAwaiter?.cancel()
        prepareAwaiter?.cancel()
        prepared = null
        _progressMs.value = null
        abandonAudioFocus()
        scope.cancel()
        player.removeListener(stateListener)
        player.release()
    }

    // —— 内部（全部主线程限定） ——

    /**
     * audioUri 解析：
     * - `res://<资源名>` → android.resource://<packageName>/raw/<资源名>（RawResourceDataSource）
     * - `asset://<路径>` → 每次复制到 cache 目录后以 `file://` 播放（规避 DataSource scheme/资产压缩差异，
     *   任何 DataSource 都能读；例句音频都是小文件，复制开销可忽略）
     * 其余 scheme（http/file/content…）原样透传。平台专属知识只存在于本 actual。
     */
    private fun resolveUri(audioUri: String): String =
        when {
            audioUri.startsWith(RES_URI_SCHEME) -> {
                "android.resource://${appContext.packageName}/raw/${audioUri.removePrefix(RES_URI_SCHEME)}"
            }
            audioUri.startsWith(ASSET_URI_SCHEME) -> {
                resolveAssetToFileUri(audioUri.removePrefix(ASSET_URI_SCHEME))
            }
            else -> audioUri
        }

    /**
     * asset 路径 → cache 中的 file:// URI。
     *
     * 每次 prepare 都从 APK 重拷（不做「已存在即复用」缓存）：例句音频随 APK 更新会被替换
     * （SCR-AUDIOTRIM 重裁 / SCR-SENTMERGE 整句扩剪），旧拷贝若复用会让设备一直播旧音频
     * （SCR-AUDIOCACHE：legion 例句文字已整句、语音仍停在旧 7.2s 拷贝）。例句音频均为小文件
     * （≤~200KB），逐次复制开销可忽略——与词典 sqlite 的 user_version 失效机制同理，取最简形态。
     */
    private fun resolveAssetToFileUri(assetPath: String): String {
        val safeName = assetPath.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val cached = File(File(appContext.cacheDir, "video_audio"), safeName)
        cached.parentFile?.mkdirs()
        appContext.assets.open(assetPath).use { input ->
            cached.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(cached).toString()
    }

    private fun resetForNewTrack() {
        stopProgressTicker()
        playbackAwaiter?.cancel()
        prepareAwaiter?.cancel()
        player.stop()
        player.clearMediaItems()
        prepared = null
        _progressMs.value = null
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        progressJob = scope.launch {
            while (isActive) {
                _progressMs.value = player.currentPosition
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    // —— 音频焦点（§8 / TC-AE-16；只在本实现内，不上漏 commonMain 端口） ——

    private fun requestAudioFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(EXO_AUDIO_ATTRIBUTES.audioAttributesV21!!.audioAttributes)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .setWillPauseWhenDucked(true) // CAN_DUCK 与 transient 同判：暂停（规格未定义 duck 音量，不扩大语义）
            .build()
            .also { focusRequest = it }
        val result = audioManager.requestAudioFocus(request)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handleFocusChange(change) // 已注册到 mainHandler 派发（setOnAudioFocusChangeListener 第二参）
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // 自动 Pause（位置保留）；端口无暂停事件通道——§8「广播状态」以
                // progressMs 冻结 + isPlaying=false 可观测（编排器级广播留待后续 UI Step）。
                if (!released && player.isPlaying) {
                    player.pause()
                    stopProgressTicker()
                    _progressMs.value = player.currentPosition
                }
                if (change == AudioManager.AUDIOFOCUS_LOSS) abandonAudioFocus()
            }
            // TC-AE-16：焦点回归不自动播放（ExoPlayer 内建 regain 续播已显式关闭）
            AudioManager.AUDIOFOCUS_GAIN -> Unit
        }
    }

    private fun ensureNotReleased() {
        check(!released) { "Media3AudioPlayer 已 release，禁止继续使用" }
    }

    private fun ensureOnMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Media3AudioPlayer 平台调用必须在主线程（DI scope 应为 Dispatchers.Main.immediate）"
        }
    }

    private companion object {
        const val PROGRESS_TICK_MS: Long = 200L

        /** 平台中立随包资产 scheme（SeedData 注记；iOS actual 对应 bundle resource）。 */
        const val RES_URI_SCHEME: String = "res://"

        /** APK assets 目录逻辑 scheme（VideoImportEngine 生成的例句原声路径）。 */
        const val ASSET_URI_SCHEME: String = "asset://"

        /** 例句朗读属语音内容：USAGE_MEDIA + SPEECH（焦点属性与 Media3 常用配置一致）。 */
        val EXO_AUDIO_ATTRIBUTES: androidx.media3.common.AudioAttributes =
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build()
    }
}
