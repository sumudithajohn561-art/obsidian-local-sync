"""
screenshotter.py — 视频智能截图模块

纯本地 MVP：ffmpeg 场景检测 + phash 去重 + OpenCV + Tesseract 文字密度评分
无需 GPU，不依赖 API，低资源消耗（内存增量 < 500MB）

用法：
    from screenshotter import extract_screenshots
    screenshots = extract_screenshots(video_paths, output_dir)

返回截图列表 [{"path": "...", "timestamp_sec": 120.5, "desc": ""}, ...]
失败返回 None（不影响转录主流程）
"""

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from datetime import datetime

import cv2
import numpy as np
from PIL import Image
import imagehash
import pytesseract

# ============================================================
# 配置
# ============================================================

# 截图输出目录（通过环境变量配置，默认在 Obsidian vault 附件目录）
SCREENSHOT_DIR = os.environ.get(
    "SCREENSHOT_DIR",
    r"E:\obsidian\包罗万象\08-attachment\笔记链接同步截图"
)

# Tesseract 路径（Windows 安装位置，代码中用 pytesseract 自动查找 + fallback）
_TESSERACT_CANDIDATES = [
    r"C:\Program Files\Tesseract-OCR\tesseract.exe",
    r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
]
for _c in _TESSERACT_CANDIDATES:
    if os.path.isfile(_c):
        pytesseract.pytesseract.tesseract_cmd = _c
        break

# Tesseract 中文语言包查找：优先用户目录，其次系统目录
_TESSDATA_CANDIDATES = [
    os.path.join(os.path.expanduser("~"), "tesseract"),
    os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), "Tesseract-OCR"),
]
for _td in _TESSDATA_CANDIDATES:
    _lang_file = os.path.join(_td, "tessdata", "chi_sim.traineddata")
    if os.path.isfile(_lang_file):
        os.environ["TESSDATA_PREFIX"] = _td
        break

SCENE_THRESHOLD = 0.4          # ffmpeg scene 检测阈值 (0-1)
PHASH_HAMMING = 12             # phash 去重 Hamming 距离阈值
MAX_CANDIDATE_FRAMES = 80      # 去重后最多保留候选帧
TOP_N_SCORES = 25              # 最终保留评分最高的帧数
SCREENSHOT_MAX = 20            # 单个视频截图硬上限
FRAME_SCALE_WIDTH = 1280       # 候选帧缩放宽度（减少计算负担）

# 评分权重（教学类 vs 知识类，差异不大，可用通用权重）
SCORE_WEIGHTS = {
    "edge": 0.40,
    "text": 0.35,
    "color": 0.25,
}


# ============================================================
# 工具函数
# ============================================================

def log(msg: str) -> None:
    """日志输出到 stderr"""
    print(f"[screenshotter] {msg}", file=sys.stderr, flush=True)


def _get_video_duration(video_path: Path) -> float:
    """获取视频时长（秒）"""
    cmd = [
        "ffprobe", "-v", "quiet",
        "-show_entries", "format=duration",
        "-of", "csv=p=0",
        str(video_path),
    ]
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True,
            timeout=30, encoding="utf-8", errors="replace",
        )
        return float(result.stdout.strip())
    except Exception:
        return 0.0


# ============================================================
# 步骤 1: ffmpeg 场景切换检测
# ============================================================

def detect_scenes(video_path: Path, output_dir: Path) -> list[Path]:
    """
    使用 ffmpeg select 滤镜提取场景变化帧。
    返回候选帧文件路径列表。
    """
    log(f"场景检测: {video_path.name}")
    output_dir.mkdir(parents=True, exist_ok=True)
    output_template = str(output_dir / "scene_%04d.jpg")

    cmd = [
        "ffmpeg",
        "-i", str(video_path),
        "-vf", f"select=gt(scene\\,{SCENE_THRESHOLD}),scale={FRAME_SCALE_WIDTH}:-1",
        "-vsync", "vfr",
        "-qscale:v", "5",
        "-frame_pts", "1",
        "-frames:v", "300",  # 安全上限
        "-y",
        output_template,
    ]

    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True,
                       timeout=300, encoding="utf-8", errors="replace")
    except subprocess.CalledProcessError as e:
        log(f"⚠️ 场景检测失败: {e.stderr[:200] if e.stderr else e}")
        return []
    except subprocess.TimeoutExpired:
        log("⚠️ 场景检测超时")
        return []

    frames = sorted(output_dir.glob("scene_*.jpg"))
    log(f"  场景检测完成: {len(frames)} 帧")
    return frames


# ============================================================
# 步骤 2: phash 感知哈希去重
# ============================================================

