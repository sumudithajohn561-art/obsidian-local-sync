import * as fs from "fs";
import * as path from "path";

/**
 * 安全的文件名生成
 */
export function sanitizeFileName(name: string, maxLen: number = 80): string {
    return name
        .replace(/[/\\:*?"<>|]/g, "-")
        .replace(/\s+/g, "-")
        .replace(/-+/g, "-")
        .trim()
        .slice(0, maxLen);
}

/**
 * 生成日期目录名 (如 "2026-06-29")
 */
export function dateDirName(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
}

/**
 * 确保目录存在
 */
export function ensureDir(dir: string): void {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

/**
 * 安全移动文件，目标存在则跳过
 */
export function safeMove(src: string, dest: string): boolean {
    try {
        ensureDir(path.dirname(dest));
        if (fs.existsSync(dest)) {
            // 目标已存在，不覆盖，移动失败
            return false;
        }
        fs.renameSync(src, dest);
        return true;
    } catch { return false; }
}

/**
 * 读取文件内容，自动检测UTF-8编码
 */
export function readFileUtf8(filePath: string): string {
    return fs.readFileSync(filePath, "utf-8");
}
