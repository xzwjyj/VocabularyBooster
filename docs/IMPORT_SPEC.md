# IMPORT_SPEC — TXT 导入规格

> 状态：Phase 7 已落地 ｜ 版本 1.1 ｜ 日期：2026-09-18
> 上游：PROJECT_SPEC FR-14 ｜ 存储：DATABASE_SCHEMA §2.1 `Word`、§2.5 `WordBookEntry.pendingTranslation`
> 位置：`shared/commonMain/…/importing/`，解析/去重/流程控制为纯 Kotlin；文件字节读取为平台端口。
> 落地注记：Kotlin 包名取 `importing`（`import` 是关键字，不可作包名）。

---

## 1. 职责与端口划分

| 能力 | 归属 | 说明 |
|---|---|---|
| 文件字节流读取（分块、不整载） | 平台端口 `FileBytesSource` | Android `InputStream` / iOS `FileHandle`；commonMain 只定义接口 |
| 编码**检测**（BOM / UTF-8 校验 / GB18030 兜底） | **共享纯 Kotlin** `EncodingDetector` | 输入文件头字节（≤ 8KB），输出决策 + 置信度 |
| 流式**解码**（字节 → 行） | 平台端口 `TextLineSource` | 由平台原生编码器按检测结果解码（天然处理跨块字符边界） |
| 行解析 / 去重 / 事务 / 报告 / 取消 | **共享纯 Kotlin** `ImportEngine` | 全部 JVM 可测 |

```kotlin
interface FileBytesSource { suspend fun readChunk(maxBytes: Int): ByteArray? }   // null = EOF
interface TextLineSource { fun lines(encoding: DetectedEncoding): Flow<String> }
```

## 2. 编码检测算法（EncodingDetector，确定性）

```
detect(head: ByteArray):
  1. BOM 匹配（优先级最高）：
     EF BB BF        -> UTF-8 (+BOM，解码时剥离)
     FF FE / FE FF   -> UTF-16LE / UTF-16BE（提示：不在需求内，检测到即支持解码）
  2. UTF-8 严格校验（整个 head）：
     全部合法多字节序列 -> UTF-8
  3. 兜底 -> GB18030（GBK 超集，中文环境最常见）
  4. GB18030 解码失败率 > 阈值（head 中出现非法序列）-> 报告 unsupportedEncoding，
     UI 提示选择：以指定编码强制导入 / 取消
```

- 不支持 Big5（非目标，简体场景）。
- 决策只依赖头部字节 → 输出可复现，单测覆盖各编码样例文件。
- **落地口径（v1.1）**：① 无「置信度」字段——BOM/UTF-8 严格校验/GB18030 三级瀑布输出确定性决策；
  ② 第 4 步为**零容忍**（阈值 = 0）：head 内任一 GB18030 非法序列 → `Unsupported(reason)`，UI 呈现原因并终止（v1 不提供强制指定编码，用户须转存文件后重试）；
  ③ head 末尾不完整多字节序列视为**截断**（head 是前缀切块，不算非法）——UTF-8 与 GB18030 同口径。

## 3. 行解析规则（LineParser，纯函数）

| 规则 | 定义 |
|---|---|
| 空行 | trim 后为空 → 跳过（计数 ignored） |
| 单词行 | `word`（仅一个 token）→ 无译文：`pendingTranslation = null` |
| 词 + 译文行 | `word<sep>translation`，`<sep>` = Tab ‖ 2+ 空格 ‖ 单空格 ‖ 逗号（半/全角）‖ 分号 ‖ 冒号 —— **按优先级尝试**（Tab > 多空格 > 单空格 > 逗号 > 分号 > 冒号），取**首次成功分割** |
| 词合法字符 | `^[A-Za-z][A-Za-z''\- ]{0,63}$`（允许连字符词 / 撇号 / 多词短语）；超限或含数字/其他字符 → invalid 行（计数 + 保留原文供报告） |
| 译文 | sep 之后整段作为译文（可含任意中文/标点），长度 ≤ 256 |
| 大小写 | 保存原文 `text`；`normalizedText = lower(trim)` 用于查询与去重 |

## 4. 去重与复用（顺序执行）

1. **文件内去重**：按 `normalizedText` 首见保留，后续 → `duplicatesInFile++`；
2. **全局复用**：`Word.normalizedText` 已存在 → 复用已有 `wordId`（不插新行），`reusedWords++`；
3. **目标本去重**：`(wordBookId, wordId)` 已存在 → 跳过（`duplicatesInBook++`）；已存在但**本行带译文而库里 pendingTranslation 为空** → 补写译文（同时 `duplicatesInBook++` 与 `updated++`——`updated ⊆ duplicatesInBook`，v1.1 落地口径，守恒式见 §6）；
4. 通过 → 插入 `WordBookEntry(entryOrder = 本内当前最大值+1, pendingTranslation = 译文)`，`imported++`。

## 5. 导入流程（ImportEngine）

