# Загрузка проекта на VPS с Windows.
# Использование:
#   .\deploy\vps\push-to-vps.ps1 -Server user@1.2.3.4
#   .\deploy\vps\push-to-vps.ps1 -Server user@1.2.3.4 -RemotePath /opt/llm-chat -Branch day30
param(
    [Parameter(Mandatory = $true)]
    [string]$Server,

    [string]$RemotePath = "/opt/llm-chat",
    [string]$Branch = "day30"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "../..")

$excludes = @(
    "node_modules",
    "frontend/dist",
    "backend/target",
    "target",
    ".git",
    "data",
    "deploy/vps/.env",
    ".cursor"
)

Write-Host "==> Создание каталога на сервере: $RemotePath"
ssh $Server "sudo mkdir -p '$RemotePath' && sudo chown -R `$(whoami):`$(whoami) '$RemotePath'"

if (Get-Command rsync -ErrorAction SilentlyContinue) {
    $excludeArgs = $excludes | ForEach-Object { "--exclude=$_" }
    Write-Host "==> rsync -> $Server`:$RemotePath"
    rsync -avz --delete @excludeArgs "$Root/" "${Server}:${RemotePath}/"
} else {
    Write-Host "==> rsync не найден, используем scp (медленнее)"
    $archive = Join-Path $env:TEMP "llm-chat-deploy.zip"
    if (Test-Path $archive) { Remove-Item $archive -Force }

    $staging = Join-Path $env:TEMP "llm-chat-staging"
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    New-Item -ItemType Directory -Path $staging | Out-Null

    robocopy $Root $staging /E /XD node_modules dist target .git data .cursor /XF deploy\vps\.env | Out-Null
    Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $archive -Force
    scp $archive "${Server}:/tmp/llm-chat-deploy.zip"
    ssh $Server "cd '$RemotePath' && unzip -o /tmp/llm-chat-deploy.zip && rm -f /tmp/llm-chat-deploy.zip"
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $archive -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "==> Готово. На сервере выполните:"
Write-Host "    cd $RemotePath"
if ($Branch) {
    Write-Host "    git checkout $Branch   # если клонировали git, а не rsync"
}
Write-Host "    sudo bash deploy/vps/bootstrap.sh"
