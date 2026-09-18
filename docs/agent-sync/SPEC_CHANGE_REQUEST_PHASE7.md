# 规格变更请求：Phase 7 TXT 导入

> 状态：待批准 ｜ 日期：2026-09-18 ｜ 关联：AUDIT_REPORT_PHASE7.md / IMPLEMENTATION_PLAN_PHASE7.md

## 1. 结论：**无需求变更**

IMPORT_SPEC v1.0（2026-09-01 定稿）+ PROJECT_SPEC FR-14（v1.0 口径）已完整覆盖 Phase 7 交付；
TEST_PLAN TC-IMP-01…08 用例已预定义。本 Phase 为纯落地，**不改任何需求语义**。

## 2. 需要同步的下游文档（非需求变更）

| 文档 | 变更 | 性质 |
|---|---|---|
| `ARCHITECTURE.md` §5 | 端口矩阵新增两行：`FileBytesSource` / `TextLineSource`（Android actual：`ContentResolver` + `InputStreamReader`；iOS 预留） | 加平台能力（CLAUDE.md 触发表） |
| `DATABASE_SCHEMA.md` | §2.5 新增 query-only `updateEntryPendingTranslation`（补写译文路径，§4 去重规则 3）——**无 DDL/索引/FK 变更，schema 版本维持 v2，无迁移** | query-only 增补（先例：v1.6 Q4b） |
| `DOMAIN_MODEL.md` §9 | `ImportFinished` 事件标 ✅ + 载荷定型（`targetWordBookId` + 五项计数） | 落地标记 |
| `TEST_PLAN.md` | TC-IMP-01…08 ✅ 标记 + 测试落点 | 落地标记 |
| `IMPORT_SPEC.md` | 版本记录（1.1：落地形态注记——单事务/取消语义/事件载荷） | 落地注记 |

## 3. 范围外（明确不做）

- Big5（IMPORT_SPEC §2 非目标）；
- 释义回填任务（§8：未来 DictionaryProvider 接入后）；
- `> 50 万行` UI 预警（NFR-2 注记，v1 仅实现 10 万行基准内的流式）；
- iOS actual（Phase iOS）。

## 4. 风险

| 风险 | 缓解 |
|---|---|
| GB18030 合法性校验需纯 Kotlin（commonMain 禁 java.nio.charset） | 字节结构校验（2/4 字节序列范围判定），TC-IMP-01 逐字节样例锁定 |
| Android `GB18030` charset 实际可用性 | API 26+ 内置支持；TextLineSource 解码失败 → 端口异常 → UI 引导强制编码/取消（§2 第 4 步） |
| 导入大事务期间 UI 可用性 | 导入在 VM scope IO 派发；取消 = 协程取消 → 事务回滚 ≤1s（TC-IMP-04） |
