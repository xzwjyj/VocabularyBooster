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

- 来源类型 `sourceType` 必须如实标注：`REAL_MOVIE_TV`（影视）、`CELEBRITY_SPEECH`（名人演讲）、`TED`、`AUDIOBOOK`（有声书）、`LICENSED_OTHER`（其他合法授权来源）、`TTS`（合成内容）、`TATOEBA`（Tatoeba 语料库，CC-BY 2.0 FR，v1.15）。
- 携带来源元数据：`sourceRef`（出处，如影视名 / 演讲标题）、`licenseNote`（授权说明）。
- Audio：有授权音频文件时播放原声（`audioUri`）；无音频时用 TTS 朗读 `Sentence` 兜底。
- **约束（Phase 0）**：不接入任何真实第三方音频；数据模型与字段先建好。
- **注记（v1.14）**：「每条释义至少 1 例句」为**精选种子内容契约**（Phase 2 DoD）；全量词典词条（FR-18）例句可为空——无例句可勾选，释义照常保存/播放。
- **注记（v1.15）**：全量词典词条的例句（如有）来自 Tatoeba 对齐（FR-18），挂首释义、中文译文可空（无译文时只显示英文原句）；例句仍为原子单元，勾选/播放语义与其它来源一致。
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
- 对每个选中的 DefinitionEntry，可进一步勾选要保存的**具体 Example**（多选；Example 自身仍为原子单元——句/译文/音频不可拆分，见 FR-3；一条都不选 = 只保存释义）；
- 不选任何 DefinitionEntry → 不允许保存（提示）；
- 重存同一词到同一本 = **替换该本的选择集**（保留队列位置 entryOrder 与导入译文 pendingTranslation）；修改已保存选择的**正规入口是本详情「编辑」（FR-17，v1.13）**——保存对话框不预填已存在选择；
- **验收**：同一 Word 加入多个生词本成功；每本保存的 DefinitionEntry 集合可不同；每条释义保存的 Example 集合可不同；底层 Word 表不产生重复行（复用）。

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
- **只创建关系行**（复用，绝不复制 Word / DefinitionEntry / Example 底层数据）：新 `WordBook` + `WordBookEntry`（保留 entryOrder、pendingTranslation）+ 释义选择关系 + 例句选择关系（均原样复制）；
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
- v1 命令集：**仅 `MASTERED`**——"会了"（别名默认："记住了"、"掌握了"；用户可编辑别名归 Phase 8 设置页实现，Phase 5 冻结为代码常量 {会了, 记住了, 掌握了}——裁决 D4，2026-09-12）；
- 预留命令枚举（v1 只定义、不实现）：`PAUSE`"暂停" / `RESUME`"继续" / `NEXT`"下一个" / `REPLAY`"再来一次" / `EXIT`"退出"；
- 命令文本解析为纯 Kotlin 组件（跨平台复用），识别引擎由平台注入。
- **识别引擎（2026-09-14 需求变更，裁决 E1/E2）**：命令识别不依赖单一系统服务——平台系统识别服务（`SpeechRecognizer`）存在时优先使用（行为不变）；**缺席时使用 App 内置离线引擎**（Vosk + 中文小模型打包进 APK），保证中国大陆发售的无 GMS / 无标准识别服务机型（华为/荣耀/小米/OPPO/vivo 等）语音命令同样可用。内置引擎**纯本地识别，无云端链路**（NFR-1/NFR-4）；命令解析/编排/掌握语义（D2–D5）零改动。**运行时回退（裁决 E3，2026-09-14）**：系统服务探测在场但窗口内发生可用性级失败（厂商助手可注册了不可用的 `RecognitionService`——实测 vivo V2436A 上蓝心 Copilot 唤醒服务可解析、绑定后 46ms 即硬失败，"可解析" ≠ "可用"）时，**当场回退内置引擎完成同一窗口剩余预算**（窗口总时长不变），并在本进程内降级系统引擎（后续窗口直连内置引擎，不反复试探；重启后重新探测）。系统服务健康设备硬失败从不发生，行为零变化。**响应看门狗（裁决 E4，2026-09-14 二次真机诊断）**：坏服务另有静默死法（startListening 后零回调零错误挂至窗口超时，与"用户未说话"不可区分，实测吞掉用户命令词）→ 系统 actual 内置看门狗：1500ms 零回调（健康服务安静时亦持续回调）即判死、按可用性级失败上报，同样触发窗口内回退；GMS 健康服务零误触。
- **近音兜底（2026-09-16 需求变更，用户裁决）**：内置离线引擎（Vosk 中文小模型）的**近音误听按命中处理**——识别最终文本命中别名的近音兜底白名单（如「坏了」「换了」「回来」及断字截断「会」→ "会了"）时，同样触发 FR-7 掌握。白名单按别名归集、证据驱动增补（真机日志），Phase 8 随别名设置化一并扩展；白名单外仍精确匹配（无关短语不误杀，D5 红线不变）。详见 AUDIO_ENGINE_SPEC §7 v1.7（`NEAR_HOMOPHONES_BY_ALIAS`）。
- **首/末字近音结构规则（2026-09-17 需求变更，用户裁决）**：对默认别名「会了」，识别最终文本**首字 ∈ 会近音字集（会/坏/换/回/惠/汇）或末字 ∈ 了近音字集（了/啦/咯/来/呀——「呀」为 2026-09-18 vivo 轻声实证「会了」→「呀」元音 a/e 混淆增补，用户裁决）即判定命中**（`STRUCTURAL_NEAR_CHARS_BY_ALIAS`）——结构级兜底连续近音误听（截断「会」「坏」、h 声母丢失「了了」、「会了吗」「…会了」句式），替代逐形白名单增补。不误杀守卫收窄：首尾字均不在近音集的无关短语（「你好」「hello」）与空文本仍不命中；繁体「會了」（末字=了）随本裁决转为命中；规则与白名单同样按别名归集（自定义别名不适用）。详见 AUDIO_ENGINE_SPEC §7 v2.0/v2.3。
- **轻声/气音支持（2026-09-18 需求变更，用户裁决）**：内置引擎弃用库 `SpeechService`（其 VOICE_RECOGNITION 源走 OEM 语音处理链，实测 vivo 04:41 把轻声/气音整窗抹成纯静默——录音流正常、识别器零回调），改为**自管录音管线**：`AudioRecord`（**MIC 原始源**，绕开 OEM 处理）50ms 块读入 + ①块 RMS **自适应预增益**（归一目标电平，上限 10×，削波保护）+ ②`EndpointerMode.SHORT`（模型 VAD 灵敏度最高档）+ ③**自端点强制终局（双触发）**：持续语音能量后 500ms 尾静默，或模型已解出非空假设 700ms 无改进（假设稳定——不依赖声学门限，环境底噪持续越过能量门限时仍可裁决），任一触发即 `getFinalResult()` 冲刷假设（不依赖模型 VAD 自行决断；冲刷后识别器重置、同窗可继续听第二句；partial 仅作冲刷裁决信号，命令语义仍只认 final 文本）。**轻声识别为尽力而为能力**：受小模型对无浊音语音的解码上限约束，验收口径（M2）仍为正常音量明确发音；轻声实测效果以真机日志证据登记。详见 AUDIO_ENGINE_SPEC §8 v2.1/v2.2。
- **验收**：TTS 播放中对着麦克风说任何内容均无效果；窗口期说"会了"触发 FR-7；别名可配置生效（该子句已按裁决 D4（2026-09-12）延期至 Phase 8：Phase 5 验收口径 = 固定代码别名 会了/记住了/掌握了 均可命中，用户可编辑别名随 FR-15 设置页交付，见 AUDIO_ENGINE_SPEC §7）；无系统识别服务、或系统服务存在但不可用的国行机型，经**内置离线引擎**（含 E3 窗口内回退）同样满足本验收（E1/E3，2026-09-14，AUDIO_ENGINE_SPEC §8）；近音误听（如「坏了」）经近音兜底白名单同样命中（2026-09-16，AUDIO_ENGINE_SPEC §7 v1.7）。

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
| 发音口音（FR-22） | 美音（EN_US；可选英音 EN_GB） |
| "会了"命令别名 | 会了、记住了、掌握了（**固定代码常量**——可配置性再延后，2026-09-18 裁决） |

