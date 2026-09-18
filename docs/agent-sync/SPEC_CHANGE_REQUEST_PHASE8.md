# 规格变更请求：Phase 8 设置页

> 状态：范围已裁决（2026-09-18 用户裁决：方案 A + 打磨项延后），待批准执行 ｜ 日期：2026-09-18 ｜ 关联：AUDIT_REPORT_PHASE8.md / IMPLEMENTATION_PLAN_PHASE8.md

## 1. 结论：五项设置 = 纯落地；别名可配置性 = **已裁决再延后（方案 A）**

- FR-15 表格的**前五行**（groupSize / commandWindowMs / 六项播放开关 / TTS 语速 / TTS 音调）
  自 PROJECT_SPEC v1.0 起已定稿，读取端口与持久化键自 Phase 3/4 起在位——本 Phase 为纯落地，
  **不改任何需求语义**。
- FR-15 第六行「"会了"命令别名（默认 会了、记住了、掌握了）」：PROJECT_SPEC **v1.4（2026-09-13，
  裁决 D-B）将「别名可配置生效」显式延期至 Phase 8**。**2026-09-18 用户裁决 = 方案 A（再延后）**：
  别名维持 Phase 5 代码常量（`CommandParser.DEFAULT_MASTERED_ALIASES`），PROJECT_SPEC 版本记录
  一笔「可配置性再延后，v1 以内置三别名为准」；方案 B（`settings.masteredAliases` 键启用 + 设置页
  编辑）**未采纳**——理由：个人使用三别名 + 近音兜底已够用；避免编辑别名集意外丢失 vivo 实证
  调优的误听容错（近音表按内置别名归集，移除「会了」即失去其结构规则）。
- ROADMAP Phase 8 其余交付（i18n 校对 / TalkBack 无障碍 / NFR-2 性能预算逐项测量）：**2026-09-18
  用户裁决 = 延后**（与 M1–M4 同口径，MVP 后集中打磨）——Phase 8 收敛为「设置页」单项，
  ROADMAP 补版本记录。

## 2. 方案 B 的既有规格约束（未采纳，留档供未来重启）

- `CommandParser.parse(text, aliases, enabled)` 已参数化别名（AUDIO §7）——解析器零改动；
- 近音兜底（`NEAR_HOMOPHONES_BY_ALIAS` / `STRUCTURAL_NEAR_CHARS_BY_ALIAS`）**按内置别名归集**：
  自定义别名无近音兜底；**从别名集中移除「会了」= 同时失去其结构规则**（vivo 实证调优的鲁棒性）
  ——已是既有测试锁定行为（AUDIO v2.0），设置页文案须提示；
- 编排器现硬编码常量（`PlaybackOrchestrator.kt:414`）→ 改为每窗随 `commandWindowMs` 同点读取
  （每窗一次 suspend 读，既有模式）。

## 3. 需要同步的下游文档（两方案共同部分 = 非需求变更）

| 文档 | 变更 | 性质 |
|---|---|---|
| `DATABASE_SCHEMA.md` | §2.11 注记设置写入路径启用（`upsertSetting` 自 Phase 1 在位）——**无 DDL/索引/FK 变更，schema 恒 v2，无迁移**；§4 若有设置事务注记则补 | 落地注记 |
| `ARCHITECTURE.md` §5 | 无新端口平台能力（写路径走既有 AppSetting KV，无 actual）——预计零变更，收尾复核 | — |
| `TEST_PLAN.md` | TC-UI 设置用例落点 + TC-LE-02「中途改设置不影响现有会话」既有断言复核 | 落地标记 |
| `PROJECT_SPEC.md` | FR-15 验收行（设置页走查后）+ 版本记录（方案 A：别名再延后注记；方案 B：别名可配置落地） | 版本记录 |
| `LEARNING_ENGINE_SPEC.md` | §12 设置端口注记「写入自 Phase 8 提供」 | 落地注记 |

## 4. 范围外（明确不做，2026-09-18 裁决落定）

- 「会了」命令别名可配置（方案 A 再延后；重启须走需求变更流程）；
- i18n 校对 / TalkBack 无障碍 pass / NFR-2 逐项测量（延后至 MVP 后打磨批；NFR-2 导入性能项
  已于 Phase 7 实测 10 万行 ~3s ≤60s）；
- 其余 VoiceCommand（PAUSE/RESUME/NEXT/REPLAY/EXIT）关键词设置——枚举本身仍是预留（DOMAIN_MODEL §3.3）；
- 设置导出/导入、云同步；
- iOS 设置页（Phase iOS）。

## 5. 风险

| 风险 | 缓解 |
|---|---|
| 设置页写入与读取校验语义不一致（读侧损坏即抛） | 写侧同范围校验（groupSize ≥1 / commandWindowMs >0 / rate·pitch >0），拒绝写入越界值；jvmTest 往返 + 越界用例 |
| groupSize 变更被误解为即时生效 | UI 固定提示「只对新会话生效」（FR-6 第 4 条）；TC-LE-02 既有断言锁定 |
| 方案 B：用户编辑别名集丢失近音兜底 | 文案明示「移除内置别名将失去其误听容错」；近音表按内置别名归集的既有测试不改 |
