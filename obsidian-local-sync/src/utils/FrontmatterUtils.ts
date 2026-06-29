import { parse as parseYaml, stringify as stringifyYaml } from "yaml";

export interface NoteFrontmatter {
    title?: string;
    source?: string;
    source_type?: string;
    url?: string;
    created?: string;
    processed?: string;
    status?: string;
    tags?: string[];
    summary?: string;
    ai_generated?: boolean;
    attachment?: string;
    [key: string]: unknown;
}

/**
 * 从Markdown文本中解析YAML frontmatter
 */
export function parseFrontmatter(mdContent: string): { frontmatter: NoteFrontmatter; body: string } {
    const match = mdContent.match(/^---\n([\s\S]*?)\n---\n?([\s\S]*)$/);
    if (!match) return { frontmatter: {}, body: mdContent };

    try {
        const fm = parseYaml(match[1]) as NoteFrontmatter;
        return { frontmatter: fm || {}, body: match[2] || "" };
    } catch {
        return { frontmatter: {}, body: mdContent };
    }
}

/**
 * 将frontmatter和body组合回Markdown
 */
export function buildMarkdown(frontmatter: NoteFrontmatter, body: string): string {
    // 移除 undefined 和 null 字段
    const cleanFm: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(frontmatter)) {
        if (v !== undefined && v !== null) cleanFm[k] = v;
    }
    const yamlStr = stringifyYaml(cleanFm, { lineWidth: 0 }).trim();
    return `---\n${yamlStr}\n---\n\n${body}`;
}
