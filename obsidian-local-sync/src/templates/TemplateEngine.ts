import type { NoteFrontmatter } from "../utils/FrontmatterUtils";

/**
 * 简单的模板引擎
 *
 * 支持的变量:
 *   {{title}}   - 标题
 *   {{source}}  - 来源
 *   {{url}}     - 原始链接
 *   {{date}}    - 创建日期 (YYYY-MM-DD)
 *   {{body}}    - 正文
 *   {{tags}}    - 标签 (逗号分隔)
 *   {{summary}} - 摘要
 */
export function renderTemplate(
    template: string,
    vars: Record<string, string>
): string {
    let result = template;

    for (const [key, value] of Object.entries(vars)) {
        result = result.replace(new RegExp(`\\{\\{${key}\\}\\}`, "g"), value);
    }

    return result;
}

/**
 * 根据 frontmatter 和 body 构建模板变量
 */
export function buildTemplateVars(fm: NoteFrontmatter, body: string): Record<string, string> {
    const now = new Date();
    const dateStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;

    return {
        title: fm.title || "未命名",
        source: fm.source || "未知",
        url: fm.url || "",
        date: dateStr,
        body: body,
        tags: fm.tags?.join(", ") || "",
        summary: fm.summary || "",
    };
}
