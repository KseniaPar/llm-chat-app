param(
    [string]$BackendUrl = "http://localhost:8080",
    [string]$LogPath = "backend/data/local-llm-optimization.log"
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

Write-Log "=== Day 29 Local LLM optimization verification ==="
Write-Log "Backend: $BackendUrl"

try {
    $demo = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/optimization/demo" -Method Get -TimeoutSec 30
    Write-Log "Demo: $($demo.dayLabel)"
    Write-Log "Use case: $($demo.useCase)"
    Write-Log "BASELINE: $($demo.baselineProfile.model) temp=$($demo.baselineProfile.temperature) max=$($demo.baselineProfile.maxTokens) ctx=$($demo.baselineProfile.contextWindow) available=$($demo.baselineProfile.modelAvailable)"
    Write-Log "OPTIMIZED: $($demo.optimizedProfile.model) temp=$($demo.optimizedProfile.temperature) max=$($demo.optimizedProfile.maxTokens) ctx=$($demo.optimizedProfile.contextWindow) quant=$($demo.optimizedProfile.quantizationNote) available=$($demo.optimizedProfile.modelAvailable)"
} catch {
    Write-Log "ERROR: Demo endpoint failed: $($_.Exception.Message)"
    exit 1
}

try {
    $status = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/status" -Method Get -TimeoutSec 30
    Write-Log "Ollama status: $($status.message)"
    if (-not $status.online) {
        Write-Log "ERROR: Ollama is not online"
        exit 1
    }
} catch {
    Write-Log "ERROR: Status check failed: $($_.Exception.Message)"
    exit 1
}

$scenario = $demo.scenarios | Where-Object { $_.id -eq 1 } | Select-Object -First 1
if (-not $scenario) {
    Write-Log "ERROR: Scenario 1 not found"
    exit 1
}

Write-Log "--- Optimization compare: scenario 1 [$($scenario.title)] ---"
Write-Log "Question: $($scenario.question)"

$started = Get-Date
try {
    $result = Invoke-RestMethod -Uri "$BackendUrl/api/local-llm/optimization/run/1" -Method Post -TimeoutSec 600
    $durationMs = [int]((Get-Date) - $started).TotalMilliseconds
    $summary = $result.compare.summary
    Write-Log "Duration: ${durationMs}ms"
    Write-Log "BASELINE: gen=$($summary.baselineGenerationMs)ms tokens=$($summary.baselineTokens) matches=$($summary.baselineSourceMatches) success=$($summary.baselineSuccess)"
    Write-Log "OPTIMIZED: gen=$($summary.optimizedGenerationMs)ms tokens=$($summary.optimizedTokens) matches=$($summary.optimizedSourceMatches) success=$($summary.optimizedSuccess)"
    Write-Log "Speed winner: $($summary.speedWinner)"
    Write-Log "Quality: $($summary.qualityNote)"
    Write-Log "Resources: $($summary.resourceNote)"
    Write-Log "BASELINE answer: $($result.compare.baselineResponse.answer)"
    Write-Log "OPTIMIZED answer: $($result.compare.optimizedResponse.answer)"
} catch {
    Write-Log "ERROR: Optimization run failed: $($_.Exception.Message)"
    exit 1
}

Write-Log "=== Day 29 optimization verification completed successfully ==="