- 设置变更即时持久化；`groupSize` 变更只对**新会话**生效（FR-6 第 4 条）。
- **验收**（Phase 8）：五项设置在设置页可查看、变更即时持久化、重启后保持；`groupSize` 变更不影响进行中会话；六项播放开关全关时设置页不拦截（会话开始时引擎裁决拒绝，LE spec §3）。

### FR-16 词典数据源可插拔

- 定义 `DictionaryProvider` 端口：`lookup(word) → Word 完整词条（DefinitionEntry / Example / 来源元数据）`；
- v1 实现：**本地种子数据**（随 App 打包的 JSON 词库）；v1.1（Phase 8.6）：复合实现 = 精选种子（JSON，例句内容源）优先 → **全量离线词典**（ECDICT 转只读 SQLite 随包，FR-18）兜底，端口契约不变；
- 未来实现：第三方词典 API、AI 生成内容、授权音频内容——均以新增 Provider 实现类接入，**不改核心模型**；
- 所有来源必须填写 `sourceType` / `sourceRef` / `licenseNote`（合规追溯）。
- **验收**：替换 Provider 实现（本地 JSON → 测试桩）无需改动任何引擎代码。

### FR-17 生词本词条选择编辑（v1.13，Phase 8.5）

对已保存进生词本的词，编辑其释义/例句选择集（增删并存）：

