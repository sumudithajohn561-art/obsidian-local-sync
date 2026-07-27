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
            $envName = $matches[1]
            $envValue = $matches[2]
            # 只设置当前进程环境变量，不写入注册表（安全：防止 API Key 泄露到持久化环境）
            Set-Item -Path "env:$envName" -Value $envValue
        }
    }
}

Set-Location $PSScriptRoot
node server.js
