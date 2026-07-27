const express = require("express");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");
const { XMLParser } = require("fast-xml-parser");

const PORT = process.env.PORT || 19527;
const INBOX = process.env.INBOX_PATH || "E:\\obsidian\\obsidian-Inbox";

// 安全: 从环境变量读取密钥
const CAPTURE_API_KEY = process.env.CAPTURE_API_KEY || "";
const WECHAT_TOKEN = process.env.WECHAT_TOKEN || "change-me-please";

const processedMsgIds = new Set();
let serverStartTime = Date.now();  // 服务启动时间
let transcriberRestartCount = 0;   // 进程重启次数
let transcriberLastStartTime = 0;  // 上次启动时间
let watchdogTriggerCount = 0;      // 看门狗触发次数

const app = express();

// 安全: 限制请求体大小
app.use(express.json({ limit: "1mb" }));

// ============================================================
// 视频转录队列
// ============================================================

const QUEUE_FILE = path.join(INBOX, "transcript-queue.json");
const transcriptQueue = [];
let transcriberProc = null;
let transcriberReady = false;
let restartScheduled = false;  // 防止重复调度重启

/** 从磁盘恢复未完成的任务 */
function restoreQueue() {
    try {
        if (fs.existsSync(QUEUE_FILE)) {
            const raw = fs.readFileSync(QUEUE_FILE, "utf-8");
            if (!raw || !raw.trim()) {
                // 空文件 → 直接清理
                fs.unlinkSync(QUEUE_FILE);
                return;
            }
            const saved = JSON.parse(raw);
            if (Array.isArray(saved) && saved.length > 0) {
                // 重置所有任务状态（进程重新启动，旧 started 引用无效）
                const valid = [];
                for (const t of saved) {
                    if (t.taskId && t.url && t.platform && !t.done) {
                        t.started = false;
                        delete t._startedAt;
                        valid.push(t);
                    }
                }
                if (valid.length > 0) {
                    transcriptQueue.push(...valid);
                    console.log(`[transcriber] ✅ 恢复 ${valid.length} 个未完成任务 (跳过 ${saved.length - valid.length} 个无效)`);
                }
            }
            // 读取后删除磁盘文件，避免重复恢复
            fs.unlinkSync(QUEUE_FILE);
        }
    } catch (e) {
        console.error("[transcriber] 队列恢复失败:", e.message);
        // 损坏的队列文件：删除，避免永久性启动错误
        try { if (fs.existsSync(QUEUE_FILE)) fs.unlinkSync(QUEUE_FILE); } catch {}
    }
}

/** 将队列持久化到磁盘 */
function persistQueue() {
    try {
        const active = transcriptQueue.filter(t => !t.done);
        if (active.length > 0) {
            fs.writeFileSync(QUEUE_FILE, JSON.stringify(active, null, 2), "utf-8");
        } else if (fs.existsSync(QUEUE_FILE)) {
            fs.unlinkSync(QUEUE_FILE);
        }
    } catch (e) {
        console.error("[transcriber] 队列持久化失败:", e.message);
    }
}

/**
 * 启动 Python 转录长驻进程
 */
