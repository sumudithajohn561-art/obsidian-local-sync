$env:HTTP_PROXY = ""
$env:HTTPS_PROXY = ""
$env:YTDLP_PROXY = ""
$env:WECHAT_TOKEN = "sumu123"
$env:HF_ENDPOINT = "https://hf-mirror.com"

# 从 .env 文件读取 DeepSeek API Key（不提交到 Git）
$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#].+?)\s*=\s*(.+)') {
            [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2])
        }
    }
}

Set-Location -Path "E:\AI\项目\obsidian同步软件\local-server"
node server.js
