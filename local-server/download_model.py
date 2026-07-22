"""下载 faster-whisper large-v3 模型（requests 直接下载 + 断点续传 + 自动重试）"""
import requests, os, time, sys

sys.stdout.reconfigure(encoding='utf-8')

# 配置
MODEL_URL = "https://huggingface.co/Systran/faster-whisper-large-v3/resolve/main/model.bin"
CACHE_DIR = os.path.expanduser("~/.cache/huggingface/hub/models--Systran--faster-whisper-large-v3/blobs")
SHA = "69f74147e3334731bc3a76048724833325d2ec74642fb52620eda87352e3d4f1"
TOTAL = 3087336192  # 2.875 GB

os.makedirs(CACHE_DIR, exist_ok=True)
dest = os.path.join(CACHE_DIR, SHA)

existing = os.path.getsize(dest) if os.path.exists(dest) else 0
print(f"模型文件: {TOTAL/1024/1024/1024:.1f} GB")
print(f"已下载: {existing/1024/1024/1024:.2f} GB" if existing > 0 else "全新下载")
print(f"保存到: {dest}")
print()

MAX_RETRIES = 20  # 最多重试 20 次
chunk_size = 4 * 1024 * 1024  # 4 MB chunks
global_start = time.time()

for attempt in range(1, MAX_RETRIES + 1):
    existing = os.path.getsize(dest) if os.path.exists(dest) else 0
    if existing >= TOTAL:
        print("文件已完整，无需下载！")
        break

    headers = {"User-Agent": "Mozilla/5.0"}
    if existing > 0:
        headers["Range"] = f"bytes={existing}-"
        print(f"断点续传 from byte {existing} ({existing/1024/1024/1024:.2f} GB)")

    try:
        r = requests.get(MODEL_URL, headers=headers, stream=True, timeout=(30, 300))
        print(f"HTTP {r.status_code}")
        if r.status_code not in (200, 206):
            print(f"错误: {r.text[:200]}")
            time.sleep(5)
            continue

        mode = "ab" if existing > 0 else "wb"
        start = time.time()
        downloaded = existing if mode == "ab" else 0
        last_report = start

        with open(dest, mode) as f:
            for chunk in r.iter_content(chunk_size=chunk_size):
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                now = time.time()
                if now - last_report >= 10:
                    elapsed = now - start
                    added = downloaded - (existing if mode == "ab" else 0)
                    speed = added / elapsed / 1024 / 1024 if elapsed > 0 else 0
                    pct = downloaded / TOTAL * 100
                    eta_sec = (TOTAL - downloaded) / (speed * 1024 * 1024) if speed > 0 else 0
                    eta_min = int(eta_sec / 60)
                    print(f"  {downloaded/1024/1024/1024:.2f}/{TOTAL/1024/1024/1024:.1f} GB ({pct:.1f}%) {speed:.1f} MB/s ETA: {eta_min} min")
                    last_report = now

        # 下载完检查完整性
        final = os.path.getsize(dest)
        if final >= TOTAL:
            elapsed = time.time() - global_start
            print()
            print(f"下载完成！耗时 {elapsed:.0f} 秒")
            print(f"速度: {downloaded/elapsed/1024/1024:.1f} MB/s")
            print("DONE")
            break
        else:
            print(f"连接关闭，文件大小 {final/1024/1024/1024:.2f} GB，将重试...")
    except (requests.exceptions.ConnectionError, requests.exceptions.ReadTimeout,
            requests.exceptions.ChunkedEncodingError, Exception) as e:
        now_size = os.path.getsize(dest) if os.path.exists(dest) else 0
        pct = now_size / TOTAL * 100
        print(f"[尝试 {attempt}/{MAX_RETRIES}] 网络中断 @ {pct:.1f}%: {type(e).__name__}")
        wait = min(attempt * 5, 60)
        print(f"  {wait} 秒后重试...")
        time.sleep(wait)

if os.path.getsize(dest) >= TOTAL:
    print("DONE")
else:
    print(f"达到最大重试次数，文件不完整 ({os.path.getsize(dest)/1024/1024/1024:.2f} GB / {TOTAL/1024/1024/1024:.1f} GB)")
    print("请重新运行此脚本继续下载。")
