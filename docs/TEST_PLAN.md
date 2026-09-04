# TEST_PLAN — 测试策略

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 原则：**核心逻辑在 JVM 上测试**（不依赖模拟器/真机）；设备层只测"胶水"。

---

## 1. 测试金字塔

```
        ┌──────────────┐
        │ 手动矩阵 §7   │  真机语音/扬声器/TTS 质量（少量）
        ├──────────────┤
        │ androidTest  │  Compose UI 测试、真机 DB/驱动冒烟（少量）
        ├──────────────┤
        │ commonTest   │  领域/学习引擎/播放编排/命令解析/导入/勋章
        │ （JVM 全量） │  + SQLDelight 查询测试（JVM sqlite driver）
        └──────────────┘
```

**关键约束**：所有引擎测试只用 Fake 端口（`FakeSpeechSynthesizer / FakeAudioPlayer / FakeRecognizer / 内存 SQLDelight driver`），虚拟时间（`TestDispatcher`）——**可在 Windows CI 无模拟器运行**（NFR-6/NFR-9）。

## 2. 工具矩阵

| 用途 | 工具 |
|---|---|
| 单测框架 | kotlin.test（commonTest，JVM/iOS 共用同一套断言） |
| 协程/Flow | kotlinx-coroutines-test + Turbine |
| DB 测试 | SQLDelight JDBC sqlite driver（JVM）；真机 AndroidSqliteDriver（androidTest） |
| UI 测试 | Compose UI Test（`createAndroidComposeRule`） |
| 架构守护 | detekt `ForbiddenImport`（commonMain 禁 `android.*`/`java.*`）+ 模块边界断言（§6） |
| 覆盖率 | Kover（shared 模块） |

## 3. 用例命名与组织

- ID 规范：`TC-<模块>-<序号>`；模块代号：`DM` 领域模型、`DB` 数据库、`LE` 学习引擎、`AE` 播放引擎、`IMP` 导入、`AC` 勋章、`ARCH` 架构守护、`UI` Android 界面；
- 测试类与被测类同包（`com.vocabularybooster.<pkg>.<SubjectTest>`）；
- 每个边界情形表条目（各规格 §10）必须一一对应至少一个用例。

## 4. 核心用例矩阵

### 4.1 TC-DM 领域与排序不变量

| ID | 用例 | 断言 |
|---|---|---|
| TC-DM-01 | 多词性词条排序 | 同 POS 全部连续；(POSOrder, defOrder) 全序正确 |
| TC-DM-02 | 乱序入库数据 | 渲染排序仍满足连续性（排序与入库顺序无关） |
| TC-DM-03 | EN/CN 顺序 | MeaningEN 恒先于 MeaningCN；Example 句先于译文（I-6/I-7） |
| TC-DM-04 | 例句原子性 | 保存/取消 Example 为整体，无半选状态 |
| TC-DM-05 | 例句选择粒度（v1.2） | 每条选中释义可勾选任意 Example 子集（含空集=仅释义）；选择的只落 `WordBookEntryExampleSelection`，底层 Example 不复制 |

### 4.2 TC-DB 数据库

| ID | 用例 |
|---|---|
| TC-DB-01 | 唯一约束生效：normalizedText / (wordBookId,wordId) / (wordBookEntryId,definitionEntryId) / (wordBookEntryId,exampleId) / (type,wordBookId) 重复插入被拒 |
| TC-DB-02 | FK 级联：删 WordBook → entries/selections/mastery 级联，Word 不动 |
| TC-DB-03 | 关键查询正确：Q1 排序 / Q2 队列 / Q3 计数 / Q4 选中释义 / Q5 派生复制 |
| TC-DB-04 | 事务原子性：MasteryMarker 中途失败 → 两个写全回滚 |
| TC-DB-05 | 派生一致性：派生本关系行与母本（除 bookId）逐行相等 |
| TC-DB-06 | 迁移链：schema v1→vN 全版本 MigrationTest（Phase 1 起基线，每版迁移必加；首个迁移 v1→v2 于 Phase 2 落地：includeExamples 移除 + WordBookEntryExampleSelection 新建 + 数据保留断言） |
| TC-DB-07 | WordBook 类型约束：`type` CHECK 生效；删除有派生子本的母本被拒（RESTRICT）；DERIVED 本 parentWordBookId/sourceSessionId 必填（**Phase 2 遗留**：仓库层删除守卫已测并绿；DB 级 CHECK/RESTRICT 断言随 Phase 3 测试电池顺手补齐，不改 Phase 2 行为） |

