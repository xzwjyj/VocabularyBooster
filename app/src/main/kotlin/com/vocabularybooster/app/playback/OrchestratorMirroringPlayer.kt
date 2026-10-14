package com.vocabularybooster.app.playback

import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vocabularybooster.playback.PlaybackState

/**
 * MediaSession 镜像 Player（FR-25 / SCR-BGPLAYBACK，AUDIO_ENGINE_SPEC §8）。
 *
 * **为何存在**：Android 14 mediaPlayback 前台服务须绑定活跃 MediaSession；MediaSession 须以真实
 * [Player] 构造。但编排器的真实出声 ExoPlayer（[com.vocabularybooster.platform.Media3AudioPlayer]）
 * 生命周期与单段 CompletableDeferred 强耦合（不可直接作为 MediaSession 持久 player）——故本类包一个
 * STATE_IDLE 桩 ExoPlayer 作 [ForwardingPlayer] 基座，**永不 prepare 媒体、永不发声**，仅：
 *
 * 1. **读**（getter 覆写）：以编排器 [PlaybackState] 投影合成 MediaSession 可见的播放态 / PlayWhenReady /
 *    MediaItem（标题=当前词）/ MediaMetadata——锁屏 / 通知栏据此渲染。
 * 2. **写**（transport 覆写）：play/pause/seekToNext/seekToPrevious 路由至 [PlaybackForegroundController]
 *    的 onNotification* → 编排器 suspend 入口（桩 player 自身零传输）。
 *
 * **线程契约**：Media3 Player 访问钉主线程——[syncTo] 与所有 getter/transport 覆写均断言主线程
 * （MediaSession 契约 + ExoPlayer 契约一致）。服务 [PlaybackForegroundService.serviceScope] = Main.immediate。
 *
 * @param delegate STATE_IDLE 桩 ExoPlayer（ForwardingPlayer 非空 Player 委托基座；永不发声）
 * @param controller transport 路由目标（通知栏 + 锁屏 → 控制器 → 编排器）
 */
@Suppress("TooManyFunctions") // ForwardingPlayer 契约：读(5 getter)+写(5 transport)+listener 自管(2)+syncTo，覆写数超阈值乃接口契约所迫
@androidx.annotation.OptIn(UnstableApi::class)
class OrchestratorMirroringPlayer(
    private val delegate: ExoPlayer,
    private val controller: PlaybackForegroundController,
) : ForwardingPlayer(delegate) {

    private val listeners: MutableList<Player.Listener> = mutableListOf()
    @Volatile private var active: Boolean = false
    @Volatile private var showPause: Boolean = false
    @Volatile private var wordText: String = ""
    @Volatile private var currentMediaItem: MediaItem? = null

    /**
     * 以编排器 [state] 同步 MediaSession 可见态。须主线程（Player 契约）。
     * 差异事件化：仅在 getter 投影值实际变更时通知 listener（避免冗余 onPlaybackStateChanged）。
     */
    fun syncTo(state: PlaybackState) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "syncTo 必须在主线程（Media3 Player 契约）" }
        val prevPlaybackState = getPlaybackState()
        val prevPlayWhenReady = getPlayWhenReady()
        val prevMediaItem = currentMediaItem

        active = state.isForegroundActive()
        showPause = state is PlaybackState.Playing || state is PlaybackState.CommandWindow
        val newText = (state as? PlaybackState.Playing)?.wordText
            ?: (state as? PlaybackState.Paused)?.wordText ?: wordText
        wordText = newText
        currentMediaItem = if (active) {
            if (newText == prevMediaItem?.mediaMetadata?.title?.toString()) prevMediaItem
            else mediaItemFor(newText)
        } else null

        val newPlaybackState = getPlaybackState()
        val newPlayWhenReady = getPlayWhenReady()
        val newMediaItem = currentMediaItem

        if (newPlaybackState != prevPlaybackState) {
            notifyListeners { onPlaybackStateChanged(newPlaybackState) }
        }
        if (newPlayWhenReady != prevPlayWhenReady) {
            notifyListeners {
                onPlayWhenReadyChanged(newPlayWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
        }
        if (newMediaItem !== prevMediaItem) {
            notifyListeners {
                onMediaItemTransition(newMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
            notifyListeners { onMediaMetadataChanged(newMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY) }
        }
    }

    // —— 读：合成态投影（覆盖 ForwardingPlayer 委托） ——

    override fun getPlaybackState(): Int =
        if (active) Player.STATE_READY else Player.STATE_IDLE

    override fun getPlayWhenReady(): Boolean = active && showPause

    override fun isPlaying(): Boolean = getPlayWhenReady() && getPlaybackState() == Player.STATE_READY

    override fun getCurrentMediaItem(): MediaItem? = currentMediaItem

    override fun getMediaMetadata(): MediaMetadata =
        currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

    /**
     * 可用命令投影（SCR-BGPLAYBACK 追补）：桩 delegate 空播放列表 → 默认无 SEEK_TO_NEXT/PREVIOUS，
     * Android 13+ 锁屏/通知栏媒体轮播的三大传输键（上一词/播放-暂停/下一词）按本集合渲染——
     * 恒定宣告这五项（播放暂停 + 前后跳），与 [seekToNext]/[seekToPrevious] 覆写一一对应。
     */
    override fun getAvailableCommands(): Player.Commands =
        Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
            .build()

    // —— 写：transport 路由至控制器（桩 delegate 零传输） ——

    override fun play() { controller.onNotificationResume() }
    override fun pause() { controller.onNotificationPause() }
    override fun seekToNext() { controller.onNotificationNext() }
    override fun seekToPrevious() { controller.onNotificationPrevious() }
    override fun stop() { /* 锁屏 stop / 系统 dismiss 不路由——退出仅经 app 内按钮（SCR-BGPLAYBACK 追补：通知栏三大键不含退出），避免误弃会话 */ }

    // —— listener 自管（ForwardingPlayer 默认委托 delegate；本类须自持以在 syncTo 时派发合成事件） ——

    override fun addListener(listener: Player.Listener) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "addListener 必须在主线程（Media3 Player 契约）" }
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "removeListener 必须在主线程（Media3 Player 契约）" }
        listeners -= listener
    }

    private fun mediaItemFor(text: String): MediaItem =
        MediaItem.Builder()
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(text.ifEmpty { DEFAULT_TITLE })
                    .setArtist(DEFAULT_ARTIST)
                    .build()
            )
            .build()

    private fun notifyListeners(block: Player.Listener.() -> Unit) {
        listeners.toList().forEach { it.block() }
    }

    private companion object {
        const val DEFAULT_TITLE = "VocabularyBooster"
        const val DEFAULT_ARTIST = "VocabularyBooster"
    }
}
