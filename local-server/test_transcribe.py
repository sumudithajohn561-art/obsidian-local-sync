"""测试核心链路：加载模型 → 转录中文语音 → 写入 Obsidian 收件箱"""
import os, sys
from pathlib import Path
from datetime import datetime

INBOX = Path(os.environ.get("CAPTURE_INBOX", r"E:\obsidian\obsidian-Inbox"))
AUDIO = r"C:\Users\29979\AppData\Local\Temp\test_speech.wav"

print("=" * 50)
print("核心链路测试：转录 + 收件箱写入")
print("=" * 50)
print()

# === 加载模型 ===
print("[1/3] 加载 faster-whisper large-v3...")
from faster_whisper import WhisperModel
model = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
print("  ✅ 模型就绪")
print()

# === 转录 ===
print(f"[2/3] 转录中文语音: {AUDIO}")
segments, info = model.transcribe(
    AUDIO,
    language="zh",
    beam_size=5,
    vad_filter=True,
    vad_parameters=dict(min_silence_duration_ms=500),
)
lines = [f"[{s.start:.1f}s - {s.end:.1f}s] {s.text.strip()}" for s in segments]
transcript = "\n".join(lines)
print(f"  ✅ 转录完成 — {len(lines)} 段, 语言={info.language}")
for line in lines:
    print(f"     {line}")
print()

# === 写入 Obsidian 收件箱 ===
print("[3/3] 写入 Obsidian 收件箱...")
ts = datetime.now().strftime("%Y%m%d-%H%M%S")
file_path = INBOX / f"{ts}-test-whisper-transcribe.md"

content = f"""---
title: "Whisper 转录测试"
source_type: "transcript"
source: "test"
url: ""
created: "{ts}"
status: "test"
tags: [test, transcription, faster-whisper]
---

## 测试说明

本次测试使用 Windows TTS 生成的中文语音文件，通过 faster-whisper large-v3 模型进行语音识别转录。

---

## 转录内容

{transcript}
"""
INBOX.mkdir(parents=True, exist_ok=True)
file_path.write_text(content, encoding="utf-8")
print(f"  ✅ 已写入: {file_path}")
print()

print("=" * 50)
print("✅ 全链路测试通过！")
print(f"📁 笔记位置: {file_path}")
print("=" * 50)
