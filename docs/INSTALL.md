# 安装指南

## 两种部署方式

| 方式 | 适用场景 | 需要 |
|------|---------|------|
| **微信回调方式**（推荐） | 有公众号/企业微信 | frp/ngrok 内网穿透 |
| **Syncthing + Android App** | 无公众号，纯局域网 | Syncthing + 手机 App |

---

# 方式一：微信回调方式（推荐）

## 前提条件

1. 一台 Windows 电脑（或能运行 Python + Node.js 的服务器）
2. 一个微信公众号/企业微信（用于接收用户消息）
3. 内网穿透工具（frp/ngrok）将电脑的 19527 端口暴露到公网

---

## 第一步：安装依赖

```powershell
# Python 依赖
pip install faster-whisper yt-dlp

# ffmpeg (下载解压后加入PATH)
# https://ffmpeg.org/download.html

# Node.js 依赖
cd local-server
npm install
```

---

## 第二步：配置环境变量

```powershell
# 收件箱路径
$env:CAPTURE_INBOX = "E:\obsidian\obsidian-Inbox"

# 微信 Token
$env:WECHAT_TOKEN = "your-wechat-token"

# API 认证密钥（可选）
$env:CAPTURE_API_KEY = "your-secret-key"

# 代理（推荐国内用户设置）
$env:YTDLP_PROXY = "http://127.0.0.1:10808"

# YouTube/抖音 Cookies（二选一）
$env:YTDLP_COOKIES_FILE = "E:\cookies.txt"
# 或（需先关闭浏览器）
$env:YTDLP_COOKIES_BROWSER = "chrome"
```

---

## 第三步：配置微信公众号回调

1. 用 frp/ngrok 将本地 `19527` 端口暴露到公网
2. 在微信公众号后台「开发 → 基本配置」中设置：
   - URL: `https://your-domain/wechat`
   - Token: 与 `WECHAT_TOKEN` 环境变量一致
3. 提交验证

---

## 第四步：启动服务

```bash
cd local-server
node server.js
```

成功输出：
```
📥 Capture Server 已启动
   端口: 19527  收件箱: E:\obsidian\obsidian-Inbox
   转录服务: 启动中...
[transcriber] ✅ 模型就绪，开始处理队列...
```

首次启动会自动下载 faster-whisper 模型（~2.9GB，约 10-30 分钟）。

---

## 第五步：安装 Obsidian 插件

1. 从 [GitHub Releases](https://github.com/sumudithajohn561-art/obsidian-local-sync/releases) 下载 `obsidian-local-inbox-sync.zip`
2. 解压到 `你的Vault\.obsidian\plugins\obsidian-local-inbox-sync\`
3. 重启 Obsidian → 设置 → 第三方插件 → 启用「Local Inbox Sync」

---

## 开始使用

1. 微信中找到好文章/视频 → 转发给你的公众号
2. local-server 自动接收 → 视频自动转录 / 文章存档
3. Obsidian 插件自动检测 → 笔记移入 Vault

---

## 验证服务状态

```bash
curl http://localhost:19527/ping
# {"status":"ok","inbox":"...","version":"2.1","transcriber":"ready","queueLength":0}
```

---

# 方式二：Syncthing + Android App

## 前提条件

1. 一台 Windows 电脑 + 一部 Android 手机
2. 手机和电脑连接**同一个 WiFi**

---

## 第一步：安装 Syncthing

### 电脑端
```powershell
winget install Syncthing.Syncthing
```
安装后通过浏览器访问 `http://127.0.0.1:8384` 管理。

> ⚠️ Windows 网络需设为「专用」模式（设置 → 网络和 Internet → WiFi → 专用）

### 手机端
从 [GitHub Releases](https://github.com/sumudithajohn561-art/obsidian-local-sync/releases) 下载 Syncthing-Fork APK 并安装。

---

## 第二步：配对 + 共享文件夹

1. 电脑端 `http://127.0.0.1:8384` → 「显示 ID」→ 二维码
2. 手机 Syncthing-Fork → 「设备」→ + →「扫描二维码」→ 扫码
3. 创建共享文件夹：
   - 电脑端：`E:\obsidian\obsidian-Inbox`，共享给手机
   - 手机端：文件夹 ID 填 `kwijn-nldif`，共享给电脑

---

## 第三步：安装 Quick Capture App

1. 从 [GitHub Releases](https://github.com/sumudithajohn561-art/obsidian-local-sync/releases) 下载 `app-debug.apk`
2. 传到手机安装
3. 授予「管理所有文件」权限

---

## 第四步：安装 Obsidian 插件

同上方式一的第五步。

---

## 开始使用

1. 手机微信/浏览器 →「分享」→ 选择「Quick Capture」
2. 手机弹出「已保存」
3. Syncthing 自动同步到电脑（5-30秒）
4. Obsidian 插件自动处理

---

# 常见问题

**Q: transcriber 一直显示 "loading"？**
A: 首次启动下载模型（~2.9GB），等待完成。或先运行 `python download_model.py` 手动下载。

**Q: yt-dlp 报 "This video may be deleted or geo-restricted"？**
A: 设置 `YTDLP_PROXY` 环境变量。

**Q: YouTube/抖音报 "Sign in to confirm you're not a bot"？**
A: 设置 `YTDLP_COOKIES_FILE` 或 `YTDLP_COOKIES_BROWSER` 环境变量。

**Q: 视频号链接没有转录？**
A: yt-dlp 不支持视频号，会自动降级为链接保存。

**Q: 公众号文章没有抓取全文？**
A: 微信反爬严格，文章会被降级为"链接+标题"保存。可在 Obsidian 中手动点击链接阅读。

**Q: 插件状态栏不显示？**
A: 确认插件已启用 → 重启 Obsidian。

**Q: 收件箱文件不被处理？**
A: Ctrl+P → 运行「手动扫描收件箱」命令。

**Q: APK 安装时提示「未知来源」？**
A: 设置 → 安全 → 允许安装未知应用 → 给浏览器/文件管理器授权。