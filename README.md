# Obsidian 本地同步系统

将手机端内容（微信文章、链接、图片、视频等）安全同步到电脑端 Obsidian，**数据不经过任何第三方服务器**。

## 核心原则

- **隐私第一**: 数据通过 Syncthing P2P 加密直传，永不经过云服务器
- **纯本地**: 所有处理在本地完成，AI 调用可选且直连 API
- **开源透明**: MIT 许可，代码完全公开

## 系统架构

```
手机微信/浏览器 → [分享] → Quick Capture App → 存 .md 到本地文件夹
                                                      ↓
                                          Syncthing P2P 加密同步
                                                      ↓
                                        电脑收件箱 (E:\obsidian\obsidian-Inbox)
                                                      ↓
                                          Obsidian 插件自动处理
                                          ├── 全文抓取 (公众号/网页)
                                          ├── 视频字幕提取 (B站/YouTube)
                                          ├── AI 标签+摘要 (可选)
                                          └── 移入 Vault 正式目录
```

## 项目包含

| 组件 | 路径 | 技术栈 | 说明 |
|------|------|--------|------|
| Android Quick Capture | `obsidian-quick-capture/` | Kotlin + Jetpack Compose | 接收手机分享，存原始 Markdown |
| Obsidian 插件 | `obsidian-local-sync/` | TypeScript | 监听收件箱，自动处理素材 |

## 前置依赖

- [Syncthing](https://syncthing.net/) — 手机和电脑之间 P2P 文件同步
- 电脑端 Obsidian vault (路径: `E:\obsidian\`)

## 快速开始

### 1. 基础设施 (已完成 ✓)

- [x] 电脑安装 Syncthing (v2.1.1)
- [x] 手机安装 Syncthing-Fork
- [x] 配对手机 ↔ 电脑
- [x] 共享文件夹: 手机 `内部存储/Syncthing/Obsidian-Inbox` ↔ 电脑 `E:\obsidian\obsidian-Inbox`

### 2. 构建 Android App

```bash
cd obsidian-quick-capture
# 用 Android Studio 打开项目 → Build → Build APK
# 或命令行:
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 3. 构建 Obsidian 插件

```bash
cd obsidian-local-sync
npm install
npm run build
# 输出: main.js
# 复制 main.js, manifest.json, styles.css 到:
# E:\obsidian\.obsidian\plugins\obsidian-local-inbox-sync\
```

### 4. 使用

1. 手机: 在微信里点「分享」→ 选择「Quick Capture」
2. Syncthing 自动同步到电脑
3. Obsidian 插件自动处理（全文抓取/AI/归档）

## 文件格式说明

手机生成的 `.md` 文件采用统一的 frontmatter 协议：

```yaml
---
title: "素材标题"
source_type: "link"    # link | image | video | file | plain
source: "weixin.qq.com"
url: "https://..."
created: "2026-06-29-143052"
status: "pending"      # pending → processing → processed → error
---
原始内容...
```

## 相关文档

- [PRD 产品需求文档](docs/PRD.md)
- [架构设计文档](docs/ARCHITECTURE.md)

## 许可

MIT License
