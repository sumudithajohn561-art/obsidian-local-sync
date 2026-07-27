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
import re
import sys
import tempfile
import site
import urllib.request
import urllib.error

# 修复 Windows GBK 编码问题：stdout 重定向为 UTF-8
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# 注册 NVIDIA CUDA DLL 路径（Python 3.8+ Windows 需要显式注册）
_pkg_dir = site.getsitepackages()[1]  # Lib/site-packages
for _sub in ["cublas", "cuda_runtime", "cuda_nvrtc"]:
    _bin = os.path.join(_pkg_dir, "nvidia", _sub, "bin")
    if os.path.isdir(_bin):
        os.add_dll_directory(_bin)
import subprocess
import shutil
import screenshotter  # 视频智能截图模块
import traceback
import threading
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
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")    # DeepSeek API 密钥（用于 AI 速览）
SUMMARIZE_MODEL = "deepseek-v4-flash"  # DeepSeek V4 Flash（快速便宜）
SCREENSHOT_DIR = Path(os.environ.get(
    "SCREENSHOT_DIR",
    r"E:\obsidian\包罗万象\08-attachment\笔记链接同步截图"
))


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
    """清理文件名，移除非法字符和 Obsidian 冲突字符"""
    illegal = r'[/\\:*?"<>|#]'
    for ch in illegal:
        name = name.replace(ch, "-")
    return name.strip()[:max_len]


def timestamp() -> str:
    """生成时间戳字符串，用于文件名"""
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def _insert_screenshots_to_transcript(
    transcript: str,
    screenshots: list[dict],
    attachment_prefix: str,
) -> str:
    """
    将截图按时间戳插入转录文本中。
    transcript: 原始转录文本，每行 "[xxx.xs - yyy.ys] 内容"
    screenshots: [{"filename": "xxx.jpg", "timestamp_sec": 120.5}, ...]
    attachment_prefix: Obsidian wikilink 中附件目录前缀

    返回插入截图引用的新转录文本。
    """
    lines = transcript.split("\n")

    # 按时间戳倒序插入，避免索引偏移
    for ss in sorted(screenshots, key=lambda s: s["timestamp_sec"], reverse=True):
        ts = ss["timestamp_sec"]
        fn = ss.get("filename", "unknown.jpg")
        wikilink = f"![[{attachment_prefix}/{fn}]]"

        # 找到 ts 所在的段落
        insert_idx = -1
        for i, line in enumerate(lines):
            match = re.match(r'\[(\d+\.?\d*)s\s*-\s*(\d+\.?\d*)s\]', line)
            if match:
                start = float(match.group(1))
                end = float(match.group(2))
                if start <= ts <= end:
                    insert_idx = i
                    break

        if insert_idx >= 0:
            caption = f"\n{wikilink}\n*[截图 · {ts:.0f}秒]*\n"
            lines.insert(insert_idx + 1, caption)
        else:
            # 没找到匹配的时间戳段落，追加到末尾
            log(f"  ⚠️ 截图 {fn} 时间戳 {ts}s 无匹配段落，追加到末尾")

    return "\n".join(lines)


# ============================================================
# 步骤 1: yt-dlp 下载视频
# ============================================================

