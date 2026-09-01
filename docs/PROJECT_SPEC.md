# PROJECT_SPEC — 产品需求规格

> 状态：Phase 0 定稿，待评审 ｜ 版本 1.0 ｜ 日期：2026-09-01
> **本文档是本产品唯一的需求事实来源。** 其他文档（架构 / 数据库 / 引擎规格 / 测试计划）若与本文冲突，以本文为准并须先修订本文。
> 每个 FR / NFR 均有稳定编号，可被 TEST_PLAN、验收清单与提交记录直接引用。

---

## 1. 产品概述

### 1.1 定位

VocabularyBooster 是一款面向中文母语者的英语单词学习 App：

**查词 → 收藏进生词本 → 分组循环朗读 + 语音命令"会了"剔除已掌握 → 整本完成获得永久勋章。**

核心交互是"听 + 说"的沉浸式循环：App 循环朗读，用户说"会了"即剔除该词，全程几乎不需要碰屏幕。

### 1.2 产品目标

| # | 目标 |
|---|---|
| G1 | 沉浸式听写循环：分组循环朗读 + 语音命令剔除，最小化手动操作 |
| G2 | 中英双语释义 + 真实语境例句（影视 / 名人演讲 / TED / 有声书等**合法授权来源**，TTS 兜底） |
| G3 | 核心逻辑跨平台（Kotlin Multiplatform）：Android 先行，未来 iOS 复用核心、不重写 |
| G4 | 离线优先：学习主流程不依赖网络 |

### 1.3 非目标（当前版本明确不做）

- ❌ 不接入第三方词典 API / AI API（端口已预留，接入排后续 Phase，见 FR-16）
- ❌ 不接入真实影视音频资源（数据模型与来源元数据已预留，见 FR-3）
- ❌ 不实现 iOS UI（架构已预留）
- ❌ 不做云同步 / 多端数据同步
- ❌ 不做社交、打卡提醒、广告、支付

---

## 2. 术语表

| 术语 | 含义 |
|---|---|
| **Word** | 一个英语单词，全局唯一（如 `booster`），可被多个生词本复用 |
| **DefinitionEntry** | 一条释义，属于某个 Word：`PartOfSpeech + MeaningEN + MeaningCN + Example(s)` |
| **PartOfSpeech** | 词性（noun / verb / …），一个 Word 可有多个词性 |
| **MeaningEN / MeaningCN** | 同一条 DefinitionEntry 的英文释义 / 中文释义，展示顺序固定 EN → CN |
| **Example** | 例句单元：`Sentence + ChineseTranslation + Audio`，整体原子保存/播放 |
| **WordBook（生词本）** | 用户创建的单词集合，可自定义命名 |
| **派生 WordBook** | 退出学习时自动创建的新生词本（原名称 + 日期时间），只含未掌握词 |
| **Learning Session** | 一次学习会话：选定生词本 → 生成队列 → 分组循环播放 |
| **Group** | 学习队列的分组单位，默认每组 10 个 Word（可配置 `groupSize`） |
| **Queue** | 会话学习队列 = 生词本中所有**未 MASTERED** 的词，按加入顺序排列 |
| **MASTERED** | 已掌握状态：以（生词本，词）为单位记录，不可逆（v1 无"取消掌握"） |
| **Command Window（命令窗口）** | 一个 Word 播放完毕后短暂开启的语音识别窗口 |
| **Segment** | 播放最小单元（如"英文释义"“例句原文”），暂停/恢复以 Segment 为基本粒度 |
| **DictionaryProvider** | 词典数据源端口（查询词 → 完整释义结构），可插拔 |
| **TTS** | 平台语音合成（Android `TextToSpeech` / iOS `AVSpeechSynthesizer`） |

---

## 3. 功能需求（FR）

### FR-1 查询单词

用户可在 App 内查一个英语单词，查看其完整词条（词性、双语释义、例句）。

