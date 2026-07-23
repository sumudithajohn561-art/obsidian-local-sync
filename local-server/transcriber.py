"""
transcriber.py — 视频转录长驻进程

生命周期:
  启动 → 加载 faster-whisper 模型 → 循环读取 stdin JSON 行
  → yt-dlp 下载视频 → ffmpeg 提取音频 → faster-whisper 转录
  → 写入收件箱 .md → stdout 输出结果 JSON

stdin 输入格式 (每行一个任务):
  {"taskId": "uuid", "url": "https://...", "platform": "bilibili"}

stdout 输出格式:
  {"taskId": "uuid", "status": "ok", "filePath": "...", "title": "..."}
  {"taskId": "uuid", "status": "error", "error": "..."}

环境变量:
  CAPTURE_INBOX — 收件箱目录路径
  YTDLP_PROXY   — yt-dlp 代理地址 (如 http://127.0.0.1:10808)，不设则不使用代理
  YTDLP_COOKIES_FILE — 浏览器导出的 Netscape 格式 cookie 文件路径 (用于 YouTube/抖音等)
  YTDLP_COOKIES_BROWSER — 浏览器名称 (如 chrome/edge/brave)，需要浏览器已关闭

依赖: faster-whisper, yt-dlp, ffmpeg (系统安装)
"""

import json
import os
import sys
import tempfile
import site

# 注册 NVIDIA CUDA DLL 路径（Python 3.8+ Windows 需要显式注册）
_pkg_dir = site.getsitepackages()[1]  # Lib/site-packages
for _sub in ["cublas", "cuda_runtime", "cuda_nvrtc"]:
    _bin = os.path.join(_pkg_dir, "nvidia", _sub, "bin")
    if os.path.isdir(_bin):
        os.add_dll_directory(_bin)
import subprocess
import shutil
import traceback
from datetime import datetime
from pathlib import Path


# ============================================================
# 配置
# ============================================================

INBOX = Path(os.environ.get("CAPTURE_INBOX", "E:\\obsidian\\obsidian-Inbox"))
MODEL_SIZE = "large-v3"
COMPUTE_TYPE = "int8_float16"  # RTX 4060 4GB 适用的 int8 量化
DEVICE = "cuda"
LANGUAGE = "zh"
PROXY = os.environ.get("YTDLP_PROXY", "")          # yt-dlp 代理，不设则直连
COOKIES_FILE = os.environ.get("YTDLP_COOKIES_FILE", "")  # Netscape格式cookie文件路径
COOKIES_BROWSER = os.environ.get("YTDLP_COOKIES_BROWSER", "")  # 浏览器名称


# ============================================================
# 工具函数
# ============================================================

def log(msg: str) -> None:
    """日志输出到 stderr（stdout 被 JSON 协议占用）"""
    print(f"[transcriber] {msg}", file=sys.stderr, flush=True)


def send_json(data: dict) -> None:
    """发送 JSON 结果到 stdout"""
    line = json.dumps(data, ensure_ascii=False)
    print(line, flush=True)
    # 同时记日志
    status = data.get("status", "?")
    task_id = data.get("taskId", "?")[:8]
    log(f"结果 → {status} task={task_id}")


def sanitize_filename(name: str, max_len: int = 60) -> str:
    """清理文件名，移除非法字符"""
    illegal = r'[/\\:*?"<>|]'
    for ch in illegal:
        name = name.replace(ch, "-")
    return name.strip()[:max_len]


def timestamp() -> str:
    """生成时间戳字符串，用于文件名"""
    return datetime.now().strftime("%Y%m%d-%H%M%S")


# ============================================================
# 步骤 1: yt-dlp 下载视频
# ============================================================

