export interface PluginSettings {
    /** 收件箱文件夹路径 (电脑端 Syncthing 同步目录) */
    inboxPath: string;
    /** 处理后文件的输出目录 (相对于 vault 根目录) */
    outputDir: string;
    /** 是否自动抓取链接全文 */
    fetchFullContent: boolean;
    /** 附件存放目录 (相对于 vault 根目录) */
    attachmentDir: string;
}

export const DEFAULT_SETTINGS: PluginSettings = {
    inboxPath: "E:\\obsidian\\obsidian-Inbox",
    outputDir: "收件箱",
    fetchFullContent: true,
    attachmentDir: "附件",
};
