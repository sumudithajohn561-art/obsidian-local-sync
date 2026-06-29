import * as path from "path";
import { processFile } from "./Processors";
import { Logger } from "../utils/Logger";
import { fetchWebContent } from "../fetchers/WebContentFetcher";
import { fetchVideoInfo } from "../fetchers/VideoSubtitleFetcher";
import type { PluginSettings } from "../settings/PluginSettings";

const log = new Logger("Pipeline");

/**
 * 主管道
 *
 * 被 InboxWatcher 触发，每检测到新文件时调用。
 * 依次执行: 全文抓取 → 视频信息获取 → AI处理
 */
export class Pipeline {
    private settings: PluginSettings;
    private vaultRoot: string;
    private processing: Set<string> = new Set();

    constructor(settings: PluginSettings, vaultRoot: string) {
        this.settings = settings;
        this.vaultRoot = vaultRoot;
    }

    /** 更新设置 (当用户在设置面板修改时) */
    updateSettings(settings: PluginSettings): void {
        this.settings = settings;
    }

    /**
     * 处理一个文件
     *
     * @param filePath 收件箱中 .md 文件的绝对路径
     */
    async handleFile(filePath: string): Promise<void> {
        const fileName = path.basename(filePath);

        // 防止重复处理同一个文件
        if (this.processing.has(filePath)) {
            log.info(`跳过(已在处理中): ${fileName}`);
            return;
        }
        this.processing.add(filePath);

        try {
            const result = await processFile(
                filePath,
                this.settings,
                this.vaultRoot,
                {
                    fetchWebContent: (url, isWechat) => fetchWebContent(url, isWechat),
                    fetchVideoInfo: (url, platform) => fetchVideoInfo(url, platform),
                }
            );

            if (result.success) {
                log.info(`✅ 处理完成: ${fileName}`);
            } else {
                log.warn(`❌ 处理失败: ${fileName} - ${result.error || "未知错误"}`);
            }
        } finally {
            this.processing.delete(filePath);
        }
    }

    /** 获取当前处理中的文件数 */
    get pendingCount(): number {
        return this.processing.size;
    }
}