- 本详情词条行提供「编辑」入口 → 编辑界面渲染该词**全部词库释义与例句**（与查词详情同源、同排序规则 FR-2），**预填当前已保存的选择**；
- 保存 = **单事务整组替换**该（生词本,词）的两类选择行（WordBookEntryDefinition / WordBookEntryExampleSelection）；校验镜像 FR-5（释义属词、例句属释义）；
- **至少保留 1 条释义**（全不勾 → 拒绝保存并提示；不需要该词请用「移除」，FR-4）；
- **不重建词条行**：entryOrder（队列位置）/ addedAt / pendingTranslation 原样保留——编辑绝不改变学习队列；
- 掌握状态（WordMastery / SessionWord）与编辑正交，零接触（D4 掌握作用域）；
- 进行中会话：编辑对该词的生效时点 = 该词**下一次开始播放**（段构建时重读选择，粒度同 FR-10 开关）；当前播放段不中断；
- 导入词（无词库释义，FR-14）：编辑界面显示空态提示"该词暂无词库释义可编辑"；
- **验收**（Phase 8.5）：编辑预填与当前选择一致；增删释义/例句保存后重开编辑界面一致；编辑不改词条队列位置；已掌握词编辑后掌握保持；全不勾释义被拒（中文提示）；进行中会话该词下一次播放分段内容与新选择一致。

### FR-18 全量离线词典与按需导入（v1.14，Phase 8.6；用户裁决：全量 77 万词）

查词收录范围扩至全部英语单词与短语（bug list 2026-09-19 第 1 项）：

- 数据源：**ECDICT（skywind3000，MIT 许可）** full 版 ~77 万词条（含数据源收录的短语/多词条目），经离线工具转换为只读 SQLite 随 APK 打包（`assets/dict/`；词典产物 >100MB 可由工具再生成，不入 git）；
- 转换映射（一次性离线完成）：中文译义按行拆词性前缀 → DefinitionEntry（vt./vi./aux.→verb、a.→adjective、ad.→adverb、无前缀行→`other`@90——DOMAIN_MODEL §3.1 逃逸口）；`meaningEN` 取数据源英义列、缺失存空串；音标取数据源 phonetic 列；
- **例句增强（v1.15，Tatoeba 对齐）**：全量词典资产追加 `examples` 列（`[["英文原句","中文译文"],…]`，Tatoeba CC-BY 2.0 FR）；匹配 = 句子词 n-gram（n=1..5，词与短语统一覆盖，含 `-`/`'` 连接变体），**带中文译文的候选句优先、次短句优先**，≤4 条/词；译文经 Tatoeba links 句对配对，未配对句 v1.16 起 opus-mt-en-zh 机器翻译兜底（**例句译文覆盖率 100%**，人译优先机译兜底），schema 仍允许空串（无译文只显原句）；语料级**粗口/成人内容过滤**（词边界屏蔽表）；例句无词性归属 → 挂**首释义**（与精选种子的"逐释义例句"不同，属数据源粒度限制）；资产带 `PRAGMA user_version` 版本（含数据修订，数据重建必须递增），旧设备缓存据此自动重拷；
- **例句回填（v1.15；v1.17 扩音标；v1.18 扩英音）**：例句增强前已导入的词条，`lookup` 命中本地库但零例句（或 Tatoeba 例句译文为空）时 → 从词典源补齐例句/译文（幂等、单事务，同句去重、只补不删）→ 重读返回；v1.17 同路径扩**音标回填**——本地音标缺失（ipaAm NULL）且词典源有值 → 只补空缺（绝不覆盖已有值，零释义 TXT 导入词同样适用）；v1.18 再扩**英音回填**（ipaBr NULL 同口径只补空缺），回填闸门 = 美音缺失 ∨ 英音缺失 ∨ 零例句 ∨ Tatoeba 译文空；
- **音标增强（v1.18，ipa-dict 对齐）**：全量词典资产并入 ipa-dict（open-dict-data，MIT）音标——en_UK 6.4 万条 → 新增 `ipaBr` 列（英音，真 IPA 记法；双音标齐备 59,387 条），en_US 12.6 万条 → **补 `ipa` 空缺**（只补空缺、绝不覆盖；美音覆盖 21.8 万 → 25.1 万词）；`PRAGMA user_version=5`（数据重建递增铁律）；ipa-dict 只收单词——短语恒无英音属预期；ECDICT 既有 phonetic（类 DJ 记法、口音归属含糊）维持现状存 `ipaAm` 不重标注，两种记法同屏并存（取舍见 ADR-010）；
- 查询路径：`WordRepository.lookup` 先查本地库（用户已存词条优先，FR-5 复用原则），未命中 → 复合 DictionaryProvider（精选种子优先 → 全量词典兜底）→ **命中即按需导入该词**（幂等，与种子同代码路径）→ 返回词条；两级均未命中 → "未收录"（FR-1 口径不变）；
- 首启精选种子导入（50 词，含例句）维持不变；
- 全量词典词条与种子词条同构入库，保存/编辑/学习/播放全链路零特殊分支（FR-17 编辑入口对其同样适用）；meaningEN 为空串的释义渲染时只显示中文（展示不变量 I-6"EN 恒在 CN 前"不受影响——EN 不存在时不渲染空行）；
- **验收**（Phase 8.6）：精选 50 词外的单词与数据源收录短语可查到并正常收藏/学习/播放；查过的词重复查询幂等（不重复建词）；未收录词仍提示"未收录"；APK 体积增量符合裁决口径（+~45MB）。

### FR-19 TTS 音色选择（v1.14，Phase 8.6；用户裁决：本地音色枚举）

播放语音音色可配置（bug list 2026-09-19 第 2 项）：

