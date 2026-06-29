import { Logger } from "../utils/Logger";

const log = new Logger("WebFetch");

/**
 * 抓取网页/公众号文章全文
 *
 * @param url 文章URL
 * @param isWechat 是否是微信公众号文章
 * @returns 清洗后的 Markdown 文本，失败返回 null
 */
export async function fetchWebContent(url: string, isWechat: boolean): Promise<string | null> {
    try {
        log.info(`抓取网页: ${url}${isWechat ? " [微信]" : ""}`);

        const headers: Record<string, string> = {
            "User-Agent": isWechat
                ? "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.42"
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9",
        };

        const response = await fetch(url, { headers, redirect: "follow" });
        if (!response.ok) {
            log.warn(`HTTP ${response.status}: ${url}`);
            return null;
        }

        const html = await response.text();

        // 提取正文
        const content = extractContent(html, isWechat);
        if (!content) return null;

        // HTML 转 Markdown (简易版)
        const markdown = htmlToMarkdown(content);
        return markdown;
    } catch (err) {
        log.error(`抓取失败: ${err instanceof Error ? err.message : String(err)}`);
        return null;
    }
}

/**
 * 从HTML中提取正文内容
 */
function extractContent(html: string, isWechat: boolean): string | null {
    if (isWechat) {
        // 微信公众号: 正文在 #js_content 或 .rich_media_content 中
        const jsContent = html.match(/id="js_content"\s*>([\s\S]*?)<\/div>/i);
        if (jsContent) return jsContent[1];

        const richContent = html.match(/class="rich_media_content[^"]*"\s*>([\s\S]*?)<\/div>/i);
        if (richContent) return richContent[1];
    }

    // 通用: 尝试提取 <article> 或 <main> 标签
    const article = html.match(/<article[^>]*>([\s\S]*?)<\/article>/i);
    if (article) return article[1];

    const main = html.match(/<main[^>]*>([\s\S]*?)<\/main>/i);
    if (main) return main[1];

    // 最终兜底: 提取 <body>
    const body = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
    return body ? body[1] : null;
}

/**
 * 简化的 HTML → Markdown 转换
 *
 * 处理常见的文章结构，去除脚本、样式、广告等
 */
function htmlToMarkdown(html: string): string {
    let text = html;

    // 1. 移除脚本和样式
    text = text.replace(/<script[\s\S]*?<\/script>/gi, "");
    text = text.replace(/<style[\s\S]*?<\/style>/gi, "");

    // 2. 移除隐藏元素
    text = text.replace(/<[^>]*hidden[^>]*>[\s\S]*?<\/[^>]+>/gi, "");

    // 3. 常见块级元素 → 换行
    text = text.replace(/<\/?(div|p|section|article|header|footer|nav|aside|blockquote)[^>]*>/gi, "\n");

    // 4. 标题 → Markdown 标题
    text = text.replace(/<h1[^>]*>([\s\S]*?)<\/h1>/gi, "\n# $1\n");
    text = text.replace(/<h2[^>]*>([\s\S]*?)<\/h2>/gi, "\n## $1\n");
    text = text.replace(/<h3[^>]*>([\s\S]*?)<\/h3>/gi, "\n### $1\n");
    text = text.replace(/<h4[^>]*>([\s\S]*?)<\/h4>/gi, "\n#### $1\n");

    // 5. 粗体和斜体
    text = text.replace(/<strong[^>]*>([\s\S]*?)<\/strong>/gi, "**$1**");
    text = text.replace(/<b[^>]*>([\s\S]*?)<\/b>/gi, "**$1**");
    text = text.replace(/<em[^>]*>([\s\S]*?)<\/em>/gi, "*$1*");

    // 6. 链接
    text = text.replace(/<a[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi, "[$2]($1)");

    // 7. 图片
    text = text.replace(/<img[^>]*src="([^"]*)"[^>]*\/?>/gi, "![]($1)");

    // 8. 列表项
    text = text.replace(/<li[^>]*>([\s\S]*?)<\/li>/gi, "- $1\n");

    // 9. 换行
    text = text.replace(/<br\s*\/?>/gi, "\n");

    // 10. 去除所有剩余HTML标签
    text = text.replace(/<[^>]+>/g, "");

    // 11. 解码 HTML 实体
    text = text.replace(/&nbsp;/g, " ");
    text = text.replace(/&amp;/g, "&");
    text = text.replace(/&lt;/g, "<");
    text = text.replace(/&gt;/g, ">");
    text = text.replace(/&quot;/g, "\"");
    text = text.replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
    text = text.replace(/&#(\d+);/g, (_, dec) => String.fromCharCode(parseInt(dec)));

    // 12. 清理多余空行 (保留最多2个连续换行)
    text = text.replace(/\n{3,}/g, "\n\n");
    text = text.trim();

    return text;
}