- 查询范围：本地词库（由 DictionaryProvider 填充，见 FR-16）。本地无此词 → 提示"未收录"。
- 查询不区分大小写与首尾空白（`booster` = `Booster`）。
- **验收**：查 `booster` 显示全部 DefinitionEntry；查询结果结构符合 FR-2 / FR-3；本地无结果时给出明确提示，不崩溃。

### FR-2 Word 详情展示与排序规则

Word 详情中的 DefinitionEntry 展示**必须**遵守：

1. 先按 **PartOfSpeech 分组**：同一词性的所有 DefinitionEntry **连续排列**；
2. 组间顺序、组内顺序由 `(partOfSpeechOrder ASC, definitionOrder ASC)` 决定；
3. 一个词性的全部 DefinitionEntry 展示完毕后，才进入下一个词性；
4. 同一 DefinitionEntry 内：**MeaningEN 必须显示在 MeaningCN 前面**，二者属于同一条释义，**不得**拆分为两个独立的选择/展示单元。

展示顺序示例（严格按此结构）：

```
Word: boost

verb
  ├─ DefinitionEntry 1: MeaningEN → MeaningCN → Example(s)
  ├─ DefinitionEntry 2: MeaningEN → MeaningCN → Example(s)
noun
  ├─ DefinitionEntry 1: MeaningEN → MeaningCN → Example(s)
adjective
  └─ DefinitionEntry 1: MeaningEN → MeaningCN → Example(s)
```

- **验收**：任意词条渲染后，同词性释义全部连续、无交叉；每组内 MeaningEN 恒在 MeaningCN 上方；排序键为 `(partOfSpeechOrder, definitionOrder)`。

### FR-3 Example 例句单元

Example 是原子单元：`Sentence（例句原文） + ChineseTranslation（例句译文） + Audio（音频）`。

- 来源类型 `sourceType` 必须如实标注：`REAL_MOVIE_TV`（影视）、`CELEBRITY_SPEECH`（名人演讲）、`TED`、`AUDIOBOOK`（有声书）、`LICENSED_OTHER`（其他合法授权来源）、`TTS`（合成内容）。
- 携带来源元数据：`sourceRef`（出处，如影视名 / 演讲标题）、`licenseNote`（授权说明）。
- Audio：有授权音频文件时播放原声（`audioUri`）；无音频时用 TTS 朗读 `Sentence` 兜底。
- **约束（Phase 0）**：不接入任何真实第三方音频；数据模型与字段先建好。
- **验收**：例句单元整体保存/播放（Sentence 与 ChineseTranslation 不可分开选择）；无音频文件时 TTS 兜底不报错；来源类型显示给用户。

### FR-4 生词本管理

- 创建生词本并**自定义命名**；可**重命名**；可**删除**。
- 一个 Word 可同时存在于**多个**生词本。
- 删除生词本：只删除关系数据（entries / selections / mastery），**不删除底层 Word**（见 DOMAIN_MODEL §7 所有权）。
- **限制**：以下生词本不可删除：① 已获完成勋章的本（勋章永久性，见 FR-13）；② 存在派生子本的本（血缘完整，先处理子本）。
- **验收**：创建 → 改名 → 添加词 → 删除，全流程无残留孤儿数据；同名词本允许存在（名称不唯一，以 ID 区分）。

### FR-5 收藏单词到生词本

保存 Word 时：

- 选择**一个或多个**目标生词本；
- 选择要保存的**具体 DefinitionEntry**（可多选）；
- 对每个选中的 DefinitionEntry，可独立选择**是否保存对应 Example**（Example 整体开关，不可拆分句子/译文）；
- 不选任何 DefinitionEntry → 不允许保存（提示）；
- **验收**：同一 Word 加入多个生词本成功；每本保存的 DefinitionEntry 集合可不同；底层 Word 表不产生重复行（复用）。

### FR-6 学习会话与分组

用户选择一个生词本开始学习：

