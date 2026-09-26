#!/usr/bin/env python3
"""
单词匹配模块 - 从转写文本中匹配目标单词的句子
"""

import json
import re
from pathlib import Path
from typing import Optional

try:
    import jellyfish
    HAS_JELLYFISH = True
except ImportError:
    HAS_JELLYFISH = False

# 词级剪切边界的前导/尾随余量（秒）：呼吸前导 + 尾音自然衰减（SCR-AUDIOTRIM）
WORD_LEAD_IN_SEC = 0.25
WORD_TAIL_OUT_SEC = 0.5

# 跨 segment 句子合并（SCR-SENTMERGE）：句末标点 + 紧邻间隔阈值（秒）
SENTENCE_TERMINALS = '.?!'
SENT_MERGE_MAX_GAP_SEC = 1.0


# 简单词形还原表
LEMMATIZE = {
    'legions': 'legion',
    'clutching': 'clutch',
    'clutched': 'clutch',
    'sieges': 'siege',
    'precipices': 'precipice',
    'rabids': 'rabid',
    'rabbit': 'rabid',  # 语音相近词
    '狼': 'wolf',  # 中文 rough
}


def lemmatize(word: str) -> str:
    """词形还原（简单版）"""
    word = word.lower()
    if word in LEMMATIZE:
        return LEMMATIZE[word]

    # 简单规则
    if word.endswith('ies'):
        return word[:-3] + 'y'
    if word.endswith('es'):
        return word[:-2]
    if word.endswith('ed'):
        return word[:-2]
    if word.endswith('ing'):
        return word[:-3]
    if word.endswith('s') and not word.endswith('ss'):
        return word[:-1]
    return word


def normalize_word(word: str) -> str:
    """标准化单词（转小写，去除标点 + 词形还原）"""
    return lemmatize(re.sub(r'[^\w]', '', word.lower()))


def soundex_match(word1: str, word2: str) -> bool:
    """检查两个单词语音相似（Metaphone 等值；首字母须一致）。

    用 Metaphone 而非 Soundex：Soundex 区分度低（reviewing/ravenous、seek/siege
    都会撞码产生噪音例句），Metaphone 编码辅音细节可区分；Whisper 同音误识别
    （rabbit/rabid → 均 RBT）仍能命中。
    """
    if not HAS_JELLYFISH:
        return False
    try:
        if word1[0] != word2[0]:
            return False
        return jellyfish.metaphone(word1) == jellyfish.metaphone(word2)
    except:
        return False


def correct_sentence(sentence: str, target_word: str, all_targets: Optional[list[str]] = None) -> str:
    """将句中 Whisper 误识别的语音相近词纠正为目标词拼写。

    只纠正"拼写差异大"的误识别（rabbit -> rabid）；
    目标词的合法变形（clutching / legions 等以目标词为前缀）保留原形。
    纠正范围覆盖全部目标词（all_targets）：同一句例句会出现在多个词条下，
    不能只纠正当前词条的目标词（rabid 词条已纠正而 ravenous 词条仍显示 rabbit）。

    Args:
        sentence: 句子文本
        target_word: 当前目标单词
        all_targets: 本次提取的全部目标词（默认仅当前目标词）

    Returns:
        纠正后的句子
    """
    targets = [t.lower() for t in (all_targets or [target_word])]

    def fix(match: re.Match) -> str:
        word = match.group(0)
        wl = word.lower()
        # 任一目标词本身或其合法变形（前缀关系）：保留
        for t in targets:
            if wl == t or wl.startswith(t) or t.startswith(wl):
                return word
        # 与某目标词词形还原命中 + 语音等值（Metaphone）= 误识别：替换
        if len(wl) >= 3:
            for t in targets:
                if lemmatize(wl) == t and soundex_match(wl, t):
                    return t.capitalize() if word[0].isupper() else t
        return word

    return re.sub(r"[A-Za-z]+(?:'[A-Za-z]+)?", fix, sentence)


