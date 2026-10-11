# 实现计划：SCR-IPACOVERAGE（单词+短语双音标全量补齐，资产 v9）

> 日期：2026-10-10 | 前置：用户批准 SCR（**D3 v2：短语纳入**，裁决 2026-10-10）| 对应审计：AUDIT_REPORT_IPA_COVERAGE.md

## 阶段 0：引擎就位与 pilot（人审签收门）——已完成（2026-10-10）

1. ~~MSI 落地~~ ✅ github 代理窗口 3 次重试后落地（6MB，签名验证 MSI）。
2. ~~解包~~ ✅ **必须 `msiexec /a` 管理员镜像**至 `tools/dict/espeak-ng-a/`（7-Zip 直解包扁平化
   目录结构 → voice 全失联，实测否决）+ UCRT app-local（`System32\downlevel\api-ms-win-crt-*.dll` +
   `ucrtbase.dll` 拷至 exe 旁，Win10 19045 必需）。
3. ~~双口音冒烟与断言~~ ✅ rhotic/trap-bath/lot/闪音判据全过（SCR D1 表）。
4. ~~pilot 500 词~~ ✅ 分层（高频/后缀/长尾/病理/随机各 100）双口音表落
   `%TEMP%/enrich_work/ipa_pilot_table.txt`；抽样清单 `%TEMP%/enrich_work/ipa_pilot_sample.txt`。
   **待办：用户人眼签收 pilot 表（含 Wiktionary 对照 20 词）——签收前禁止全量跑批（质量门）。**
5. ~~批量通道实测~~ ✅ stdin 行模式：单词每行入、每词一行出、行数守恒（10k→10k、零空行、3.9s）；
   全量双列 ~4 分钟。**写库硬门 = 行数守恒 + 非空值零触碰断言**。

## 阶段 1：fill_ipa.py（tools/dict，入 git）

- 读当前资产（直改，不经 build_v6 重建——绕开管线单向性 FATAL；填前备份
  `%TEMP%/enrich_work/ecdict.sqlite.v8-pre-ipa.bak`，rename EXDEV 先例 → copyFile+unlink）。
- 选行：**全部词条**（用户裁决含短语，SCR D3 v2）；`ipa=''` → 填 en-us 产物；`ipaBr IS NULL OR ipaBr=''` → 填 en-gb 产物；
  **非空值零接触**。espeak 调用 = stdin 行模式分批（每批 5 万行，双口音各一遍），
  输出剥首尾空白；**每批行数守恒断言**，违例 FATAL 不写库。
- 写完 `PRAGMA user_version=9`。
- 自校验（脚本内置 FATAL 门）：
  - 覆盖率 = 全部 836,398 条两列双全；
  - 非空值 diff = 0（改前非空集合逐值比对，只允许 NULL/'' → 值）；
  - 短语行计数与值逐字节不变；
  - probe 断言：flayer 双列非空、slayer/maker/zebra/octodecillion 既有值原样。
- 体积与耗时统计输出。

## 阶段 2：门禁与文档

- `BundledDictionaryProvider.DICT_ASSET_VERSION` 8→9（忘改 = 设备静默不更新，v7 实录教训）。
- 门禁：`./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:assembleDebug` + detekt
  （预期全绿——零行为变更，回归性质）。
- 文档：PROJECT_SPEC（FR-18 增补「单词双音标 espeak-ng 补齐覆盖条款」+ 版本记录）、
  tools/dict README（第 9 步再生说明 + espeak-ng 解包步骤）、DECISION_LOG 终条目（终数+抽样结果）。
  TEST_PLAN 零新用例（纯数据填充，回填行为已有 TC 覆盖）——如装机走查抓出行为问题再议。

## 阶段 3：装机走查（vivo）

1. v8→v9 资产重拷门禁生效（filesDir mtime/user_version 取证）。
2. flayer 查词：US/UK 双音标显示；朗读口音切换照常（音标纯展示）。
3. 已导入词回填：旧库导入时音标为空的词 → lookup 后 DB `ipa`/`ipaBr` 回填（run-as sqlite3 取证，幂等二次 lookup 零写）。
4. probe 组：flayer / 抽样新填长尾词 / 既有 ECDICT 记法词（maker）同屏显示无异常。

## 收尾

- DECISION_LOG 记终数（填充条数、资产字节、耗时、抽样签收记录）；
- 随下个 release 发布（APK 预计 +10~12MB）；
- 「bug list.md」如有对应条目划记（永不提交）。

## 回滚

- 资产回滚 = 回置 `%TEMP%/enrich_work/ecdict.sqlite.v8-pre-ipa.bak` + `DICT_ASSET_VERSION` 回 8
  （与 v8 备份同款流程）；脚本独立于既有管线，无代码耦合。
