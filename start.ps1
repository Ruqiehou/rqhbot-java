$ErrorActionPreference = 'Stop'

Set-Location -LiteralPath $PSScriptRoot

function Write-Section($title) {
    "===================================="
    "  $title"
    "===================================="
    ""
}

function Get-RequiredCommand($name) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "未找到 $name，请安装 JDK 21+ 并把 JDK bin 目录加入 PATH"
    }
    $command.Source
}

function Read-EnvFile($path) {
    $result = @{}
    if (-not (Test-Path -LiteralPath $path)) {
        throw "缺少 .env 配置文件，请先创建并编辑配置"
    }

    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }

        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -eq 2) {
            $result[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    $result
}

Write-Section 'RqhBot Java 版 一键启动脚本'

"[检查] Java 环境..."
$javaExe = Get-RequiredCommand 'java'
$javacExe = Get-RequiredCommand 'javac'
"[成功] Java 环境正常 - $javaExe"
"[成功] Javac 环境正常 - $javacExe"
""

"[检查] .env 文件..."
$envConfig = Read-EnvFile '.env'
"[成功] 配置文件就绪"
""

Write-Section '启动 RqhBot Java 版'

$classpath = 'sdk-java\target\classes;sdk-java\lib\*'
"[信息] 使用 Java: $javaExe"
"[信息] 使用 Javac: $javacExe"
"[信息] Classpath: $classpath"
""

"[编译] 编译 SDK 和 Java 插件..."
New-Item -ItemType Directory -Path 'sdk-java\target\classes' -Force | Out-Null
$sources = @()
$sources += Get-ChildItem -LiteralPath 'sdk-java\src\main\java' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -LiteralPath 'plugins' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

if ($sources.Count -eq 0) {
    throw '未找到 Java 源码文件'
}

& $javacExe --release 21 -encoding UTF-8 '-Xlint:all,-processing' -Werror -cp 'sdk-java\lib\*' -d 'sdk-java\target\classes' @sources
if ($LASTEXITCODE -ne 0) {
    throw '编译失败，请根据上方错误修复源码'
}
"[成功] 编译完成"
""

$wsUrl = $envConfig['WS_URL']
if ([string]::IsNullOrWhiteSpace($wsUrl)) {
    $wsUrl = $envConfig['SNOWLUMA_WS_URL']
}
if ([string]::IsNullOrWhiteSpace($wsUrl)) {
    $wsUrl = $envConfig['NAPCAT_WS_URL']
}
$accessToken = $envConfig['ACCESS_TOKEN']
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    $accessToken = $envConfig['SNOWLUMA_ACCESS_TOKEN']
}
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    $accessToken = $envConfig['NAPCAT_ACCESS_TOKEN']
}
$qqId = $envConfig['QQ_ID']
if ([string]::IsNullOrWhiteSpace($qqId)) {
    $qqId = $envConfig['SNOWLUMA_QQ_ID']
}
if ([string]::IsNullOrWhiteSpace($qqId)) {
    $qqId = $envConfig['NAPCAT_QQ_ID']
}
$backend = $envConfig['BACKEND']
if ([string]::IsNullOrWhiteSpace($backend)) {
    $backend = 'napcat'
}

if ([string]::IsNullOrWhiteSpace($wsUrl)) {
    throw '.env 文件中未找到 WS_URL 或 NAPCAT_WS_URL 配置'
}

"[配置] 协议端后端: $backend"
"[配置] WebSocket URL: $wsUrl"
if ([string]::IsNullOrWhiteSpace($qqId)) {
    '[配置] QQ 号: (未配置)'
} else {
    "[配置] QQ 号: $qqId"
}
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    '[配置] Access Token: (空)'
} else {
    '[配置] Access Token: ***已隐藏***'
}
""

"[检查] WebSocket 端口连通性..."
$uri = [uri]$wsUrl
$connection = Test-NetConnection -ComputerName $uri.Host -Port $uri.Port -WarningAction SilentlyContinue
if (-not $connection.TcpTestSucceeded) {
    throw "无法连接到 $wsUrl"
}
"[成功] WebSocket 端口可连接"
""

"[启动] RqhBot Java 版主程序..."
""
& $javaExe -cp $classpath com.rqhbot.BotMain --ws-url $wsUrl --access-token $accessToken --qq-id $qqId --plugin-dir plugins
if ($LASTEXITCODE -ne 0) {
    throw "机器人启动失败，退出码: $LASTEXITCODE"
}