def build_ytdlp_cmd(url: str, output_template: str) -> list[str]:
    """
    构建 yt-dlp 命令行，根据环境变量决定是否使用代理和 cookies。
    B站和抖音只下载音频（-x --audio-format wav），YouTube下载视频后转音频。
    """
    cmd = [
        "yt-dlp",
        # B站视频格式分离，用 bv*+ba 自动合并视频+音频
        "-f", "bv*+ba/best",
        "--playlist-end", "10",
        "-o", output_template,
        "--socket-timeout", "60",
        "--extractor-retries", "10",
        "--retries", "10",
        "--fragment-retries", "10",
        "--no-check-certificates",
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


def download_video(url: str, output_dir: Path) -> list[Path]:
    """
    使用 yt-dlp 下载视频（支持合集多P），返回视频文件路径列表。
    用 Popen + 实时读取输出，避免 subprocess.run+管道缓冲区死锁。
    """
    log(f"下载视频: {url}")
    output_template = str(output_dir / "%(title)s.%(ext)s")

    cmd = build_ytdlp_cmd(url, output_template)

    try:
        # 用 Popen 替代 subprocess.run，实时读取输出防止管道死锁
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,  # yt-dlp 日志输出到 stdout
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        # 收集最近几行输出，用于错误日志
        output_tail: list[str] = []
        try:
            for line in proc.stdout:
                line = line.rstrip()
                output_tail.append(line)
                output_tail = output_tail[-5:]  # 只保留最后5行
                # 过滤 yt-dlp 的进度行，只输出关键日志
                if not line.startswith("[download]") and line.strip():
                    log(f"  yt-dlp: {line[:120]}")
        except Exception as e:
            log(f"  ⚠️ yt-dlp 读取输出异常: {e}")

        proc.wait(timeout=600)

        if proc.returncode != 0:
            tail = "\n".join(output_tail) if output_tail else "(无输出)"
            log(f"❌ yt-dlp 失败 (code={proc.returncode}): {tail[:300]}")
            return []

        # 找到下载的文件（排除 part 文件，按文件名排序保持P顺序）
        videos = sorted(
            [v for v in output_dir.glob("*") if not v.name.endswith(".part") and v.is_file()],
            key=lambda p: p.name,
        )
        if not videos:
            log("❌ 未找到下载文件")
            return []
        total_mb = sum(v.stat().st_size for v in videos) / 1024 / 1024
        log(f"✅ 下载完成: {len(videos)} 个视频, 共 {total_mb:.1f} MB")
        for v in videos:
            log(f"   {v.name} ({v.stat().st_size / 1024 / 1024:.1f} MB)")
        return videos
    except subprocess.TimeoutExpired:
        try: proc.kill()
        except: pass
        log("❌ yt-dlp 超时 (600s)")
        return []
    except Exception as e:
        log(f"❌ yt-dlp 异常: {e}")
        return []


# ============================================================
# 步骤 2: ffmpeg 提取音频
# ============================================================

def extract_audio(video_paths: list[Path], output_dir: Path) -> Path | None:
    """
    从视频提取音频为 16kHz 单声道 WAV（Whisper 最优格式）。
    如果有多个视频，先用 ffmpeg concat 合并后再提取。
    """
    if not video_paths:
        return None

    if len(video_paths) == 1:
        audio_path = output_dir / "audio.wav"
        video_path = video_paths[0]
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
    else:
        # 多P视频：用 ffmpeg concat 合并
        audio_path = output_dir / "audio.wav"
        log(f"合并 {len(video_paths)} 个视频并提取音频...")

        # 生成 concat 文件列表
        concat_file = output_dir / "concat.txt"
        with open(concat_file, "w", encoding="utf-8") as f:
            for v in video_paths:
                # ffmpeg concat 格式，路径需要转义
                f.write(f"file '{v.as_posix()}'\n")

        cmd = [
            "ffmpeg",
            "-f", "concat",
            "-safe", "0",
            "-i", str(concat_file),
            "-vn",
            "-acodec", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            "-y",
            str(audio_path),
        ]

    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=600, encoding="utf-8", errors="replace")
        size_mb = audio_path.stat().st_size / 1024 / 1024
        log(f"✅ 音频提取完成: {size_mb:.1f} MB")
        return audio_path
    except subprocess.CalledProcessError as e:
        log(f"❌ ffmpeg 失败: {e.stderr[:200] if e.stderr else e}")
        return None
    except subprocess.TimeoutExpired:
        log("❌ ffmpeg 超时")
        return None


def get_audio_duration(audio_path: Path) -> float | None:
    """
    使用 ffprobe 获取音频时长（秒）。
    返回 float 秒数，失败返回 None。
    """
    cmd = [
        "ffprobe", "-v", "quiet",
        "-show_entries", "format=duration",
        "-of", "csv=p=0",
        str(audio_path),
    ]
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True,
            timeout=30, encoding="utf-8", errors="replace",
        )
        return float(result.stdout.strip())
    except Exception as e:
        log(f"  ⚠️ 无法获取音频时长: {e}")
        return None


# ============================================================
# 步骤 2.5: AI 速览（DeepSeek API）
# ============================================================

# 用于提取纯文本的预编译正则（去掉时间戳标记）
_SUMMARIZE_CLEAN_RE = re.compile(r'^\[\d+\.\ds\s*-\s*\d+\.\ds\]\s*', re.MULTILINE)
# 摘要最大输入 token 数（haiku 上下文窗口远大于此，但为省钱和速度做截断）
_SUMMARIZE_MAX_CHARS = 12000


