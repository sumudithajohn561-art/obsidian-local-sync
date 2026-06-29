import { Plugin, type PluginManifest } from "obsidian";
import { DEFAULT_SETTINGS, type PluginSettings } from "./src/settings/PluginSettings";
import { SettingsTab } from "./src/settings/SettingsTab";
import { InboxWatcher } from "./src/watcher/InboxWatcher";
import { Pipeline } from "./src/pipeline/Pipeline";
import { Logger } from "./src/utils/Logger";
import * as path from "path";
import * as fs from "fs";

const log = new Logger("Plugin");

export default class LocalInboxSyncPlugin extends Plugin {
    settings: PluginSettings = DEFAULT_SETTINGS;
    private pipeline: Pipeline | null = null;
    private watcher: InboxWatcher | null = null;
    private statusBarItem: HTMLElement | null = null;

    async onload(): Promise<void> {
        log.info("加载 Local Inbox Sync 插件");

        // 加载设置
        await this.loadSettings();

        // 设置面板
        this.addSettingTab(new SettingsTab(this.app, this));

        // 启动文件监听和处理管道
        this.startEngine();

        // 状态栏指示器
        this.statusBarItem = this.addStatusBarItem();
        this.statusBarItem.setText("📥 扫描收件箱...");

        // 启动时自动扫描已有文件（处理插件未运行期间到达的素材）
        this.manualScan().catch(e => log.error("启动扫描失败:", e));

        // 命令: 手动扫描收件箱
        this.addCommand({
            id: "scan-inbox",
            name: "手动扫描收件箱",
            callback: () => this.manualScan(),
        });
    }

    onunload(): void {
        log.info("卸载 Local Inbox Sync 插件");
        this.stopEngine();
    }

    /** 启动引擎: 文件监听 + 处理管道 */
    private startEngine(): void {
        const vaultRoot = (this.app.vault.adapter as { basePath?: string }).basePath
            || path.resolve(".");

        // 管道
        this.pipeline = new Pipeline(this.settings, vaultRoot);

        // 监听器
        this.watcher = new InboxWatcher(
            this.settings.inboxPath,
            (filePath: string) => {
                this.pipeline?.handleFile(filePath);
            },
            3000 // 3秒防抖
        );
        this.watcher.start();

        log.info(`引擎已启动 - 收件箱: ${this.settings.inboxPath}`);
    }

    /** 停止引擎 */
    private stopEngine(): void {
        if (this.watcher) {
            this.watcher.stop();
            this.watcher = null;
        }
        this.pipeline = null;
    }

    /** 重启监听器 (路径变更时调用) */
    restartWatcher(): void {
        this.stopEngine();
        this.startEngine();
    }

    /** 手动扫描收件箱 */
    private async manualScan(): Promise<void> {
        if (!this.pipeline) {
            log.warn("管道未启动");
            return;
        }
        const inboxPath = this.settings.inboxPath;
        log.info(`扫描收件箱: ${inboxPath}`);
        if (!fs.existsSync(inboxPath)) {
            log.warn(`收件箱路径不存在: ${inboxPath}`);
            if (this.statusBarItem) this.statusBarItem.setText("📥 收件箱路径不存在");
            return;
        }
        const files = fs.readdirSync(inboxPath).filter(f => f.endsWith(".md"));
        log.info(`手动扫描: 发现 ${files.length} 个文件`);
        for (const file of files) {
            const filePath = path.join(inboxPath, file);
            await this.pipeline.handleFile(filePath);
        }
        if (this.statusBarItem) {
            this.statusBarItem.setText("📥 收件箱监听中...");
        }
    }

    async loadSettings(): Promise<void> {
        this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
    }

    async saveSettings(): Promise<void> {
        await this.saveData(this.settings);
    }
}