def find_word_in_sentence(sentence: str, target_word: str) -> bool:
    """
    检查句子中是否包含目标单词（支持词形还原 + 语音相似匹配）

    Args:
        sentence: 句子文本
        target_word: 目标单词

    Returns:
        是否找到匹配
    """
    target = target_word.lower()
    target_lemma = lemmatize(target)
    sentence_lower = sentence.lower()

    # 1. 精确匹配
    pattern = r'\b' + re.escape(target) + r'\b'
    if re.search(pattern, sentence_lower):
        return True

    # 2. 词形还原匹配
    pattern = r'\b' + re.escape(target_lemma) + r'\b'
    if re.search(pattern, sentence_lower):
        return True

    # 3. 提取句子中的所有单词，检查词形还原
    words = re.findall(r'\b\w+\b', sentence_lower)
    for word in words:
        if lemmatize(word) == target_lemma:
            return True
        # 4. 语音相似匹配（用于纠正 Whisper 识别错误）
        if len(word) >= 3 and len(target) >= 3:
            if soundex_match(word, target):
                return True

    return False


def speech_bounds_from_words(
    segment: dict,
    lead_in: float = WORD_LEAD_IN_SEC,
    tail_out: float = WORD_TAIL_OUT_SEC
) -> Optional[dict]:
    """由词级时间戳推剪切边界。

    Whisper segment 的 start 在持续 BGM 下常远早于首个发声词（巫师三批次
    clutch 实测 22.4s 片段语音 11.2s 才开始），直接用 segment 边界会带
    长 BGM 前奏。有词级时间时改用 首词−lead_in → 末词+tail_out。

    Returns:
        {"start":…, "end":…}；无词级时间返回 None（调用方回退 segment 边界）
    """
    words = segment.get("words") or []
    if not words:
        return None
    first = min(w["start"] for w in words)
    last = max(w["end"] for w in words)
    if last <= first:
        return None
    return {
        "start": max(0.0, first - lead_in),
        "end": last + tail_out
    }


def merge_continuation_segments(
    segments: list[dict],
    max_gap: float = SENT_MERGE_MAX_GAP_SEC
) -> list[dict]:
    """跨 segment 句子合并（SCR-SENTMERGE，2026-09-26）。

    Whisper VAD 会把一句长句劈成两个紧邻 segment（巫师三批次实证：seg1
    "…laid siege to every fortress" 无句末标点 + seg2 28.62s 紧邻续接
    "from here to the Blue Mountains…"）——segment 不是可靠句子边界。
    预合并规则：上一段文本不以句末标点（.?!）结尾，且当前段 start ≤ 上一段
    end + max_gap → 合并文本/词表/时间跨度；句末标点即停。

    返回浅拷贝列表（不改动调用方传入的 segment dict）。
    """
    merged: list[dict] = []
    for seg in segments:
        if merged:
            prev = merged[-1]
            prev_text = (prev.get("text") or "").strip()
            cur_start = seg.get("start", 0)
            if prev_text and prev_text[-1] not in SENTENCE_TERMINALS and \
                    cur_start <= prev.get("end", 0) + max_gap:
                cur_text = (seg.get("text") or "").strip()
                prev["text"] = (prev_text + " " + cur_text).strip()
                prev["end"] = max(prev.get("end", 0), seg.get("end", 0))
                words = (prev.get("words") or []) + (seg.get("words") or [])
                if words:
                    prev["words"] = words
                continue
        merged.append(dict(seg))
    return merged


def extract_sentences_for_words(
    segments: list[dict],
    target_words: list[str]
) -> dict[str, list[dict]]:
    """
    从转写片段中提取包含目标单词的句子

    Args:
        segments: Whisper 输出的时间戳片段列表
        target_words: 目标单词列表

    Returns:
        单词到句子列表的映射
    """
    # 跨 segment 句子合并（SCR-SENTMERGE）：VAD 边界不是句子边界
    segments = merge_continuation_segments(segments)

    # 构建目标单词集合（用于快速查找）
    target_set = {normalize_word(w) for w in target_words}

    # 收集每个目标单词的句子
    matched: dict[str, list[dict]] = {w: [] for w in target_words}

    for segment in segments:
        text = segment.get("text", "")
        if not text:
            continue

        # 检查每个目标单词
        for word in target_words:
            if find_word_in_sentence(text, word):
                # 剪切边界优先词级（去 BGM 前奏/尾奏），无词级时间回退 segment 边界
                bounds = speech_bounds_from_words(segment) or {
                    "start": segment.get("start", 0),
                    "end": segment.get("end", 0)
                }
                matched[word].append({
                    # 纠正 Whisper 误识别的语音相近词（rabbit -> rabid），保留合法变形
                    "sentence": correct_sentence(text, word, all_targets=target_words),
                    "start": bounds["start"],
                    "end": bounds["end"]
                })

    return matched


