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

/** 从磁盘恢复未完成的任务 */
function restoreQueue() {
    try {
        if (fs.existsSync(QUEUE_FILE)) {
            const saved = JSON.parse(fs.readFileSync(QUEUE_FILE, "utf-8"));
            if (Array.isArray(saved) && saved.length > 0) {
                transcriptQueue.push(...saved);
                console.log(`[transcriber] 恢复 ${saved.length} 个未完成任务`);
            }
            fs.unlinkSync(QUEUE_FILE);  // 读取后删除，避免重复恢复
        }
    } catch (e) {
        console.error("[transcriber] 队列恢复失败:", e.message);
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
    const scriptPath = path.join(__dirname, "transcriber.py");

    // 设置环境变量给 Python 进程
    const env = {
        ...process.env,
        CAPTURE_INBOX: INBOX,
        HF_HUB_DISABLE_SYMLINKS_WARNING: "1",
        HF_ENDPOINT: "https://hf-mirror.com",
    };

    transcriberProc = spawn("python", [scriptPath], {
        env,
        stdio: ["pipe", "pipe", "pipe"],
    });

    transcriberReady = false;

    transcriberProc.stdout.on("data", (data) => {
        const lines = data.toString().trim().split("\n");
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

    transcriberProc.on("exit", (code) => {
        console.log(`[transcriber] 进程退出 (code=${code})，5秒后重启...`);
        transcriberReady = false;
        setTimeout(startTranscriber, 5000);
    });

    transcriberProc.on("error", (err) => {
        console.error(`[transcriber] 启动失败:`, err.message);
        transcriberReady = false;
        setTimeout(startTranscriber, 10000);
    });
}

/**
 * 处理 Python 进程返回的结果
 */
function handleTranscriberResult(result) {
    if (result.status === "ready") {
        transcriberReady = true;
        console.log("[transcriber] ✅ 模型就绪，开始处理队列...");
        drainQueue();
        return;
    }

    // 找到对应的队列任务
    const task = transcriptQueue.find(t => t.taskId === result.taskId);
    if (task) {
        if (result.status === "ok") {
            console.log(`[transcriber] ✅ ${task.platform}: ${result.title} → ${result.filePath}`);
        } else {
            console.error(`[transcriber] ❌ ${task.platform}: ${result.error}`);
        }
        // 任务完成，检查是否还有待处理的
        task.done = true;
    }

    // 清理已完成的任务引用
    const idx = transcriptQueue.findIndex(t => t.taskId === result.taskId);
    if (idx >= 0) { transcriptQueue.splice(idx, 1); persistQueue(); }

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
    const payload = JSON.stringify({
        taskId: next.taskId,
        url: next.url,
        platform: next.platform,
    }) + "\n";

    console.log(`[transcriber] 🎬 开始转录: ${next.platform}`);
    transcriberProc.stdin.write(payload);
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

app.get("/ping", (req, res) => res.json({
    status: "ok",
    inbox: INBOX,
    version: "2.1",
    transcriber: transcriberReady ? "ready" : (transcriberProc ? "loading" : "stopped"),
    queueLength: transcriptQueue.length,
}));

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
    else if (hostname.includes("douyin.com") || hostname.includes("weixin.qq.com/sph")) { sourceType = "video"; source = "douyin"; }
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
    if (sourceType === "video" && source !== "weixin") {
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
