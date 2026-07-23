# 架构设计文档

> 版本: v2.0 | 日期: 2026-07-23

---

## 系统架构图（v2 HTTP 直传）

```
┌─────────────────────────────────────────────────────────────────┐
│                          手机端                                  │
│                                                                   │
│  微信 (用户)                                                       │
│   └── 发送链接到公众号/企业微信客服                                 │
│                    │                                              │
│   Quick Capture App (可选)                                        │
│   └── Android Share Intent 接收 → 存 Markdown 到 Syncthing 目录  │
└────────────────────┬──────────────────────────────────────────────┘
                     │
         ┌───────────┴───────────┐
         │  微信服务器            │
         │  POST XML → 公网URL   │
         └───────────┬───────────┘
                     │ (frp/ngrok 内网穿透)
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                        电脑端 (Windows)                          │
│                                                                   │
│  local-server (Node.js) :19527                                   │
│  ┌─────────────────────────────────────────────┐                │
│  │  /wechat     — 微信公众号回调                │                │
│  │  /wecom-kf   — 企业微信客服回调               │                │
│  │  /capture    — 通用捕获 API (API Key 认证)    │                │
│  │  /ping       — 健康检查                      │                │
│  └───────────────────┬─────────────────────────┘                │
│                      │                                            │
│              ┌───────┴──────────┐                                │
│              │  saveToInbox()    │                                │
│              │  URL 识别+分类    │                                │
│              └───────┬──────────┘                                │
│                      │                                            │
│         ┌────────────┼──────────────┐                            │
│         ▼            ▼              ▼                            │
│    视频链接      网页/公众号     其他内容                           │
│   (B站/YT/抖音)    链接                                          │
│         │            │              │                            │
│         ▼            ▼              ▼                            │
│   转录队列     写 .md 到       写 .md 到                           │
│   (持久化)     收件箱          收件箱                              │
│         │                                                         │
│         ▼                                                         │
│  transcriber.py (Python 长驻进程)                                 │
│  ┌─────────────────────────────────────┐                        │
│  │  stdin JSON ← 待处理任务             │                        │
│  │         │                            │                        │
│  │  yt-dlp 下载视频 (代理+cookies可选)   │                        │
│  │         ↓                            │                        │
│  │  ffmpeg 提取音频 (16kHz 单声道)       │                        │
│  │         ↓                            │                        │
│  │  faster-whisper large-v3 转录        │                        │
│  │         ↓                            │                        │
│  │  写入收件箱 .md                       │                        │
│  │         ↓                            │                        │
│  │  stdout JSON → 处理结果              │                        │
│  └─────────────────────────────────────┘                        │
│                      │                                            │
│  E:\obsidian\obsidian-Inbox\                                     │
│          │                                                        │
│  ┌───────┴──────────┐                                           │
│  │  InboxWatcher.ts  │  chokidar 监听 + 防抖                     │
│  │  (awaitWriteFinish)│                                          │
│  └───────┬──────────┘                                           │
│          │                                                        │
│  ┌───────┴──────────┐                                           │
│  │   Pipeline.ts     │  处理管道编排                              │
│  └───────┬──────────┘                                           │
│          │                                                        │
│  ┌───────┴──────────────────────────────┐                       │
│  │         ContentClassifier.ts          │                       │
│  │  link | transcript | image | video |  │                       │
│  │  file | plain                        │                       │
│  └───────┬──────────────────────────────┘                       │
│          │                                                        │
│    ┌─────┼─────────┬──────────┬──────────┐                      │
│    ▼     ▼         ▼          ▼          ▼                      │
│  [Link] [Transcript] [Video] [Image] [File/Plain]               │
│    │     │         │          │          │                       │
│    ▼     ▼         ▼          ▼          ▼                      │
│  全文   直接      字幕       移动      格式化                      │
│  抓取   搬运      抓取       附件                                │
│    │     │         │          │          │                       │
│    └─────┴─────────┴──────────┴──────────┘                      │
│                      │                                            │
│              ┌───────┴──────────┐                               │
│              │    Mover.ts      │                               │
│              │  收件箱→正式目录  │                               │
│              └──────────────────┘                               │
│                      │                                            │
│                      ▼                                            │
│  E:\obsidian\收件箱\2026-07-23\                                  │
│  ├── 20260723-143052-bilibili-树恨你.md                           │
│  └── ...                                                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 技术决策记录 (ADR)

### ADR-1: 为什么用 HTTP 回调而不是 Syncthing P2P？

- **决策**: v2 采用微信回调 + HTTP 直传，v1 的 Syncthing 方案作为可选通道保留
- **原因**: 微信回调是"用户最自然的操作"——在微信里转发给公众号，比打开 App 分享更快
- **代价**: 需要内网穿透（frp/ngrok），且依赖微信服务器转发
- **对比 v1**: Syncthing 方案不需要公网暴露，但需要用户额外安装 Syncthing

### ADR-2: 为什么转录用 Python 长驻进程而不是 on-demand？

- **决策**: transcriber.py 作为长驻子进程，通过 stdin/stdout JSON 通信
- **原因**: faster-whisper 模型 ~2.9GB，加载一次需数十秒。常驻内存避免每次加载
- **代价**: 空闲时占用 GPU 显存 (~2GB int8 量化)，但 RTX 4060 有 8GB 总显存
- **通信协议**: 每行一个 JSON，简单可靠

### ADR-3: 为什么视频号/抖音不做本地 Whisper？

- **决策**: 视频号和抖音的 yt-dlp 支持有限，降级为链接保存
- **原因**: 视频号无 yt-dlp 提取器；抖音需 cookies。强行实现成本远大于收益
- **降级策略**: 保留原文链接+平台标注，用户可手动查看

---

## 数据流

### URL 识别与分类

```
URL hostname ─┬── bilibili.com / b23.tv ──────→ video/bilibili → 转录队列
              ├── youtube.com / youtu.be ──────→ video/youtube → 转录队列
              ├── douyin.com ──────────────────→ video/douyin → 转录队列
              ├── channels.weixin.qq.com ──────→ video/weixin-video → 写文件(降级)
              ├── mp.weixin.qq.com ────────────→ link/weixin → 写文件
              ├── 其他 URL ────────────────────→ link → 写文件
              └── 非 URL ──────────────────────→ plain → 写文件