def deduplicate_by_phash(frames: list[Path], threshold: int = PHASH_HAMMING) -> list[Path]:
    """使用 phash 将相似帧分组，每组保留一张。"""
    if len(frames) <= MAX_CANDIDATE_FRAMES:
        return frames

    groups: list[dict] = []  # [{"hash": ImageHash, "frames": [Path, ...]}]
    for fp in frames:
        try:
            img = Image.open(fp)
            h = imagehash.phash(img)
            img.close()
        except Exception:
            continue

        matched = False
        for g in groups:
            if h - g["hash"] < threshold:
                g["frames"].append(fp)
                matched = True
                break
        if not matched:
            groups.append({"hash": h, "frames": [fp]})

    result = [g["frames"][0] for g in groups]
    log(f"  去重: {len(frames)} → {len(result)} 唯一场景")
    return result


# ============================================================
# 步骤 3: 启发式评分
# ============================================================

def _edge_density(img_bgr: np.ndarray) -> float:
    """Canny 边缘检测，返回非零像素占比 (0-1)。"""
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150)
    return float(np.count_nonzero(edges)) / edges.size


def _text_density(img_bgr: np.ndarray) -> float:
    """
    Tesseract 文字区域检测。
    用 --psm 11（稀疏文字检测）识别文本框数量，归一化到 0-1。
    """
    try:
        # 转灰度 + 自适应二值化提升检测率
        gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        gray = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                     cv2.THRESH_BINARY, 11, 2)

        # 用 pytesseract 获取文字块边界框
        data = pytesseract.image_to_data(gray, lang="chi_sim+eng",
                                         output_type=pytesseract.Output.DICT,
                                         config="--psm 11")
        text_count = sum(1 for c in data.get("conf", []) if isinstance(c, (int, float)) and c > 30)
        return min(text_count / 30.0, 1.0)
    except Exception:
        return 0.0


def _color_entropy(img_bgr: np.ndarray) -> float:
    """HSV 直方图信息熵（排除纯色过渡页），归一化到 0-1。"""
    hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], None, [30, 32], [0, 180, 0, 256])
    hist_norm = hist / (hist.sum() + 1e-7)
    entropy = float(-np.sum(hist_norm * np.log2(hist_norm + 1e-7)))
    return min(entropy / 8.0, 1.0)


def score_frames(frames: list[Path], weights: dict | None = None) -> list[dict]:
    """
    对帧列表进行启发式评分。
    返回 [{"path": Path, "timestamp_sec": float, "score": float, "edge": float, "text": float, "color": float}, ...]
    按 score 降序排列。
    """
    if weights is None:
        weights = SCORE_WEIGHTS

    # 批量获取时间戳，避免逐帧调用 ffprobe
    frame_dir = frames[0].parent if frames else None
    timestamps = _get_frame_timestamps(frame_dir) if frame_dir else {}

    results = []
    for i, fp in enumerate(frames):
        try:
            img = cv2.imread(str(fp))
            if img is None or img.size == 0:
                continue

            edge_s = _edge_density(img)
            text_s = _text_density(img)
            color_s = _color_entropy(img)
            composite = (weights["edge"] * edge_s +
                         weights["text"] * text_s +
                         weights["color"] * color_s)

            # 从批量 ffprobe 结果获取精确时间戳
            ts = timestamps.get(fp.name, 0.0)

            results.append({
                "path": fp,
                "timestamp_sec": ts,
                "score": round(composite, 4),
                "edge": round(edge_s, 4),
                "text": round(text_s, 4),
                "color": round(color_s, 4),
            })
        except Exception as e:
            log(f"  ⚠️ 评分失败 {fp.name}: {e}")
            continue

        if (i + 1) % 20 == 0:
            log(f"  评分进度: {i + 1}/{len(frames)}")

    results.sort(key=lambda r: r["score"], reverse=True)
    log(f"  评分完成: {len(results)} 帧有效, top-5 分数: " +
        ", ".join(f"{r['score']:.2f}" for r in results[:5]))
    return results


