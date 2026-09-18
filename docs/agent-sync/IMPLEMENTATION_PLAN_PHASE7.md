# 实现计划：Phase 7 TXT 导入

> 关联：SPEC_CHANGE_REQUEST_PHASE7.md / AUDIT_REPORT_PHASE7.md ｜ 状态：Draft — 待用户批准 ｜ 日期：2026-09-18

## 设计约束

1. **业务规则只在 shared**（铁律 2）：编码检测 / 行解析 / 去重 / 事务 / 报告 / 取消全部 `shared/import`；app 只做 SAF 选文件、状态渲染、文案。
2. **零迁移**：不动 DDL；`.sq` 仅加 query-only `updateEntryPendingTranslation`。
3. **commonMain 零平台依赖**（铁律 1）：解码全部在 `TextLineSource` actual；`EncodingDetector` 对字节做纯 Kotlin 判定（UTF-8 严格校验 + GB18030 2/4 字节结构校验）。
4. **单一大事务 + 整体回滚**（IMPORT_SPEC §5 v1 裁决）：取消/异常/空文件 → 目标本零变化。
5. **时间与派发注入**（铁律 10）：进度节流用注入 `Clock`；测试虚拟时间。

## 模块结构（as-built 目标）

```
shared/import/
  EncodingDetector.kt      # detect(head: ByteArray): DetectedEncoding（sealed：Utf8/Utf16LE/Utf16BE/Gb18030/Unsupported）
  LineParser.kt            # parse(raw: String): ParsedLine（sealed：WordOnly/WordWithTranslation/Invalid/Ignored）
  ImportEngine.kt          # 流程编排：preview(20 行) / import(...)，去重四层 + ImportReport + ImportFinished
  ImportRepository.kt      # 端口：单事务导入（创建目标本可选 + 批写 + 回查）
domain/event/DomainEvent.kt  # + ImportFinished(targetWordBookId, imported, reusedWords, duplicatesInFile, duplicatesInBook, updated, invalid)
data/SqlDelightImportRepository.kt
data/WordBookEntry.sq        # + updateEntryPendingTranslation（query-only）
platform/(commonMain) ImportFiles.kt   # FileBytesSource + TextLineSource 端口
shared/androidMain/platform/ImportFiles.android.kt  # ContentResolver/Uri + InputStreamReader→Flow<String>（BOM 剥离在此）
app/ui/ImportScreen.kt + ImportViewModel（AppViewModels.kt）
app/MainActivity.kt         # + showImport 全屏子页（同 learningBookId 先例）；WordBooksScreen 加「导入」入口
```

## 实施步骤（单批次，Step 内序贯）

| # | 内容 | 文件 | 备注 |
|---|---|---|---|
| 1 | 端口 + 事件 | `platform/ImportFiles.kt`、`domain/event/DomainEvent.kt` +ImportFinished | explicitApi 显式 public |
| 2 | 编码检测 | `import/EncodingDetector.kt` | BOM 表 / UTF-8 严格 / GB18030 结构校验；head ≤8KB |
| 3 | 行解析 | `import/LineParser.kt` | 分隔符优先级 Tab>多空格>单空格>逗号(半/全)>分号>冒号；词 `^[A-Za-z][A-Za-z''\- ]{0,63}$`；译文 ≤256 整段 |
| 4 | 仓储 | `ImportRepository.kt` 端口 + `SqlDelightImportRepository.kt` + `.sq` 新查询 | `runImport(target, lines, onProgress, clock): ImportReport` 单 transactionWithResult；新建本在事务内；有效行=0 → 回滚不建本 |
| 5 | 引擎 | `import/ImportEngine.kt` | 去重四层（文件内 Set → 全局 selectByNormalizedText → 本内 selectEntryByWord → 补写译文）；invalidSamples ≤20；进度节流 100ms；成功 → publish ImportFinished |
| 6 | commonTest | TC-IMP-01（编码样例逐字节）/ TC-IMP-02（分隔符 + 译文含逗号整段）/ TC-IMP-06（非法行） | 纯函数；UTF-16 顺带 |
| 7 | jvmTest 集成 | `ImportEngineTest.kt`：TC-IMP-03 守恒 / TC-IMP-05 去重三层 / TC-IMP-07 回滚（取消+异常）/ TC-IMP-08 pendingTranslation 落库与补写 | 真实 JDBC + 内存 Fake 源；含「导入词可开学习会话」断言（selectedDefinitions 空 → 可播 PRON/SPELL） |
| 8 | jvmTest 性能 | TC-IMP-04：10 万行 GB18030 内存 Fake ≤60s + 取消 ≤1s 回滚 | 规格 JVM 基准口径 |
| 9 | Android actual + DI | `ImportFiles.android.kt`、`DataModule`/`AppModule` 注册 | GB18030 解码失败 → 端口异常 → UI 引导 |
| 10 | 导入 UI | `ImportScreen.kt` + `ImportViewModel` + MainActivity `showImport` + WordBooks 入口 | 五态：选文件(SAF OpenDocument) → 检测+预览(20 行,合法/非法标记) → 选本(已有列表/新建名) → 导入中(行数进度+可取消) → 报告(七计数+样例)；文案简体中文 |
| 11 | 文档同步 | ARCHITECTURE §5 / DATABASE_SCHEMA(query-only, 无迁移注记) / DOMAIN_MODEL §9 ✅ / TEST_PLAN TC-IMP ✅ + 版本记录 / IMPORT_SPEC 1.1 | SCR §2 清单 |
| 12 | 门禁 + 真机 | 全量测试 / 双 detekt / 边界 / assembleDebug → vivo 安装走查 → 用户确认 → `feat(import)` 提交 + DECISION_LOG | 走查同 Phase 6 口径可延后 |

## 验收对照

- ROADMAP Phase 7 出口：TC-IMP-01…08 绿 ✓（步骤 6–8）；10 万行 ≤60s 可取消 ✓（步骤 8）；FR-14 验收 ✓（步骤 10 走查）。
- 守恒断言（IMPORT_SPEC §6）：`imported + 复用且不在本 + duplicatesInFile + duplicatesInBook + invalid = 有效行`（TC-IMP-03 强制）。

## 待确认（默认按规格执行，无需答复）

- 译文上限 256 / invalid 样例 20 条 / 预览 20 行——均为 IMPORT_SPEC v1.0 既定值，不重开讨论。
