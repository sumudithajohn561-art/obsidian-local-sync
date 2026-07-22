"""GPU 模式测试核心链路：加载模型 → 转录中文语音 → 写入 Obsidian 收件箱"""
import os, sys, site
from pathlib import Path
from datetime import datetime

# 注册 NVIDIA CUDA DLL 路径
_pkg_dir = site.getsitepackages()[1]
for _sub in ["cublas", "cuda_runtime", "cuda_nvrtc"]:
    _bin = os.path.join(_pkg_dir, "nvidia", _sub, "bin")
    if os.path.isdir(_bin):
        os.add_dll_directory(_bin)

os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"

INBOX = Path(os.environ.get("CAPTURE_INBOX", r"E:\obsidian\obsidian-Inbox"))
AUDIO = r"C:\Users\29979\AppData\Local\Temp\test_speech.wav"

print("=" * 50)
print("核心链路测试（GPU 模式）")
print("=" * 50)
print()

# === 加载模型 (GPU) ===
print("[1/3] 加载 faster-whisper large-v3 (CUDA)...")
from faster_whisper import WhisperModel
model = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
print("  ✅ 模型就绪（CUDA 模式）")
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
file_path = INBOX / f"{ts}-test-gpu-transcribe.md"

content = f"""---
title: "Whisper 转录测试（GPU模式）"
source_type: "transcript"
source: "test"
url: ""
created: "{ts}"
status: "test"
tags: [test, transcription, faster-whisper, gpu]
---

## 测试说明

本次测试使用 Windows TTS 生成的中文语音文件作为输入，通过 faster-whisper large-v3 模型（**CUDA/INT8_float16 GPU 模式**）进行语音识别转录。

---

## 转录内容

{transcript}
"""
INBOX.mkdir(parents=True, exist_ok=True)
file_path.write_text(content, encoding="utf-8")
print(f"  ✅ 已写入: {file_path}")
print()

print("=" * 50)
print("✅ GPU 模式全链路测试通过！")
print(f"📁 笔记位置: {file_path}")
print("=" * 50)