def build_ytdlp_cmd(url: str, output_template: str) -> list[str]:
    """
    构建 yt-dlp 命令行，根据环境变量决定是否使用代理和 cookies。
    """
    cmd = [
        "yt-dlp",
        "-f", "best[height<=1080]/best",
        "--no-playlist",
        "--merge-output-format", "mp4",
        "-o", output_template,
        "--socket-timeout", "60",
        "--extractor-retries", "3",
    ]

    # 代理（可选）
    if PROXY:
        cmd += ["--proxy", PROXY]

    # Cookies（二选一：优先 cookie 文件，其次浏览器）
    if COOKIES_FILE:
        if os.path.isfile(COOKIES_FILE):
            cmd += ["--cookies", COOKIES_FILE]
            log(f"  使用 cookie 文件: {COOKIES_FILE}")
        else:
            log(f"  ⚠️ cookie 文件不存在: {COOKIES_FILE}")
    elif COOKIES_BROWSER:
        cmd += ["--cookies-from-browser", COOKIES_BROWSER]

    cmd.append(url)
    return cmd


def download_video(url: str, output_dir: Path) -> Path | None:
    """
    使用 yt-dlp 下载视频，返回视频文件路径。
    优先 1080p 及以下画质，控制文件大小。
    """
    log(f"下载视频: {url}")
    output_template = str(output_dir / "%(title)s.%(ext)s")

    cmd = build_ytdlp_cmd(url, output_template)

    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=300)
        # 找到下载的文件
        videos = list(output_dir.glob("*"))
        if not videos:
            log("❌ 未找到下载文件")
            return None
        video_path = videos[0]
        log(f"✅ 下载完成: {video_path.name} ({video_path.stat().st_size / 1024 / 1024:.1f} MB)")
        return video_path
    except subprocess.CalledProcessError as e:
        log(f"❌ yt-dlp 失败: {e.stderr[:200] if e.stderr else e}")
        return None
    except subprocess.TimeoutExpired:
        log("❌ yt-dlp 超时 (300s)")
        return None


# ============================================================
# 步骤 2: ffmpeg 提取音频
# ============================================================

def extract_audio(video_path: Path, output_dir: Path) -> Path | None:
    """
    从视频提取音频为 16kHz 单声道 WAV（Whisper 最优格式）。
    """
    audio_path = output_dir / "audio.wav"
    log(f"提取音频: {video_path.name}")

    cmd = [
        "ffmpeg",
        "-i", str(video_path),
        "-vn",                # 不要视频流
        "-acodec", "pcm_s16le",  # 16-bit PCM
        "-ar", "16000",       # 16kHz 采样率
        "-ac", "1",           # 单声道
        "-y",                 # 覆盖已有文件
        str(audio_path),
    ]

    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=120)
        size_mb = audio_path.stat().st_size / 1024 / 1024
        log(f"✅ 音频提取完成: {size_mb:.1f} MB")
        return audio_path
    except subprocess.CalledProcessError as e:
        log(f"❌ ffmpeg 失败: {e.stderr[:200] if e.stderr else e}")
        return None
    except subprocess.TimeoutExpired:
        log("❌ ffmpeg 超时 (120s)")
        return None


# ============================================================
# 步骤 3: faster-whisper 转录
# ============================================================

def transcribe(audio_path: Path, model) -> tuple[str, str] | None:
    """
    使用 faster-whisper 转录音频。
    返回 (完整文本, 检测到的语言)。
    """
    log(f"转录中: {audio_path.name}")
    try:
        segments, info = model.transcribe(
            str(audio_path),
            language=LANGUAGE,
            beam_size=5,
            vad_filter=True,         # 过滤静音段
            vad_parameters=dict(
                min_silence_duration_ms=500,
            ),
        )

        # 收集所有分段
        lines: list[str] = []
        for segment in segments:
            lines.append(f"[{segment.start:.1f}s - {segment.end:.1f}s] {segment.text.strip()}")

        text = "\n".join(lines)
        detected_lang = info.language
        log(f"✅ 转录完成: {len(lines)} 段, 语言={detected_lang}, 总长={len(text)} 字符")
        return text, detected_lang
    except Exception as e:
        log(f"❌ 转录失败: {e}")
        return None


# ============================================================
# 步骤 4: 写入收件箱
# ============================================================