### 4.3 TC-LE 学习引擎（覆盖 LEARNING_ENGINE_SPEC §10 十一条边界，Phase 3 批准全集）

| ID | 用例 |
|---|---|
| TC-LE-01 | 队列构建：100 词 → 未掌握全量，entryOrder 升序；空本 / 全掌握 → 拒绝并给出明确原因（§10-1） |
| TC-LE-02 | 分组固化：groupSize=10 → 组号 0..9；词数 < groupSize → 单组/末组不足（§10-2）；中途改设置不影响现有会话 |
| TC-LE-03 | 组内循环：随机顺序 master 部分 → 推进严格跳过已掌握并回绕 |
| TC-LE-04 | "会了"语义：同词重复调用 → AlreadyMastered 幂等无重复计数（§10-5）；词刚开始播放即"会了" → 立即落库并 advance（§10-4）；导入词（无释义）"会了"照常（§10-3 掌握侧；播放分段侧由 Phase 4 TC-AE-01/02 覆盖） |
| TC-LE-05 | 组推进：组清空 → 下一组首词 |
| TC-LE-06 | 书完成：最后一词 master → COMPLETED + 事件次序（停止→状态→勋章） |
| TC-LE-07 | 退出分支 B：掌握 37/100 → 派生本恰 63 词、`type=DERIVED`、parentWordBookId/sourceSessionId 记录正确、名称格式 `yyyy-MM-dd HH:mm`（注入时区精确断言）、重名 -2、再重名 -3；释义 + 例句选择关系逐 ID 一致；派生读事务内 DB 快照，母本并发编辑不影响一致性（§10-10）。**mid-session 母本编辑三边界（交集语义，2026-09-04 裁决，真实 JDBC 集成）**：Case 1 会话外新增未掌握词 → 不进派生本（留母本，Q3 计入）；Case 2 会话中词被移出母本（SessionWord 尚在）→ 不进派生本（entryOrder/pendingTranslation 恒取自母本行）；Case 3 交集为空（MASTERED>0 且 REMAINING>0）→ **不创建空 DERIVED 本**：derivedWordBookId=null、会话 ABANDONED、endedAt 正常写入 |
| TC-LE-08 | 退出分支 A：0 掌握退出 → 无新本（不产生空本）；会话状态与学习历史保留 |
| TC-LE-09 | 崩溃恢复：杀进程模拟 → 队列/分组/掌握状态完整还原 |
| TC-LE-10 | 恢复异常两分支：书已删 → 安全 ABANDON + 提示（§10-8）；某词已从本移除 → 该词从会话剔除，其余照常（§10-9，**不**整会话 ABANDON） |
| TC-LE-11 | 退出分支 C：退出瞬间 REMAINING=0 → COMPLETED + 勋章 + 不派生（以 DB 实时状态裁决） |

> TC-LE-01…11 即 Phase 3 批准测试全集（ROADMAP Phase 3 出口条件）；与 LEARNING_ENGINE_SPEC §10 #1–#11 逐条映射如上括注。另：ACTIVE 会话唯一性不变量（LE spec §3）断言并入 TC-LE-01（存在 ACTIVE 时第二次 startSession → Rejected）。

### 4.4 TC-AE 播放引擎（AUDIO_ENGINE_SPEC §3 状态表逐行）