def _get_frame_timestamps(frame_dir: Path) -> dict[str, float]:
    """
    用 ffprobe 批量获取任意图片文件的 PTS 时间戳。
    逐个文件探测，避免通配符在 Windows 上不工作。
    返回 {filename: timestamp_sec} 映射。
    """
    timestamps = {}
    frames = sorted(frame_dir.glob("scene_*.jpg"), key=lambda p: p.name)
    if not frames:
        return timestamps

    # 逐个探测：文件名不包含内嵌时间戳，用 ffprobe 从帧数据中提取
    for fp in frames:
        cmd = [
            "ffprobe", "-v", "quiet",
            "-select_streams", "v:0",
            "-show_entries", "frame=pts_time",
            "-of", "csv=p=0",
            str(fp),
        ]
        try:
            result = subprocess.run(
                cmd, capture_output=True, text=True,
                timeout=5, encoding="utf-8", errors="replace",
            )
            pts_str = result.stdout.strip()
            if pts_str and pts_str.replace(".", "").replace("-", "").isdigit():
                timestamps[fp.name] = float(pts_str)
        except Exception:
            pass
    log(f"  提取了 {len(timestamps)}/{len(frames)} 帧的 PTS 时间戳")
    return timestamps


# ============================================================
# 步骤 4: 原分辨率截图
# ============================================================

def capture_hires(video_path: Path, timestamp_sec: float, output_dir: Path,
                  frame_index: int) -> Path | None:
    """
    从原视频指定时间点提取原分辨率截图。
    用 ffmpeg -ss 精确定位时间戳。
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    out_file = output_dir / f"screenshot_{frame_index:03d}.jpg"

    cmd = [
        "ffmpeg",
        "-ss", str(timestamp_sec),
        "-i", str(video_path),
        "-vframes", "1",
        "-q:v", "3",
        "-y",
        str(out_file),
    ]
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True,
                       timeout=60, encoding="utf-8", errors="replace")
        if out_file.exists() and out_file.stat().st_size > 1000:
            return out_file
    except Exception as e:
        log(f"  ⚠️ 高分辨率截图失败: {e}")
    return None


# ============================================================
# 顶层编排
# ============================================================

def extract_screenshots(
    video_paths: list[Path],
    output_dir: Path,
) -> list[dict] | None:
    """
    视频智能截图主入口。

    参数:
        video_paths: 下载的视频文件路径列表（支持多P）
        output_dir: 截图最终存储目录

    返回:
        [{"path": "screenshot_001.jpg", "timestamp_sec": 120.5}, ...]
        失败返回 None
    """
    try:
        screenshots: list[dict] = []

        # 处理每个视频（支持多P：每个视频独立检测后在转录中按累计时间偏移对齐）
        cumulative_offset = 0.0
        all_video_durations = [_get_video_duration(vp) for vp in video_paths]

        for vid_idx, (video_path, duration) in enumerate(zip(video_paths, all_video_durations)):
            log(f"处理视频 {vid_idx + 1}/{len(video_paths)}: {video_path.name}")

            # Step 1: 场景检测
            scene_dir = video_path.parent / f"scenes_{vid_idx}"
            frames = detect_scenes(video_path, scene_dir)
            if not frames:
                log("  ⚠️ 无场景帧，跳过该视频")
                cumulative_offset += duration
                continue

            # Step 2: phash 去重
            unique_frames = deduplicate_by_phash(frames)

            # Step 3: 评分
            scored = score_frames(unique_frames)
            if not scored:
                cumulative_offset += duration
                continue

            # 取前 TOP_N_SCORES，但排除 score 太低的（< 0.08）
            top_frames = [sf for sf in scored[:TOP_N_SCORES] if sf["score"] >= 0.08]
            if not top_frames and scored:
                # 如果全部低于阈值，至少保留 top 3
                top_frames = scored[:3]

            # Step 4: 截取原分辨率图（只截分数 > 阈值的高信息密度帧）
            for rank, sf in enumerate(top_frames):
                if len(screenshots) >= SCREENSHOT_MAX:
                    break
                if sf["score"] < 0.05:  # 极低分不截图
                    continue

                # 直接使用 score_frames() 中已经计算好的精确时间戳
                hi_path = capture_hires(video_path, sf["timestamp_sec"], output_dir, len(screenshots))
                if hi_path:
                    ts = sf["timestamp_sec"] + cumulative_offset

                    screenshots.append({
                        "path": str(hi_path),
                        "filename": hi_path.name,
                        "timestamp_sec": round(ts, 1),
                        "score": sf["score"],
                    })

            cumulative_offset += duration
            log(f"  该视频已截 {len([s for s in screenshots if s.get('_vid', vid_idx) == vid_idx])} 张，累计 {len(screenshots)} 张")

        if not screenshots:
            log("⚠️ 未产生任何截图（可能视频内容不适合截图）")
            return None

        # 标记多P
        is_multi = len(video_paths) > 1
        log(f"✅ 截图完成: {len(screenshots)} 张" + (" (多P视频)" if is_multi else ""))
        return screenshots

    except Exception as e:
        log(f"❌ 截图失败: {e}")
        import traceback
        log(f"  堆栈: {traceback.format_exc()}")
        return None
