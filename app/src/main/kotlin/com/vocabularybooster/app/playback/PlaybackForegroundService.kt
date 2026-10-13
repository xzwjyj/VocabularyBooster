package com.vocabularybooster.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.vocabularybooster.app.R
import com.vocabularybooster.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 熄屏 / 后台续播前台媒体服务（FR-25 / SCR-BGPLAYBACK，AUDIO_ENGINE_SPEC §8「熄屏/后台续播契约」）。
 *
 * **为何 plain [Service] 而非 `MediaSessionService`**：前台时机须确定性——编排器 Playing/Paused/
 * CommandWindow 须立即升前台保活 + WakeLock，而 `MediaSessionService` 在「paused-start」（无活跃
 * 传输时被拉起）下是否自动升前台不确定（Android 14 前台服务启动限制 + MediaSession 生命周期耦合）。
 * 故取 plain Service + 手动 [ServiceCompat.startForeground]，MediaSession 独立构造以满足 Android 14
 * 「mediaPlayback 前台服务须有活跃 MediaSession」合规要求。
 *
 * - **保活作用域**：传输态（Playing/Paused/CommandWindow）→ 升前台 + 4h 有界 PARTIAL_WAKE_LOCK；
 *   终态（Idle/Completed/Stopped）→ 控制器 [PlaybackForegroundController.reflect] 经 gateway.stop()
 *   投递 ACTION_STOP 停服（服务自身 collector **不**自停，避免与 gateway.stop() 投递的 STOP 竞态重启）。
 * - **通知更新**：服务自有 [serviceScope] 在进程内采集 `controls.state`，差异签名（wordText /
 *   segmentIndex / listening）变更才 [NotificationManager.notify] + [OrchestratorMirroringPlayer.syncTo]
 *   ——避免每 100ms CommandWindow 倒计时 tick 刷通知（倒计时仅 app 前台 UI 可见）。
 * - **MediaSession 传输路由**：锁屏 / 通知栏 play/pause/next/previous → MediaSession →
 *   [OrchestratorMirroringPlayer] 覆写的 transport → [PlaybackForegroundController.onNotification*]
 *   → [PlaybackControls]（编排器 suspend 入口）；通知栏「退出」按钮 → ACTION_NOTIFY_EXIT 同路。
 *
 * @param controls 编排器控制（app-local seam，DI 注入；进程内采集状态）
 * @param controller 前台控制器（DI 注入；transport 路由 + appForeground 标志源）
 */