function startTranscriber() {
    // 先杀掉旧的 Python 进程，防止多个并存导致 stdin 混乱
    if (transcriberProc && !transcriberProc.killed) {
        transcriberProc.stdin.end();
        transcriberProc.kill("SIGTERM");
        console.log("[transcriber] 终止旧进程");
    }

    transcriberRestartCount++;
    transcriberLastStartTime = Date.now();
    const scriptPath = path.join(__dirname, "transcriber.py");

    // 设置环境变量给 Python 进程
    const env = {
        ...process.env,
        CAPTURE_INBOX: INBOX,
        HF_HUB_DISABLE_SYMLINKS_WARNING: "1",
        HF_ENDPOINT: "https://hf-mirror.com",
        // 清除全局代理，B站等国内平台直连更快
        // 如需单独为 yt-dlp 设代理，使用 YTDLP_PROXY 环境变量
        HTTP_PROXY: "",
        HTTPS_PROXY: "",
        http_proxy: "",
        https_proxy: "",
    };

    transcriberProc = spawn("python", [scriptPath], {
        env,
        stdio: ["pipe", "pipe", "pipe"],
    });

    transcriberReady = false;

    let stdoutBuffer = "";
    transcriberProc.stdout.on("data", (data) => {
        stdoutBuffer += data.toString();
        const lines = stdoutBuffer.split("\n");
        // 最后一段可能不完整，保留到下次拼接
        stdoutBuffer = lines.pop();
        for (const line of lines) {
            if (!line.trim()) continue;
            try {
                const result = JSON.parse(line);
                handleTranscriberResult(result);
            } catch (e) {
                // 非 JSON 输出忽略
            }
        }
    });

    transcriberProc.stderr.on("data", (data) => {
        // Python 脚本的日志通过 stderr 输出
        process.stderr.write(data);
    });

    transcriberProc.on("exit", (code, signal) => {
        console.log(`[transcriber] 进程退出 (code=${code}, signal=${signal})`);
        transcriberReady = false;
        transcriberProc = null;

        // 防止与看门狗强杀 + 重启冲突造成双重重启
        if (restartScheduled) return;

        // 重置所有 started 任务，防止崩溃导致队列卡死
        for (const t of transcriptQueue) { t.started = false; }

        restartScheduled = true;
        setTimeout(() => {
            restartScheduled = false;
            startTranscriber();
        }, 5000);
    });

    transcriberProc.on("error", (err) => {
        console.error(`[transcriber] 启动失败:`, err.message);
        transcriberReady = false;
        transcriberProc = null;

        if (restartScheduled) return;
        restartScheduled = true;
        setTimeout(() => {
            restartScheduled = false;
            startTranscriber();
        }, 10000);
    });
}

/**
 * 处理 Python 进程返回的结果
 */
function handleTranscriberResult(result) {
    if (result.status === "fatal") {
        console.error(`[transcriber] ❌ 致命错误: ${result.error}`);
        transcriberReady = false;
        return;
    }

    if (result.status === "ready") {
        transcriberReady = true;
        console.log("[transcriber] ✅ 模型就绪，开始处理队列...");
        drainQueue();
        return;
    }

    // 找到对应的队列任务
    const idx = transcriptQueue.findIndex(t => t.taskId === result.taskId);
    const task = idx >= 0 ? transcriptQueue[idx] : null;
    if (task) {
        if (result.status === "ok") {
            console.log(`[transcriber] ✅ ${task.platform}: ${result.title} → ${result.filePath}`);
        } else {
            console.error(`[transcriber] ❌ ${task.platform}: ${result.error}`);
            // 降级：转录失败时写 pending 笔记，保留链接不丢失
            try {
                const now = new Date();
                const ts = now.toISOString().replace(/[:.]/g, "-").slice(0, 19).replace("T", "-");
                const domain = task.platform || "unknown";
                const fileName = `${ts}-${domain}-FAILED.md`;
                const md = [
                    "---",
                    `title: "转录失败"`,
                    `source_type: "link"`,
                    `source: "${task.platform || "unknown"}"`,
                    `url: "${task.url}"`,
                    `created: "${ts}"`,
                    `status: "failed"`,
                    "---",
                    "",
                    `> ⚠️ 转录失败：${result.error}`,
                    "",
                    `- **链接:** ${task.url}`,
                    `- **来源:** ${task.platform}`,
                ].join("\n");
                fs.mkdirSync(INBOX, { recursive: true });
                fs.writeFileSync(path.join(INBOX, fileName), md, "utf-8");
                console.error(`[transcriber] 📝 降级笔记已保存: ${fileName}`);
            } catch (e2) {
                console.error("[transcriber] 降级笔记写入失败:", e2.message);
            }
        }
        // 任务完成，从队列中移除
        transcriptQueue.splice(idx, 1);
        persistQueue();
    }

    // 继续处理队列
    drainQueue();
}

/**
 * 将视频 URL 加入转录队列
 */
