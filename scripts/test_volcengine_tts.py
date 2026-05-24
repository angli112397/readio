#!/usr/bin/env python3
"""
Volcengine Doubao 异步长文本 TTS 测试脚本 (v3 API)

测试目标：
  1. 合成延迟（提交 → 音频可下载需要多久）
  2. data.sentences 的结构和时间戳精度
  3. 每条 sentence 的粒度（多长？是否跟我们的 chunk 边界接近）

参考文档：https://www.volcengine.com/docs/6561/1829010
"""

import json
import time
import uuid
import sys
import re
import requests

# 凭证
APP_ID       = ""
ACCESS_KEY   = ""
RESOURCE_ID  = "seed-tts-2.0"
SPEAKER      = "zh_female_vv_uranus_bigtts"
BASE_URL     = "https://openspeech.bytedance.com"

def compress_text(text: str) -> str:
    """
    大幅度压缩，只保留核心发音符号。
    """
    # 1. 预处理：移除所有换行和缩进，但保留英文单词间的空格
    text = re.sub(r'\s+', ' ', text).strip()

    # 2. 移除视觉符号（引号、书名号、括号、装饰符等）
    # 这些符号在 TTS 中通常不产生停顿，删掉它们最省钱
    visual_chars = r'[“”‘’「」《》〈〉（）\(\)\[\]\{\}\*\-\+_—]'
    text = re.sub(visual_chars, '', text)

    # 3. 规范化标点：合并重复的标点
    text = re.sub(r'([，。？！；：,.?!;: ])\1+', r'\1', text)

    return text

# 测试文本（包含对话、书名、括号等）
ORIGINAL_TEXT = (
    "凌晨三点，Case坐在廉价旅馆的床沿，盯着对面墙上的裂缝。"
    "「这不像是我在使用，」他听见有人说，（当他用肩膀拨开Chat门口的人群）。"
    "「更像是我的身体产生了大量的药物缺乏。」"
    "威廉·吉布森在《神经漫游者》中构建了一个令人窒息的未来世界。"
    "每一盏霓虹灯后面，都藏着无数不为人知的秘密。"
)

def make_headers():
    return {
        "X-Api-App-Id":      APP_ID,
        "X-Api-Access-Key":  ACCESS_KEY,
        "X-Api-Resource-Id": RESOURCE_ID,
        "X-Api-Request-Id":  str(uuid.uuid4()),
        "Content-Type":      "application/json",
    }

def submit(text: str):
    payload = {
        "user": {"uid": "readio_prod_test"},
        "unique_id": str(uuid.uuid4()),
        "req_params": {
            "text": text,
            "speaker": SPEAKER,
            "audio_params": {
                "format": "mp3",
                "enable_timestamp": True,
            },
        },
    }
    r = requests.post(f"{BASE_URL}/api/v3/tts/submit", headers=make_headers(), json=payload)
    r.raise_for_status()
    return r.json()

def query(task_id: str):
    r = requests.post(f"{BASE_URL}/api/v3/tts/query", headers=make_headers(), json={"task_id": task_id})
    r.raise_for_status()
    return r.json()

def main():
    processed_text = compress_text(ORIGINAL_TEXT)

    print(f"原始字数: {len(ORIGINAL_TEXT)}")
    print(f"压缩后字数: {len(processed_text)} (节省 {len(ORIGINAL_TEXT)-len(processed_text)} 字符)")
    print(f"提交内容预览: \n{processed_text}\n")

    # 1. 提交
    print("--- 提交任务 ---")
    resp = submit(processed_text)
    if resp.get("code") != 20000000:
        print(f"失败: {resp}")
        return
    task_id = resp["data"]["task_id"]

    # 2. 轮询
    t0 = time.time()
    while True:
        time.sleep(2)
        res = query(task_id)
        status = res.get("data", {}).get("task_status")
        print(f"[{time.time()-t0:.1f}s] 状态: {status}")
        if status == 2: break
        if status == 3: return

    # 3. 输出时间轴
    sentences = res.get("data", {}).get("sentences", [])
    print(f"\n--- 字幕导出 (共 {len(sentences)} 句) ---")
    for i, s in enumerate(sentences):
        print(f"#{i}: {s['startTime']:.3f}s -> {s['endTime']:.3f}s | {s['text']}")

    audio_url = res['data'].get('audio_url')
    print(f"\n音频下载地址: {audio_url}")

    # 4. 下载到本地
    if audio_url:
        try:
            filename = f"output_{task_id}.mp3"
            print(f"正在下载音频到: {filename} ...")
            audio_resp = requests.get(audio_url, timeout=30)
            audio_resp.raise_for_status()
            with open(filename, "wb") as f:
                f.write(audio_resp.content)
            print(f"下载成功: {filename}")
        except Exception as e:
            print(f"下载失败: {e}")

if __name__ == "__main__":
    main()