@Suppress("TooManyFunctions") // Service 生命周期 + 通知构造 + collector + transport 路由，方法数超阈值乃前台服务职责所迫
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackForegroundService : Service(), KoinComponent {

    private val controls: PlaybackControls by inject()
    private val controller: PlaybackForegroundController by inject()

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var mirroringPlayer: OrchestratorMirroringPlayer
    private lateinit var dummyPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession

    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private var lastNotifiedSignature: String = ""
    @Volatile private var cachedWordText: String = ""

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        ensureNotificationChannel()
        // STATE_IDLE 桩——永不 prepare 媒体，仅满足 MediaSession 非空 Player 委托 + ForwardingPlayer 基座
        dummyPlayer = ExoPlayer.Builder(this).build()
        mirroringPlayer = OrchestratorMirroringPlayer(dummyPlayer, controller)
        mediaSession = MediaSession.Builder(this, mirroringPlayer)
            .setId(SESSION_ID)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_NOTIFY_PAUSE -> controller.onNotificationPause()
            ACTION_NOTIFY_RESUME -> controller.onNotificationResume()
            ACTION_NOTIFY_NEXT -> controller.onNotificationNext()
            ACTION_NOTIFY_PREVIOUS -> controller.onNotificationPrevious()
            ACTION_NOTIFY_EXIT -> controller.onNotificationExit()
            else -> handleStart() // ACTION_START 或系统重建（null intent）：按当前 state 决定升降前台
        }
        return START_NOT_STICKY // 服务被杀不重建——控制器 reflect() 在 state 再活跃时经 gateway.start() 重拉
    }

    private fun handleStart() {
        val state = controls.state.value
        if (!state.isForegroundActive()) {
            // 拉起时已终态（控制器 gateway.start() 与本服务 onStartCommand 间 state 翻转，或系统重建）：
            // 不升前台，直接自停——避免空占前台服务槽位
            stopForegroundAndSelf()
            return
        }
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        mirroringPlayer.syncTo(state)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        startCollector()
    }

    private fun startCollector() {
        if (collectorJob?.isActive == true) return
        collectorJob = serviceScope.launch {
            controls.state.collect { state ->
                if (!state.isForegroundActive()) return@collect // 终态：由控制器 gateway.stop() 停服（避免自停-重启竞态）
                cacheWordText(state)
                val sig = notifSignature(state)
                if (sig != lastNotifiedSignature) {
                    mirroringPlayer.syncTo(state)
                    lastNotifiedSignature = sig
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_ID, buildNotification(state))
                }
            }
        }
    }

    private fun cacheWordText(state: PlaybackState) {
        val text = (state as? PlaybackState.Playing)?.wordText
            ?: (state as? PlaybackState.Paused)?.wordText
        if (!text.isNullOrEmpty()) cachedWordText = text
    }

    private fun notifSignature(state: PlaybackState): String = when (state) {
        is PlaybackState.Playing -> "Playing:${state.wordText}:${state.segmentIndex}"
        is PlaybackState.Paused -> "Paused:${state.wordText}:${state.segmentIndex}"
        is PlaybackState.CommandWindow -> "CommandWindow:${state.listening}"
        else -> "other"
    }

    private fun buildNotification(state: PlaybackState): Notification {
        val isPlaying = state is PlaybackState.Playing || state is PlaybackState.CommandWindow
        val title = cachedWordText.ifEmpty { DEFAULT_TITLE }
        val content = when (state) {
            is PlaybackState.Playing -> "正在播放"
            is PlaybackState.Paused -> "已暂停"
            is PlaybackState.CommandWindow -> if (state.listening) "命令窗口（听音中）" else "命令窗口（倒计时）"
            else -> "VocabularyBooster"
        }
        val pauseResumeAction = if (isPlaying) {
            notificationAction(ACTION_NOTIFY_PAUSE, "暂停", android.R.drawable.ic_media_pause)
        } else {
            notificationAction(ACTION_NOTIFY_RESUME, "继续", android.R.drawable.ic_media_play)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(notificationAction(ACTION_NOTIFY_PREVIOUS, "上一词", android.R.drawable.ic_media_previous))
            .addAction(pauseResumeAction)
            .addAction(notificationAction(ACTION_NOTIFY_NEXT, "下一词", android.R.drawable.ic_media_next))
            .addAction(notificationAction(ACTION_NOTIFY_EXIT, "退出", android.R.drawable.ic_menu_close_clear_cancel))
            .build()
    }

    private fun notificationAction(action: String, label: String, iconRes: Int): NotificationCompat.Action {
        val intent = Intent(this, PlaybackForegroundService::class.java).setAction(action)
        val pendingIntent = PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(iconRes, label, pendingIntent).build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "熄屏/后台续播前台服务通知"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    @Suppress("TooGenericExceptionCaught") // 停前台时 ServiceCompat 可能抛抽象异常——停服路径绝不崩溃（防御性吞记）
    private fun stopForegroundAndSelf() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground 异常", e)
        }
        stopSelf()
    }

    @Suppress("TooGenericExceptionCaught") // onDestroy 释放各资源——异常种类不可穷举（OEM/Media3/WakeLock），吞记不崩（服务已停）
    override fun onDestroy() {
        super.onDestroy()
        collectorJob?.cancel()
        collectorJob = null
        serviceScope.cancel()
        try {
            if (this::mediaSession.isInitialized) mediaSession.release()
        } catch (e: Exception) {
            Log.w(TAG, "mediaSession.release 异常", e)
        }
        try {
            if (this::dummyPlayer.isInitialized) dummyPlayer.release()
        } catch (e: Exception) {
            Log.w(TAG, "dummyPlayer.release 异常", e)
        }
        try {
            if (this::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock.release 异常", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "VB-PlaybackService"
        const val NOTIF_ID = 425
        const val CHANNEL_ID = "playback"
        const val SESSION_ID = "vocab-playback"
        const val WAKE_LOCK_TAG = "vocab:playback"
        const val WAKE_LOCK_TIMEOUT_MS: Long = 4L * 60 * 60 * 1000 // 4h 有界——防泄漏
        const val ACTION_START = "com.vocabularybooster.playback.START"
        const val ACTION_STOP = "com.vocabularybooster.playback.STOP"
        const val ACTION_NOTIFY_PAUSE = "com.vocabularybooster.playback.NOTIFY_PAUSE"
        const val ACTION_NOTIFY_RESUME = "com.vocabularybooster.playback.NOTIFY_RESUME"
        const val ACTION_NOTIFY_NEXT = "com.vocabularybooster.playback.NOTIFY_NEXT"
        const val ACTION_NOTIFY_PREVIOUS = "com.vocabularybooster.playback.NOTIFY_PREVIOUS"
        const val ACTION_NOTIFY_EXIT = "com.vocabularybooster.playback.NOTIFY_EXIT"
        const val DEFAULT_TITLE = "VocabularyBooster"
    }
}
