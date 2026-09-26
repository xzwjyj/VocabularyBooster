#!/usr/bin/env python3
"""
翻译模块 - 将英文翻译为中文
"""

import json
import urllib.request
import urllib.parse
import time
from typing import Optional

# 免费翻译 API (MyMemory)
TRANSLATION_API = "https://api.mymemory.translated.net/get"


def translate_to_chinese(text: str, max_retries: int = 3) -> str:
    """
    将英文翻译为中文

    Args:
        text: 要翻译的英文文本
        max_retries: 最大重试次数

    Returns:
        翻译后的中文文本
    """
    if not text or not text.strip():
        return ""

    # URL 编码
    params = urllib.parse.urlencode({
        'q': text,
        'langpair': 'en|zh-CN'
    })

    url = f"{TRANSLATION_API}?{params}"

    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                data = json.loads(response.read().decode("utf-8"))

            if data.get('responseStatus') == 200:
                return data.get('responseData', {}).get('translatedText', '')

            # API 限制或错误
            if attempt < max_retries - 1:
                time.sleep(1)
                continue

        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(1)
                continue

    return ""


def translate_batch(texts: list[str], delay: float = 0.5) -> list[str]:
    """
    批量翻译

    Args:
        texts: 文本列表
        delay: 请求间隔（秒）

    Returns:
        翻译后的文本列表
    """
    results = []

    for i, text in enumerate(texts):
        print(f"  翻译 [{i+1}/{len(texts)}]: {text[:50]}...")
        result = translate_to_chinese(text)
        results.append(result)

        if i < len(texts) - 1:
            time.sleep(delay)

    return results


if __name__ == "__main__":
    # 测试
    test_sentences = [
        "I see you gather before me hungry terrified clutching your babes to your breast.",
        "Emperor Emre has marched his legions into our lands.",
    ]

    for sentence in test_sentences:
        result = translate_to_chinese(sentence)
        print(f"EN: {sentence}")
        print(f"CN: {result}")
        print()