def write_to_inbox(url: str, platform: str, title: str, transcript: str) -> Path:
    """
    生成 Markdown 笔记并写入收件箱，返回文件路径。
    """
    ts = timestamp()
    safe_title = sanitize_filename(title)
    domain = platform or "video"
    file_name = f"{ts}-{domain}-{safe_title}.md"
    file_path = INBOX / file_name

    frontmatter_lines = [
        "---",
        f'title: "{title}"',
        f'source_type: "transcript"',
        f'source: "{platform}"',
        f'url: "{url}"',
        f'created: "{ts}"',
        "---",
    ]
    frontmatter = "\n".join(frontmatter_lines)

    body_parts = [
        "## 视频信息",
        "",
        f"- **标题:** {title}",
        f"- **来源:** {platform}",
        f"- **链接:** {url}",
        "",
        "---",
        "",
        "## 转录内容",
        "",
        transcript,
    ]
    body = "\n".join(body_parts)

    full_content = frontmatter + "\n\n" + body

    INBOX.mkdir(parents=True, exist_ok=True)
    file_path.write_text(full_content, encoding="utf-8")
    log(f"✅ 写入收件箱: {file_name}")
    return file_path


# ============================================================
# 核心：处理单个任务
# ============================================================

def process_task(task: dict, model) -> dict:
    """
    处理一个转录任务的全流程。
    返回结果字典 {"status": "ok"|"error", ...}
    """
    url = task.get("url", "")
    platform = task.get("platform", "unknown")
    task_id = task.get("taskId", "unknown")

    log(f"开始处理 task={task_id[:8]} platform={platform}")
    log(f"  URL: {url}")

    # 创建临时目录
    tmpdir = Path(tempfile.mkdtemp(prefix="transcribe-"))
    log(f"  临时目录: {tmpdir}")

    try:
        # 步骤 1: 下载视频
        video_path = download_video(url, tmpdir)
        if not video_path:
            return {"taskId": task_id, "status": "error", "error": "视频下载失败"}

        # 提取视频标题（从文件名）
        title = video_path.stem or "未命名视频"

        # 步骤 2: 提取音频
        audio_path = extract_audio(video_path, tmpdir)
        if not audio_path:
            return {"taskId": task_id, "status": "error", "error": "音频提取失败"}

        # 步骤 3: 转录
        result = transcribe(audio_path, model)
        if not result:
            return {"taskId": task_id, "status": "error", "error": "转录失败"}
        transcript_text, _ = result

        if not transcript_text.strip():
            return {"taskId": task_id, "status": "error", "error": "转录结果为空"}

        # 步骤 4: 写入收件箱
        out_file = write_to_inbox(url, platform, title, transcript_text)

        return {
            "taskId": task_id,
            "status": "ok",
            "filePath": str(out_file),
            "title": title,
            "platform": platform,
        }

    except Exception as e:
        log(f"❌ 异常: {traceback.format_exc()}")
        return {"taskId": task_id, "status": "error", "error": str(e)}

    finally:
        # 清理临时文件
        try:
            if tmpdir.exists():
                shutil.rmtree(tmpdir)
                log(f"  已清理临时目录")
        except Exception as e:
            log(f"  ⚠️ 清理临时目录失败: {e}")


# ============================================================
# 主循环
# ============================================================

def main():
    log("=" * 50)
    log("transcriber.py 启动")
    log(f"  收件箱: {INBOX}")
    log(f"  模型: {MODEL_SIZE} ({COMPUTE_TYPE}) 设备: {DEVICE}")
    log("=" * 50)

    # 加载模型
    log("加载 faster-whisper 模型...")
    try:
        from faster_whisper import WhisperModel
        model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
        log("✅ 模型加载完成，等待任务...")
    except Exception as e:
        log(f"❌ 模型加载失败: {e}")
        send_json({"status": "fatal", "error": f"模型加载失败: {str(e)}"})
        sys.exit(1)

    # 通知父进程：就绪
    send_json({"status": "ready"})

    # 主循环：逐行读取 stdin
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            task = json.loads(line)
        except json.JSONDecodeError as e:
            log(f"⚠️ 无效 JSON: {line[:100]}... 错误: {e}")
            continue

        result = process_task(task, model)
        send_json(result)

    log("stdin 关闭，transcriber 退出")


if __name__ == "__main__":
    main()
