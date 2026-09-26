# SPEC CHANGE REQUEST — 学习会话按钮反馈卡顿（神经 TTS 段取消不感知 + 主线程 IO）

- **编号**：SCR-BUTTONLAG
- **日期**：2026-09-26
- **影响范围**：`shared/androidMain SherpaOnnxSpeechSynthesizer`（actual 内部：写入循环取消感知 + 缓存读取/PCM 转换移出主线程）；**端口 / 编排器 / 语义 / 系统降级路径 / Media3 均零改动**；无 schema / Gradle / 资产变更
- **关联需求**：FR-11（播放控制）体验缺陷；AUDIO_ENGINE_SPEC §6 控制语义不变

## 1. 问题（用户报告）

学习会话界面按键反馈卡顿不丝滑——继续/暂停、退出等：点「暂停」后声音继续播、按钮状态迟迟不切；播放期间整体操作不跟手。

## 2. 根因（代码审计，两层）

### 2.1 主因：神经 TTS 段内控制要等写入循环喂完全部音频

`SherpaOnnxSpeechSynthesizer.speak()` 的 PCM 写入循环（`withContext(Dispatchers.IO)` 内）以 `AudioTrack.write(WRITE_BLOCKING)` 逐 16KB 块喂 16KB 流缓冲——**按 1× 实时速率 pacing**（每块等上一块播完腾出缓冲）。循环退出条件只有「数据写完」或 `stopRequested`（**仅 `stop()` 置位**），不感知协程取消；`AudioTrack.write` 是阻塞 native 调用，取消无法打断。

而编排器全部控制（pause / next / masterCurrentWord / exit）的顺序是**先 `cancelStep()`（`cancelAndJoin` 等 speak 协程退完）再 `synthesizer.stop()`**（`PlaybackOrchestrator.kt:148-162` 等五处）——该顺序是防「stop 后 speak 正常返回 completed=false、驱动循环继续推进下一词」竞态的既定设计，不能反转。

结果：TTS 段播放中点暂停 → `cancelAndJoin` 要等写入循环以实时速率喂完**剩余全部 PCM**（长中文段 5–15s）→ 期间音频继续出声、Paused 状态不发、按钮不变。文件段（例句，Media3 `playAt` 的 CE 处理立即 `player.pause()`）与系统 TTS（CE → `tts.stop()`）路径均无此问题——所以卡顿集中在 TTS 段，与用户感知一致。

### 2.2 次因：speak() 主体在主线程做磁盘 IO 与大数组转换

编排器 scope = `Dispatchers.Main.immediate`（AppModule.kt:137），`speak()` 主体在主线程执行：每段 `readCache`（同步文件读，长段 WAV ~1MB）、cache-miss 时 `toPcm16Le`（~50 万样本循环转 PCM）都在主线程——逐词多段推进（PRON→SPELLING→EN→CN→例句…）主线程反复磁盘 IO → 丢帧，播放期操作「不丝滑」。`diag()` 同步小追加（每段 3–4 次）保留不动（装机取证优先，量小）。

## 3. 方案（纯 actual 内部，两处）

1. **写入循环取消感知**：`withContext(Dispatchers.IO) { ... }` 块内循环条件追加 `isActive`（块本身是 `CoroutineScope`，直接可用）；取消后 CE 顺协程传播，**既有 `finally`（track pause/flush/release + abandonFocus）立即停声**，`portCall` 对 CE 照常重抛、驱动循环如常死亡。效果：`cancelAndJoin` 最坏等一个 16KB 块（≤0.4s @22050/24000），常态 <100ms；暂停即静。
2. **主线程 IO 挪移**：`readCache(cacheFile)` 与 `toPcm16Le()` 包 `withContext(Dispatchers.IO)`（后者并入生成 `withContext` 块尾或独立 IO 块）。

**已知边界（记录不修）**：cache-miss 首播的**生成期**（native generate 不可中断）点暂停，join 仍需等生成完成（zipvoice numSteps=4，实测单段秒级）——仅首播窗口、有 WAV 缓存后不复现。

## 4. 验收

1. 门禁照旧：jvmTest（编排器控制语义既有用例不受影响——Fake 端口立即响应取消）/ testDebugUnitTest / detekt×2 / checkPlatformBoundaries / assembleDebug；
2. vivo 装机取证：播放中文释义段中途点暂停——tts_diag.log `stop invoked` → `write bytes … stop=true` 时间差 ≤ 一个块周期；人耳：点暂停即静、按钮立即切「继续」；退出/下一个/会了同验；
3. 人耳复核：暂停-继续-暂停连续操作跟手；例句音频段（Media3 路径）行为无回归。

## 5. 风险

低——纯 androidMain actual 内部改动，端口契约、编排器控制顺序、系统降级路径、缓存键均不变；系统 TTS 与 Media3 路径零触碰。
