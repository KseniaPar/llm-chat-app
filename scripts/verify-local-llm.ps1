param(
    [string]$BaseUrl = "http://localhost:11434",
    [string]$Model = "qwen2.5:14b",
    [string]$LogPath = "backend/data/local-llm-verification.log"
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

$scenarios = @(
    @{ Id = 1; Complexity = "Простой"; Prompt = "Сколько будет 2 + 2? Ответ одним словом." },
    @{ Id = 2; Complexity = "Средний"; Prompt = "Объясни в трёх предложениях, что такое православный пост." },
    @{ Id = 3; Complexity = "Сложный"; Prompt = "Сравни три основных периода поста в православном календаре: таблицей из 3 строк (период, продолжительность, отличие)." },
    @{ Id = 4; Complexity = "Простой"; Prompt = "Сколько книг в Новом Завете? Ответ только числом." },
    @{ Id = 5; Complexity = "Средний"; Prompt = "В чём разница между храмовым праздником и двунадесятым? Ответ в 3–4 предложениях." },
    @{ Id = 6; Complexity = "Сложный"; Prompt = "Составь план подготовки к экзамену по «Основам православия» на 5 дней: таблица из 5 строк (день, тема, что выучить)." },
    @{ Id = 7; Complexity = "Рассуждение"; Prompt = "Можно ли есть рыбу в Великий пост по средам и пятницам? Ответ: да или нет и краткое обоснование в 2–3 предложениях." }
)

Write-Log "=== Day 26 Local LLM verification ==="
Write-Log "Base URL: $BaseUrl"
Write-Log "Model: $Model"

try {
    $tags = Invoke-RestMethod -Uri "$BaseUrl/api/tags" -Method Get -TimeoutSec 30
    $modelNames = @($tags.models | ForEach-Object { $_.name })
    Write-Log "Ollama online. Installed models: $($modelNames -join ', ')"
} catch {
    Write-Log "ERROR: Ollama is not reachable at $BaseUrl"
    exit 1
}

$modelFound = $false
foreach ($name in $modelNames) {
    if ($name -eq $Model -or $name.StartsWith("${Model}:")) {
        $modelFound = $true
        break
    }
}
if (-not $modelFound) {
    Write-Log "ERROR: Model '$Model' not found. Run: ollama pull $Model"
    exit 1
}

Write-Log "--- CLI check (ollama run) ---"
try {
    $cliJob = Start-Job -ScriptBlock {
        param($model)
        "Привет" | & ollama run $model
    } -ArgumentList $Model
    if (Wait-Job $cliJob -Timeout 120) {
        $cliOutput = Receive-Job $cliJob
        Write-Log "CLI response: $($cliOutput -join ' ')"
    } else {
        Write-Log "WARN: CLI check timed out after 120s (HTTP check continues below)"
    }
    Remove-Job $cliJob -Force -ErrorAction SilentlyContinue
} catch {
    Write-Log "WARN: CLI check failed: $($_.Exception.Message)"
}

foreach ($scenario in $scenarios) {
    Write-Log "--- Scenario #$($scenario.Id) [$($scenario.Complexity)] ---"
    Write-Log "Prompt: $($scenario.Prompt)"

    $body = @{
        model = $Model
        messages = @(@{ role = "user"; content = $scenario.Prompt })
        stream = $false
        options = @{ temperature = 0.5; num_predict = 512 }
    } | ConvertTo-Json -Depth 5

    $started = Get-Date
    try {
        $response = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -Body $body -ContentType "application/json; charset=utf-8" -TimeoutSec 300
        $durationMs = [int]((Get-Date) - $started).TotalMilliseconds
        $answer = $response.message.content
        Write-Log "Duration: ${durationMs}ms"
        Write-Log "Answer: $answer"
    } catch {
        Write-Log "ERROR: HTTP request failed: $($_.Exception.Message)"
        exit 1
    }
}

Write-Log "=== Verification completed successfully ==="
