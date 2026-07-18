const express = require("express");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { XMLParser } = require("fast-xml-parser");

const PORT = process.env.PORT || 19527;
const INBOX = process.env.INBOX_PATH || "E:\\obsidian\\obsidian-Inbox";

// 安全: 从环境变量读取密钥，命令行: set CAPTURE_API_KEY=xxx && node server.js
const CAPTURE_API_KEY = process.env.CAPTURE_API_KEY || "";
const WECHAT_TOKEN = process.env.WECHAT_TOKEN || "change-me-please";

const processedMsgIds = new Set();

const app = express();

// 安全: 限制请求体大小为 1MB
app.use(express.json({ limit: "1mb" }));

// 安全: API认证中间件
function requireApiKey(req, res, next) {
    if (!CAPTURE_API_KEY) return next(); // 未设密钥时兼容旧版
    const key = req.headers["x-api-key"] || req.query.api_key || "";
    if (key !== CAPTURE_API_KEY) {
        return res.status(401).json({ error: "unauthorized" });
    }
    next();
}

// 全局请求日志
app.use((req, res, next) => {
    if (req.url.startsWith("/wechat") || req.method === "POST") {
        console.log(`🌐 [${new Date().toISOString()}] ${req.method} ${req.url}`);
    }
    next();
});

// ========== 通用接口 ==========

app.get("/ping", (req, res) => res.json({ status: "ok", inbox: INBOX, version: "2.0" }));

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
        fs.writeFileSync(path.join(INBOX, fileName), content, "utf-8");
        console.log(`[capture] ✅ ${fileName}`);
        res.json({ ok: true, file: fileName });
    } catch (e) {
        console.error("[capture] ❌", e.message);
        res.status(500).json({ error: e.message });
    }
});

// ========== 微信公众号接口（兼容旧版） ==========

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
        if (!url) { console.log(`[wechat] 非链接: ${content.slice(0,50)}`); return; }
        saveToInbox(url, content, msgId, msg.FromUserName);
    } catch (e) {
        console.error("[wechat] ❌", e.message);
    }
});

// ========== 企业微信微信客服接口 ==========

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

// ========== 通用存入收件箱 ==========

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
    fs.writeFileSync(path.join(INBOX, fileName), frontmatter + "\n\n" + content, "utf-8");
    console.log(`[inbox] ✅ ${fileName}`);
}

// ========== 启动 ==========

app.listen(PORT, "0.0.0.0", () => {
    const localIP = getLocalIP();
    console.log(`\n📥 Capture Server 已启动`);
    console.log(`   端口: ${PORT}  收件箱: ${INBOX}`);
    console.log(`   安全: API认证${CAPTURE_API_KEY ? "已启用 ✅" : "未启用 ⚠️"}`);
    console.log(`   微信Token: ${WECHAT_TOKEN === "change-me-please" ? "请修改 ⚠️" : "已设置 ✅"}`);
    console.log(`   企业微信端点: /wecom-kf\n`);
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

function decryptAES(encrypted) {
    try {
        const key = Buffer.from(WECHAT_TOKEN + "=".repeat(43 - WECHAT_TOKEN.length), "utf-8").slice(0, 43);
        const aesKey = Buffer.from(key.toString() + "=", "base64");
        const iv = aesKey.slice(0, 16);
        const decipher = crypto.createDecipheriv("aes-256-cbc", aesKey, iv);
        decipher.setAutoPadding(false);
        let decrypted = decipher.update(encrypted, "base64", "utf-8");
        decrypted += decipher.final("utf-8");
        const pad = decrypted.charCodeAt(decrypted.length - 1);
        return decrypted.slice(0, decrypted.length - pad);
    } catch (e) {
        console.error("[decrypt] 失败:", e.message);
        return null;
    }
}
