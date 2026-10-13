package com.vocabularybooster.app.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 前台服务启停网关（FR-25 / SCR-BGPLAYBACK，AUDIO_ENGINE_SPEC §8）。
 *
 * **粒度 = 仅 start/stop**（不传状态快照）：
 * - start 一次性升 [PlaybackForegroundService] 前台（Android 12+ 后台启动前台服务限制——
 *   服务必须在 `onCreate`→`onStartCommand` 内立即 `startForeground`，故 start 仅投递 ACTION_START
 *   触发首次升前台；后续通知/MediaSession 状态更新由服务进程内采集 `controls.state` 驱动，
 *   不再走 `startForegroundService` 避免熄屏时后台启动被系统拒）。
 * - stop 投递 ACTION_STOP 停服；服务已停时 `startService(STOP)` 可能抛 `IllegalStateException`
 * （后台限制）——吞记日志（控制器 `serviceActive` 标志已翻 false，幂等）。
 */
public interface PlaybackServiceGateway {
    public fun start()
    public fun stop()
}

public class AndroidPlaybackServiceGateway(private val context: Context) : PlaybackServiceGateway {
    override fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, PlaybackForegroundService::class.java).setAction(PlaybackForegroundService.ACTION_START),
        )
    }

    override fun stop() {
        try {
            context.startService(
                Intent(context, PlaybackForegroundService::class.java).setAction(PlaybackForegroundService.ACTION_STOP),
            )
        } catch (e: IllegalStateException) {
            // 熄屏/后台限制下 startService 投递阻断——服务可能已停；控制器 serviceActive 标志已翻 false，幂等
            Log.w(TAG, "stop() 投递阻断（服务已停或后台限制）", e)
        }
    }

    private companion object {
        const val TAG = "VB-PlaybackGateway"
    }
}
