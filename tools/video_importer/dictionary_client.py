#!/usr/bin/env python3
"""
词典查询模块 - 从本地数据库获取单词释义
优先使用本地词典（+ Datamuse 补充缺失词性义项），备用网络 API
"""

import json
import re
import sys
import time
from pathlib import Path
from typing import Optional
import sqlite3

# 本地词典路径
LOCAL_DICT_PATH = Path(__file__).parent.parent.parent / "app/src/main/assets/dict/ecdict.sqlite"

# Free Dictionary API (备用)
DICTIONARY_API_BASE = "https://api.dictionaryapi.dev/api/v2/entries/en"

# Datamuse API（补充本地词典缺失的词性义项，如动词义）
DATAMUSE_API = "https://api.datamuse.com/words"

# 主题词库：上下文主题词 ↔ 释义主题词（通用规则，不针对具体单词）
TOPIC_LEXICONS = {
    "military": {
        "context": {"marched", "march", "army", "fortress", "siege", "battle", "war",
                    "troops", "soldiers", "emperor", "legion", "legions", "kingdom", "sword"},
        "def": {"military", "army", "troop", "force", "unit", "division", "soldier"},
    },
    "hunger": {
        "context": {"hungry", "hunger", "starve", "starving", "famished", "devour", "bite", "bites"},
        "def": {"hungry", "hunger", "famished", "starving", "greedy", "devour", "ravenous",
                # 饥饿/撕咬语境下的凶猛引申义（如 rabid and ravenous）
                "furious", "raging", "violent", "extreme", "savage", "vicious", "mad"},
    },
}

# Datamuse 词性缩写 → 全称
_DATAMUSE_POS_MAP = {"n": "noun", "v": "verb", "adj": "adjective", "adv": "adverb"}


def _is_verb_form(word: str) -> bool:
    """判断上下文里的词形是否是动词形式（-ing/-ed 是可靠标记；-s 可能是复数不算）"""
    return word.endswith("ing") or word.endswith("ed")


def select_best_definition(definitions: list, context_sentences: list[str], target_word: str = "") -> list:
    """
    根据上下文句子选择最合适的释义（通用规则，无单词级硬编码）

    规则：
    1. 释义英文关键词与上下文重合 → 每词 +3
    2. 目标词在上下文中以 -ing/-ed 动词形式出现 → 动词释义 +12
       （-s 可能是名词复数，不做动词推断）
    3. 主题词库匹配（如军事语境 ↔ military/army 释义）→ +10

    Args:
        definitions: 释义列表 [[pos, en, cn], ...]
        context_sentences: 上下文句子列表
        target_word: 目标单词（用于判断其词形）

    Returns:
        最佳释义
    """
    if not definitions or not context_sentences:
        return definitions[0] if definitions else ["noun", "", ""]

    context_text = " ".join(context_sentences).lower()
    context_words = set(re.findall(r'\b[a-z]+\b', context_text))

    # 目标词是否以动词形式出现在上下文中（如 clutch -> clutching）
    target = target_word.lower().strip()
    inflected_forms = set()
    if target:
        for w in context_words:
            lemma = w
            if _is_verb_form(w):
                # clutching -> clutch / clutched -> clutch（简化还原）
                if w.endswith("ing"):
                    lemma = w[:-3]
                elif w.endswith("ed"):
                    lemma = w[:-2]
                if lemma == target or lemma.rstrip("e") == target or lemma + "e" == target:
                    inflected_forms.add(w)

    target_used_as_verb = len(inflected_forms) > 0

    # 匹配的主题
    active_topics = []
    for topic, lex in TOPIC_LEXICONS.items():
        if context_words & lex["context"]:
            active_topics.append(topic)

    best_def = definitions[0]
    best_score = -1

    # 主题词不参与关键词重合计分：主题信号已由规则 3 表达，且主题词（force/fortress 等）
    # 同时出现在多个义项中会让重合分抵消主题区分度（siege 动词义曾借此反超名词义）
    topic_words = set()
    for lex in TOPIC_LEXICONS.values():
        topic_words |= lex["context"] | lex["def"]

    for definition in definitions:
        if len(definition) < 2:
            continue

        pos = (definition[0] or "").lower()
        en_meaning = (definition[1] or "").lower()

        score = 0

        raw_meaning_words = set(re.findall(r'\b[a-z]+\b', en_meaning))

        # 1. 关键词重合（排除主题词，避免重复计分）
        score += len((raw_meaning_words - topic_words) & context_words) * 3

        # 2. 动词形式 → 动词释义
        if target_used_as_verb and ("verb" in pos or pos.startswith("v")):
            score += 12

        # 3. 主题匹配（用未排除的原始释义词）
        for topic in active_topics:
            if raw_meaning_words & TOPIC_LEXICONS[topic]["def"]:
                score += 10

        if score > best_score:
            best_score = score
            best_def = definition

    return best_def


