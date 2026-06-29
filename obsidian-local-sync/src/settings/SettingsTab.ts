import { App, PluginSettingTab, Setting } from "obsidian";
import type LocalInboxSyncPlugin from "../../main";

export class SettingsTab extends PluginSettingTab {
    plugin: LocalInboxSyncPlugin;

    constructor(app: App, plugin: LocalInboxSyncPlugin) {
        super(app, plugin);
        this.plugin = plugin;
    }

    display(): void {
        const { containerEl } = this;
        containerEl.empty();
        containerEl.createEl("h2", { text: "Local Inbox Sync - 设置" });

        // === 收件箱设置 ===
        containerEl.createEl("h3", { text: "📥 收件箱" });
        new Setting(containerEl)
            .setName("收件箱路径")
            .setDesc("电脑端 Syncthing 同步文件夹的完整路径")
            .addText(text => text
                .setPlaceholder("E:\\obsidian\\obsidian-Inbox")
                .setValue(this.plugin.settings.inboxPath)
                .onChange(async (value) => {
                    this.plugin.settings.inboxPath = value;
                    await this.plugin.saveSettings();
                    this.plugin.restartWatcher();
                }));

        new Setting(containerEl)
            .setName("输出目录")
            .setDesc("处理后笔记存放的目录 (相对于 Vault 根目录)")
            .addText(text => text
                .setPlaceholder("收件箱")
                .setValue(this.plugin.settings.outputDir)
                .onChange(async (value) => {
                    this.plugin.settings.outputDir = value;
                    await this.plugin.saveSettings();
                }));

        // === 内容抓取 ===
        containerEl.createEl("h3", { text: "🔍 内容抓取" });
        new Setting(containerEl)
            .setName("自动抓取全文")
            .setDesc("对链接类型的素材，自动抓取网页/公众号全文内容")
            .addToggle(toggle => toggle
                .setValue(this.plugin.settings.fetchFullContent)
                .onChange(async (value) => {
                    this.plugin.settings.fetchFullContent = value;
                    await this.plugin.saveSettings();
                }));

        // === 附件 ===
        containerEl.createEl("h3", { text: "📎 附件" });
        new Setting(containerEl)
            .setName("附件目录")
            .setDesc("图片和文件附件的存放目录 (相对于 Vault 根目录)")
            .addText(text => text
                .setPlaceholder("附件")
                .setValue(this.plugin.settings.attachmentDir)
                .onChange(async (value) => {
                    this.plugin.settings.attachmentDir = value;
                    await this.plugin.saveSettings();
                }));

        // === 关于 AI 处理 ===
        containerEl.createEl("h3", { text: "🤖 关于 AI 处理" });
        const infoDiv = containerEl.createEl("div");
        infoDiv.createEl("p", {
            text: "本插件不内置 AI 自动标签/摘要功能。"
        });
        infoDiv.createEl("p", {
            text: "推荐使用 Claudian 插件调用 Claude Code，在 Obsidian 内手动对素材进行分析、打标签、写摘要、关联知识图谱。Claude Code 比任何自动脚本都更理解你的知识体系。"
        });
        infoDiv.createEl("p", {
            text: "工作流: 素材到达收件箱 → 本插件归档 → 你打开笔记 → Ctrl+P 调出 Claude Code → \"帮我分析这篇文章，打标签，写摘要\""
        });
    }
}