function enqueueTranscribe(url, platform) {
    const taskId = crypto.randomUUID();
    transcriptQueue.push({ taskId, url: url, platform: platform, done: false });
    persistQueue();  // 持久化到磁盘
    console.log(`[transcriber] 入队: ${platform} (队列长度: ${transcriptQueue.length})`);
    drainQueue();
}

/**
 * 消费队列：如果进程就绪且有等待任务，发送给 Python
 */
function drainQueue() {
    if (!transcriberReady) return;
    if (!transcriberProc || transcriberProc.killed) return;

    // 检查是否有正在处理的任务
    const running = transcriptQueue.find(t => t.started && !t.done);
    if (running) return; // 串行，等当前任务完成

    // 找下一个未开始的任务
    const next = transcriptQueue.find(t => !t.started);
    if (!next) return;

    next.started = true;
    next._startedAt = Date.now();
    const payload = JSON.stringify({
        taskId: next.taskId,
        url: next.url,
        platform: next.platform,
    }) + "\n";

    console.log(`[transcriber] 🎬 开始转录: ${next.platform}`);
    transcriberProc.stdin.write(payload);
}

/**
 * 看门狗：检测卡死的转录任务。
 * 每30秒检查一次，如果任务耗时超过30分钟，强杀进程 + 写降级笔记 + 自动重启。
 * 防止 faster-whisper GPU hang 导致队列永久死锁。
 */
function startWatchdog() {
    const TASK_TIMEOUT_MS = 30 * 60 * 1000;  // 30分钟超时

    setInterval(() => {
        // 只在有进程且就绪状态下检查
        if (!transcriberProc || transcriberProc.killed) return;

        const running = transcriptQueue.find(t => t.started && !t.done);
        if (!running) return;

        const elapsed = Date.now() - (running._startedAt || 0);
        if (elapsed < TASK_TIMEOUT_MS) return;

        const elapsedMin = Math.round(elapsed / 60000);
        watchdogTriggerCount++;
        console.error(`[watchdog] ⚠️ 任务 ${running.taskId.slice(0,8)} 已卡住 ${elapsedMin} 分钟，强制终止！`);

        // 1) 写降级笔记：保留链接，避免丢失
        try {
            const now = new Date();
            const ts = now.toISOString().replace(/[:.]/g, "-").slice(0, 19).replace("T", "-");
            const domain = running.platform || "unknown";
            const fileName = `${ts}-${domain}-TIMEOUT.md`;
            const md = [
                "---",
                `title: "转录超时"`,
                `source_type: "link"`,
                `source: "${running.platform || "unknown"}"`,
                `url: "${running.url}"`,
                `created: "${ts}"`,
                `status: "timeout"`,
                "---",
                "",
                `> ⚠️ 转录耗时超过 ${elapsedMin} 分钟被看门狗终止。`,
                "",
                `- **链接:** ${running.url}`,
                `- **来源:** ${running.platform}`,
            ].join("\n");
            fs.mkdirSync(INBOX, { recursive: true });
            fs.writeFileSync(path.join(INBOX, fileName), md, "utf-8");
            console.error(`[watchdog] 📝 降级笔记已保存: ${fileName}`);
        } catch (e) {
            console.error("[watchdog] 降级笔记写入失败:", e.message);
        }

        // 2) 移除卡死任务，重置所有 started 状态
        const idx = transcriptQueue.indexOf(running);
        if (idx >= 0) transcriptQueue.splice(idx, 1);

        // 3) 先标记重启已调度，再强杀（防止 exit 事件再次触发重启）
        transcriberReady = false;
        restartScheduled = true;
        setTimeout(() => {
            restartScheduled = false;
            startTranscriber();
        }, 5000);

        transcriberProc.stdin.end();
        transcriberProc.kill("SIGKILL");  // SIGKILL 强杀僵死进程
        transcriberProc = null;
        console.error("[watchdog] 🔪 已强杀 Python 进程，5秒后重启...");

        // 4) 重置所有 started 任务
        for (const t of transcriptQueue) { t.started = false; }

        persistQueue();
    }, 30_000);

    console.log("[watchdog] 🐕 看门狗已启动 (超时阈值: 30分钟, 检测间隔: 30秒)");
}

