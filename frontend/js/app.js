const questionInput = document.getElementById('question-input');
const compareBtn = document.getElementById('compare-btn');
const demoRunAllBtn = document.getElementById('demo-run-all-btn');
const indexStatusEl = document.getElementById('index-status');
const demoScenariosEl = document.getElementById('demo-scenarios');
const demoSummaryEl = document.getElementById('demo-summary');
const demoStatusEl = document.getElementById('demo-status');
const lastRunBanner = document.getElementById('last-run-banner');
const statusDot = document.getElementById('status-dot');
const statusMessage = document.getElementById('status-message');
const baselineMeta = document.getElementById('baseline-meta');
const optimizedMeta = document.getElementById('optimized-meta');
const useCaseEl = document.getElementById('use-case');
const demoDescription = document.getElementById('demo-description');
const queryStatus = document.getElementById('query-status');
const compareSection = document.getElementById('compare-section');
const compareStats = document.getElementById('compare-stats');

let ollamaReady = false;
let demoScenarios = [];
let baselineProfile = null;
let optimizedProfile = null;
const pendingTimers = new Map();
let pollTimer = null;
let runProgressTimer = null;
let lastRunStatus = null;
let lastActiveStep = 0;

async function apiFetch(path, options = {}, fetchOptions = {}) {
  const { allowNoContent = false } = fetchOptions;
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const text = await response.text();
  if (response.status === 204 && allowNoContent) {
    return null;
  }
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { error: text };
    }
  }
  if (!response.ok) {
    throw new Error(data?.error || data?.message || `HTTP ${response.status}`);
  }
  return data;
}

function formatDateTime(ms) {
  if (!ms) return '—';
  return new Date(ms).toLocaleString('ru-RU');
}