| ID | 用例 |
|---|---|
| TC-AE-01 | 分段构建：六开关全开 → 段序 = I-8 结构（POS 排序 + 例句序） |
| TC-AE-02 | 开关组合：随机开关 → 段序列与映射表一致；全关 → 引擎拒绝会话 |
| TC-AE-03 | 每词周期：末段完成 → 300ms guard → 窗口开启（虚拟时间断言） |
| TC-AE-04 | 窗口命中"会了" → markMastered 被调 + 立即换词 |
| TC-AE-05 | 窗口超时/噪音文本 → 换词，不误掌握 |
| TC-AE-06 | **TTS 期间识别关闭**：Playing 态全程 recognizer 零调用（间谍断言） |
| TC-AE-07 | Pause/Resume：文件段恢复 offsetMs 精确；TTS 段重读本段（段索引不变、词索引不变） |
| TC-AE-08 | Pause/Resume 于窗口态 → 重开整窗 |
| TC-AE-09 | Next / Replay：不改掌握状态；词级重置正确 |
| TC-AE-10 | 命令解析：别名/大小写/全角/带标点 → MASTERED；未知 → UNKNOWN |
| TC-AE-11 | 双语切换：EN 段 en-US、CN 段 zh-CN 的 speak 请求逐段正确 |
| TC-AE-12 | 降级：识别不可用 → 会话继续 + 手动按钮可用；音频失败 → TTS 兜底 |

### 4.5 TC-IMP 导入（IMPORT_SPEC §9 九条边界）

| ID | 用例 |
|---|---|
| TC-IMP-01 | UTF-8 / BOM / GB18030 样例逐字节断言（BOM 剥离） |
| TC-IMP-02 | 六种分隔符 + 分隔符优先级 + 译文含逗号整段保留 |
| TC-IMP-03 | 守恒断言：报告计数恒等式 |
| TC-IMP-04 | 性能：10 万行 GBK 内存 Fake 源 ≤ 60s（JVM 基准）+ 取消 ≤1s 回滚 |
| TC-IMP-05 | 去重三层：文件内/本内/全局复用（词行数不变） |
| TC-IMP-06 | 非法行（数字/中文开头/超长）→ invalid + 样例收集 |
| TC-IMP-07 | 事务：中途异常 → 目标本零变化 |
| TC-IMP-08 | pendingTranslation 落库与回填清除后的状态 |

### 4.6 TC-AC 勋章（ACHIEVEMENT_SPEC §6）

| ID | 用例 |
|---|---|
| TC-AC-01 | 幂等重放：同一 `WordBookCompleted` 重放 N 次 → 恰一枚勋章 |
| TC-AC-02 | 防御复核：书未完成却收到事件 → 丢弃不授予 |
| TC-AC-03 | 快照不可变：授予后改名 / 派生 → payload 不变 |
| TC-AC-04 | 删除拦截：有完成勋章的书调用删除 → 拒绝 |
| TC-AC-05 | 授予顺序：发生在播放停止与会话 COMPLETED 之后（事件次序断言） |

### 4.7 TC-ARCH 架构守护

| ID | 用例 |
|---|---|
| TC-ARCH-01 | detekt：`shared/src/commonMain` 无 `android.*`、`java.*` import（fail 构建） |
| TC-ARCH-02 | app 模块无状态机/业务规则（Code owner review + 抽样 Konsist 断言） |
| TC-ARCH-03 | 共享模块 public API 显式声明（explicitApiMode 编译通过） |

### 4.8 TC-UI Android 界面（androidTest，少量关键）

词条渲染顺序快照断言（FR-2）、播放控制条状态（六控制可用性矩阵）、生词本增删改流、保存释义选择流、勋章 ceremony 出现条件。

## 5. FR 追踪矩阵（需求 → 用例）