def summarize(transcript_text: str, title: str) -> dict | None:
    """
    调 DeepSeek API 生成 AI 速览。
    返回 {"summary": "摘要Markdown", "keywords": ["关键词1", "关键词2", ...]}，失败返回 None。
    """
    if not DEEPSEEK_API_KEY:
        log("⚠️ 未设置 DEEPSEEK_API_KEY，跳过 AI 速览")
        return None

    log(f"  调用 DeepSeek API (model={SUMMARIZE_MODEL})...")

    # 清理转录文本：去掉时间戳，只留正文
    clean = _SUMMARIZE_CLEAN_RE.sub("", transcript_text).strip()
    if not clean:
        return None

    # 截断超长文本
    if len(clean) > _SUMMARIZE_MAX_CHARS:
        half = _SUMMARIZE_MAX_CHARS // 2
        clean = clean[:half] + "\n\n…(中间省略)…\n\n" + clean[-half:]

    prompt = f"""你是一个视频内容摘要助手。请根据以下视频转录文本，生成结构化的"AI 速览"。

视频标题：{title}

转录文本：
{clean}

请按以下格式输出（Markdown），不要输出其他内容：

**一句话**：（15-30字概括这期视频的核心内容）

**核心要点**：
- （要点1）
- （要点2）
- （要点3）
- （如有更多可用要点可继续列出，3-5条为宜）

**金句**：（从转录中摘录1-3句最有价值或最打动人的原句，每条一行引用格式，没有可写"无"）

**行动建议**：（1-2条看完后的具体行动建议，没有可写"无"）

**关键词**：（3-6个关键词/标签，用中文顿号分隔）"""

    for attempt in range(3):
        try:
            req = urllib.request.Request(
                "https://api.deepseek.com/v1/chat/completions",
                data=json.dumps({
                    "model": SUMMARIZE_MODEL,
                    "max_tokens": 800,
                    "temperature": 0.3,
                    "messages": [
                        {"role": "system", "content": "你是一个专业的中文视频内容摘要助手。输出简洁、准确、有洞察力。只输出要求的格式，不要额外说明。"},
                        {"role": "user", "content": prompt}
                    ],
                }).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
                },
            )

            with urllib.request.urlopen(req, timeout=120) as resp:
                body = json.loads(resp.read().decode("utf-8"))

            # DeepSeek 返回格式：choices[0].message.content
            choices = body.get("choices", [])
            if not choices:
                log("⚠️ AI 速览返回为空")
                return None
            text = choices[0].get("message", {}).get("content", "")
            break  # 成功，跳出重试循环

        except urllib.error.HTTPError as e:
            log(f"⚠️ AI 速览 API 错误 (HTTP {e.code}, 第{attempt+1}次): {e.reason}")
            if e.code in (429, 500, 502, 503, 504):
                if attempt < 2:
                    import time as _time
                    _time.sleep(2 ** attempt)
                    continue
            return None
        except Exception as e:
            log(f"⚠️ AI 速览异常 (第{attempt+1}次): {type(e).__name__}: {str(e)[:200]}")
            if attempt < 2:
                import time as _time
                _time.sleep(3)
                continue
            return None

    # 以下在 for 循环外部：break 成功后执行
    if not text.strip():
        log("⚠️ AI 速览返回为空")
        return None

    # 提取关键词行，并从摘要正文中移除
    keywords: list[str] = []
    keyword_re = re.compile(r'^\*?\*?关键词\*?\*?\s*[：:]\s*(.+)', re.MULTILINE)
    keyword_match = keyword_re.search(text)
    if keyword_match:
        raw = keyword_match.group(1).strip()
        # 用顿号、逗号、/ 拆分关键词
        keywords = [k.strip() for k in re.split(r'[、,，/]', raw) if k.strip()]
        # 从正文中移除关键词行
        text = keyword_re.sub("", text).strip()

    log(f"✅ AI 速览生成成功 ({len(text)} 字符, {len(keywords)} 个关键词)")
    return {"summary": text.strip(), "keywords": keywords}


# ============================================================
# 步骤 3: faster-whisper 转录
# ============================================================