1. **队列构建**：该生词本所有**未 MASTERED** 的 WordBookEntry，按 `entryOrder ASC` 排列；
2. **分组**：按 `groupSize`（默认 10）从头切分——Group 1 = 第 1–10 词，Group 2 = 第 11–20 词……最后一组允许不足 10 词；
3. **组内循环**：按组内顺序播放；MASTERED 的词被跳过；一轮播完回到组内**第一个未掌握**的词继续循环；
4. 分组在**会话开始时固化**（materialize 到 SessionWord，含 groupIndex），此后 `groupSize` 设置变更不影响本会话。
- **验收**：100 词 → 10 组；每组成员固定不漂移；第 N 组的词恒为队列第 `(N-1)×groupSize+1 … N×groupSize` 个。

### FR-7 语音命令"会了"

- 在命令窗口内（见 FR-12）识别到"会了"（或配置的别名，默认含"记住了""掌握了"）：
  1. 当前 Word 标记为 **MASTERED**（写入该生词本的掌握记录）；
  2. 从当前学习队列**移除**；
  3. **立即**继续下一个未掌握 Word。
- MASTERED 状态 v1 不可逆（无"取消掌握"入口）。
- 等价手动入口：屏幕上的"会了"按钮（无障碍要求，效果与语音完全一致）。
- **验收**：识别成功 → 状态变更 + 队列移除 + 立即切词，三步原子生效；重复对同一词说"会了"（下轮循环前）不重复计数。

### FR-8 组推进与整本完成

- 当前 Group 所有 Word 均 MASTERED → **自动进入下一 Group**（无确认弹窗）；
- 下一 Group 不存在（即最后一组完成，生词本全部词 MASTERED）→ **整本完成**：停止播放 → 触发勋章（FR-13）。
- **验收**：组边界推进准确；整本完成必须先停止播放再展示勋章，二者顺序不可颠倒。

### FR-9 退出学习与派生生词本（决策 D1/D2/D3/D4 已冻结）

**总原则（D2）**：**原 WordBook（ORIGINAL）是永久母本**，任何学习会话退出都**绝不修改**它；退出只会"派生"，永不"改写"。派生 WordBook（DERIVED）= 某一次会话退出时刻的**未掌握词快照**。

**WordBook 类型（D3）**：`type ∈ { ORIGINAL, DERIVED }`；DERIVED 本必须记录 `parentWordBookId`（母本）、`sourceSessionId`（来源会话）、`createdAt`。

点击退出时按掌握状态**三分支**裁决（D1）：

| 分支 | 条件 | 行为 |
|---|---|---|
| **A 零掌握** | `MASTERED = 0` | 保存必要的会话状态与学习历史（会话 ABANDONED）；**不创建**派生本（不产生空本）；母本完全不变 |
| **B 部分掌握** | `MASTERED > 0 AND REMAINING > 0` | 创建派生 WordBook（规则见下）；母本不变 |
| **C 全部掌握** | `MASTERED = ALL`（REMAINING = 0） | 会话标记 **COMPLETED**、停止播放、解锁勋章（FR-13）；**不创建**派生本（无未掌握词可快照） |

> 分支 C 通常已由正常学习流程（FR-8：最后一词 master 即触发完成）先行发生；退出裁决一律以数据库实时状态为准，不信任缓存计数。

分支 B 的派生规则：
- 名称 = `{母本名称} {yyyy-MM-dd HH:mm}`（24 小时制，本地时间），例：`TOEFL Core 2026-09-01 08:30`；重名追加 `-2`、`-3`…；
- `type = DERIVED`，`parentWordBookId = 母本 ID`，`sourceSessionId = 本次会话 ID`；
- 内容 = 母本当前**所有未 MASTERED** 的词；
- **只创建关系行**（复用，绝不复制 Word / DefinitionEntry / Example 底层数据）：新 `WordBook` + `WordBookEntry`（保留 entryOrder、pendingTranslation）+ 释义/例句选择关系（原样复制 includeExamples）；
- **不继承**母本的 MASTERED 状态。

**掌握状态作用域（D4，已确认）**：MASTERED 属于（WordBook, Word）二元组——同一词 `TOEFL → MASTERED` 与 `GRE → NOT_MASTERED` 互不影响；派生本从"未掌握"全新开始。

