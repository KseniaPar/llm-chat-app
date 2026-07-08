param(
    [string]$BackendUrl = "http://localhost:8080",
    [string]$BaseUrl = "http://localhost:11434",
    [string]$Model = "qwen2.5:14b",
    [string]$LogPath = "backend/data/local-llm-integration.log"
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptRoot
$logFile = Join-Path $repoRoot $LogPath
$logDir = Split-Path -Parent $logFile
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

function Write-Log {
    param([string]$Message)
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Message"
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

Write-Log "=== Day 27 Local LLM integration verification ==="
Write-Log "Backend: $BackendUrl"
Write-Log "Ollama: $BaseUrl"
Write-Log "Model: $Model"

try {
    $status = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/status" -Method Get -TimeoutSec 30
    Write-Log "Status: $($status.message)"
    if (-not $status.online -or -not $status.modelAvailable) {
        Write-Log "ERROR: Ollama is not ready"
        exit 1
    }
} catch {
    Write-Log "ERROR: Backend status check failed: $($_.Exception.Message)"
    exit 1
}

$sessionId = $null
$turns = @(
    "Hello! Briefly introduce yourself as a local study assistant.",
    "What is 15 + 27? Reply with one number only."
)

foreach ($turn in $turns) {
    Write-Log "--- Chat turn ---"
    Write-Log "User: $turn"

    $body = @{ prompt = $turn }
    if ($sessionId) {
        $body.sessionId = $sessionId
    }
    $json = $body | ConvertTo-Json

    $started = Get-Date
    try {
        $response = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/agent/chat" -Method Post -Body $json -ContentType "application/json; charset=utf-8" -TimeoutSec 300
        $durationMs = [int]((Get-Date) - $started).TotalMilliseconds
        $sessionId = $response.sessionId
        Write-Log "Session: $sessionId"
        Write-Log "Duration: ${durationMs}ms (server: $($response.durationMs)ms)"
        Write-Log "Assistant: $($response.response)"
    } catch {
        Write-Log "ERROR: Agent chat failed: $($_.Exception.Message)"
        exit 1
    }
}

try {
    $history = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/agent/history?sessionId=$sessionId" -Method Get -TimeoutSec 30
    $count = @($history.messages).Count
    Write-Log "History messages: $count"
    if ($count -lt 4) {
        Write-Log "ERROR: Expected at least 4 messages in history after 2 turns"
        exit 1
    }
} catch {
    Write-Log "ERROR: History check failed: $($_.Exception.Message)"
    exit 1
}

Write-Log "=== Day 27 integration verification completed successfully ==="
