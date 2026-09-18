package com.vocabularybooster.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.koin.androidx.compose.koinViewModel

/**
 * 正式学习会话屏（Phase 4 Step 4，Phase 3 偏差补齐 + Phase 5 Step 1 语音命令）：
 * 纯渲染 + 意图转发——播放状态全部来自 [LearningSessionViewModel.ui]（编排器唯一状态源），
 * 控制按钮全部转发 ViewModel（→ PlaybackOrchestrator）；本屏零播放逻辑、零计时器。
 *
 * - Pause/Resume/Next/Replay/Exit 五控制（FR-11）；Exit 需二次确认（AUDIO §6）。
 * - CommandWindow：渲染编排器倒计时（remainingMs/totalMs），不自建定时器（裁决 L1：P4 纯倒计时）。
 *   Phase 5 Step 1：窗口内按 [LearningUiState.CommandWindow.listening] 显示「请说：会了」或降级提示
 *   （诚实呈现，不伪造录音状态），「会了」按钮**仅窗口态出现**——与语音命令同一执行路径（裁决 D2）。
 * - RECORD_AUDIO 运行时权限：UI 持有的非阻断授权条（用户点击才发起请求，绝不自动弹出；
 *   未授权 = 语音降级纯倒计时，按钮照常可用，会话不中断——裁决 D5）。
 * - Completed：编排器权威终态呈现，不重判完成条件；BOOK_DELETED 等一次性结果经对话框 + 返回上一层。
 */
@Composable
fun LearningSessionScreen(
    bookId: Long,
    onExit: () -> Unit,
    viewModel: LearningSessionViewModel = koinViewModel(),
) {
    val ui = viewModel.ui
    var showExitConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(context.hasRecordAudio()) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
    }

    LaunchedEffect(bookId) { viewModel.start(bookId) }

    // 返回键：会话进行中（含窗口/暂停/错误）→ 退出二次确认；Loading/终态 → 直接返回
    val sessionActive = ui is LearningUiState.Playing || ui is LearningUiState.Paused ||
        ui is LearningUiState.CommandWindow || ui is LearningUiState.Error
    BackHandler(enabled = sessionActive) { showExitConfirm = true }
    BackHandler(enabled = !sessionActive) { onExit() }

    // Exit 已确认执行 → 编排器状态变化（Stopped）即导航离开（等待状态变化，不自行判终态）
    LaunchedEffect(ui) {
        if (ui is LearningUiState.Stopped) onExit()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("学习会话", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.padding(top = 24.dp))

        SessionContent(state = ui, onExit = onExit, onMasterWord = viewModel::masterCurrentWord)

        Spacer(Modifier.weight(1f))

        // 麦克风权限：非阻断提示条（仅会话中显示；点击才发起系统授权，无自动请求）
        if (sessionActive && !micGranted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("mic_banner"),
            ) {
                Text(
                    "语音命令需要麦克风权限；也可以直接点「会了」按钮",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.testTag("btn_grant_mic"),
                ) { Text("授权") }
            }
        }

        if (sessionActive) {
            PlaybackControls(
                ui = ui,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onNext = viewModel::next,
                onReplay = viewModel::replay,
                onExit = { showExitConfirm = true },
            )
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出学习？") },
            text = { Text("将结束本次学习会话；已掌握的单词会保留进度。") },
            confirmButton = {
                TextButton(
                    onClick = { showExitConfirm = false; viewModel.exit() },
                    modifier = Modifier.testTag("btn_confirm_exit"),
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("继续学习") }
            },
        )
    }

    if (viewModel.conflictSessionId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text("已有进行中的学习会话") },
            text = { Text("同一时刻只能有一个学习会话。可以恢复上次的学习，或放弃它并开始本次学习。") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::resumeExistingSession,
                    modifier = Modifier.testTag("btn_conflict_resume"),
                ) { Text("恢复上次学习") }
            },
            dismissButton = {
                Column {
                    TextButton(
                        onClick = viewModel::abandonExistingAndStart,
                        modifier = Modifier.testTag("btn_conflict_abandon"),
                    ) { Text("放弃旧的并开始") }
                    TextButton(
                        onClick = { viewModel.dismissConflict(); onExit() },
                        modifier = Modifier.testTag("btn_conflict_cancel"),
                    ) { Text("取消") }
                }
            },
            properties = DialogProperties(dismissOnClickOutside = false),
        )
    }

    viewModel.notice?.let { current ->
        val navigateAway = current !is LearningNotice.CommandError
        AlertDialog(
            onDismissRequest = {
                viewModel.consumeNotice()
                if (navigateAway) onExit()
            },
            title = { Text(noticeTitle(current)) },
            text = { Text(noticeMessage(current)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.consumeNotice()
                        if (navigateAway) onExit()
                    },
                    modifier = Modifier.testTag("btn_notice_confirm"),
                ) { Text("确定") }
            },
        )
    }
}

