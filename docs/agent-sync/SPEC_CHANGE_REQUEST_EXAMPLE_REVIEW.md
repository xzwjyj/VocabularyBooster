# 规格变更请求：SCR-EXREVIEW 例句译文术语审校批（资产 v10）

> 日期：2026-10-11
> 需求来源：用户（装机报告「nautiloid 例句把 the nautiloid shell 译成珍珠层」→ 追问裁决
> 「类似其它词汇翻译不好的例子，也一起改掉」→ 方案 A：审校批 ~14.4k + qwen3:14b）

## 变更概述

随包词典资产中 **AI 生成例句（"g" 标记）的术语级误译**批量审校修正：检出集合 =
「EN 例句含词头 + 译文与释义中文零共享汉字」（tier-1，13,298 条）∪「模板幻觉句」（3,301 条，
重叠 2,248）= **14,351 条**（初审估 14,445 用了缩写版模板正则，全 6 分支口径重叠实为 2,248），
逐条由本地 qwen3:14b 判定 OK / 重译 / 整句重生成，
nautiloid 手工修正并入本批。纯数据资产变更（v9 → v10），运行时代码唯一改动 =
`DICT_ASSET_VERSION` 门禁常量同步 9→10。

## 背景与现状审计（2026-10-11 实测，脚本 `%TEMP%/enrich_work/audit_term_mismatch.py`）

- 触发词 `nautiloid`：gen2 生成例句 EN 无问题，中文把 the nautiloid shell 误作「珍珠层」
  （应为「鹦鹉螺壳」）——全库「珍珠层」仅此 1 条，但同类术语错误系统性存在。
- 检测法：词条释义中文（meaningCN）天然给出词头标准术语；对「EN 例句含词头（词边界匹配）」
  的例句做译文-释义中文重叠检测，两档：
  - **bigram 级**（相邻两字对无交集）71,737 旗标——疑似池，抽样显示误报为主
    （`take off` 释义「脱下」译文「脱掉」这类同义换词两字对全错开）；
  - **tier-1**（连单个汉字都不共享）**13,298**——硬伤池，抽样 40 条约半数为真错：
    术语换概念（`aster turbinellis` 紫菀属植物→「海蜗牛」）、语义译反
    （`sappression of menses` 停经→「子宫出血停止」）、译文夹英文
    （`hectically` 译文混 "frenziedly"）、合法保留原文（`polyram`/`echinid` 科技术语）。
- **模板幻觉句** 3,301（正则确定性判定："The form X appears in historical dictionaries…"、
  "recorded as a variant…" 等）：例句本身是套话，无学习价值，无论译文好坏须整句重生成；
  其中 2,248 已在 tier-1 内，**待修并集 14,351**。
- 根因：gen2（v7）用 qwen3:8b 为 729,304 个零例句生僻词条逐释义生成 818,241 条例句，
  模型对生僻术语语境幻觉；「找不到例句的释义不能零例句」的覆盖优先目标下尾部质量坑。

## 技术方案

### D1 审校器 = `tools/dict/review_tasks.js`（新建，入 git；qwen3:14b）

- 照抄 `llm_tasks.js` 基建：append-only 输出 `review_out.jsonl`（键去重断点续传）、
  失败纠正性重试一次后 parked（`review_failed.jsonl`）、ECONNREFUSED 快速中止。
- 任务输入 = 词头 + 词性 + 目标释义（EN+CN）+ 现例句 EN + 现译文 CN；输出单行判定：
  - `OK`——译文忠实（含合法保留英文原词的缩写/学名/科技术语）；
  - `FIX <新译文>`——EN 例句可用，仅重译；
  - `REGEN <新句> | <新译>`——EN 本身幻觉/模板/病句，整句重生成。
- 模型 = 本地 ollama `qwen3:14b`（think:false 同 8b 通道）。

### D2 门禁（防机器改机器失控）

- FIX：新译文必须与释义 CN 有 CJK 字重叠**或**包含词头原文（保留英文原词合法）；
  `validZh`（长度/含中文/BLOCK_RE）同既有标准。
- REGEN：EN 必须过 **wordPresent**（llm_tasks.js 六轮打磨、39 用例验证的词头在场校验，
  本批对 llm_tasks.js 仅做 `require.main === module` 导出化微改，逻辑零变更）
  + 长度 15–150 + 句末标点 + BLOCK_RE；zh 同 FIX 规则。
- 两级都不过的 parked 不强写；跑批完成后**随机抽 50 条给用户签收后才写库**。

### D3 写库 = fill_ipa 先例资产直改（不经 build_v6.js 重建）

- 备份现网 v9 → `%TEMP%/enrich_work/ecdict.sqlite.v9.bak`；单事务按（词头, 原句精确匹配）
  定位替换：FIX 只换 zh，REGEN 换 en+zh，"g" 标记保留（如实标注来源）。
- `user_version=10`（资产版本铁律）+ Kotlin `DICT_ASSET_VERSION` 9→10 同步
  （忘改 = 设备静默不更新，v7 教训）。
- 独立复核（--check）：nautiloid probe 新译文在位 + 全库「珍珠层」计数归零 +
  实改行数 = 批内 FIX+REGEN 数 + 其余行零改动。

### D4 重建安全 = llm_out.jsonl 追加 `g:` 覆盖条目

- build_v6.js 的 llm map 为 `Map.set` 后写覆盖：FIX 追加 `{k:"g:word#di", en:原句, zh:新译}`、
  REGEN 追加 `{k:"g:word#di", en:新句, zh:新译}`——未来从 v6 源确定性重建不回退本批修正
  （hand_d3.js 同思路）。

### D5 范围边界（明确不做）

- bigram 级 71,737 旗标、全量 818,241 条 g 例句的系统性重生成不在本批
  （误报为主 / 成本×5 以上）；语料例句（Tatoeba）同义转述为正常译文不动。

## 风险与取舍

- [x] 中风险（数据面）——qwen3:14b 审校仍可能误判：
  - 门禁 + 50 条人工抽检双闸；parked 不强写；
  - REGEN 新句质量由 wordPresent + 既有生成提示词约束（与 gen2 同格式）。
- 跑批时长估 2–4 小时（14.4k × qwen3:14b，并发 8；ollama serve 输出重定向防 5.1GB 日志复发）。

## 验收标准

1. 并集 14,445 条全部有 verdict（ok / fix / regen / parked 明细计数）。
2. 修正行过 D2 门禁；parked 清单留档可查。
3. 资产 v10：nautiloid 译文 =「鹦鹉螺壳是古代海洋生物以化石形式保存下来的一个迷人例子。」；
   probe 词（aster turbinis、sappression of menses 等）译文修正。
4. 设备 v9→v10 门禁重拷生效；已导入词 lookup 幂等回填收敛（既有链路零变更）。
5. 抽检 50 条用户签收。

---

**用户已交互批准方案 A + qwen3:14b（2026-10-11），按 IMPLEMENTATION_PLAN_EXAMPLE_REVIEW.md 执行**