def transcribe(audio_path: Path, model, audio_duration: float | None = None) -> tuple[str, str] | None:
    """
    使用 faster-whisper 转录音频。
    返回 (完整文本, 检测到的语言)。

    安全机制: 用看门狗线程防止 CUDA hang 导致进程永久僵死。
    若转录超过 25 分钟，os._exit(1) 自毁（绕过所有清理，确保进程终止）。
    """
    log(f"转录中: {audio_path.name}" + (f" (音频时长: {audio_duration:.0f}s)" if audio_duration else ""))

    # 看门狗：25分钟内未完成 → 自毁
    # 用 threading.Event 而不是 Timer，因为转录完成后可以取消
    timeout_sec = 25 * 60  # 25分钟，留5分钟余量给 Node 端看门狗
    watchdog_fired = threading.Event()

    def watchdog():
        """超时自毁：os._exit 绕过 Python 清理，直接终止进程"""
        watchdog_fired.set()
        log(f"❌ 转录超时 ({timeout_sec // 60} 分钟)，CUDA 可能已 hang，触发自毁...")
        # os._exit 是原子级进程终止，不执行 finally、不调用 atexit、不清理
        os._exit(1)

    timer = threading.Timer(timeout_sec, watchdog)
    timer.daemon = True  # 主线程退出时自动取消
    timer.start()

    try:
        log("  调用 model.transcribe()...")
        segments, info = model.transcribe(
            str(audio_path),
            language=LANGUAGE,
            beam_size=5,
            # 注意：不启用 vad_filter，防止 VAD 误判静音导致转录提前终止
            # 长视频中段落的间隔容易被 VAD 误判为"语音结束"
            # condition_on_previous_text=False: 防止 ASR 把前文幻觉续接到下一段，
            #   这是长视频转录"陷入循环/提前终止"的常见根因
            condition_on_previous_text=False,
        )
        log("  model.transcribe() 返回，开始收集分段...")

        # 收集所有分段，并定期输出进度
        lines: list[str] = []
        last_progress_log = 0.0
        for segment in segments:
            lines.append(f"[{segment.start:.1f}s - {segment.end:.1f}s] {segment.text.strip()}")
            # 每30秒报告一次进度（按转录时间戳）
            if segment.end - last_progress_log >= 30:
                log(f"  转录进度: {len(lines)} 段, 已到 {segment.end:.0f}s" +
                    (f" / {audio_duration:.0f}s ({segment.end / audio_duration * 100:.0f}%)" if audio_duration else ""))
                last_progress_log = segment.end

        text = "\n".join(lines)
        detected_lang = info.language

        # 覆盖率验证
        if lines:
            # 从最后一行格式 "[xxx.xs - yyy.ys] ..." 中提取结束时间
            last_seg_end = 0.0
            try:
                end_str = lines[-1].split(" - ")[1].split("s]")[0]
                last_seg_end = float(end_str)
            except (IndexError, ValueError):
                pass

            if audio_duration and audio_duration > 0:
                coverage = last_seg_end / audio_duration * 100
                quality = "⚠️ 异常" if coverage < 80 else ("良好" if coverage >= 95 else "偏低")
                log(f"✅ 转录完成: {len(lines)} 段, 语言={detected_lang}, 字符={len(text)}, "
                    f"覆盖={last_seg_end:.0f}s/{audio_duration:.0f}s={coverage:.0f}% [{quality}]")
            else:
                log(f"✅ 转录完成: {len(lines)} 段, 语言={detected_lang}, 字符={len(text)}, "
                    f"结尾={last_seg_end:.0f}s (无音频时长参考)")
        else:
            log(f"⚠️ 转录结果为空: 语言={detected_lang}")

        return text, detected_lang
    except Exception as e:
        log(f"❌ 转录失败: {e}")
        log(f"  堆栈: {traceback.format_exc()}")
        return None
    finally:
        timer.cancel()  # 取消看门狗（如果还没触发）


# ============================================================
# 步骤 4: 写入收件箱
# ============================================================