- **验收**：三分支各自行为正确；派生后母本词条数不变；派生本词条数 = 母本未掌握数；Word / DefinitionEntry / Example 表行数不变；派生本 type / parentWordBookId / sourceSessionId 完整；派生本的释义/例句选择与母本逐字一致。

### FR-10 播放内容配置

播放内容六项**独立开关**，全局设置（对所有会话生效）：

| 开关 | 控制的 Segment |
|---|---|
| Word Pronunciation（单词发音） | 词的读音（v1：TTS 朗读单词；`audioUri` 字段为未来授权音频预留） |
| Word Spelling（单词拼写） | 逐字母朗读拼写（如 `b-o-o-s-t-e-r`） |
| Meaning EN（英文释义） | 每个选中 DefinitionEntry 的 MeaningEN |
| Meaning CN（中文释义） | 每个选中 DefinitionEntry 的 MeaningCN |
| Example（例句） | 例句原声/原文朗读（含例句音频） |
| Chinese Translation（例句译文） | 例句的 ChineseTranslation 朗读 |

- 一个 Word 的播放序列 = 词级 Segment（发音、拼写）→ 依次每个**选中的** DefinitionEntry（EN → CN → Example(s)：Sentence → Translation）。
- 开关变更**立即生效**（下一个 Segment 起应用，不中断当前 Segment）。
- **验收**：任意开关组合的播放序列与 AUDIO_ENGINE_SPEC 的分段表一致；六项全关时给出提示并拒绝开始会话。

### FR-11 播放控制

| 控制 | 语义 |
|---|---|
| **Play** | 开始播放（新会话从队列头开始；已有 ACTIVE 会话则从持久化位置恢复） |
| **Pause** | 暂停，冻结当前位置 `(wordIndex, segmentIndex, offsetMs)` |
| **Resume** | 从冻结位置继续——**绝不从头重播整个 Word** |
| **Next** | 立即跳到队列下一个未掌握 Word（**不**标记 MASTERED） |
| **Replay** | 重播当前 Word（从该 Word 的第一个 Segment 开始） |
| **Exit** | 退出会话，触发 FR-9 流程（若整本未完成） |

- **验收**：Pause→Resume 恢复到同一 Segment 的同一进度（文件音频恢复到 offsetMs；TTS Segment 允许整段重读——系统 TTS 无 seek 能力，属已确认的平台限制，但**不得**回到 Word 开头）；Next/Replay 不改变任何词的掌握状态。

### FR-12 语音识别交互协议

- **TTS / 音频播放期间：SpeechRecognizer 保持关闭**——杜绝 TTS 被自识别；
- 当前 Word 的**最后一个 Segment 播放完成** → 静默 `guardDelayMs`（默认 300ms，防尾音串扰）→ 打开命令窗口 `commandWindowMs`（默认 4000ms，可配置）；
- 窗口内：识别到命令 → 执行并立即关闭窗口；超时/无有效结果 → 关闭窗口，自动进入下一个未掌握 Word；
- v1 命令集：**仅 `MASTERED`**——"会了"（别名默认："记住了"、"掌握了"，可在设置扩展）；
- 预留命令枚举（v1 只定义、不实现）：`PAUSE`"暂停" / `RESUME`"继续" / `NEXT`"下一个" / `REPLAY`"再来一次" / `EXIT`"退出"；
- 命令文本解析为纯 Kotlin 组件（跨平台复用），识别引擎由平台注入。
- **验收**：TTS 播放中对着麦克风说任何内容均无效果；窗口期说"会了"触发 FR-7；别名可配置生效。

### FR-13 完成勋章

- **触发**：某 WordBook 全部 Word 均 MASTERED（FR-8）；
- **行为**：停止播放 → 全屏勋章展示 → 永久保存（用户不可删除）；
- **幂等**：同一 WordBook 只授予一次（唯一约束）；
- **数据**：勋章记录留存 `bookName` 与 `wordCount` 快照（防止后续改名/派生影响勋章真实性）；
- 未来扩展（事件架构已预留，v1 不实现）：连续学习天数、累计掌握词数、完成组数、完成生词本数。
- **验收**：整本完成必得勋章且只得一次；重启 App 后勋章仍在；勋章界面可回看已获列表。