def match_words(
    transcript: str,
    target_words: list[str]
) -> dict[str, list[str]]:
    """
    从转写文本中匹配目标单词的句子（简化版，无时间戳）

    Args:
        transcript: 转写文本（可以是纯文本或 JSON）
        target_words: 目标单词列表

    Returns:
        单词到句子列表的映射
    """
    # 尝试解析为 JSON
    try:
        segments = json.loads(transcript)
        if isinstance(segments, dict) and "segments" in segments:
            segments = segments["segments"]
    except (json.JSONDecodeError, AttributeError):
        # 不是 JSON，视为纯文本
        segments = [{"text": transcript, "start": 0, "end": 0}]

    if not isinstance(segments, list):
        segments = [{"text": str(segments), "start": 0, "end": 0}]

    # 提取每个目标单词的句子
    matched_segments = extract_sentences_for_words(segments, target_words)

    # 转换为简化格式（只保留句子文本）
    result = {}
    for word, segs in matched_segments.items():
        if segs:
            result[word] = [seg["sentence"] for seg in segs]

    return result


def match_words_with_timestamps(
    transcript: str,
    target_words: list[str]
) -> dict[str, list[dict]]:
    """
    从转写文本中匹配目标单词的句子（带时间戳）

    Args:
        transcript: 转写文本（JSON 格式）
        target_words: 目标单词列表

    Returns:
        单词到句子列表的映射，每个句子包含 text, start, end
    """
    # 尝试解析为 JSON
    try:
        segments = json.loads(transcript)
        if isinstance(segments, dict) and "segments" in segments:
            segments = segments["segments"]
    except (json.JSONDecodeError, AttributeError):
        return {}

    if not isinstance(segments, list):
        return {}

    # 提取每个目标单词的句子
    return extract_sentences_for_words(segments, target_words)


def match_from_srt(
    srt_path: str,
    target_words: list[str]
) -> dict[str, list[dict]]:
    """
    从 SRT 字幕文件匹配目标单词

    Args:
        srt_path: SRT 字幕文件路径
        target_words: 目标单词列表

    Returns:
        单词到句子（带时间戳）的映射
    """
    from speech_recognizer import load_subtitles

    segments = load_subtitles(srt_path)
    return extract_sentences_for_words(segments, target_words)


def merge_overlapping_segments(
    segments: list[dict],
    min_gap: float = 0.5
) -> list[dict]:
    """
    合并时间上重叠或接近的片段

    Args:
        segments: 时间戳片段列表
        min_gap: 最小间隔（秒），小于此值则合并

    Returns:
        合并后的片段列表
    """
    if not segments:
        return []

    # 按开始时间排序
    sorted_segments = sorted(segments, key=lambda x: x.get("start", 0))

    merged = [sorted_segments[0]]

    for seg in sorted_segments[1:]:
        last = merged[-1]
        gap = seg.get("start", 0) - last.get("end", 0)

        if gap < min_gap:
            # 合并
            last["end"] = max(last.get("end", 0), seg.get("end", 0))
            last["text"] = last.get("text", "") + " " + seg.get("text", "")
        else:
            merged.append(seg)

    return merged


if __name__ == "__main__":
    # 测试
    test_transcript = """
    [
        {"start": 0.0, "end": 5.0, "text": "Hello everyone, welcome to the show."},
        {"start": 5.0, "end": 10.0, "text": "Today we are going to talk about learning English."},
        {"start": 10.0, "end": 15.0, "text": "When you learn a new word, practice is important."},
        {"start": 15.0, "end": 20.0, "text": "Don't abandon your studies halfway."}
    ]
    """

    words = ["learn", "abandon", "practice"]
    result = match_words(test_transcript, words)
    print(json.dumps(result, indent=2, ensure_ascii=False))
