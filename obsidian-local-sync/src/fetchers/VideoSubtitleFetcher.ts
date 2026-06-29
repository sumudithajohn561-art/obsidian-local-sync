import { Logger } from "../utils/Logger";

const log = new Logger("VideoFetch");

export interface VideoInfo {
    title?: string;
    subtitle?: string;
}

/**
 * 抓取视频平台的信息 (标题 + 字幕)
 *
 * @param url 视频链接
 * @param platform 平台名称 ("bilibili" | "youtube" | ...)
 */
export async function fetchVideoInfo(url: string, platform: string): Promise<VideoInfo | null> {
    try {
        log.info(`抓取视频信息: ${platform} - ${url}`);

        switch (platform) {
            case "bilibili":
                return await fetchBilibiliInfo(url);
            case "youtube":
                return await fetchYoutubeInfo(url);
            default:
                log.warn(`不支持的视频平台: ${platform}`);
                return null;
        }
    } catch (err) {
        log.error(`视频抓取失败: ${err instanceof Error ? err.message : String(err)}`);
        return null;
    }
}

/**
 * 抓取 B站 视频信息
 *
 * 使用 B站公开 API (不需要认证):
 * https://api.bilibili.com/x/web-interface/view?bvid=xxx
 */
async function fetchBilibiliInfo(url: string): Promise<VideoInfo | null> {
    try {
        // 从URL提取 BV号
        const bvMatch = url.match(/BV([a-zA-Z0-9]+)/);
        if (!bvMatch) return null;
        const bvid = `BV${bvMatch[1]}`;

        // 获取视频信息
        const infoUrl = `https://api.bilibili.com/x/web-interface/view?bvid=${bvid}`;
        const response = await fetch(infoUrl, {
            headers: {
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer": "https://www.bilibili.com/",
            },
        });

        if (!response.ok) return null;
        const data = await response.json() as {
            code: number;
            data: {
                title: string;
                desc: string;
                subtitle?: { list?: Array<{ subtitle_url: string }> };
            };
        };

        if (data.code !== 0 || !data.data) return null;

        const title = data.data.title;
        const desc = data.data.desc || "";

        // 尝试抓取字幕
        let subtitleText = "";
        if (data.data.subtitle?.list && data.data.subtitle.list.length > 0) {
            const subUrl = data.data.subtitle.list[0].subtitle_url;
            if (subUrl.startsWith("//")) {
                const subResponse = await fetch(`https:${subUrl}`);
                if (subResponse.ok) {
                    const subData = await subResponse.json() as { body?: Array<{ content: string }> };
                    if (subData.body) {
                        subtitleText = subData.body.map(item => item.content).join("\n");
                    }
                }
            }
        }

        const fullText = desc + (subtitleText ? `\n\n---\n## 字幕\n\n${subtitleText}` : "");
        return { title, subtitle: fullText.trim() || undefined };
    } catch {
        return null;
    }
}

/**
 * 抓取 YouTube 视频信息
 *
 * 注意: YouTube 需要 API Key 或 yt-dlp 才能获取完整字幕。
 * 这里做一个轻量实现，只抓取页面标题。
 */
async function fetchYoutubeInfo(url: string): Promise<VideoInfo | null> {
    try {
        const response = await fetch(url, {
            headers: {
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept-Language": "zh-CN,zh;q=0.9",
            },
        });

        if (!response.ok) return null;
        const html = await response.text();

        // 从 <title> 标签提取标题
        const titleMatch = html.match(/<title>([\s\S]*?)<\/title>/i);
        let title = titleMatch ? titleMatch[1].trim() : undefined;

        // YouTube 标题格式: "视频标题 - YouTube"，去掉 "- YouTube" 后缀
        if (title) {
            title = title.replace(/\s*-\s*YouTube\s*$/, "");
        }

        return { title, subtitle: undefined };
    } catch {
        return null;
    }
}