### FR-14 TXT 导入

- 用户选择 TXT 单词表文件 + **目标生词本**（可选已有本，或借此新建）；
- 编码支持：**UTF-8、UTF-8 BOM、GBK/GB18030**（自动检测：BOM → UTF-8 严格校验 → GB18030 兜底）；
- 行格式：① 每行一个 Word；② `Word + 分隔符 + 译文`（分隔符：Tab / 空格 / 逗号 / 分号）；忽略空行与首尾空白；
- 去重：文件内大小写不敏感、首见保留；与目标生词本已有词去重；与全局词库同词**复用已有 Word 行**（不重复建词）；
- 大文件：**流式**处理（逐行读，不整文件载入内存），进度回调，**可随时取消**；导入原子性：取消/失败不残留半成品；
- 结果报告：`新增 / 复用已有词 / 文件内重复 / 生词本内已存在 / 无效行` 计数；
- 导入的词 v1 尚无释义（等待 DictionaryProvider 填充），播放时仅发单词音 + 拼写，不影响学习流程。
- **验收**：10 万行 GBK 文件导入不 OOM、可取消；各编码样例解析正确；报告计数与实际一致。

### FR-15 设置

| 设置项 | 默认值 |
|---|---|
| `groupSize`（每组词数） | 10 |
| `commandWindowMs`（命令窗口时长） | 4000 |
| 六项播放开关（FR-10） | 全开 |
| TTS 语速 / 音调 | 1.0 / 1.0 |
| "会了"命令别名 | 会了、记住了、掌握了 |

- 设置变更即时持久化；`groupSize` 变更只对**新会话**生效（FR-6 第 4 条）。

### FR-16 词典数据源可插拔

- 定义 `DictionaryProvider` 端口：`lookup(word) → Word 完整词条（DefinitionEntry / Example / 来源元数据）`；
- v1 实现：**本地种子数据**（随 App 打包的 JSON 词库）；
- 未来实现：第三方词典 API、AI 生成内容、授权音频内容——均以新增 Provider 实现类接入，**不改核心模型**；
- 所有来源必须填写 `sourceType` / `sourceRef` / `licenseNote`（合规追溯）。
- **验收**：替换 Provider 实现（本地 JSON → 测试桩）无需改动任何引擎代码。

---

## 4. 非功能需求（NFR）

| # | 需求 | 指标 / 说明 |
|---|---|---|
| NFR-1 | **离线优先** | 学习主流程（查本地词、学习、播放、掌握、勋章、导入）100% 离线可用 |
| NFR-2 | **性能** | 冷启动 ≤ 3s；Segment 间切换间隙 ≤ 500ms（目标 300ms）；命令窗口在 Segment 结束后 ≤ 300ms 打开；10 万行导入 ≤ 60s 且可取消 |
| NFR-3 | **可靠性** | 会话状态持久化，进程被杀后可恢复到词/段粒度；DB 写入事务化；导入原子；DB 迁移有回归测试 |
| NFR-4 | **隐私** | 语音仅在命令窗口内采集，命令文本本地解析、不上传；若平台识别引擎存在云端链路（Android SpeechRecognizer 可能走 Google 云），须在隐私声明中披露，并优先尝试离线识别（`EXTRA_PREFER_OFFLINE`） |
| NFR-5 | **内容合规** | 音频素材仅限 TTS 或**合法授权来源**；`sourceType / sourceRef / licenseNote` 强制留存；无授权素材不得上线 |
| NFR-6 | **跨平台可移植** | shared 模块 `commonMain` 禁止 import `android.*` / `java.*`（静态检查强制）；学习/播放/导入/勋章引擎为纯 Kotlin，JVM 可 100% 单测；iOS 仅实现平台 actual，**零核心重写** |
| NFR-7 | **国际化** | UI 默认简体中文；词条内容天然双语（EN/CN） |
| NFR-8 | **无障碍** | 所有语音操作均有手动等价按钮；播放内容同步显示文本；基本 TalkBack 可用 |
| NFR-9 | **可测试性** | 领域与引擎层行覆盖率 ≥ 85%；引擎测试不依赖模拟器 |