def _fetch_datamuse_definitions(word: str) -> list[list[str]]:
    """
    从 Datamuse API 获取单词释义（WordNet 数据，覆盖多词性）

    Returns:
        释义列表 [[pos, en, ''], ...]，失败返回空列表
    """
    import urllib.request
    import urllib.parse

    url = f"{DATAMUSE_API}?{urllib.parse.urlencode({'sp': word, 'md': 'd', 'max': 1})}"

    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            data = json.loads(response.read().decode("utf-8"))

        if not data or not isinstance(data, list):
            return []

        defs = data[0].get("defs", [])
        result = []
        for d in defs:
            parts = d.split("\t", 1)
            if len(parts) == 2:
                pos = _DATAMUSE_POS_MAP.get(parts[0], parts[0])
                result.append([pos, parts[1], ""])
        return result

    except Exception as e:
        print(f"  Datamuse error: {e}", file=sys.stderr)
        return []


def _merge_definitions(local_defs: list, datamuse_defs: list) -> list:
    """合并本地与 Datamuse 释义（按 pos+英文前缀去重，本地在前）"""
    seen = set()
    merged = []
    for d in local_defs:
        key = ((d[0] or "").lower(), (d[1] or "").lower()[:40])
        if key not in seen:
            seen.add(key)
            merged.append(d)
    for d in datamuse_defs:
        key = ((d[0] or "").lower(), (d[1] or "").lower()[:40])
        if key not in seen:
            seen.add(key)
            merged.append(d)
    return merged


def fetch_definition(word: str, max_retries: int = 3, retry_delay: float = 1.0, context_sentences: list[str] = None) -> Optional[dict]:
    """
    从本地词典获取单词释义，根据上下文选择最佳释义

    Args:
        word: 单词
        max_retries: 最大重试次数（备用 API 用）
        retry_delay: 重试间隔（秒）
        context_sentences: 上下文句子列表，用于选择最佳释义

    Returns:
        包含 meaningEN, meaningCN, partOfSpeech 的字典，或 None（如果失败）
    """
    # 尝试本地词典
    if LOCAL_DICT_PATH.exists():
        try:
            conn = sqlite3.connect(str(LOCAL_DICT_PATH))
            cursor = conn.cursor()

            cursor.execute(
                'SELECT text, ipa, definitions, examples FROM DictEntry WHERE normalized=? OR text=?',
                (word.lower(), word)
            )
            result = cursor.fetchone()
            conn.close()

            if result:
                text, ipa, definitions_json, examples_json = result

                # 解析 definitions
                try:
                    definitions = json.loads(definitions_json)
                    if definitions and len(definitions) > 0:
                        # Datamuse 补充本地词典缺失的词性义项（如 clutch 的动词义）
                        datamuse_defs = _fetch_datamuse_definitions(word.lower())
                        all_defs = _merge_definitions(definitions, datamuse_defs)

                        # 根据上下文选择最合适的释义
                        if context_sentences:
                            best_def = select_best_definition(all_defs, context_sentences, target_word=word.lower())
                        else:
                            best_def = all_defs[0]

                        part_of_speech = best_def[0] if best_def else "noun"
                        meaning_en = best_def[1] if len(best_def) > 1 else ""
                        meaning_cn = best_def[2] if len(best_def) > 2 else ""
                    else:
                        return None
                except (json.JSONDecodeError, IndexError):
                    return None

                # 选中义项无中文释义时（本地词条 CN 为空 / Datamuse 义项），机器翻译回填
                if not meaning_cn and meaning_en:
                    try:
                        from translator import translate_to_chinese
                        meaning_cn = translate_to_chinese(meaning_en)
                    except Exception as e:
                        print(f"  CN translation error: {e}", file=sys.stderr)
                        meaning_cn = ""

                return {
                    "word": text,
                    "meaningEN": meaning_en,
                    "meaningCN": meaning_cn,
                    "partOfSpeech": part_of_speech,
                    "ipa": ipa or ""
                }
        except Exception as e:
            print(f"  Local dict error: {e}", file=sys.stderr)

    # 备用：尝试网络 API
    return _fetch_definition_from_api(word, max_retries, retry_delay)