```
chooseFile → chooseTargetBook(已有 | 新建名称)
   → readHead → EncodingDetector（失败 → §2 第 4 步交互）
   → preview：解析前 20 行（合法/非法标记）→ 用户确认
   → 执行导入：
        lines(单一大事务内逐行流式)
        对每行: LineParser → 去重 → SQL 写
        进度回调: linesRead / imported / invalid（节流 100ms，注入 Clock）
        取消: 协程取消 → 每 256 行 ensureActive → 事务回滚后向上传播
   → 结果: ImportReport(见 §6) → 事件 ImportFinished（仅当 imported+reused+updated > 0）
```

**事务策略（v1，已落地）**：整个导入 = **单一大事务**（10 万行内 SQLite 完全可行，速度优于分块提交）；取消 / 任何失败 → **整体回滚**，目标本保持导入前原样（DATABASE_SCHEMA §4）。落地细节：
- 事务载体 = `ImportRepository.withImportTransaction`（SQLDelight 同步事务 + `runBlocking(outerJob)` 桥接——外层协程取消经父 Job 传播至事务体内 `ensureActive`，回滚 ≤1s，铁律 10 时间全走注入 `Clock`）；
- **新建本延迟创建**（空本防线）：目标本 id 在首条 entry 插入时才创建——全空 / 全非法 / 全重复文件**不建空本**（边界 #1/#2）；
- 零有效写入（imported = reused = updated = 0）→ 不发布 `ImportFinished`。

## 6. ImportReport（结果报告，五项计数 + 样例）

```kotlin
data class ImportReport(
    val targetWordBookId: Long,
    val totalLines: Long,        // 有效非空行数
    val imported: Long,          // 新增词条
    val reusedWords: Long,      // 复用全局已有词
    val duplicatesInFile: Long,
    val duplicatesInBook: Long,
    val updated: Long,          // 补写了临时译文
    val invalid: Long,
    val invalidSamples: List<String>,   // 最多 20 条原文，供 UI 呈现
)
```

验收：`imported + reusedWords(且不在本) + duplicatesInFile + duplicatesInBook + invalid = 有效行`（守恒断言，测试强制）。

## 7. 大文件与性能预算（NFR-2）

- 逐行流式：内存占用与文件大小**无关**（只保留去重 Set——10 万词 normalizedText ≈ 数 MB，可接受；> 50 万行时 UI 预警）；
- 10 万行 ≤ 60s（中端 Android 设备基准，TEST_PLAN TC-IMP-04）；
- 进度必须可感知（行数百分比），取消 ≤ 1s 内生效并回滚完毕。

## 8. 与词典回填的关系

- 导入词此时**没有 DefinitionEntry**：学习时仅播 PRONUNCIATION + SPELLING（LEARNING 边界表 #3）；详情页展示 `pendingTranslation` 作为临时中文释义；
- 未来 DictionaryProvider 接入后：**回填任务**为导入词拉取正式词条 → 清除 `pendingTranslation`（ROADMAP 对应 Phase）；
- 回填前后均不破坏学习/掌握记录（Word 行 id 不变）。

## 9. 边界情形表

| # | 情形 | 规定 |
|---|---|---|
| 1 | 空文件（0 有效行） | 报告全零 + 提示，不建本 |
| 2 | 全部为重复 | imported=0，报告如实 |
| 3 | UTF-8 BOM 文件首行 | BOM 剥离后正常解析，不把 BOM 带进词 |
| 4 | GBK 文件混有非法字节 | 检测报告 unsupported → 用户强制指定编码或取消 |
| 5 | 一行 = 中文开头（译文在前） | 违反词合法字符 → invalid（不猜测换位） |
| 6 | 目标本被并发编辑 | 导入事务隔离保证一致；提示导入期间避免编辑 |
| 7 | 导入进行中 App 被杀 | 整体事务回滚语义：下次打开目标本无半成品 |
| 8 | 行内多个分隔符（`word, trans1, trans2`） | 首次分割成功为准：译文 = `trans1, trans2` 整段 |
| 9 | 同一行 10 列（含逗号译文） | 同上——译文保留逗号原样 |

## 10. 测试要点（详见 TEST_PLAN TC-IMP-01…08）

- 各编码样例文件（UTF-8 / BOM / GB18030 / 混错字节）逐字节断言；
- 守恒断言（§6）+ 事务回滚（取消/异常）+ 10 万行性能基准；
- Fake `FileBytesSource`（内存字节）驱动全部 JVM 测试，无需真文件系统。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-18 | Phase 7 落地回写：包名 `importing`（`import` 为关键字）；§2 落地口径（无置信度字段 / GB18030 零容忍阈值=0 / head 末尾截断容忍）；§4 `updated ⊆ duplicatesInBook` 口径；§5 流程图对齐实现（单一大事务 + 协程取消 ensureActive 每 256 行 + 进度三字段 + 延迟建本空本防线 + 零有效写入不发布事件）+ `withImportTransaction` 事务载体（runBlocking 父 Job 桥接） |