// ============================================================
// 安全中间件
// ============================================================

function requireApiKey(req, res, next) {
    if (!CAPTURE_API_KEY) return next();
    const key = req.headers["x-api-key"] || req.query.api_key || "";
    if (key !== CAPTURE_API_KEY) {
        return res.status(401).json({ error: "unauthorized" });
    }
    next();
}

// 全局请求日志
app.use((req, res, next) => {
    if (req.url.startsWith("/wechat") || req.url.startsWith("/wecom") || req.method === "POST") {
        console.log(`🌐 [${new Date().toISOString()}] ${req.method} ${req.url}`);
    }
    next();
});

// ============================================================
// 通用接口
// ============================================================

app.get("/ping", (req, res) => {
    const uptime = Math.floor((Date.now() - serverStartTime) / 1000);
    const currentTask = transcriptQueue.find(t => t.started && !t.done);

    res.json({
        status: "ok",
        inbox: INBOX,
        version: "2.1",
        uptime: `${uptime}s`,
        transcriber: {
            status: transcriberReady ? "ready" : (transcriberProc && !transcriberProc.killed ? "loading" : "stopped"),
            queueLength: transcriptQueue.length,
            currentTask: currentTask ? {
                platform: currentTask.platform,
                url: currentTask.url.slice(0, 80),
                elapsed: currentTask._startedAt ? `${Math.floor((Date.now() - currentTask._startedAt) / 1000)}s` : "unknown",
            } : null,
            restartCount: transcriberRestartCount,
            watchdogTriggers: watchdogTriggerCount,
        },
    });
});

