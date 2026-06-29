# 安装指南

## 前提条件

1. 一台 Windows 电脑 + 一部 Android 手机
2. 手机和电脑连接**同一个 WiFi**（首次配对需要；配对后可跨网络）

---

## 第一步：安装 Syncthing（一次性）

### 电脑端
```powershell
winget install Syncthing.Syncthing
```

安装后 Syncthing 在后台运行，通过浏览器访问 `http://127.0.0.1:8384` 管理。

> ⚠️ 重要：Windows 网络需设为「专用」模式（设置 → 网络和 Internet → WiFi → 点击当前网络 → 专用）

### 手机端
从 [GitHub Releases](https://github.com/sumudithajohn561-art/obsidian-local-sync/releases) 下载 Syncthing-Fork APK 并安装。

> 华为手机提示「ICP未备案」是正常的——Syncthing 是国际开源项目，没有中国 ICP 备案。安全可信。

---

## 第二步：配对手机和电脑

1. 电脑浏览器打开 `http://127.0.0.1:8384`
2. 点右上角「操作」→「显示 ID」→ 看到二维码
3. 手机打开 Syncthing-Fork →「设备」标签 → 点 + →「扫描二维码」
4. 扫码后，电脑端会弹出确认提示 → 点「保存」
5. 创建共享文件夹：
   - 电脑端：添加文件夹 → 路径选 `E:\obsidian\obsidian-Inbox` → 共享标签勾选手机
   - 手机端：添加文件夹 → 文件夹 ID 填 `kwijn-nldif` → 路径选手机存储 → 共享给电脑

---

## 第三步：安装 Quick Capture App

1. 从 [GitHub Releases](https://github.com/sumudithajohn561-art/obsidian-local-sync/releases) 下载最新 `app-debug.apk`
2. 传到手机并安装
3. 打开 App → 首次使用需授予「管理所有文件」权限（用于写入 Syncthing 文件夹）
4. 不需要打开主界面——App 通过「分享」菜单使用

---

## 第四步：安装 Obsidian 插件

1. 从 [GitHub Releases](https://github.com/sumudithajohn561-art/obsidian-local-sync/releases) 下载 `obsidian-local-inbox-sync.zip`
2. 解压到 `你的Vault\.obsidian\plugins\obsidian-local-inbox-sync\`
3. 重启 Obsidian → 设置 → 第三方插件 → 启用「Local Inbox Sync」
4. 右下角状态栏应显示「📥 收件箱监听中...」

---

## 第五步：开始使用

1. 手机微信/浏览器 → 看到好文章 → 点「分享」→ 选择「Quick Capture」
2. 手机弹出「已保存」
3. Syncthing 自动同步到电脑（约5-30秒）
4. Obsidian 插件自动处理 → 笔记出现在 Vault 的「收件箱」目录

> 💡 AI 处理建议：在 Obsidian 中打开素材笔记 → Ctrl+P → 调出 Claude Code（需安装 Claudian 插件）→ "帮我分析这篇文章，打标签，写摘要"

---

## 常见问题

**Q: 手机和电脑显示「已断开连接」？**
A: ① 确认在同一 WiFi ② Windows 网络设为「专用」③ 手机打开 Syncthing-Fork App 保持前台

**Q: 插件状态栏不显示？**
A: 确认插件已启用 → 重启 Obsidian → 看右下角

**Q: 收件箱文件不被处理？**
A: Ctrl+P → 运行「手动扫描收件箱」命令

**Q: APK 安装时提示「未知来源」？**
A: 设置 → 安全 → 允许安装未知应用 → 给浏览器/文件管理器授权