def write_to_inbox(url: str, platform: str, title: str, transcript: str,
                   ai_summary: str | None = None, keywords: list[str] | None = None,
                   screenshots: list[dict] | None = None) -> Path:
    """
    生成 Markdown 笔记并写入收件箱，返回文件路径。
    ai_summary: 可选的 AI 速览内容（Markdown 格式）
    keywords: 可选的关键词列表，写入 frontmatter tags
    screenshots: 可选的截图列表 [{"filename": "xxx.jpg", "timestamp_sec": 120.5, "score": 0.35}, ...]
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
    ]
    # 关键词写入 frontmatter tags
    if keywords:
        tags_yaml = json.dumps(keywords, ensure_ascii=False)
        frontmatter_lines.append(f"tags: {tags_yaml}")
    frontmatter_lines.append("---")
    frontmatter = "\n".join(frontmatter_lines)

    body_parts = [
        "## 视频信息",
        "",
        f"- **标题:** {title}",
        f"- **来源:** {platform}",
        f"- **链接:** {url}",
        "",
        "---",
    ]

    # 插入 AI 速览（如果有）
    if ai_summary:
        body_parts += [
            "",
            "## AI 速览",
            "",
            ai_summary,
            "",
            "---",
        ]

    body_parts += [
        "",
        "## 转录内容",
        "",
    ]

    # 如果有截图，按时间戳插入到转录文本中
    if screenshots:
        # 构建附件目录相对于 vault 的路径（用于 Obsidian wikilink）
        # SCREENSHOT_DIR 在 vault 内，如 E:\obsidian\包罗万象\08-attachment\笔记链接同步截图
        # wikilink 格式: ![[08-attachment/笔记链接同步截图/子目录/filename.jpg]]
        # 子目录名从第一个截图的完整路径中提取
        if screenshots[0].get("path"):
            shot_full = Path(screenshots[0]["path"])
            shot_parent = shot_full.parent
            # 尝试构建相对于 vault 的附件路径
            # vault_root = E:\obsidian\包罗万象
            vault_root = Path(r"E:\obsidian\包罗万象")
            try:
                rel_shot_dir = shot_parent.relative_to(vault_root)
            except ValueError:
                rel_shot_dir = shot_parent
            attachment_prefix = rel_shot_dir.as_posix()
        else:
            attachment_prefix = "08-attachment/笔记链接同步截图"

        transcript_with_screenshots = _insert_screenshots_to_transcript(
            transcript, screenshots, attachment_prefix)
        body_parts.append(transcript_with_screenshots)
    else:
        body_parts.append(transcript)
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
        # 步骤 1: 下载视频（支持合集多P）
        video_paths = download_video(url, tmpdir)
        if not video_paths:
            return {"taskId": task_id, "status": "error", "error": "视频下载失败"}

        # 提取视频标题（从第一个文件）
        title = video_paths[0].stem or "未命名视频"
        # 去掉 _p1 后缀（合集分P标记）
        title = re.sub(r'_p\d+$', '', title)

        # 步骤 2: 提取/合并音频
        audio_path = extract_audio(video_paths, tmpdir)
        if not audio_path:
            return {"taskId": task_id, "status": "error", "error": "音频提取失败"}

        # 步骤 3: 转录（传入音频时长用于覆盖率验证）
        audio_duration = get_audio_duration(audio_path)

        # 步骤 3b: 智能截图（在转录之前执行，失败不影响转录）
        screenshot_result: list[dict] | None = None
        try:
            if video_paths:
                ts = datetime.now().strftime("%Y%m%d_%H%M%S")
                safe_dir = sanitize_filename(title)[:30]
                shot_dir = SCREENSHOT_DIR / f"{ts}_{safe_dir}"
                screenshot_result = screenshotter.extract_screenshots(video_paths, shot_dir)
        except Exception as e:
            log(f"⚠️ 截图步骤异常（不影响转录）: {e}")

        result = transcribe(audio_path, model, audio_duration)
        if not result:
            return {"taskId": task_id, "status": "error", "error": "转录失败"}
        transcript_text, _ = result

        if not transcript_text.strip():
            return {"taskId": task_id, "status": "error", "error": "转录结果为空"}

        # 步骤 4: AI 速览（可选，失败不影响主流程）
        ai_result = summarize(transcript_text, title)
        ai_summary = ai_result.get("summary") if ai_result else None
        keywords = ai_result.get("keywords") if ai_result else None

        # 步骤 5: 写入收件箱
        out_file = write_to_inbox(url, platform, title, transcript_text,
                                  ai_summary, keywords, screenshot_result)

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
    # 额外输出醒目的就绪确认到 stderr（不会被 JSON 协议影响）
    log("=" * 50)
    log("✅✅✅ 转录服务已就绪，开始处理队列 ✅✅✅")
    log("=" * 50)

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
