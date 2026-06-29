import type { NoteFrontmatter } from "../utils/FrontmatterUtils";

export type ContentType = "link" | "image" | "video" | "file" | "plain" | "unknown";

/**
 * 根据 frontmatter 判断素材类型
 */
export function classify(fm: NoteFrontmatter): ContentType {
    const st = fm.source_type;
    if (st === "link") return "link";
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
 */
export function isVideoUrl(url: string): { platform: string } | null {
    if (url.includes("bilibili.com/video/")) return { platform: "bilibili" };
    if (url.includes("youtube.com/watch") || url.includes("youtu.be/")) return { platform: "youtube" };
    if (url.includes("douyin.com/video/")) return { platform: "douyin" };
    return null;
}
