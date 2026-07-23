import * as path from "path";
import * as fs from "fs";
import { classify, type ContentType, isVideoUrl } from "./ContentClassifier";
import { parseFrontmatter, buildMarkdown, type NoteFrontmatter } from "../utils/FrontmatterUtils";
import { readFileUtf8, dateDirName, safeMove, ensureDir } from "../utils/FileUtils";
import { Logger } from "../utils/Logger";
import type { PluginSettings } from "../settings/PluginSettings";

const log = new Logger("Processor");

export interface ProcessResult {
    /** 更新后的 frontmatter */
    frontmatter: NoteFrontmatter;
    /** 更新后的正文 */
    body: string;
    /** 是否处理成功 */
    success: boolean;
    /** 错误信息 (如果失败) */
    error?: string;
}

/**
 * 处理单个收件箱文件
 *
 * 管道: 读取 → 分类 → 按类型处理 → 更新frontmatter → 写回 → 移动到输出目录
 */
export async function processFile(
    filePath: string,
    settings: PluginSettings,
    vaultRoot: string,
    fetchers: {
        fetchWebContent: (url: string, isWechat: boolean) => Promise<string | null>;
        fetchVideoInfo: (url: string, platform: string) => Promise<{ title?: string; subtitle?: string } | null>;
    }
): Promise<ProcessResult> {
    try {
        // 1. 读取文件
        const content = readFileUtf8(filePath);
        const { frontmatter, body } = parseFrontmatter(content);

        // 2. 标记开始处理
        frontmatter.status = "processing";

        // 3. 分类
        const contentType = classify(frontmatter);
        log.info(`处理: ${path.basename(filePath)} [类型: ${contentType}]`);

        // 4. 按类型处理
        let newBody = body.trim();

        if (contentType === "link" && settings.fetchFullContent && frontmatter.url) {
            // 抓取网页全文
            const isWechat = frontmatter.url.includes("mp.weixin.qq.com");
            const fetched = await fetchers.fetchWebContent(frontmatter.url, isWechat);
            if (fetched) {
                newBody = fetched;
                frontmatter.source = isWechat ? "微信公众号" : frontmatter.source;
            } else {
                // 抓取失败降级：保留原始链接，让用户在 Obsidian 中手动打开
                newBody = `> ${frontmatter.title || "未命名"}\n\n` +
                    `**原文链接:** ${frontmatter.url}\n\n` +
                    `> ⚠️ 全文抓取失败，请点击链接手动阅读。\n\n` +
                    (body.trim() || "");
            }
        }

        if (contentType === "video" && frontmatter.url) {
            // 视频: 抓取字幕和标题
            const videoInfo = isVideoUrl(frontmatter.url);
            if (videoInfo) {
                const info = await fetchers.fetchVideoInfo(frontmatter.url, videoInfo.platform);
                if (info) {
                    let videoBody = `> ${info.title || frontmatter.title || "视频"}\n\n`;
                    videoBody += `**视频链接:** ${frontmatter.url}\n`;
                    videoBody += `**平台:** ${videoInfo.platform}\n`;
                    if (info.subtitle) {
                        videoBody += `\n---\n## 字幕/转写\n\n${info.subtitle}\n`;
                    }
                    newBody = videoBody;
                    if (info.title) frontmatter.title = info.title;
                } else {
                    // 视频信息抓取失败降级
                    newBody = `> ${frontmatter.title || "视频"}\n\n` +
                        `**视频链接:** ${frontmatter.url}\n` +
                        `**平台:** ${videoInfo.platform}\n\n` +
                        `> ⚠️ 字幕/标题抓取失败，请点击链接手动查看。\n`;
                }
            } else {
                // 非支持平台的视频链接，降级保留链接
                newBody = `> ${frontmatter.title || "视频"}\n\n` +
                    `**视频链接:** ${frontmatter.url}\n\n` +
                    `> ⚠️ 该视频平台暂不支持自动抓取。\n`;
            }
        }

        if (contentType === "transcript") {
            // 视频转录完成品——内容已由 transcriber.py 生成，直接搬运到 Vault
            // 原始 frontmatter 保持不变（Obsidian 会自动管理属性），只做文件搬家
            // 避免 parseFrontmatter/buildMarkdown 破坏原始 YAML 结构
            const outputDir = path.join(vaultRoot, settings.outputDir, dateDirName());
            ensureDir(outputDir);
            const outputFileName = path.basename(filePath);
            let finalPath = path.join(outputDir, outputFileName);
            let counter = 1;
            while (fs.existsSync(finalPath)) {
                const ext = path.extname(outputFileName);
                const base = path.basename(outputFileName, ext);
                finalPath = path.join(outputDir, `${base}-${counter}${ext}`);
                counter++;
            }

            fs.writeFileSync(finalPath, content, "utf-8");
            fs.unlinkSync(filePath);
            return { frontmatter: {}, body: "", success: true };
        }

        if (contentType === "image" && frontmatter.attachment) {
            // 图片: 移动附件到 vault 附件目录
            const inboxDir = path.dirname(filePath);
            const attSrc = path.join(inboxDir, frontmatter.attachment);
            if (fs.existsSync(attSrc)) {
                const attDest = path.join(vaultRoot, settings.attachmentDir, path.basename(frontmatter.attachment));
                ensureDir(path.dirname(attDest));
                safeMove(attSrc, attDest);
                newBody = newBody.replace(frontmatter.attachment, path.join(settings.attachmentDir, path.basename(frontmatter.attachment)));
            }
        }

        // 5. 标记完成
        // 注意: AI 标签/摘要/关联由用户通过 Claudian(Claude Code) 手动完成
        // 本插件只负责"物流"——素材从手机到 Obsidian 的正确位置
        frontmatter.status = "processed";
        frontmatter.processed = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);

        // 7. 生成最终内容
        const finalContent = buildMarkdown(frontmatter, newBody);

        // 8. 写入输出目录
        const outputDir = path.join(vaultRoot, settings.outputDir, dateDirName());
        ensureDir(outputDir);
        const outputFileName = path.basename(filePath);
        const outputPath = path.join(outputDir, outputFileName);

        // 如果输出目录已有同名文件，加序号
        let finalPath = outputPath;
        let counter = 1;
        while (fs.existsSync(finalPath)) {
            const ext = path.extname(outputFileName);
            const base = path.basename(outputFileName, ext);
            finalPath = path.join(outputDir, `${base}-${counter}${ext}`);
            counter++;
        }

        fs.writeFileSync(finalPath, finalContent, "utf-8");

        // 9. 删除收件箱中的原始文件
        fs.unlinkSync(filePath);

        return { frontmatter, body: newBody, success: true };
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        log.error(`处理失败: ${path.basename(filePath)} - ${msg}`);
        return { frontmatter: {}, body: "", success: false, error: msg };
    }
}