```

### 转录队列状态机

```
入队 → started (发送给Python) → 处理中 → done/ok (写入收件箱, 清理)
                        ↓
                      error (日志记录, 清理)
```

### Frontmatter 状态机

```
pending → processing → processed (成功, 移入Vault)
                      → error (保留在收件箱, 不删除)
```

---

## 安全设计

| 层面 | 措施 |
|------|------|
| API 认证 | `CAPTURE_API_KEY` 环境变量，所有 /capture 请求需携带 `x-api-key` 头 |
| 微信回调 | SHA1 签名验证 (/wechat)，AES-256-CBC 解密 (/wecom-kf) |
| msgId 去重 | `processedMsgIds` Set 防止重复处理 |
| 请求限制 | Express JSON body 限制 1MB |
| Token | 微信 Token 从环境变量读取，不硬编码 |
| 本地存储 | 所有数据仅存本地磁盘 |

---

## 依赖清单

### local-server
- Node.js 20+ (Express, fast-xml-parser)
- Python 3.10+ (faster-whisper, yt-dlp)
- ffmpeg (系统安装，需在 PATH 中)
- NVIDIA GPU + CUDA (可选，加速 Whisper)

### Obsidian 插件
- TypeScript 5.3
- chokidar 3.6 (文件监听)
- yaml 2.4 (frontmatter 解析)
- Obsidian API 0.15+

### Android App (可选)
- Kotlin 1.9.20
- Jetpack Compose (Material3)
- Android SDK 34 (min 26)

### 基础设施 (可选)
- frp/ngrok (内网穿透，暴露 /wechat 到公网)
- Syncthing 2.1.1 (如果使用 Android App 通道)