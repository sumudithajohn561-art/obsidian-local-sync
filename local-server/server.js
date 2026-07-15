const express = require("express");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { XMLParser } = require("fast-xml-parser");
const bonjour = require("bonjour")();

const PORT = process.env.PORT || 19527;
const INBOX = process.env.INBOX_PATH || "E:\\obsidian\\obsidian-Inbox";
const WECHAT_TOKEN = process.env.WECHAT_TOKEN || "obsidianSync2024";
const processedMsgIds = new Set();

const app = express();

// 全局请求日志——捕捉一切
app.use((req, res, next) => {
    if (req.url.startsWith("/wechat") || req.method === "POST") {
        console.log(`🌐 [${new Date().toISOString()}] ${req.method} ${req.url} from ${req.ip}`);
    }
    next();
});

app.use(express.json({ limit: "50mb" }));

// ========== 通用接口 ==========

app.get("/ping", (req, res) => res.json({ status: "ok", inbox: INBOX, version: "2.0" }));

app.post("/capture", (req, res) => {
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

// ========== 微信公众号接口 ==========

// 请求日志
app.use("/wechat", (req, res, next) => {
    console.log(`[wechat] ${req.method} from ${req.ip} type=${req.headers["content-type"]}`);
    next();
});

// GET: 微信服务器URL验证
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

// POST: 接收微信消息
app.post("/wechat", express.text({ type: "*/*" }), (req, res) => {
    res.send(""); // 5秒内必须响应

    try {
        const xmlBody = req.body || "";
        if (!xmlBody) { console.log("[wechat] ❌ 空body"); return; }
        console.log(`[wechat] 收到消息 (${xmlBody.length}字节)`);

        const parser = new XMLParser();
        const msg = parser.parse(xmlBody)?.xml;
        if (!msg) { console.log("[wechat] ❌ XML解析失败"); return; }

        const msgType = msg.MsgType;
        const msgId = msg.MsgId;
        const content = msg.Content;

        if (msgId && processedMsgIds.has(msgId)) { console.log(`[wechat] ⏭ 重复消息`); return; }
        if (msgId) processedMsgIds.add(msgId);

        if (msgType !== "text" || !content) { console.log(`[wechat] 忽略: type=${msgType}`); return; }

        const urlMatch = content.match(/https?:\/\/[^\s]+/);
        const url = urlMatch ? urlMatch[0] : null;
        if (!url) { console.log(`[wechat] 非链接: ${content.slice(0,50)}`); return; }

        const now = new Date();
        const ts = now.toISOString().replace(/[:.]/g, "-").slice(0, 19).replace("T", "-");
        const hostname = new URL(url).hostname;
        const domain = hostname.replace(/\./g, "-");
        const fileName = `${ts}-${domain}.md`;

        let sourceType = "link", source = hostname;
        if (hostname.includes("bilibili.com") || hostname.includes("b23.tv")) { sourceType = "video"; source = "bilibili"; }
        else if (hostname.includes("youtube.com") || hostname.includes("youtu.be")) { sourceType = "video"; source = "youtube"; }
        else if (hostname.includes("douyin.com")) { sourceType = "video"; source = "douyin"; }
        else if (hostname.includes("mp.weixin.qq.com")) { source = "weixin"; }

        const fromUser = (msg.FromUserName || "").slice(-6);
        const frontmatter = [
            "---",
            `title: "微信消息 ${ts}"`,
            `source_type: "${sourceType}"`,
            `source: "${source}"`,
            `url: "${url}"`,
            `from_user: "${fromUser}"`,
            `msg_id: "${msgId || "unknown"}"`,
            `created: "${ts}"`,
            `status: "pending"`,
            "---",
        ].join("\n");

        const fileContent = frontmatter + "\n\n" + content;
        fs.mkdirSync(INBOX, { recursive: true });
        fs.writeFileSync(path.join(INBOX, fileName), fileContent, "utf-8");
        console.log(`[wechat] ✅ ${fileName}`);
    } catch (e) {
        console.error("[wechat] ❌", e.message);
    }
});

// ========== 启动 ==========

app.listen(PORT, "0.0.0.0", () => {
    const localIP = getLocalIP();
    console.log(`\n📥 Obsidian Capture Server v2`);
    console.log(`   端口: ${PORT}  收件箱: ${INBOX}`);
    console.log(`   地址: http://${localIP}:${PORT}/capture\n`);

    bonjour.publish({
        name: `obsidian-capture-${localIP.replace(/\./g, "-")}`,
        type: "obsidian-capture", protocol: "tcp", port: PORT,
        txt: { version: "2.0", hostname: require("os").hostname() }
    });
    console.log("   mDNS 已广播 ✅\n");
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