- `SpeechSynthesizer` 端口枚举当前引擎可用音色（英语 en-US / 中文 zh-CN 分列，剔除需联网的音色）；设置页「语音音色」区提供两个音色选择器 + 「打开系统语音设置」直达入口（下载更高自然度音色）；
- 选择经设置端口持久化（`ttsVoiceEn` / `ttsVoiceZh` 两键，L6 加法扩展）；逐段朗读前应用所选音色；未选择或所选音色已不可用（如被卸载）→ 回退语言级默认（既有 `setLanguage` 行为零回归）；
- 口径（诚实约束）：**自然度上限 = 设备已安装音色**；不引入云端语音（离线 MVP 决策）；引擎级切换不在 v1 范围；
- **验收**（Phase 8.6）：设置页可枚举并选择英/中音色，重启保持；所选音色实际生效（朗读听感变化）；未选/失效回退默认不报错；语速/音调（FR-15）与音色叠加生效。

### FR-20 学习统计（v1.14，Phase 8.6）

勋章 Tab 增加学习统计（bug list 2026-09-19 第 3 项）：

- 勋章页顶部「统计」卡：**今日学习词数 / 今日复习词数 / 累计学习词数（不含复习）/ 今日学习时长 / 累计学习时长**；点击进入统计详情页；
- 统计详情页：按 **日（近 30 天）/ 月（近 12 月）/ 年（全部）** 切换查看**学习词数**与**学习时长**两组柱状图（App 内自绘，无三方图表库）；
- 口径：**学习** = 当日**首次掌握**的去重词数（跨生词本去重）；**复习** = 当日掌握事件中、今日之前已掌握过的词；**时长** = 当日结束会话（COMPLETED/ABANDONED，endedAt 非空）的时长合计，按会话结束日归集；崩溃未恢复（endedAt NULL）的会话不计时长；跨午夜会话整体归入结束日；
- 实现口径：统计聚合基于 SessionWord 掌握事件 + LearningSession 时长（**query-only，零迁移**）；日界按设备本地时区（分桶在共享层完成，注入 TimeZone 可测）；
- **验收**（Phase 8.6）：学新词后今日学习 +1、重复学习已掌握词记复习不记学习；时长随会话累计；日/月/年聚合与明细一致；空库显示零值不崩溃。

### FR-21 学习会话词内容展示与播放高亮（v1.17，bug list 2026-09-20 第 5 项）

学习会话界面显示当前词完整信息，播放内容实时高亮：

- 词头（词文本 + 组号）下方展示**音标**（美音/英音双音标，FR-22 有则双显、无则单显、全缺不渲染空行）与滚动卡片列表：**释义卡**（词性 + MeaningEN + MeaningCN 同卡不可拆分，FR-2）+ **例句卡**（句 + 中文译文原子单元，FR-3，缩进挂属释义下方，空译文不渲染）；卡片数据 = 该词本次会话播放内容（PlaybackContent 选中项——与 FR-10「只播被选中的」同源，面板与播放所见即所听）；
- **高亮联动**：当前播放段（Segment.owner）命中的卡片高亮（主题容器色 + 边框），段内正在朗读的行（英文释义/中文释义/例句/例句译文，按段类型）加粗；发音/拼写段（owner=Word）高亮词头；命令窗口期无高亮（卡片平铺）；
- 暂停态保持冻结段高亮（Paused 携带最近 Playing 段快照——暂停位 = 该段，ADR-09 同口径）；换词异步重载卡片（按 wordId 去重，加载间隙收起不闪旧词）；
- 展示纯只读：不引入第二播放状态源（编排器 PlaybackState 唯一事实），卡片数据不参与任何播放/掌握裁决；
- **验收**（2026-09-20 vivo 实测）：播放推进时高亮卡片随段实时切换（释义段→释义卡、例句段→例句卡、词头段→词头变色）；暂停高亮保持；换词卡片重载；音标回填（FR-18）后面板显示音标；卡片列表可滚动、控制条恒在底部。

### FR-22 双音标显示与发音口音设置（v1.18，用户 2026-09-20 提出）

- **双音标显示**：学习会话面板（FR-21）、查词结果行与查词详情头部显示 `US /…/  UK /…/`——**有则双显、无则单显、全缺不渲染空行**（短语/长尾无英音属数据源覆盖预期）；斜杠兼容库内两种存法（种子带斜杠、词典导入不带）；
- **发音口音设置**：设置页「朗读」区新增「发音口音」二选一（**美音（缺省）/ 英音**），即时持久化（`settings.ttsAccent`，L6 加法扩展——缺键默认美音、损坏抛 RepositoryValidationException、ZH_CN 非口音选项拒写）；只影响英文段（单词发音/拼写/英文释义/例句原文）TTS 口音 en-US / en-GB，**中文段不受影响**；生效粒度 = 下一英文 Segment（编排器游标懒读，对齐 L4 开关语义）；
- **en-GB 语音缺失回退**：口音是用户偏好而非双语硬要求——设备无 en-GB 语音时**回退 en-US 继续播，不进 Paused(error)**（回退也缺失才硬失败；zh-CN 缺失仍硬失败，TC-AE-21 语义不变）；设置页在选中英音且设备无英音音色时提示「设备未安装英音语音，将回退美音」（探测复用 FR-19 availableVoices，EN_GB 枚举只列真英音音色）；
- **FR-19 音色与口音正交**：已选音色仅在其 locale 与段语言一致时应用，不一致走 `setLanguage`（失效回退语义自然覆盖）；
- 种子词库 59 词补全英音音标（ipaBr 全带，类 DJ 记法与既有美音同风格）；
- **验收**：常见单词双音标同显；无英音词只显美音不渲染空行；切换英音后英文段朗读口音变化（装机实测）；无英音语音设备回退美音且会话不中断；口音设置重启保持；资产 v5 设备旧缓存自动重拷。