def _fetch_definition_from_api(word: str, max_retries: int = 3, retry_delay: float = 1.0) -> Optional[dict]:
    """
    从 Free Dictionary API 获取单词释义（备用）
    """
    import urllib.request
    import urllib.error

    url = f"{DICTIONARY_API_BASE}/{word}"

    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(url, timeout=15) as response:
                data = json.loads(response.read().decode("utf-8"))

            if not data or not isinstance(data, list):
                return None

            entry = data[0]
            meanings = entry.get("meanings", [])

            if not meanings:
                return None

            # 取第一个词性
            first_meaning = meanings[0]
            part_of_speech = first_meaning.get("partOfSpeech", "noun")

            # 取第一个英文释义
            definitions = first_meaning.get("definitions", [])
            if not definitions:
                return None

            meaning_en = definitions[0].get("definition", "")

            # 尝试获取中文释义（如果有）
            meaning_cn = ""

            return {
                "word": word,
                "meaningEN": meaning_en,
                "meaningCN": meaning_cn,
                "partOfSpeech": part_of_speech
            }

        except urllib.error.HTTPError as e:
            if e.code == 404:
                print(f"  Warning: '{word}' not found in dictionary", file=sys.stderr)
                return None
            elif e.code in (403, 422, 429, 500, 502, 503, 520, 522):
                # 服务器错误，重试
                if attempt < max_retries - 1:
                    print(f"  Retry {attempt + 1}/{max_retries}: {word} (HTTP {e.code})")
                    time.sleep(retry_delay * (attempt + 1))
                    continue
            print(f"  HTTP error {e.code}: {word}", file=sys.stderr)
            return None
        except urllib.error.URLError as e:
            # 网络错误，重试
            if attempt < max_retries - 1:
                print(f"  Retry {attempt + 1}/{max_retries}: {word} (network error)")
                time.sleep(retry_delay * (attempt + 1))
                continue
            print(f"  Network error: {e}", file=sys.stderr)
            return None
        except Exception as e:
            print(f"  Error: {e}", file=sys.stderr)
            return None

    return None


def fetch_definitions_batch(words: list[str], delay: float = 0.5) -> dict[str, dict]:
    """
    批量查询单词释义

    Args:
        words: 单词列表
        delay: 请求间隔（秒），避免过快请求

    Returns:
        单词到释义的映射
    """
    results = {}

    for i, word in enumerate(words):
        print(f"  查询 [{i+1}/{len(words)}]: {word}")

        definition = fetch_definition(word)
        if definition:
            results[word] = definition
        else:
            results[word] = None

        # 避免过快请求
        if i < len(words) - 1:
            time.sleep(delay)

    return results


def load_local_dictionary(dict_path: str) -> dict:
    """
    加载本地词典文件（JSON 格式）

    格式: {"word": {"meaningEN": "...", "meaningCN": "...", "partOfSpeech": "..."}}

    Args:
        dict_path: 词典文件路径

    Returns:
        词典字典
    """
    path = Path(dict_path)

    if not path.exists():
        return {}

    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_local_dictionary(data: dict, dict_path: str):
    """
    保存词典到本地文件

    Args:
        data: 词典数据
        dict_path: 词典文件路径
    """
    path = Path(dict_path)
    path.parent.mkdir(parents=True, exist_ok=True)

    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def enrich_with_chinese(
    english_def: str,
    target_lang: str = "zh"
) -> str:
    """
    使用机器翻译 API 将英文释义翻译为中文

    注意：这需要额外的翻译 API，这里是占位实现

    Args:
        english_def: 英文释义
        target_lang: 目标语言代码

    Returns:
        翻译后的释义
    """
    # 占位实现：返回空字符串
    # 可以集成 Google Translate, DeepL, 有道翻译等 API
    return ""


# 词性映射（Free Dictionary API 使用英文词性）
PART_OF_SPEECH_MAP = {
    "noun": "n.",
    "verb": "v.",
    "adjective": "adj.",
    "adverb": "adv.",
    "pronoun": "pron.",
    "preposition": "prep.",
    "conjunction": "conj.",
    "interjection": "interj.",
    "determiner": "det.",
    "phrase": "phr."
}


def normalize_part_of_speech(pos: str) -> str:
    """标准化词性格式"""
    return PART_OF_SPEECH_MAP.get(pos.lower(), pos.lower())


if __name__ == "__main__":
    # 测试
    test_words = ["hello", "world", "abandon"]

    for word in test_words:
        result = fetch_definition(word)
        if result:
            print(f"{word}: {result['partOfSpeech']} - {result['meaningEN']}")
        else:
            print(f"{word}: 未找到")
        time.sleep(0.5)
