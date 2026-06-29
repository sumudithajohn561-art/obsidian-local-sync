export class Logger {
    private prefix: string;
    constructor(prefix: string) { this.prefix = `[InboxSync:${prefix}]`; }
    info(msg: string, ...args: unknown[]): void { console.log(`${this.prefix} ${msg}`, ...args); }
    warn(msg: string, ...args: unknown[]): void { console.warn(`${this.prefix} ${msg}`, ...args); }
    error(msg: string, ...args: unknown[]): void { console.error(`${this.prefix} ${msg}`, ...args); }
}