/** 会话主体内容区：ui 状态 → 纯渲染分支（状态机权威在编排器，本函数零逻辑）。 */
@Composable
private fun SessionContent(state: LearningUiState, onExit: () -> Unit, onMasterWord: () -> Unit) {
    when (state) {
        is LearningUiState.Loading -> Text("正在准备学习…", modifier = Modifier.testTag("learning_state"))

        is LearningUiState.Playing -> {
            WordHeader(state.wordText, state.groupIndex)
            Text(
                "正在播放 · ${state.segmentLabel}（第 ${state.segmentIndex} 段）",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("learning_state"),
            )
            if (state.degraded) {
                Text(
                    "例句音频不可用，已改用语音合成朗读",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("learning_degraded"),
                )
            }
        }

        is LearningUiState.Paused -> {
            WordHeader(state.wordText, state.groupIndex)
            Text(
                "已暂停（第 ${state.segmentIndex} 段）",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("learning_state"),
            )
        }

        is LearningUiState.CommandWindow -> {
            WordHeader(state.wordText, state.groupIndex)
            Text(
                "命令窗口",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("learning_state"),
            )
            // 语音形态指示（编排器 listening 透传，诚实呈现；不伪造录音动画）
            Text(
                if (state.listening) "🎤 请说「会了」" else "语音命令不可用，可点击「会了」按钮",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.listening) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("learning_listening"),
            )
            LinearProgressIndicator(
                progress = { if (state.totalMs > 0) state.remainingMs.toFloat() / state.totalMs else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("learning_countdown_bar"),
            )
            Text(
                "剩余 %.1f 秒".format(state.remainingMs / MILLIS_PER_SECOND),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("learning_countdown"),
            )
            // 「会了」按钮：仅窗口态渲染（D2）——与语音命令同一执行路径（markMastered(BUTTON)）
            Button(
                onClick = onMasterWord,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .testTag("btn_mastered"),
            ) { Text("会了") }
        }

        is LearningUiState.Error -> {
            WordHeader(state.wordText, state.groupIndex)
            Text(
                "语音合成失败，播放已暂停",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("learning_state"),
            )
        }

        is LearningUiState.Completed -> {
            Spacer(Modifier.padding(top = 32.dp))
            Text("🏆", style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag("learning_state"))
            Text(
                "学习完成！",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            val medal = state.medal
            if (medal != null) {
                // Phase 6 完成仪式（ACHIEVEMENT_SPEC §3）：书名/词数/完成日期（勋章快照）
                Text(
                    "《${medal.bookName}》",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("ceremony_book_name"),
                )
                Text(
                    "完整掌握 ${medal.wordCount} 个单词",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "完成于 ${formatDate(medal.finishedAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("ceremony_medal_date"),
                )
            } else {
                Text(
                    "本生词本的全部单词已掌握",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = onExit,
                modifier = Modifier
                    .padding(top = 32.dp)
                    .testTag("btn_back_to_books"),
            ) { Text("返回生词本") }
        }

        is LearningUiState.Stopped -> Text("已退出学习", modifier = Modifier.testTag("learning_state"))
    }
}

@Composable
private fun WordHeader(wordText: String, groupIndex: Int) {
    Text(
        wordText,
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.testTag("learning_word"),
    )
    Text(
        "第 $groupIndex 组",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.testTag("learning_group"),
    )
}

/** 播放控制条（FR-11）：全部转发 ViewModel 命令（→ PlaybackOrchestrator）。 */
@Composable
private fun PlaybackControls(
    ui: LearningUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        when (ui) {
            is LearningUiState.Playing, is LearningUiState.CommandWindow ->
                OutlinedButton(onClick = onPause, modifier = Modifier.testTag("btn_pause")) { Text("暂停") }
            is LearningUiState.Paused, is LearningUiState.Error ->
                Button(onClick = onResume, modifier = Modifier.testTag("btn_resume")) { Text("继续") }
            else -> Unit
        }
        OutlinedButton(onClick = onNext, modifier = Modifier.testTag("btn_next")) { Text("下一个") }
        OutlinedButton(onClick = onReplay, modifier = Modifier.testTag("btn_replay")) { Text("重播") }
        OutlinedButton(onClick = onExit, modifier = Modifier.testTag("btn_exit")) { Text("退出") }
    }
}

private fun noticeTitle(notice: LearningNotice): String = when (notice) {
    is LearningNotice.StartRejected -> "无法开始学习"
    LearningNotice.BookDeleted -> "生词本已删除"
    LearningNotice.SessionUnavailable -> "学习会话不可用"
    is LearningNotice.StartError -> "开始学习失败"
    is LearningNotice.CommandError -> "操作失败"
}

/** 倒计时文案换算（ms → 秒）：UI 展示常量，非播放语义。 */
private const val MILLIS_PER_SECOND: Float = 1000f

/** RECORD_AUDIO 已授权判定（UI 权限呈现用；授权裁决只属系统，本屏不缓存结果之外的状态）。 */
private fun Context.hasRecordAudio(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun noticeMessage(notice: LearningNotice): String = when (notice) {
    is LearningNotice.StartRejected -> notice.message
    LearningNotice.BookDeleted -> "该生词本已被删除，本次学习会话已安全结束。"
    LearningNotice.SessionUnavailable -> "学习会话不存在或已结束，无法恢复。"
    is LearningNotice.StartError -> notice.message ?: "发生未知错误"
    is LearningNotice.CommandError -> notice.message ?: "发生未知错误"
}

/** 完成仪式日期（Phase 6）：epoch 毫秒 → 本地日期（minSdk 26，java.time 直用）。 */
private fun formatDate(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))
