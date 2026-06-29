import * as chokidar from "chokidar";
import * as path from "path";
import { Logger } from "../utils/Logger";

const log = new Logger("Watcher");

export type FileEventCallback = (filePath: string) => void;

/**
 * 文件监听器
 *
 * 使用 chokidar 监听收件箱目录，侦测新文件到达。
 * 关键配置:
 * - awaitWriteFinish: 等待文件写入完成后才触发事件（应对Syncthing分块写入）
 * - ignoreInitial: 忽略启动时已有文件，只处理新文件
 * - depth: 0 只看根目录
 */
export class InboxWatcher {
    private watcher: chokidar.FSWatcher | null = null;
    private inboxPath: string;
    private debounceMs: number;
    private timers: Map<string, NodeJS.Timeout> = new Map();
    private onNewFile: FileEventCallback;

    constructor(inboxPath: string, onNewFile: FileEventCallback, debounceMs: number = 3000) {
        this.inboxPath = inboxPath;
        this.onNewFile = onNewFile;
        this.debounceMs = debounceMs;
    }

    /** 启动监听 */
    start(): void {
        if (this.watcher) return;

        log.info(`开始监听: ${this.inboxPath}`);
        this.watcher = chokidar.watch(path.join(this.inboxPath, "*.md"), {
            persistent: true,
            ignoreInitial: true,
            awaitWriteFinish: {
                stabilityThreshold: 2000,
                pollInterval: 100,
            },
            depth: 0,
        });

        this.watcher
            .on("add", (filePath: string) => this.debouncedHandle(filePath))
            .on("change", (filePath: string) => this.debouncedHandle(filePath))
            .on("error", (err: Error) => log.error("监听错误:", err.message));
    }

    /** 停止监听 */
    async stop(): Promise<void> {
        if (!this.watcher) return;
        await this.watcher.close();
        this.watcher = null;
        // 清除所有待执行的定时器
        this.timers.forEach(t => clearTimeout(t));
        this.timers.clear();
        log.info("监听已停止");
    }

    /** 修改监听路径 */
    setPath(newPath: string): void {
        this.inboxPath = newPath;
    }

    /**
     * 防抖处理: 同一个文件在 debounceMs 内多次变化只触发一次
     * Syncthing 可能分多次写入文件，用防抖确保文件完全同步后再处理
     */
    private debouncedHandle(filePath: string): void {
        const existing = this.timers.get(filePath);
        if (existing) clearTimeout(existing);

        const timer = setTimeout(() => {
            this.timers.delete(filePath);
            log.info(`检测到新文件: ${path.basename(filePath)}`);
            this.onNewFile(filePath);
        }, this.debounceMs);

        this.timers.set(filePath, timer);
    }
}
