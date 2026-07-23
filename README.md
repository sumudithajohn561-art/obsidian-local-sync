# Obsidian 本地同步系统

将手机端内容（微信文章、链接、视频等）同步到电脑端 Obsidian，**数据不经过任何第三方服务器**。

## 核心原则

- **隐私第一**: HTTP 直传仅限局域网，内容不经过云服务器
- **纯本地**: 所有处理在本地完成，AI 调用可选且直连 API
- **开源透明**: MIT 许可，代码完全公开

## 系统架构（v2 HTTP 直传）

```
手机微信 → 微信公众号/企业微信回调
      ↓ (HTTPS → 腾讯服务器 → 转发到你的电脑)
local-server (Node.js Express, 端口19527)
      ├── /wechat      — 微信公众号消息回调
      ├── /wecom-kf    — 企业微信客服消息回调
      ├── /capture     — 通用捕获API（需要API Key）
      └── /ping        — 健康检查
      ↓
识别内容类型:
  ├── B站/YouTube/抖音视频 → 转录队列 → transcriber.py
  │     ├── yt-dlp 下载视频 (代理+cookies可选)
  │     ├── ffmpeg 提取音频 (16kHz单声道)
  │     └── faster-whisper large-v3 转录中文
  │              ↓
  │  写入收件箱 .md (source_type: "transcript")
  │
  └── 网页/公众号/视频号链接 → 直接写 .md 到收件箱 (source_type: "link")
                          ↓
              Obsidian 插件 (InboxWatcher) 检测新文件
                          ↓
              Pipeline 按类型处理:
              ├── link → WebContentFetcher 尝试全文抓取
              ├── transcript → 直接搬运到 Vault
              ├── video → 视频信息抓取(字幕/标题)
              ├── image → 移动附件
              └── 全部 → 归档到 Vault/{输出目录}/YYYY-MM-DD/
```

## 平台支持状态

| 平台 | 转录/转写 | 说明 |
|------|----------|------|
| **B站** | ✅ 全链路可用 | yt-dlp + faster-whisper，无需cookies |
| **YouTube** | ⚠️ 需cookies | 设置环境变量 `YTDLP_COOKIES_FILE` 或 `YTDLP_COOKIES_BROWSER` |
| **抖音** | ⚠️ 需cookies | 用浏览器访问一次抖音后导出cookies |
| **视频号** | ❌ 暂不支持 | yt-dlp 无提取器，降级为链接保存 |
| **公众号文章** | ⚠️ 易被拦截 | 全文抓取可能触发验证码，失败时降级为链接+标题 |

## 项目包含

| 组件 | 路径 | 技术栈 | 说明 |
|------|------|--------|------|
| Android Quick Capture | `obsidian-quick-capture/` | Kotlin + Jetpack Compose | 接收手机分享，存原始 Markdown |
| Obsidian 插件 | `obsidian-local-sync/` | TypeScript | 监听收件箱，自动处理素材 |
| 本地服务 | `local-server/` | Node.js + Python | HTTP接收微信回调，视频转录 |

## 前置依赖

- **local-server**: Node.js 20+, Python 3.10+, yt-dlp, ffmpeg, faster-whisper
- **Obsidian 插件**: Obsidian 桌面端 (需要 Node.js 文件系统能力)
- **可选**: Android Quick Capture App + Syncthing (如果不想用微信回调)
- **可选**: NVIDIA GPU (CUDA) 用于加速 Whisper 转录
- **可选**: HTTP 代理 (Clash/V2Ray 等) 用于绕过平台反爬

## 快速开始

### 1. 启动 local-server

```bash
cd local-server

# 设置环境变量 (可选)
set CAPTURE_API_KEY=your-secret-key          # API认证密钥
set WECHAT_TOKEN=your-wechat-token           # 微信公众号Token
set YTDLP_PROXY=http://127.0.0.1:10808       # yt-dlp代理
set YTDLP_COOKIES_FILE=E:\cookies.txt        # YouTube/抖音cookies
# 或
set YTDLP_COOKIES_BROWSER=chrome              # 从浏览器读取cookies

npm install
pip install faster-whisper yt-dlp
node server.js
```

首次启动时会自动下载 faster-whisper large-v3 模型 (~2.9GB)。

### 2. 配置微信公众号回调

在你的电脑上部署 local-server 后，通过 frp/ngrok 等内网穿透工具暴露到公网，然后在微信公众号后台配置：
- URL: `https://your-domain/wechat`
- Token: 与 `WECHAT_TOKEN` 环境变量一致

### 3. 构建 Android App（可选）

```bash
cd obsidian-quick-capture
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 4. 构建 Obsidian 插件

```bash
cd obsidian-local-sync
npm install
npm run build
# 复制 main.js, manifest.json, styles.css 到:
# 你的Vault\.obsidian\plugins\obsidian-local-inbox-sync\
```

### 5. 使用

**微信回调方式（推荐）:**
1. 在微信中把文章/视频链接发给公众号
2. local-server 自动接收 → 识别 → 转录/存档
3. Obsidian 插件自动检测并移入 Vault

**Android App 方式:**
1. 手机: 在微信里点「分享」→ 选择「Quick Capture」
2. Syncthing P2P 同步到电脑
3. Obsidian 插件自动处理

## 环境变量参考

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `PORT` | 服务端口 | `19527` |
| `INBOX_PATH` | 收件箱路径 | `E:\obsidian\obsidian-Inbox` |
| `CAPTURE_API_KEY` | API认证密钥 | 空(不启用) |
| `WECHAT_TOKEN` | 微信公众号/企业微信Token | `change-me-please` |
| `YTDLP_PROXY` | yt-dlp 代理地址 | 空(直连) |
| `YTDLP_COOKIES_FILE` | Netscape格式cookie文件 | 空 |
| `YTDLP_COOKIES_BROWSER` | 浏览器名称(chrome/edge) | 空 |
| `HF_ENDPOINT` | HuggingFace镜像 | `https://hf-mirror.com` |

## 相关文档

- [PRD 产品需求文档](docs/PRD.md)
- [架构设计文档](docs/ARCHITECTURE.md)

## 许可

MIT License