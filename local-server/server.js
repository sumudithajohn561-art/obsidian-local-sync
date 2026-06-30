const express = require("express");
const fs = require("fs");
const path = require("path");
const bonjour = require("bonjour")();

const PORT = process.env.PORT || 19527;
const INBOX = process.env.INBOX_PATH || "E:\\obsidian\\obsidian-Inbox";
const SERVICE_NAME = "obsidian-capture";

const app = express();
app.use(express.json({ limit: "50mb" }));

// 健康检查
app.get("/ping", (req, res) => res.json({ status: "ok", inbox: INBOX, version: "2.0" }));

// 接收分享内容
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
        const filePath = path.join(INBOX, fileName);

        fs.mkdirSync(INBOX, { recursive: true });
        fs.writeFileSync(filePath, content, "utf-8");

        console.log(`✅ 已保存: ${fileName}`);
        res.json({ ok: true, file: fileName });
    } catch (e) {
        console.error("保存失败:", e.message);
        res.status(500).json({ error: e.message });
    }
});

app.listen(PORT, "0.0.0.0", () => {
    const localIP = getLocalIP();
    console.log(`\n📥 Obsidian Capture Server v2 已启动`);
    console.log(`   端口: ${PORT}`);
    console.log(`   收件箱: ${INBOX}`);
    console.log(`   手机端地址: http://${localIP}:${PORT}/capture`);
    console.log(`   mDNS广播: ${SERVICE_NAME}._tcp\n`);

    // mDNS 广播，让手机 App 自动发现
    bonjour.publish({
        name: `${SERVICE_NAME}-${localIP.replace(/\./g, "-")}`,
        type: "obsidian-capture",
        protocol: "tcp",
        port: PORT,
        txt: { version: "2.0", hostname: require("os").hostname() }
    });
    console.log("   mDNS 服务已广播 ✅");
});

function getLocalIP() {
    const { networkInterfaces } = require("os");
    const nets = networkInterfaces();
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === "IPv4" && !net.internal) return net.address;
        }
    }
    return "127.0.0.1";
}
