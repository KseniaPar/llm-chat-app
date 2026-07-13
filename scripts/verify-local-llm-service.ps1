param(
    [string]$BackendUrl = "http://localhost:8080",
    [string]$OllamaUrl = "http://localhost:11434",
    [string]$Model = "qwen2.5:14b",
    [string]$LogPath = "backend/data/local-llm-service.log"
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

function Invoke-Chat {
    param([string]$Prompt)
    $body = @{ prompt = $Prompt } | ConvertTo-Json
    return Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/service/chat" -Method Post -Body $body -ContentType "application/json; charset=utf-8" -TimeoutSec 300
}

Write-Log "=== Day 30 Private LLM Service verification ==="
Write-Log "Backend: $BackendUrl"
Write-Log "Ollama: $OllamaUrl"
Write-Log "Model: $Model"

try {
    $tags = Invoke-RestMethod -Uri "$OllamaUrl/api/tags" -Method Get -TimeoutSec 30
    $modelNames = @($tags.models | ForEach-Object { $_.name })
    Write-Log "Ollama online. Models: $($modelNames -join ', ')"
} catch {
    Write-Log "ERROR: Ollama not reachable at $OllamaUrl"
    exit 1
}

try {
    $info = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/service/info" -Method Get -TimeoutSec 30
    Write-Log "Service info: $($info.message)"
    Write-Log "Limits: rate=$($info.rateLimitPerMinute)/min, maxPrompt=$($info.maxPromptChars), concurrent=$($info.maxConcurrentRequests)"
    if (-not $info.online -or -not $info.modelAvailable) {
        Write-Log "ERROR: Service not ready"
        exit 1
    }
} catch {
    Write-Log "ERROR: Service info failed: $($_.Exception.Message)"
    exit 1
}

Write-Log "--- Single chat via HTTP API ---"
try {
    $started = Get-Date
    $chat = Invoke-Chat -Prompt "2+2=? Ответ одним числом."
    $durationMs = [int]((Get-Date) - $started).TotalMilliseconds
    Write-Log "Chat OK in ${durationMs}ms. Answer: $($chat.answer)"
    Write-Log "Rate limit remaining: $($chat.rateLimitRemaining)"
} catch {
    Write-Log "ERROR: Chat failed: $($_.Exception.Message)"
    exit 1
}

Write-Log "--- Sequential stability (3 requests) ---"
$ok = 0
for ($i = 1; $i -le 3; $i++) {
    try {
        $chat = Invoke-Chat -Prompt "($i) 3+3=? Только число."
        Write-Log "Request $i OK: $($chat.answer)"
        $ok++
    } catch {
        Write-Log "Request $i FAILED: $($_.Exception.Message)"
    }
}
if ($ok -lt 3) {
    Write-Log "ERROR: Sequential stability failed ($ok/3)"
    exit 1
}

Write-Log "--- Max context limit ---"
try {
    $oversized = "x" * ($info.maxPromptChars + 1)
    $body = @{ prompt = $oversized } | ConvertTo-Json
    Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/service/chat" -Method Post -Body $body -ContentType "application/json; charset=utf-8" -TimeoutSec 30
    Write-Log "ERROR: Oversized prompt was accepted"
    exit 1
} catch {
    if ($_.Exception.Response.StatusCode.value__ -eq 400) {
        Write-Log "Max context limit OK: HTTP 400 for $($info.maxPromptChars + 1) chars"
    } else {
        Write-Log "ERROR: Unexpected response for oversized prompt: $($_.Exception.Message)"
        exit 1
    }
}

Write-Log "--- Rate limit (burst) ---"
$rateLimit = [Math]::Min($info.rateLimitPerMinute, 5)
$rateOk = 0
$rateBlocked = $false
for ($i = 1; $i -le ($rateLimit + 2); $i++) {
    try {
        $null = Invoke-Chat -Prompt "ping $i"
        $rateOk++
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 429) {
            Write-Log "Rate limit triggered on request $i (expected after $rateLimit)"
            $rateBlocked = $true
            break
        }
        Write-Log "WARN: Request $i failed: $($_.Exception.Message)"
    }
}
if (-not $rateBlocked -and $info.rateLimitPerMinute -le 5) {
    Write-Log "WARN: Rate limit not triggered in burst test (sent $($rateLimit + 2) requests)"
}

Write-Log "--- Full verify endpoint ---"
try {
    $verify = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/service/verify" -Method Post -TimeoutSec 600
    foreach ($check in $verify.checks) {
        $mark = if ($check.passed) { "OK" } else { "FAIL" }
        Write-Log "[$mark] $($check.name): $($check.detail)"
    }
    if (-not $verify.allPassed) {
        Write-Log "ERROR: Verify endpoint reported failures"
        exit 1
    }
    Write-Log "Verify summary: $($verify.summary)"
} catch {
    Write-Log "ERROR: Verify endpoint failed: $($_.Exception.Message)"
    exit 1
}

Write-Log "=== Day 30 verification completed successfully ==="