---

## 5. 约束

| # | 约束 |
|---|---|
| C1 | **Phase 纪律**：任何业务代码须在 ROADMAP 对应 Phase 批准后编写；Phase 0 产出仅为规格与骨架 |
| C2 | **数据模型基准**：`Word → DefinitionEntry → (PartOfSpeech, MeaningEN, MeaningCN) → Example(Sentence, ChineseTranslation, Audio)` 层级不可违反 |
| C3 | Phase 0 禁止接入第三方词典 API / AI API / 真实音频素材 / 完整播放器 |
| C4 | 开发机为 Windows（无 macOS）：iOS 构建在 iOS 阶段另行解决（实体 Mac 或 CI macOS runner） |
| C5 | 版本基线：minSdk 26 / compileSdk 36（Phase 1 按当时 SDK 最终确定）；iOS 15+ |

---

## 6. 需求追踪矩阵（原始需求 → 规格）

| 原始需求 | 覆盖 |
|---|---|
| 查英语单词 | FR-1 / FR-16 |
| Word 含多 DefinitionEntry；每条含 POS / MeaningEN / MeaningCN / Example / ChineseTranslation | FR-2 / FR-3 → DOMAIN_MODEL §2 |
| Example 来源（影视 / 演讲 / TED / 有声书 / 授权 / TTS） | FR-3 / NFR-5 |
| 一个 Word 多个 POS；同 POS 释义连续排列；POS 完成后进入下一个 | FR-2 |
| MeaningEN 显示在 MeaningCN 前；同属一条 DefinitionEntry | FR-2 |
| Example + ChineseTranslation 属同一例句单元 | FR-3 / FR-5 |
| 生词本：创建 / 命名 / 重命名 / 删除 / 一词多本 / 保存时选 DefinitionEntry / Example 可选 | FR-4 / FR-5 |
| 学习模式：选生词本、每组默认 10 词、组内循环 | FR-6 |
| "会了" → MASTERED → 移出队列 → 下一个；组完成 → 下一组 | FR-7 / FR-8 |
| 退出：剔除已 MASTERED，另存"原名称+日期时间"新本，复用不复制 | FR-9 |
| 播放配置六项独立开关 | FR-10 |
| Play / Pause / Resume / Next / Replay / Exit；Pause/Resume 保持状态 | FR-11 |
| 语音控制 v1 仅"会了"，预留暂停/继续/下一个/再来一次/退出；TTS 期间不识别 | FR-12 |
| 完成勋章：停止播放 / 永久保存 / 未来扩展 | FR-13 |
| TXT 导入：编码 / 格式 / 去重 / 大文件 / 目标生词本 | FR-14 |
| 跨平台：KMP 共享核心；Compose / SwiftUI；核心不依赖平台 API | NFR-6 → ARCHITECTURE |
| 数据模型基准与排序规则（partOfSpeechOrder ASC, definitionOrder ASC） | FR-2 → DOMAIN_MODEL §4 |

---

## 7. 变更管理

- 需求变更须先修改本文档（含版本号与变更记录表），再同步受影响文档；
- 每条变更需标注影响的 FR/NFR 与下游文档（DOMAIN_MODEL / DATABASE_SCHEMA / 引擎规格 / TEST_PLAN）；
- Phase 0 期间发现的**开放问题**统一登记在 docs/reports/PHASE_0_REPORT.md §开放问题，逐条决策后回写本文档。

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-01 | 冻结 D1–D4：退出三分支；WordBook.type（ORIGINAL/DERIVED）+ parentWordBookId + sourceSessionId；母本不变性；掌握作用域=（生词本,词） |