### FR-23 英文段端内实时神经 TTS（v1.19，用户 2026-09-20 提出，批准 SPEC_CHANGE_REQUEST_PIPER_TTS v2）

- **动机**：系统 TTS 朗读英文段机械感明显，且部分设备（vivo）无 en-GB 厂商语音包——FR-22 口音设置只能回退美音。端内实时神经合成一并解决自然度与英音可用性（v1 预录方案装机实证无差异后撤销：词表命中与实际播放内容覆盖错位，且预录天然无法覆盖长尾词——实时合成对任意文本生效）；
- **架构**：`LangRoutedSpeechSynthesizer`（commonMain 组合实现）按段语言路由——**EN_US / EN_GB → 神经引擎**（Android actual `SherpaOnnxSpeechSynthesizer`：sherpa-onnx v1.13.8 + Piper VITS medium 模型 en_US lessac / en_GB alan 各 ~63MB，随包 assets——模型 / tokens 单文件直读，espeak-ng-data 目录树一次性解包至 filesDir（native 只认真实路径））；**ZH_CN → 系统 TTS 原路径零变化**；
- **口音 = 模型**：FR-22 消费时映射不变（段 lang=EN_GB → 英音模型）——设备无厂商英音语音包也生效（根诉求解决）；同时最多驻留一个英文模型（RAM 约束），换口音 = 先载新成功后释放旧，生效粒度仍为下一英文 Segment；
- **合成与播放**：整段一次合成后分块直写 AudioTrack（STREAM、阻塞写即背压、阻塞写在 IO 线程；轨道缓冲 16KB + 不足一缓冲的微型段补静音至超缓冲——部分 OEM mixer 对未填满缓冲的轨道不启动消费，vivo 装机实证）；段文本短 + WAV 磁盘缓存弥补首播延迟；流式回调方案因 JNI 精确签名在本工具链不可达而弃用（native 按精确 `invoke([F)Ljava/lang/Integer;` 查回调，Kotlin lambda 经 D8 脱糖只剩擦除签名 → NoSuchMethodError 全进程崩，该桥只能以 Java 源声明而 KMP androidMain 不支持 Kotlin→Java 同模块解析）；rate → 语速 clamp 0.5–2.0；**pitch 不生效**（VITS 无基频参数，平台限制——设置无 pitch 暴露，无用户可感知面）；暂停/恢复沿用 ADR-09 段语义（stop 立停、在途段 completed=false、恢复重读整段；stop 期在途合成后台自然完成仅浪费少量 CPU）；
- **磁盘缓存**：合成结果按（lang | speed | text）SHA-256 落 `filesDir/tts-cache/*.wav`（16-bit PCM），命中免合成直接直播；LRU（mtime）上限 100MB（构造时与写后清理）；
- **降级**：神经引擎非 READY（模型缺席 / 加载失败 / 预热超时）或 speak 抛非取消异常 → **EN 段当场回退系统 TTS** 同段播完并进程内粘滞（会话不中断，行为回到 FR-19/FR-22 系统路径）；`CancellationException` 照常重抛绝不降级（暂停/退出是调用方生命周期非引擎故障——Vosk 取消被吞回归先例）；readiness 对 UI 投影系统引擎（既有界面零变化，INITIALIZING 期间 EN 段先走系统、预热完成自动切换）；
- **资产与体积**：模型、native 库（arm64-v8a）与 vendored Kotlin API 经 `tools/tts-neural/fetch_deps.py` 可再生产出（espeak-ng-data 双模型逐字节相同共享一份）；APK 196MB → ~324MB（用户已批准）；
- **许可**：sherpa-onnx Apache-2.0；Piper 模型 MIT；均随包分发合规；
- **验收**：任意单词（不限词表，如长尾词）发音 / 拼写 / 英文释义 / 例句原文全部为神经自然语音；切换英音后下一英文段为英音；中文段（中文释义 / 例句译文）仍系统 TTS 零变化；同段第二次播放命中缓存（段间隙可感缩短）；杀进程重进预热正常；神经不可用时英文段自动回系统 TTS 不中断；既有门禁全绿（jvmTest / testDebugUnitTest / detekt / checkPlatformBoundaries / assembleDebug）。

### 应用图标（v1.14，Phase 8.6；bug list 第 4 项，打磨项）

自定义自适应应用图标（矢量前景/背景 + Android 13+ 单色主题图标），替换系统默认图标；无行为需求，交付记录见 ROADMAP Phase 8.6 与 ADR-007。

---

## 4. 非功能需求（NFR）

