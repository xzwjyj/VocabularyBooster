# 实现计划：SCR-EXREVIEW 例句译文术语审校批（资产 v10）

> 2026-10-11。批准记录：用户交互批准方案 A（审校批 ~14.4k）+ qwen3:14b。
> 依赖：审计产物 `%TEMP%/enrich_work/term_mismatch_tier1.jsonl`（13,298）+ 全库模板句扫描（3,301）。

## 步骤

1. **llm_tasks.js 导出化微改**：`main()` 调用加 `require.main === module` 守卫，
   `module.exports = { wordPresent, BLOCK_RE, validZh }`——供 review_tasks.js 复用，
   校验逻辑零变更（39 用例资产不动）。
2. **review_tasks.js**（tools/dict/，入 git）：
   - 任务收集：tier-1 文件 ∪ 资产模板句扫描（键 = `r:word#di#en.slice(0,60)`），
     从资产带出词性 + 目标释义 EN/CN；
   - `sample [n]`（抽检）/ `run`（全量，parked≥2 跳过同 gen2）/ `sweep`（终扫）/
     `status`；
   - 输出 `review_out.jsonl`：`{k, v:"ok"}` / `{k, v:"fix", zh}` / `{k, v:"regen", en, zh}`。
3. **跑批**：`ollama serve >/dev/null`（OLLAMA_NUM_PARALLEL=16）→ `sample 50` 自查 →
   全量 `run`（后台 node，review_detached.log）→ `sweep` 收尾。
4. **门禁与抽检**：跑批后统计 verdict 分布；随机 50 条（分层 ok/fix/regen）用户签收。
5. **写库 v10**（新脚本 `apply_review.py`，fill_ipa 先例）：
   备份 v9 → 按（word, 原 en 精确匹配）应用 fix/regen → `user_version=10` →
   `--check` 独立复核（probe + 珍珠层=0 + 行数守恒）。
6. **Kotlin 门禁**：`DICT_ASSET_VERSION` 9→10。
7. **管线记录**：llm_out.jsonl 追加 `g:` 覆盖（fix=原 en+新 zh；regen=新 en+新 zh）。
8. **文档**：DECISION_LOG + tools/dict README 第 10 步（v10 注记）。

## 回滚

- 资产：`%TEMP%/enrich_work/ecdict.sqlite.v9.bak` 回置 + user_version 复位即可。
- llm_out.jsonl：追加条目不动旧键，回滚无需删行（重建时移除本批追加行即可）。
