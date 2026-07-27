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
            # 设置当前进程环境变量（Node/Python 进程才能读取）
            Set-Item -Path "env:$envName" -Value $envValue
            # 同时写入用户级持久化（登录后依然有效）
            [System.Environment]::SetEnvironmentVariable($envName, $envValue, [System.EnvironmentVariableTarget]::User)
        }
    }
}

Set-Location -Path "E:\AI\项目\obsidian同步软件\local-server"
node server.js