| # | 需求 | 指标 / 说明 |
|---|---|---|
| NFR-1 | **离线优先** | 学习主流程（查本地词、学习、播放、掌握、勋章、导入）100% 离线可用 |
| NFR-2 | **性能** | 冷启动 ≤ 3s；Segment 间切换间隙 ≤ 500ms（目标 300ms）；命令窗口在 Segment 结束后 ≤ 300ms 打开；10 万行导入 ≤ 60s 且可取消 |
| NFR-3 | **可靠性** | 会话状态持久化，进程被杀后可恢复到词/段粒度；DB 写入事务化；导入原子；DB 迁移有回归测试 |
| NFR-4 | **隐私** | 语音仅在命令窗口内采集，命令文本本地解析、不上传；若平台识别引擎存在云端链路（Android SpeechRecognizer 可能走 Google 云），须在隐私声明中披露，并优先尝试离线识别（`EXTRA_PREFER_OFFLINE`）；**内置离线引擎路径（E1，2026-09-14）纯本地识别，无任何云端链路** |
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
| C5 | 版本基线：minSdk 26 / compileSdk 35（Phase 1 定格：AGP 8.8.2 稳定支持 35；升 36 需 AGP ≥8.9，与后续依赖升级一并处理）；iOS 15+ |

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
| 生词本：创建 / 命名 / 重命名 / 删除 / 一词多本 / 保存时选 DefinitionEntry / Example 逐条可选 | FR-4 / FR-5 |
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
| 1.2 | 2026-09-01 | C5 定格 compileSdk 35（Phase 1 实装；升 36 需 AGP ≥8.9 随依赖升级处理） |
| 1.3 | 2026-09-01 | FR-5 例句选择粒度细化（Phase 2 批准）：由「per-DefinitionEntry 整体开关 includeExamples」改为「逐 Example 勾选（多选，可全不选）」；Example 原子性不变。下游同步：DOMAIN_MODEL §1/§2.6、DATABASE_SCHEMA（schema v2）、TEST_PLAN、CLAUDE.md 铁律 4 |
| 1.4 | 2026-09-13 | FR-12 别名验收口径对齐 Phase 5 Step 1 裁决 D4（2026-09-12，Phase 5 收尾决策 D-B）：「别名可配置生效」延期至 Phase 8（Settings Repository/Settings UI，不改引擎）；Phase 5 使用 CommandParser 固定代码常量 {会了, 记住了, 掌握了}，验收口径 = 三别名均可命中。FR-7/FR-12 产品语义不变。文档对齐：AUDIO_ENGINE_SPEC v1.2 §7（既有） |
| 1.5 | 2026-09-14 | **需求变更（用户裁决 E1/E2）：语音命令须支持中国大陆发售的所有安卓手机。**实测 vivo V2436A（Android 16）系统无任何标准 RecognitionService（仅 Gemini 无传统服务，vivo 私有服务不暴露标准接口），`SpeechRecognizer` 路径在国行主流机型不可用。FR-12 增识别引擎条款：系统服务在场优先（行为不变），缺席时 App 内置离线引擎（Vosk + 中文小模型打包进 APK）；NFR-4 增内置路径纯本地无云端。下游同步：AUDIO_ENGINE_SPEC v1.3（§8 双 actual + E2 选择规则）、ARCHITECTURE §5 矩阵、TEST_PLAN v2.0（TC-AE-27）、ROADMAP v1.5（Phase 5 收尾范围修订） |
| 1.6 | 2026-09-14 | **裁决 E3（诊断修正 + 窗口内引擎回退）**：真机诊断证明 v1.5 对 vivo 的判断需修正——App 声明 `<queries>` 包可见性后，vivo V2436A 上**有**服务注册 `RecognitionService`（蓝心 Copilot 唤醒服务等），E2 启动探测返回 true → 装配系统 actual，但该服务对标准识别请求**立即可用性级硬失败**（46ms，权限已授予），首窗即降级且粘滞 →「语音命令不可用」。FR-12 识别引擎条款增运行时回退：主引擎窗口内可用性级失败 → 当场回退内置引擎完成同窗剩余预算 + 进程内降级主引擎（重启重新探测）；健康系统服务设备零变化。下游同步：AUDIO_ENGINE_SPEC v1.4（§8 选择与回退规则 E2/E3 + §9 可用性级行）、ARCHITECTURE §5 矩阵、TEST_PLAN v2.1（TC-AE-28）、ROADMAP v1.6 |
| 1.7 | 2026-09-14 | **裁决 E4（系统引擎响应看门狗）**：E3 交付后真机复测暴露 vivo 假服务第二种死法——静默僵尸（零回调零错误挂至窗口超时，E3 未触发，用户说「会了」被吞）。FR-12 识别引擎条款增看门狗：startListening 后 1500ms 零回调（健康服务安静时亦持续回调）→ 判死按可用性级失败上报 → 纳入 E3 同窗回退；GMS 零误触（模拟器静默 androidTest 锁定）。下游同步：AUDIO_ENGINE_SPEC v1.5（§8 系统 actual 看门狗 + E4 段落 + §9 行）、TEST_PLAN v2.2（TC-AE-29）、ROADMAP v1.7 |
| 1.8 | 2026-09-16 | **需求变更（用户裁决）：「会了」近音误听按命中处理**（M1 真机复测驱动——Vosk 中文小模型把「会了」听成「坏了」「回来」、断字截断成「会」等，用户说完命令但倒计时走满零掌握）。FR-12 增**近音兜底**条款：识别最终文本命中别名的近音兜底白名单（坏了/换了/会啦/惠了/汇了/回来/回了/会来/会 → 会了；记住啦/记住咯 → 记住了；掌握啦/掌握咯 → 掌握了）时同样触发 FR-7；白名单证据驱动增补，Phase 8 随别名设置化扩展；白名单外仍精确匹配，无关短语不误杀（D5 红线不变）。下游同步：AUDIO_ENGINE_SPEC v1.7（§7 匹配规则 + `NEAR_HOMOPHONES_BY_ALIAS`）、TEST_PLAN v2.4（TC-AE-10 近音用例 + 防误杀守卫） |
| 1.9 | 2026-09-17 | **需求变更（用户裁决）：首/末字近音结构规则**（白名单逐形增补仍漏新形态——同日 vivo 实测「会了」→「了了」（h 声母丢失）零掌握后，用户裁决改为结构覆盖）。FR-12 增**结构规则**条款：默认别名「会了」，识别文本**首字 ∈ 会近音集（会/坏/换/回/惠/汇）或末字 ∈ 了近音集（了/啦/咯/来）即命中**；截断、声母丢失、任意组合句式全覆盖。**不误杀守卫收窄**：「会了吗」「我觉得这个词已经会了」及繁体「會了」（末字=了；原 UNKNOWN 守卫例）转为命中；剩余守卫 = 首尾字均不在近音集（「你好」「hello」）+ 空文本 + 自定义别名集不适用。下游同步：AUDIO_ENGINE_SPEC v2.0（§7 `STRUCTURAL_NEAR_CHARS_BY_ALIAS`）、TEST_PLAN v2.7（TC-AE-10 结构规则用例组 + 守卫翻转） |
| 1.10 | 2026-09-18 | **需求变更（用户裁决）：轻声/气音支持**（根因 = vivo 04:41 实证轻声说话被 OEM 语音链路抹成纯静默：AudioRecord 流正常开启活跃、Vosk 零回调零 partial、appops allow、无蓝牙/有线占用——用户裁决直接加支持）。FR-12 内置引擎条款升级：弃用库 `SpeechService`，自管 `AudioRecord`（MIC 原始源）+ 自适应预增益（10× 上限）+ `EndpointerMode.SHORT` + 自端点强制终局（**双触发**：500ms 尾静默 / 非空假设 700ms 无改进——后者不依赖声学门限，环境底噪持续越过能量门限（同日插桩诊断文件实证 117–142 贴 120 门限）时轻声冲刷仍可裁决）。轻声识别为**尽力而为**（小模型无浊音解码上限），M2 验收口径仍为正常音量。下游同步：AUDIO_ENGINE_SPEC v2.1/v2.2（§8 Vosk 行重写 + 冲刷双触发/诊断文件）、TEST_PLAN v2.8/v2.9（TC-AE-27 自管管线与冲刷双触发注记；既有四用例 = 新管线生命周期回归） |
| 1.11 | 2026-09-18 | **证据驱动扩集（v1.9 结构规则既定扩展路径，用户裁决）**：「会了」末字近音字集 +「呀」——轻声管线首测 vivo 取证（窗口诊断文件）：轻声「会了」被解码为单字「呀」（元音 a/e 弱音频混淆），按 v1.8「近音误听全部按命中处理」既定裁决与用户同日确认增补；副作用与现行口径同级（X了/会X 全命中的同类取舍，X呀/单字呀 命中）。下游同步：AUDIO_ENGINE_SPEC v2.3（§7 字集）、TEST_PLAN v2.10（TC-AE-10 +「呀」「好呀」用例） |
| 1.12 | 2026-09-18 | **Phase 8 范围裁决（用户）：「会了」命令别名可配置性再延后**（v1.4 延期至 Phase 8 的裁决点，本次裁决不实施——个人使用三别名 + 近音兜底已够用；避免编辑别名集意外丢失 vivo 实证调优的误听容错）。FR-15 第六行标注固定代码常量；`settings.masteredAliases` 键保持预登记未启用。同批裁决：i18n 校对 / TalkBack / NFR-2 逐项测量延后至 MVP 后打磨批（ROADMAP v1.9）。FR-15 增验收行（Phase 8 五项设置落地）。协议文档：docs/agent-sync/ `*_PHASE8.md` |
| 1.13 | 2026-09-19 | **新增 FR-17 生词本词条选择编辑（用户 2026-09-19 vivo 走查后提出）**：已保存词在本详情「编辑」入口增删释义/例句选择，单事务整组替换、≥1 释义守卫、不重建词条行（entryOrder/addedAt/pendingTranslation 原样）、掌握零接触、生效时点 = 该词下一次播放（粒度同 FR-10 开关）、导入词空态提示。FR-5 注记重存替换语义与正规编辑入口。零迁移（Q7 query-only）。裁决：查词保存流不预填。协议文档：docs/agent-sync/ `*_PHASE8_5.md` |
| 1.14 | 2026-09-19 | **新增 FR-18 全量离线词典与按需导入 / FR-19 TTS 音色选择 / FR-20 学习统计 + 应用图标打磨**（bug list 四项，用户裁决：词典全量 77 万词、TTS 本地音色枚举）：ECDICT（MIT）只读 SQLite 随包 + DB 未命中按需导入（幂等复用种子代码路径）；SpeechSynthesizer 端口 +availableVoices 与设置两键持久化、失效回退 setLanguage；勋章页统计卡 + 日/月/年图表（SessionWord/LearningSession 聚合，query-only 零迁移）；自适应矢量图标。FR-3 注记例句契约分域（仅精选种子）；FR-16 注记 v1.1 复合实现。协议文档：docs/agent-sync/ `*_BUGLIST.md` |
| 1.15 | 2026-09-19 | **FR-18 例句增强 + 例句回填（用户批准 SPEC_CHANGE_REQUEST_TATOEBA）**：全量词典资产经 Tatoeba（CC-BY 2.0 FR）离线对齐追加例句列 `[["英文原句","中文译文"],…]`（≤4 条/词，译文经 links 句对配对可空，挂首释义——数据源无词性归属）；资产 `PRAGMA user_version` 版本化，旧设备缓存自动重拷；增强前已导入词条 lookup 时幂等回填例句/译文（单事务，同句去重只补不删）。FR-3 来源类型 +`TATOEBA`；FR-3 注记 v1.15 全量词典例句译文可空。协议文档：docs/agent-sync/ `SPEC_CHANGE_REQUEST_TATOEBA.md` |
| 1.16 | 2026-09-20 | **FR-18 例句译文全量覆盖（v4 资产）**：Tatoeba links 配对仅覆盖 3.05 万/29.9 万例句 → opus-mt-en-zh 批量机器翻译补齐全部空译文（人译优先、机译兜底），覆盖率 299,558/299,576（100%）；资产 `user_version=4`（数据重建递增铁律），设备旧缓存自动重拷；再生管线见 tools/dict/README（重活 Node 化）。vivo 装机实测例句中文正常显示。决策记录：ADR-008 v4 后续批 |
| 1.17 | 2026-09-20 | **新增 FR-21 学习会话词内容展示与播放高亮 + FR-18 音标回填**（bug list 第 5 项）：学习屏滚动卡片面板（音标 + 释义卡 + 例句卡，数据同源 PlaybackContent 选中项）+ 当前段 owner 卡片高亮/行加粗/词头变色；Paused 冻结段高亮（最近 Playing 段快照）；换词异步重载（wordId 去重）。FR-18 回填路径扩音标（`importExamplesOnly` 改名 `backfillEnhancements`，只补空缺绝不覆盖，零释义 TXT 词适用）。2026-09-20 vivo 装机实测全通过。下游同步：TEST_PLAN v2.18；决策记录：ADR-009 |
| 1.18 | 2026-09-20 | **新增 FR-22 双音标显示与发音口音设置 + FR-18 音标增强（ipa-dict）**（用户 2026-09-20 提出，批准 SPEC_CHANGE_REQUEST_DUAL_IPA）：词典资产并入 ipa-dict（MIT）——en_UK → `ipaBr` 列、en_US 补 `ipa` 空缺，`user_version=5`；FR-18 回填闸门扩英音（ipaBr NULL 只补空缺）；FR-15 设置表 +发音口音行（`settings.ttsAccent` 缺省美音）；en-GB 缺失回退 en-US 不中断（zh-CN 仍硬失败）；FR-19 音色与口音正交；FR-21 面板音标行改双显；种子 59 词补全 ipaBr。零 schema 迁移。下游同步：TEST_PLAN v2.19；决策记录：ADR-010 |
| 1.19 | 2026-09-20 | **新增 FR-23 英文段端内实时神经 TTS**（用户批准 SPEC_CHANGE_REQUEST_PIPER_TTS v2 + IMPLEMENTATION_PLAN_PIPER_RT_TTS；同日 v1 预录方案装机实证「无差异」证伪撤销——词表命中与实际播放内容覆盖错位且预录无法覆盖长尾，未提交全量回退）：`LangRoutedSpeechSynthesizer` 按段语言路由（EN_* → SherpaOnnx 神经引擎（sherpa-onnx v1.13.8 + Piper VITS medium en_US lessac / en_GB alan，模型单文件 assets 直读 + espeak-ng-data 解包至 filesDir；整段合成分块直写 AudioTrack，16KB 缓冲 + 补静音对冲 OEM 不消费短轨道）；ZH_CN → 系统零变化）；口音 = 模型（FR-22 消费时映射不变，无厂商英音包设备也生效）；单英文模型驻留换载；WAV 磁盘缓存 100MB LRU；非 READY / speak 非取消异常 → EN 段当场回退系统 TTS 进程内粘滞，取消重抛不降级；rate clamp 0.5–2.0、pitch 不生效（VITS 限制）；APK 196MB → ~324MB。零 schema 变更。下游同步：AUDIO_ENGINE_SPEC v2.4 / ARCHITECTURE §5 / TEST_PLAN v2.20；决策记录：ADR-011 |