| FR | 用例组 |
|---|---|
| FR-1/FR-16 | TC-DB-01、TC-UI（词条页） |
| FR-2 | TC-DM-01…03、TC-UI 词条渲染 |
| FR-3 | TC-DM-04、TC-AE-12 |
| FR-4/FR-5 | TC-DB-01/02、TC-DM-04/05、TC-UI 生词本流 |
| FR-6 | TC-LE-01…03、TC-DB-03 |
| FR-7 | TC-LE-04、TC-AE-04/05/10 |
| FR-8 | TC-LE-05/06、TC-AC |
| FR-9 | TC-LE-07/08/11、TC-DB-05/07 |
| FR-10 | TC-AE-01/02 |
| FR-11 | TC-AE-07/08/09 |
| FR-12 | TC-AE-03/06 |
| FR-13 | TC-AC 全组 |
| FR-14 | TC-IMP 全组 |
| FR-15 | TC-UI 设置、TC-LE-02 |

## 6. 覆盖率门槛（Kover，Phase 质量门）

| 层 | 门槛 |
|---|---|
| shared/domain + learning + playback + speech + import + achievement | **行覆盖 ≥ 85%**（引擎核心分支 ≥ 90%） |
| data/repository | ≥ 70% |
| app（UI/胶水） | 不设硬门槛，以 TC-UI 关键流为准 |

## 7. 手动测试矩阵（每 Phase 收尾 + 发版前）

| 场景 | 检查点 |
|---|---|
| 真机语音 | 命令窗口对"会了"识别率（安静/嘈杂）；TTS 播放中说话**必须无效** |
| TTS 双语质量 | en-US 与 zh-CN 发音自然度；SPELLING 0.8× 可懂度 |
| 暂停恢复实感 | 恢复后听觉上"从断点续"（TTS 段重读为已知限制，验证不回到整词开头） |
| 音频焦点 | 来电/其他 App 抢焦点 → 自动暂停，回归焦点不自动播放（安全） |
| 权限流 | 麦克风拒绝 → 降级路径可用（NFR-8） |
| 大文件真机 | 5 万行 GBK 真机导入时长 + 取消 |
| 熄屏/后台 | 会话状态与音频焦点行为符合预期 |

## 8. CI 计划（Phase 1 落地）

- Windows runner：`:shared:jvmTest` + detekt + Kover 报告（**核心回归不等 macOS**）；
- macOS runner（iOS 阶段起）：`:shared:allTests`（iosSimulatorArm64）+ Xcode 构建；
- 每次合并门禁：JVM 测试绿 + detekt 绿 + 覆盖率不回退。

## 9. 测试数据策略

- 词库 fixtures：JSON 种子（含多词性/多例句/多来源词，与 Phase 2 种子数据同源）；
- 编码样例：字节数组常量（BOM/GBK/混错）内嵌测试，不依赖磁盘文件；
- Fake 端口：可编程段完成时序、命令命中脚本、超时注入。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-01 | 冻结 D1–D4：TC-LE-07/08 升级，新增 TC-LE-11（分支 C）与 TC-DB-07（type/血缘约束） |
| 1.2 | 2026-09-01 | FR-5 粒度细化（PROJECT_SPEC v1.3）：新增 TC-DM-05；TC-DB-01 增 (wordBookEntryId,exampleId)；TC-DB-06 记 v1→v2 首迁移 |
| 1.3 | 2026-09-03 | Phase 3 规格对齐：§4.3 标题改"十一条边界"（ROADMAP 出口统一为 TC-LE-01…11）；TC-LE-01/02/04/07 补 §10 边界映射（空本、<groupSize、开播即会了、导入词、事务快照）+ ACTIVE 唯一性断言并入 TC-LE-01；**修正 TC-LE-10 语义**（词已移除 → 剔除该词其余照常，非整会话 ABANDON，对齐 LE spec §10-9）；TC-DB-07 标注 Phase 2 遗留、随 Phase 3 电池补齐 |
| 1.4 | 2026-09-04 | Step 5D 验收裁决（交集语义）：TC-LE-07 补 mid-session 母本编辑三边界——Case 1 会话外新增词不进派生本 / Case 2 会话中移除词不进派生本 / Case 3 交集为空**不创建空 DERIVED 本**（derivedWordBookId=null、会话 ABANDONED、endedAt 写入）；命名断言升级为注入时区精确格式 + 重名 -2/-3 两级 |
