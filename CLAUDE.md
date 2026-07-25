# Obsidian 本地同步系统 — 项目 CLAUDE.md

## 项目概述

将手机端内容（B站/YouTube/抖音视频、公众号文章等）通过微信公众号接收，自动转录/存档到电脑端 Obsidian。

## 当前状态

- **分支**: v2-http-direct
- **阶段**: 技术验证 → 部署联调
- **local-server**: Node.js Express (端口 19527)，监听微信公众号回调
- **Obsidian 插件**: TypeScript，监听收件箱 → 归档到 Vault

## 目录结构

| 目录 | 用途 |
|------|------|
| `local-server/` | 核心服务：接收微信消息、视频转录（yt-dlp + ffmpeg + faster-whisper） |
| `obsidian-local-sync/` | Obsidian 插件：监听收件箱、处理管道 |
| `obsidian-quick-capture/` | Android App（Syncthing 备选方案） |
| `docs/` | PRD、架构、安装文档 |

## 已完成

- [x] B站视频全链路转录（微信链接 → yt-dlp → ffmpeg → Whisper → Obsidian 笔记）
- [x] 多平台支持框架（B站/YouTube/抖音，代理+cookies环境变量化）
- [x] 降级策略（公众号/视频号抓取失败时保留链接+标题）
- [x] 文档更新（README、ARCHITECTURE、INSTALL）
- [x] cloudflared 公网隧道搭建

## 待完成

- [ ] 微信公众号后台配置（Token=sumu123，需要用户在后台提交 URL 验证）
- [ ] 端到端测试（微信发 B站链接 → 转录 → Obsidian 笔记）
- [ ] 公众号文章全文抓取方案（当前被验证码拦截，需调研 wechat-download-api）
- [ ] 视频号下载方案（yt-dlp 不支持，需调研 scribe-transcribe）
- [ ] YouTube/抖音 cookie 配置文档

## 技术要点

- **视频转录**: yt-dlp -> ffmpeg 16kHz -> faster-whisper large-v3 (CUDA)
- **队列**: JSON 文件持久化，重启不丢任务
- **代理**: YTDLP_PROXY 环境变量，默认直连
- **模型**: large-v3 int8_float16，首次加载约 2-3 分钟
- **前端约束**: 不经过任何第三方服务器（微信服务器转发不可避免）

## 用户偏好

- 数据安全 > 功能丰富
- 微信转发 > App 分享
- 纯本地 > 云服务
- 不接受降级方案（文章全文、视频号必须有解决方案）