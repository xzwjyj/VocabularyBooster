# 审计报告：Phase 7 TXT 导入

> 状态：完成 ｜ 日期：2026-09-18 ｜ 关联：SPEC_CHANGE_REQUEST_PHASE7.md

## A. 现状盘点（as-is）

| 项 | 现状 | 结论 |
|---|---|---|
| `shared/import/` | 仅 `.gitkeep`（Phase 0 占位） | 全新实现 |
| `.sq` 查询 | `insertWord` / `selectByNormalizedText`（全局复用）/ `selectEntryByWord`（本内去重 + pendingTranslation 读取）/ `maxEntryOrder` / `insertEntry` **均已存在** | 仅缺 `updateEntryPendingTranslation`（补写译文），query-only |
| 端口矩阵（ARCHITECTURE §5） | 无 `FileBytesSource` / `TextLineSource` | 新增两端口 + Android actual |
| `DomainEvent` | `ImportFinished` 在 DOMAIN_MODEL §9 为预留 | 新增 sealed 子类（载荷：targetWordBookId + 五项计数） |
| 导入 UI | 无；导航 = MainActivity 扁平状态变量（`learningBookId`/`openWordText`/`openBookId` 先例） | 新增 `showImport` 同模式全屏子页 |
| DI | `DataModule`（仓储）/ `AppModule`（VM 装配）先例齐备 | 新增 `ImportRepository` + `ImportViewModel` 槽位 |

## B. 依赖与一致性核查

1. **schema 零迁移**：本 Phase 不动 DDL（补一条命名查询；先例 v1.6 Q4b query-only）。
2. **导入词学习路径已具备**（无需改播放/学习引擎）：
   - `PlaybackContent.selectedDefinitions` 为空 → SegmentBuilder 只产 PRONUNCIATION + SPELLING（LE 边界 #3，ADR-002 后 Q2 含全部词，导入词可入会话）；
   - `WordBookEntry.pendingTranslation` 由 `BookDetail`/`WordDetail` 既有查询携带（`selectEntryWordsForBook` 含该列）。
3. **空文件不建本（边界 #1）**：新建本发生在导入事务内；有效行 = 0 → 回滚（不落空本）——与「整体回滚」事务策略天然一致。
4. **事务口径一致**：DATABASE_SCHEMA §4（导入行）与 IMPORT_SPEC §5/§6 均为 v1 整体回滚，无冲突。
5. **测试基建**：`TestDb.inMemory` + `DispatchersForTest` + `FixedClock`（jvmTest 集成先例：`AchievementEngineTest`）；编码/解析纯函数 → commonTest；10 万行基准 → jvmTest（JVM 基准即规格口径）。
6. **Android 编码能力**：API 26+ 内置 `GB18030` charset；解码在 `TextLineSource` actual 内（commonMain 不触 java.nio.charset，边界合规）。
7. **取消语义**：单事务 + 协程取消（`CancellationException` → SqlDelight 事务回滚）；进度节流 100ms 由引擎内注入 `Clock` 时间戳控制（铁律 10）。

## C. 缺口清单（→ 实现计划输入）

| # | 缺口 | 归属 |
|---|---|---|
| 1 | `EncodingDetector`（BOM / UTF-8 严格校验 / GB18030 字节结构校验） | shared 纯 Kotlin |
| 2 | `LineParser`（六分隔符优先级 + 词合法字符 + 译文 ≤256） | shared 纯函数 |
| 3 | `ImportEngine`（流式去重四层 / 报告守恒 / 取消 / 事件） | shared |
| 4 | `ImportRepository` 端口 + SqlDelight 实现（单事务批写） | shared data |
| 5 | `FileBytesSource` / `TextLineSource` 端口 + Android actual | shared port / app actual |
| 6 | 导入 UI（选文件 SAF → 选本 → 预览 20 行 → 进度 → 报告） | app |
| 7 | TC-IMP-01…08 测试 | shared 测试 |
| 8 | 五份文档同步（见 SCR §2） | docs |

## D. 结论

无阻塞项；全部缺口有既有先例与规格条文支撑，可直接进入实现计划。