app.post("/capture", requireApiKey, (req, res) => {
    try {
        const { title, body, url, sourceType, source } = req.body;
        if (!body && !url) return res.status(400).json({ error: "empty content" });
        const now = new Date();
        const ts = now.toISOString().replace(/[:.]/g, "-").slice(0, 19).replace("T", "-");
        const safeTitle = (title || "未命名").replace(/[/\\:*?"<>|]/g, "-").slice(0, 50);
        const fileName = `${ts}-${safeTitle}.md`;
        const frontmatter = [
            "---",
            `title: "${(title || "未命名").replace(/"/g, "\\\"")}"`,
            `source_type: "${sourceType || "link"}"`,
            `source: "${source || "direct"}"`,
            `url: "${url || ""}"`,
            `created: "${ts}"`,
            `status: "pending"`,
            "---",
        ].join("\n");
        const content = frontmatter + "\n\n" + (body || url || "");
        fs.mkdirSync(INBOX, { recursive: true });

        // 视频链接放入转录队列
        if (sourceType === "video" && source !== "weixin" && url) {
            enqueueTranscribe(url, source || "unknown");
            console.log(`[capture] 🎬 视频链接入队: ${url.slice(0, 60)}...`);
            res.json({ ok: true, file: null, queued: true });
            return;
        }

        fs.writeFileSync(path.join(INBOX, fileName), content, "utf-8");
        console.log(`[capture] ✅ ${fileName}`);
        res.json({ ok: true, file: fileName });
    } catch (e) {
        console.error("[capture] ❌", e.message);
        res.status(500).json({ error: e.message });
    }
});

// ============================================================
// 微信公众号接口
// ============================================================

app.get("/wechat", (req, res) => {
    const { signature, timestamp, nonce, echostr } = req.query;
    if (!signature || !timestamp || !nonce || !echostr) {
        return res.status(400).send("missing params");
    }
    const arr = [WECHAT_TOKEN, timestamp, nonce].sort();
    const hash = crypto.createHash("sha1").update(arr.join("")).digest("hex");
    if (hash === signature) {
        console.log("[wechat] ✅ URL验证通过");
        res.send(echostr);
    } else {
        console.log("[wechat] ❌ 签名验证失败");
        res.status(403).send("signature error");
    }
});

app.post("/wechat", express.text({ type: "*/*" }), (req, res) => {
    res.send("");
    try {
        const xmlBody = req.body || "";
        if (!xmlBody) { console.log("[wechat] ❌ 空body"); return; }
        const parser = new XMLParser();
        const msg = parser.parse(xmlBody)?.xml;
        if (!msg) { console.log("[wechat] ❌ XML解析失败"); return; }
        const msgType = msg.MsgType;
        const msgId = msg.MsgId;
        const content = msg.Content;
        if (msgId && processedMsgIds.has(msgId)) { return; }
        if (msgId) processedMsgIds.add(msgId);
        if (msgType !== "text" || !content) { console.log(`[wechat] 忽略: type=${msgType}`); return; }
        const urlMatch = content.match(/https?:\/\/[^\s]+/);
        const url = urlMatch ? urlMatch[0] : null;
        if (!url) { console.log(`[wechat] 非链接: ${content.slice(0, 50)}`); return; }
        saveToInbox(url, content, msgId, msg.FromUserName);
    } catch (e) {
        console.error("[wechat] ❌", e.message);
    }
});

// ============================================================
// 企业微信微信客服接口
// ============================================================

app.get("/wecom-kf", (req, res) => {
    const { msg_signature, timestamp, nonce, echostr } = req.query;
    if (!msg_signature || !timestamp || !nonce || !echostr) {
        return res.status(400).send("missing params");
    }
    try {
        const arr = [WECHAT_TOKEN, timestamp, nonce].sort();
        const hash = crypto.createHash("sha1").update(arr.join("")).digest("hex");
        if (hash !== msg_signature) {
            return res.status(403).send("signature error");
        }
        const decrypted = decryptAES(echostr);
        if (decrypted) {
            console.log("[wecom-kf] ✅ URL验证通过");
            res.send(decrypted);
        } else {
            res.status(500).send("decrypt error");
        }
    } catch (e) {
        console.error("[wecom-kf] ❌ 验证失败:", e.message);
        res.status(500).send("error");
    }
});

app.post("/wecom-kf", express.text({ type: "*/*" }), (req, res) => {
    res.send("");
    try {
        const xmlBody = req.body || "";
        if (!xmlBody) return;
        const parser = new XMLParser({ ignoreAttributes: false });
        const result = parser.parse(xmlBody);
        const encrypted = result?.xml?.Encrypt;
        if (!encrypted) return;

        const decryptedXml = decryptAES(encrypted);
        if (!decryptedXml) return;

        const decryptedMsg = parser.parse(decryptedXml)?.xml;
        if (!decryptedMsg) return;

        const msgType = decryptedMsg.MsgType;
        const content = decryptedMsg.Content;
        const msgId = decryptedMsg.MsgId;

        if (msgId && processedMsgIds.has(msgId)) return;
        if (msgId) processedMsgIds.add(msgId);
        if (msgType !== "text" || !content) return;

        const urlMatch = content.match(/https?:\/\/[^\s]+/);
        const url = urlMatch ? urlMatch[0] : null;
        if (!url) return;

        saveToInbox(url, content, msgId, decryptedMsg.FromUserName);
    } catch (e) {
        console.error("[wecom-kf] ❌", e.message);
    }
});

// ============================================================
// 通用存入收件箱 + 视频转录调度
// ============================================================

function saveToInbox(url, content, msgId, fromUser) {
    const now = new Date();
    const ts = now.toISOString().replace(/[:.]/g, "-").slice(0, 19).replace("T", "-");
    const hostname = new URL(url).hostname;
    const domain = hostname.replace(/\./g, "-");
    const fileName = `${ts}-${domain}.md`;

    let sourceType = "link", source = hostname;
    if (hostname.includes("bilibili.com") || hostname.includes("b23.tv")) { sourceType = "video"; source = "bilibili"; }
    else if (hostname.includes("youtube.com") || hostname.includes("youtu.be")) { sourceType = "video"; source = "youtube"; }
    else if (hostname.includes("douyin.com")) { sourceType = "video"; source = "douyin"; }
    // 视频号：channels.weixin.qq.com 是正式域名，weixin.qq.com/sph 是旧格式
    else if (hostname.includes("channels.weixin.qq.com") || url.includes("weixin.qq.com/sph")) { sourceType = "video"; source = "weixin-video"; }
    else if (hostname.includes("mp.weixin.qq.com")) { source = "weixin"; }

    const from = (fromUser || "").slice(-6);
    const frontmatter = [
        "---",
        `title: "消息 ${ts}"`,
        `source_type: "${sourceType}"`,
        `source: "${source}"`,
        `url: "${url}"`,
        `from_user: "${from}"`,
        `msg_id: "${msgId || "unknown"}"`,
        `created: "${ts}"`,
        `status: "pending"`,
        "---",
    ].join("\n");

    fs.mkdirSync(INBOX, { recursive: true });

    // 视频链接放入转录队列，由 transcriber.py 处理完成后写入收件箱
    // 例外: 视频号(weixin-video) yt-dlp 不支持，写 pending 文件降级为链接
    if (sourceType === "video" && source !== "weixin" && source !== "weixin-video") {
        enqueueTranscribe(url, source);
        console.log(`[inbox] 🎬 视频链接入队: ${url.slice(0, 60)}...`);
        return;  // 不写 pending 文件，等转录完成后统一写入
    }

    fs.writeFileSync(path.join(INBOX, fileName), frontmatter + "\n\n" + content, "utf-8");
    console.log(`[inbox] ✅ ${fileName}`);
}

// ============================================================
// AES 解密（企业微信）
// ============================================================

function decryptAES(encrypted) {
    try {
        const encodingAESKey = WECHAT_TOKEN;
        const key = Buffer.from(encodingAESKey + "=", "base64");
        const iv = key.slice(0, 16);
        const decipher = crypto.createDecipheriv("aes-256-cbc", key, iv);
        decipher.setAutoPadding(false);
        let decrypted = decipher.update(encrypted, "base64", "utf-8");
        decrypted += decipher.final("utf-8");
        const lastByte = decrypted.charCodeAt(decrypted.length - 1);
        return decrypted.slice(0, decrypted.length - lastByte);
    } catch (e) {
        console.error("[decrypt] 失败:", e.message);
        return null;
    }
}

// ============================================================
// 启动
// ============================================================

app.listen(PORT, "0.0.0.0", () => {
    const localIP = getLocalIP();
    console.log(`\n📥 Capture Server 已启动`);
    console.log(`   端口: ${PORT}  收件箱: ${INBOX}`);
    console.log(`   安全: API认证${CAPTURE_API_KEY ? "已启用 ✅" : "未启用 ⚠️"}`);
    console.log(`   微信Token: ${WECHAT_TOKEN === "change-me-please" ? "请修改 ⚠️" : "已设置 ✅"}`);
    console.log(`   企业微信端点: /wecom-kf`);
    console.log(`   转录服务: 启动中...\n`);

    // 恢复未完成的转录任务
    restoreQueue();

    // 启动 Python 转录进程
    startTranscriber();

    // 启动看门狗
    startWatchdog();
});

// 优雅退出
process.on("SIGINT", () => {
    console.log("\n[server] 收到 SIGINT，正在退出...");
    persistQueue();  // 退出前持久化未完成任务
    if (transcriberProc && !transcriberProc.killed) {
        transcriberProc.stdin.end();
        transcriberProc.kill("SIGTERM");
    }
    process.exit(0);
});

process.on("SIGTERM", () => {
    persistQueue();  // 退出前持久化未完成任务
    if (transcriberProc && !transcriberProc.killed) {
        transcriberProc.stdin.end();
        transcriberProc.kill("SIGTERM");
    }
    process.exit(0);
});

function getLocalIP() {
    const nets = require("os").networkInterfaces();
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === "IPv4" && !net.internal) return net.address;
        }
    }
    return "127.0.0.1";
}
