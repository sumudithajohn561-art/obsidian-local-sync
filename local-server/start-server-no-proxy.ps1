$env:HTTP_PROXY = ""
$env:HTTPS_PROXY = ""
$env:YTDLP_PROXY = ""
$env:WECHAT_TOKEN = "sumu123"
$env:HF_ENDPOINT = "https://hf-mirror.com"
$env:ANTHROPIC_API_KEY = "你的API_KEY填这里"
Set-Location -Path "E:\AI\项目\obsidian同步软件\local-server"
node server.js
