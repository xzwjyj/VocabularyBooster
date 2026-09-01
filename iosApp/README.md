# iosApp — iOS 应用（未来阶段，Phase 1 骨架）

SwiftUI 应用层。当前开发机为 Windows（无 macOS / Xcode），本模块在 iOS 阶段另行启用。

**工程门控（Phase 1 起生效）**：`settings.gradle.kts` 仅在 macOS 宿主 `include(":iosApp")`；
`shared` 的 `iosArm64` / `iosSimulatorArm64` targets 与 `iosMain` 源集同样仅 macOS 启用
（Kotlin/Native iOS 目标的链接需要 Apple 工具链）。Windows 构建自动跳过，无需任何配置改动。

## 规划要点（详见 docs/ARCHITECTURE.md「iOS 复用策略」）

- 依赖 shared 模块产出的 XCFramework（通过 Swift Package 集成）
- 平台实现：`AVSpeechSynthesizer`（TTS）、`AVPlayer`（例句音频）、`SFSpeechRecognizer`（语音命令）、SQLDelight iOS 驱动
- **不重写任何核心逻辑**：学习引擎、播放编排、导入、勋章、设置全部直接复用 shared 模块
- Flow 与 SwiftUI 交互：通过 kotlinx.coroutines Flow → AsyncStream / KMP-NativeCoroutines 桥接