function escapeHtml(text) {
  return String(text ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function setBusy(isBusy, message = '') {
  compareBtn.disabled = isBusy;
  demoRunAllBtn.disabled = isBusy;
  questionInput.disabled = isBusy;
  queryStatus.textContent = message;
  demoScenariosEl.querySelectorAll('button').forEach((btn) => {
    btn.disabled = isBusy;
  });
}

function formatMs(ms) {
  if (ms == null || Number.isNaN(ms)) return '—';
  return `${Math.round(ms)} ms`;
}

function totalDurationMs(response) {
  if (!response) return null;
  return (response.retrievalDurationMs ?? 0) + (response.generationDurationMs ?? 0);
}

function formatSources(response) {
  const sources = response?.sources ?? [];
  if (!sources.length) return '0';
  const labels = sources
    .map((s) => s.section || s.source)
    .filter(Boolean)
    .slice(0, 3);
  const suffix = sources.length > labels.length ? ` +${sources.length - labels.length}` : '';
  return `${sources.length} — ${labels.join(', ')}${suffix}`;
}

function statusBadge(success, errorMessage) {
  if (success !== false) {
    return '<span class="llm-compare-stats__ok">✓ Успех</span>';
  }
  const err = errorMessage ? `: ${escapeHtml(errorMessage)}` : '';
  return `<span class="llm-compare-stats__fail">✗ Ошибка${err}</span>`;
}

function winnerLabel(side, winner) {
  if (!winner || winner === '—' || winner === 'TIE') {
    return winner === 'TIE' ? '≈ одинаково' : '—';
  }
  if (winner === side) {
    return side === 'BASELINE' ? '📦 BASELINE' : '⚡ OPTIMIZED';
  }
  return '—';
}

function formatProfileLine(profile) {
  if (!profile) return '';
  const quant = profile.quantizationNote ? ` · ${profile.quantizationNote}` : '';
  const avail = profile.modelAvailable ? '✓' : '✗ pull required';
  return `${profile.label}: ${profile.model}${quant} · temp ${profile.temperature} · max ${profile.maxTokens} · ctx ${profile.contextWindow} · ${avail}`;
}

function toOptimizationView(data) {
  return {
    localResponse: data.baselineResponse,
    cloudResponse: data.optimizedResponse,
    summary: data.summary
      ? {
          speedWinner: data.summary.speedWinner === 'BASELINE'
            ? 'LOCAL'
            : data.summary.speedWinner === 'OPTIMIZED'
              ? 'CLOUD'
              : data.summary.speedWinner,
          qualityNote: data.summary.qualityNote,
          stabilityNote: data.summary.resourceNote,
        }
      : null,
    optimizationSummary: data.summary,
  };
}

function renderCompareStats(data, options = { mode: 'full' }) {
  const target = options.target ?? compareStats;
  if (!target) return;

  const view = data.baselineResponse ? toOptimizationView(data) : data;
  const isFull = options.mode === 'full' && view.cloudResponse;
  const baseline = view.localResponse ?? view;
  const optimized = view.cloudResponse;
  const summary = view.summary;
  const optSummary = view.optimizationSummary ?? data.summary;

  const baselineTotal = totalDurationMs(baseline);
  const optimizedTotal = isFull ? totalDurationMs(optimized) : null;
  const baselineSources = baseline?.sources?.length ?? 0;
  const optimizedSources = isFull ? (optimized?.sources?.length ?? 0) : 0;
  const baselineChunks = baseline?.chunksUsed?.length ?? 0;
  const optimizedChunks = isFull ? (optimized?.chunksUsed?.length ?? 0) : 0;

  let qualityWinner = '—';
  if (isFull && optSummary) {
    if (optSummary.optimizedSourceMatches > optSummary.baselineSourceMatches) qualityWinner = 'CLOUD';
    else if (optSummary.baselineSourceMatches > optSummary.optimizedSourceMatches) qualityWinner = 'LOCAL';
    else qualityWinner = 'TIE';
  }

  const speedWinner = summary?.speedWinner ?? (
    isFull && baselineTotal != null && optimizedTotal != null
      ? (optimizedTotal < baselineTotal ? 'CLOUD' : optimizedTotal > baselineTotal ? 'LOCAL' : 'TIE')
      : '—'
  );

  const cloudHeader = isFull
    ? '<th class="llm-compare-stats__col-cloud">⚡ OPTIMIZED</th><th class="llm-compare-stats__col-verdict">Итог</th>'
    : '';
  const cloudColspan = isFull ? 4 : 2;

  const row = (metric, baselineVal, optimizedVal, verdict, baselineClass = '', optimizedClass = '') => {
    const optimizedCells = isFull
      ? `<td class="${optimizedClass}">${optimizedVal}</td><td class="llm-compare-stats__col-verdict">${verdict}</td>`
      : '';
    return `<tr>
      <th scope="row">${metric}</th>
      <td class="llm-compare-stats__col-local ${baselineClass}">${baselineVal}</td>
      ${optimizedCells}
    </tr>`;
  };

  target.innerHTML = `
    <table class="compare-table llm-compare-stats">
      <thead>
        <tr>
          <th>Метрика</th>
          <th class="llm-compare-stats__col-local">📦 BASELINE</th>
          ${cloudHeader}
        </tr>
      </thead>
      <tbody>
        <tr class="llm-compare-stats__group">
          <td colspan="${cloudColspan}">⚡ Скорость</td>
        </tr>
        ${row('Retrieval', formatMs(baseline?.retrievalDurationMs), isFull ? formatMs(optimized?.retrievalDurationMs) : '', 'общий')}
        ${row(
          'Генерация',
          formatMs(baseline?.generationDurationMs),
          isFull ? formatMs(optimized?.generationDurationMs) : '',
          winnerLabel('CLOUD', speedWinner),
          speedWinner === 'LOCAL' ? 'llm-compare-stats__winner' : '',
          speedWinner === 'CLOUD' ? 'llm-compare-stats__winner' : '',
        )}
        ${row(
          'Итого',
          formatMs(baselineTotal),
          isFull ? formatMs(optimizedTotal) : '',
          winnerLabel('CLOUD', speedWinner) !== '—' ? winnerLabel('CLOUD', speedWinner) : winnerLabel('BASELINE', speedWinner),
        )}
        <tr class="llm-compare-stats__group">
          <td colspan="${cloudColspan}">📚 Качество</td>
        </tr>
        ${row(
          'Совпадения терминов',
          String(optSummary?.baselineSourceMatches ?? '—'),
          isFull ? String(optSummary?.optimizedSourceMatches ?? '—') : '',
          winnerLabel('CLOUD', qualityWinner) !== '—' ? winnerLabel('CLOUD', qualityWinner) : winnerLabel('BASELINE', qualityWinner),
        )}
        ${row(
          'Confidence',
          escapeHtml(baseline?.confidence ?? '—'),
          isFull ? escapeHtml(optimized?.confidence ?? '—') : '',
          '—',
        )}
        ${row(
          'Источники',
          escapeHtml(formatSources(baseline)),
          isFull ? escapeHtml(formatSources(optimized)) : '',
          '—',
        )}
        ${row(
          'Чанков',
          String(baselineChunks),
          isFull ? String(optimizedChunks) : '',
          '—',
        )}
        ${isFull && optSummary?.qualityNote
          ? `<tr><th scope="row">Оценка</th><td colspan="3" class="llm-compare-stats__note-cell">${escapeHtml(optSummary.qualityNote)}</td></tr>`
          : ''}
        <tr class="llm-compare-stats__group">
          <td colspan="${cloudColspan}">🛡 Ресурсы</td>
        </tr>
        ${row(
          'Статус',
          statusBadge(baseline?.generationSuccess, baseline?.generationError),
          isFull ? statusBadge(optimized?.generationSuccess, optimized?.generationError) : '',
          '—',
        )}
        ${row(
          'Модель',
          escapeHtml(baseline?.llmModel ?? '—'),
          isFull ? escapeHtml(optimized?.llmModel ?? '—') : '',
          '—',
        )}
        ${row(
          'Токенов',
          baseline?.tokenCount ?? '—',
          isFull ? (optimized?.tokenCount ?? '—') : '',
          '—',
        )}
        ${isFull && optSummary?.resourceNote
          ? `<tr><th scope="row">Экономия</th><td colspan="3" class="llm-compare-stats__note-cell">${escapeHtml(optSummary.resourceNote)}</td></tr>`
          : ''}
      </tbody>
    </table>`;
}

function renderDemoScenarios(scenarios) {
  demoScenariosEl.innerHTML = '';
  for (const scenario of scenarios) {
    const card = document.createElement('article');
    card.className = 'local-llm-scenario';
    card.dataset.scenarioId = String(scenario.id);
    card.innerHTML = `
      <div class="local-llm-scenario__header">
        <div>
          <span class="local-llm-scenario__badge">${escapeHtml(scenario.complexity)}</span>
          <strong>${escapeHtml(scenario.title)}</strong>
        </div>
        <button type="button" class="rag-panel__btn" data-run-id="${scenario.id}">Запустить</button>
      </div>
      <p class="rag-panel__hint">${escapeHtml(scenario.question)}</p>
      <div class="local-llm-result hidden" data-result-for="${scenario.id}"></div>`;
    demoScenariosEl.appendChild(card);
  }
}

function onDemoScenarioClick(event) {
  const btn = event.target.closest('[data-run-id]');
  if (!btn || btn.disabled) return;
  runDemoScenario(Number(btn.dataset.runId));
}

function markScenarioState(scenarioId, state) {
  const card = demoScenariosEl.querySelector(`[data-scenario-id="${scenarioId}"]`);
  if (!card) return;
  card.classList.remove('local-llm-scenario--running', 'local-llm-scenario--done', 'local-llm-scenario--error');
  if (!state) return;
  if (state === 'running') card.classList.add('local-llm-scenario--running');
  if (state === 'done') card.classList.add('local-llm-scenario--done');
  if (state === 'error') card.classList.add('local-llm-scenario--error');
}

function stopScenarioPending(scenarioId) {
  const timer = pendingTimers.get(scenarioId);
  if (timer) {
    clearInterval(timer);
    pendingTimers.delete(scenarioId);
  }
}

function hintForElapsed(elapsed) {
  if (elapsed < 30) return 'retrieval + загрузка модели…';
  if (elapsed < 180) return 'BASELINE (qwen2.5:14b)…';
  return 'OPTIMIZED (qwen2.5:7b)…';
}

function updateScenarioPendingText(scenarioId, label, startedAtMs) {
  const container = demoScenariosEl.querySelector(`[data-result-for="${scenarioId}"]`);
  if (!container) return;
  const elapsed = startedAtMs ? Math.round((Date.now() - startedAtMs) / 1000) : 0;
  const pendingEl = container.querySelector('[data-pending-label]');
  if (!pendingEl) return;
  pendingEl.textContent = `⏳ ${label} · ${elapsed} с · ${hintForElapsed(elapsed)}`;
}

function ensurePendingContainer(scenarioId) {
  const container = demoScenariosEl.querySelector(`[data-result-for="${scenarioId}"]`);
  if (!container) return null;
  container.classList.remove('hidden');
  if (!container.querySelector('[data-pending-label]')) {
    container.innerHTML = '<p class="rag-panel__hint" data-pending-label>⏳ …</p>';
  }
  return container;
}

function stopRunProgressTimer() {
  if (runProgressTimer) {
    clearInterval(runProgressTimer);
    runProgressTimer = null;
  }
  lastRunStatus = null;
  lastActiveStep = 0;
}

function tickRunProgressUI() {
  const status = lastRunStatus;
  if (!status?.running) return;
  const elapsed = status.startedAtMs ? Math.round((Date.now() - status.startedAtMs) / 1000) : 0;
  demoStatusEl.textContent = `В фоне: шаг ${status.currentStep}/${status.totalSteps} — ${status.currentScenarioTitle || '…'} · ${elapsed} с`;
  if (status.currentStep > 0) {
    updateScenarioPendingText(
      status.currentStep,
      `Шаг ${status.currentStep}/${status.totalSteps}`,
      status.startedAtMs,
    );
  }
}

function showScenarioPending(scenarioId, label, startedAtMs = null) {
  const container = ensurePendingContainer(scenarioId);
  if (!container) return;
  stopScenarioPending(scenarioId);
  if (startedAtMs) {
    updateScenarioPendingText(scenarioId, label, startedAtMs);
    pendingTimers.set(scenarioId, setInterval(() => {
      updateScenarioPendingText(scenarioId, label, startedAtMs);
    }, 1000));
    container.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    return;
  }
  const localStartedAt = Date.now();
  const renderPending = () => {
    updateScenarioPendingText(scenarioId, label, localStartedAt);
  };
  renderPending();
  pendingTimers.set(scenarioId, setInterval(renderPending, 1000));
  container.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function buildDemoSummaryFromResults(results, totalDurationMs) {
  const count = results.length;
  if (count === 0) {
    return {
      scenarioCount: 0,
      totalDurationMs,
      summary: {
        speedVerdict: 'Нет результатов',
        qualityVerdict: '',
        resourceVerdict: '',
        baselineWins: 0,
        optimizedWins: 0,
      },
    };
  }

  let baselineMsSum = 0;
  let optimizedMsSum = 0;
  let baselineWins = 0;
  let optimizedWins = 0;
  let baselineSuccess = 0;
  let optimizedSuccess = 0;
  let baselineMatches = 0;
  let optimizedMatches = 0;
  let baselineTokens = 0;
  let optimizedTokens = 0;

  for (const result of results) {
    const summary = result.compare?.summary;
    if (!summary) continue;
    baselineMsSum += summary.baselineGenerationMs ?? 0;
    optimizedMsSum += summary.optimizedGenerationMs ?? 0;
    baselineTokens += summary.baselineTokens ?? 0;
    optimizedTokens += summary.optimizedTokens ?? 0;
    baselineMatches += summary.baselineSourceMatches ?? 0;
    optimizedMatches += summary.optimizedSourceMatches ?? 0;
    if (summary.baselineSuccess) baselineSuccess += 1;
    if (summary.optimizedSuccess) optimizedSuccess += 1;
    if (summary.speedWinner === 'BASELINE') baselineWins += 1;
    if (summary.speedWinner === 'OPTIMIZED') optimizedWins += 1;
  }

  const avgBaseline = Math.round(baselineMsSum / count);
  const avgOptimized = Math.round(optimizedMsSum / count);
  const speedVerdict = avgOptimized < avgBaseline
    ? `OPTIMIZED быстрее в среднем (${avgOptimized} vs ${avgBaseline} ms)`
    : `BASELINE быстрее в среднем (${avgBaseline} vs ${avgOptimized} ms)`;
  const qualityVerdict = optimizedMatches >= baselineMatches
    ? `OPTIMIZED: ${optimizedMatches} совпадений, BASELINE: ${baselineMatches}`
    : `BASELINE: ${baselineMatches} совпадений, OPTIMIZED: ${optimizedMatches}`;
  const tokenSave = baselineTokens > 0
    ? Math.round((1 - optimizedTokens / baselineTokens) * 100)
    : 0;
  const resourceVerdict = `Токены: BASELINE ${baselineTokens}, OPTIMIZED ${optimizedTokens} (экономия ~${tokenSave}%). Успех: ${baselineSuccess}/${count} vs ${optimizedSuccess}/${count}.`;

  return {
    scenarioCount: count,
    totalDurationMs,
    summary: { speedVerdict, qualityVerdict, resourceVerdict, baselineWins, optimizedWins },
  };
}

function renderScenarioResult(scenarioId, result) {
  const container = demoScenariosEl.querySelector(`[data-result-for="${scenarioId}"]`);
  if (!container) return;

  const compare = result.compare;
  container.classList.remove('hidden');
  container.innerHTML = `
    <div class="local-llm-result__stats"></div>
    <p class="local-llm-result__label">BASELINE</p>
    <pre class="local-llm-result__block local-llm-result__block--answer">${escapeHtml(compare.baselineResponse?.answer ?? '—')}</pre>
    <p class="local-llm-result__label">OPTIMIZED</p>
    <pre class="local-llm-result__block">${escapeHtml(compare.optimizedResponse?.answer ?? '—')}</pre>`;
  renderCompareStats(compare, {
    mode: 'full',
    target: container.querySelector('.local-llm-result__stats'),
  });
}

function renderDemoRunSummary(data) {
  const s = data.summary;
  demoSummaryEl.classList.remove('hidden');
  demoSummaryEl.innerHTML = `
    <p><strong>Итог оптимизации</strong> (${data.scenarioCount} шагов, ${Math.round(data.totalDurationMs / 1000)} с)</p>
    <p>${escapeHtml(s.speedVerdict)}</p>
    <p>${escapeHtml(s.qualityVerdict)}</p>
    <p>${escapeHtml(s.resourceVerdict)}</p>
    <p>BASELINE побед по скорости: ${s.baselineWins}, OPTIMIZED: ${s.optimizedWins}</p>`;
}

function renderLastRun(lastRun) {
  if (!lastRun?.response) return;
  const run = lastRun.response;
  if (lastRunBanner) {
    lastRunBanner.classList.remove('hidden');
    lastRunBanner.innerHTML = `
      <p><strong>Последний успешный запуск:</strong> ${formatDateTime(lastRun.completedAtMs)}
        · ${run.scenarioCount} шагов · ${Math.round(run.totalDurationMs / 1000)} с</p>`;
  }
  for (const result of run.results || []) {
    stopScenarioPending(result.scenario.id);
    renderScenarioResult(result.scenario.id, result);
    markScenarioState(result.scenario.id, 'done');
  }
  renderDemoRunSummary(run);
}

async function loadLastRun() {
  const lastRun = await apiFetch('/api/local-llm/optimization/last-run', {}, { allowNoContent: true });
  if (lastRun) {
    renderLastRun(lastRun);
  }
}

function updateRunProgress(status) {
  if (!status?.running) return;

  const stepChanged = status.currentStep !== lastActiveStep;
  lastRunStatus = status;

  if (stepChanged && status.currentStep > 0) {
    if (lastActiveStep > 0) {
      stopScenarioPending(lastActiveStep);
    }
    lastActiveStep = status.currentStep;
    markScenarioState(status.currentStep, 'running');
    ensurePendingContainer(status.currentStep);
  }

  tickRunProgressUI();

  if (!runProgressTimer) {
    runProgressTimer = setInterval(tickRunProgressUI, 1000);
  }
}

async function pollRunUntilDone() {
  const status = await apiFetch('/api/local-llm/optimization/run/status');
  updateRunProgress(status);
  if (status.running) {
    return false;
  }
  if (status.lastError) {
    throw new Error(status.lastError);
  }
  await loadLastRun();
  return true;
}

function startRunPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(async () => {
    try {
      const done = await pollRunUntilDone();
      if (done) {
        clearInterval(pollTimer);
        pollTimer = null;
        stopRunProgressTimer();
        if (lastActiveStep > 0) {
          stopScenarioPending(lastActiveStep);
        }
        setBusy(false);
        demoStatusEl.textContent = 'Готово — показан последний успешный запуск.';
      }
    } catch (error) {
      clearInterval(pollTimer);
      pollTimer = null;
      stopRunProgressTimer();
      setBusy(false);
      demoStatusEl.textContent = error.message;
    }
  }, 3000);
}

async function loadDemo() {
  const demo = await apiFetch('/api/local-llm/optimization/demo');
  demoScenarios = demo.scenarios || [];
  baselineProfile = demo.baselineProfile;
  optimizedProfile = demo.optimizedProfile;

  if (demoDescription) {
    demoDescription.textContent = demo.description || '';
  }
  if (useCaseEl) {
    useCaseEl.textContent = demo.useCase || '';
  }
  baselineMeta.textContent = formatProfileLine(baselineProfile);
  optimizedMeta.textContent = formatProfileLine(optimizedProfile);

  const missingModels = [];
  if (baselineProfile && !baselineProfile.modelAvailable) missingModels.push(baselineProfile.model);
  if (optimizedProfile && !optimizedProfile.modelAvailable) missingModels.push(optimizedProfile.model);
  if (missingModels.length) {
    indexStatusEl.textContent = `Нужны модели: ollama pull ${missingModels.join(' && ollama pull ')}`;
  } else {
    indexStatusEl.textContent = '';
  }

  renderDemoScenarios(demoScenarios);

  try {
    const llmStatus = await apiFetch('/api/local-llm/status');
    ollamaReady = llmStatus.online;
    statusDot.className = `status-dot ${ollamaReady ? 'status-dot--online' : 'status-dot--offline'}`;
    statusMessage.textContent = llmStatus.message || (ollamaReady ? 'Ollama online' : 'Ollama offline');
  } catch (error) {
    ollamaReady = false;
    statusDot.className = 'status-dot status-dot--offline';
    statusMessage.textContent = error.message;
  }
}

async function runDemoScenario(scenarioId) {
  const scenario = demoScenarios.find((item) => item.id === scenarioId);
  if (!scenario) return;

  setBusy(true, '');
  demoStatusEl.textContent = `Шаг ${scenarioId}: ${scenario.title}…`;
  questionInput.value = scenario.question;
  markScenarioState(scenarioId, 'running');
  showScenarioPending(scenarioId, 'Retrieval + BASELINE + OPTIMIZED…');

  try {
    const result = await apiFetch(`/api/local-llm/optimization/run/${scenarioId}`, { method: 'POST' });
    stopScenarioPending(scenarioId);
    renderScenarioResult(scenarioId, result);
    markScenarioState(scenarioId, 'done');
    demoStatusEl.textContent = `Шаг ${scenarioId} завершён.`;
  } catch (error) {
    stopScenarioPending(scenarioId);
    markScenarioState(scenarioId, 'error');
    demoStatusEl.textContent = error.message;
    const container = demoScenariosEl.querySelector(`[data-result-for="${scenarioId}"]`);
    if (container) {
      container.classList.remove('hidden');
      container.innerHTML = `<p class="local-llm-scenario__error">${escapeHtml(error.message)}</p>`;
    }
  } finally {
    setBusy(false);
  }
}

async function runDemoAll() {
  if (!demoScenarios.length) return;

  if (!ollamaReady) {
    demoStatusEl.textContent = 'Ollama недоступен — сравнение может не выполниться.';
  }

  setBusy(true, '');
  demoStatusEl.textContent = 'Запуск сценария на backend…';

  try {
    const status = await apiFetch('/api/local-llm/optimization/run', { method: 'POST' });
    if (status.running) {
      demoStatusEl.textContent = 'Сценарий выполняется в фоне (~15 мин на CPU). Можно обновить страницу — результаты сохранятся.';
      updateRunProgress(status);
      startRunPolling();
      return;
    }
    demoStatusEl.textContent = 'Сценарий уже выполняется — отслеживаем прогресс…';
    startRunPolling();
  } catch (error) {
    demoStatusEl.textContent = error.message;
    setBusy(false);
  }
}

async function runCompare() {
  const question = questionInput.value.trim();
  if (!question) {
    queryStatus.textContent = 'Введите вопрос.';
    return;
  }
  setBusy(true, 'Retrieval + BASELINE + OPTIMIZED… (ожидайте 3–5 мин на CPU)');
  try {
    const data = await apiFetch('/api/local-llm/optimization/compare', {
      method: 'POST',
      body: JSON.stringify({ prompt: question }),
    });
    compareSection.classList.remove('hidden');
    renderCompareStats(data, { mode: 'full' });
    queryStatus.textContent = 'Готово.';
  } catch (error) {
    queryStatus.textContent = error.message;
  } finally {
    setBusy(false);
  }
}

compareBtn.addEventListener('click', runCompare);
demoRunAllBtn.addEventListener('click', runDemoAll);

async function init() {
  demoScenariosEl.addEventListener('click', onDemoScenarioClick);
  setBusy(true, 'Загрузка…');
  try {
    await loadDemo();
    await loadLastRun();
    const status = await apiFetch('/api/local-llm/optimization/run/status');
    if (status.running) {
      setBusy(true, '');
      demoStatusEl.textContent = 'Сценарий уже выполняется на backend — подключаемся…';
      updateRunProgress(status);
      startRunPolling();
    }
  } catch (error) {
    statusMessage.textContent = error.message;
  } finally {
    if (!pollTimer) {
      setBusy(false);
    }
  }
}

init();
