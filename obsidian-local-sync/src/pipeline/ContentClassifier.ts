import type { NoteFrontmatter } from "../utils/FrontmatterUtils";

export type ContentType = "link" | "transcript" | "image" | "video" | "file" | "plain" | "unknown";

export function classify(fm: NoteFrontmatter): ContentType {
    const st = fm.source_type;
    if (st === "link") return "link";
    if (st === "transcript") return "transcript";  // 已转录，直接搬运
    if (st === "image") return "image";
    if (st === "video") return "video";
    if (st === "file") return "file";
    if (st === "plain" || st === "plaintext") return "plain";
    return "unknown";
}

/**
 * 判断URL是否来自微信公众号
 */
export function isWechatArticle(url: string): boolean {
    return url.includes("mp.weixin.qq.com");
}

/**
 * 判断URL是否为视频平台链接
 * 注意: 视频号(weixin-video)不返回，因为 yt-dlp 不支持，
 * server.js 端会以 plain/link 格式存入，Obsidian 插件不做特殊处理
 */
export function isVideoUrl(url: string): { platform: string } | null {
    if (url.includes("bilibili.com") || url.includes("b23.tv")) return { platform: "bilibili" };
    if (url.includes("youtube.com/watch") || url.includes("youtu.be/")) return { platform: "youtube" };
    if (url.includes("douyin.com/video/") || url.includes("v.douyin.com/")) return { platform: "douyin" };
    return null;
}
