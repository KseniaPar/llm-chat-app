const questionInput = document.getElementById('question-input');
const compareBtn = document.getElementById('compare-btn');
const localOnlyBtn = document.getElementById('local-only-btn');
const evalBtn = document.getElementById('eval-btn');
const demoRunAllBtn = document.getElementById('demo-run-all-btn');
const indexStatusEl = document.getElementById('index-status');
const cloudIndexMeta = document.getElementById('cloud-index-meta');
const demoScenariosEl = document.getElementById('demo-scenarios');
const demoSummaryEl = document.getElementById('demo-summary');
const demoStatusEl = document.getElementById('demo-status');
const statusDot = document.getElementById('status-dot');
const statusMessage = document.getElementById('status-message');
const indexMeta = document.getElementById('index-meta');
const queryStatus = document.getElementById('query-status');
const compareSection = document.getElementById('compare-section');
const compareStats = document.getElementById('compare-stats');
const evalSection = document.getElementById('eval-section');
const evalSummary = document.getElementById('eval-summary');
const evalTableBody = document.getElementById('eval-table-body');

let ollamaReady = false;
let demoScenarios = [];

async function apiFetch(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const text = await response.text();
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

function escapeHtml(text) {
  return String(text ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function setBusy(isBusy, message = '') {
  compareBtn.disabled = isBusy;
  localOnlyBtn.disabled = isBusy;
  evalBtn.disabled = isBusy;
  demoRunAllBtn.disabled = isBusy;
  questionInput.disabled = isBusy;
  queryStatus.textContent = message;
  demoScenariosEl.querySelectorAll('button').forEach((btn) => {
    btn.disabled = isBusy;
  });
}

function showCompare(data) {
  compareSection.classList.remove('hidden');
  renderCompareStats(data, { mode: 'full' });
}

function showLocalOnly(response) {
  compareSection.classList.remove('hidden');
  renderCompareStats({ localResponse: response }, { mode: 'local' });
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
    return side === 'LOCAL' ? '🦙 LOCAL' : '☁️ CLOUD';
  }
  return '—';
}

function renderCompareStats(data, options = { mode: 'full' }) {
  const target = options.target ?? compareStats;
  if (!target) return;
  const isFull = options.mode === 'full' && data.cloudResponse;
  const local = data.localResponse ?? data;
  const cloud = data.cloudResponse;
  const summary = data.summary;

  const localTotal = totalDurationMs(local);
  const cloudTotal = isFull ? totalDurationMs(cloud) : null;
  const localSources = local?.sources?.length ?? 0;
  const cloudSources = isFull ? (cloud?.sources?.length ?? 0) : 0;
  const localChunks = local?.chunksUsed?.length ?? 0;
  const cloudChunks = isFull ? (cloud?.chunksUsed?.length ?? 0) : 0;
  const localQuotes = local?.quotes?.length ?? 0;
  const cloudQuotes = isFull ? (cloud?.quotes?.length ?? 0) : 0;

  let qualityWinner = '—';
  if (isFull) {
    if (localSources > cloudSources) qualityWinner = 'LOCAL';
    else if (cloudSources > localSources) qualityWinner = 'CLOUD';
    else qualityWinner = 'TIE';
  }

  let stabilityWinner = '—';
  if (isFull) {
    const localOk = local?.generationSuccess !== false;
    const cloudOk = cloud?.generationSuccess !== false;
    if (localOk && cloudOk) stabilityWinner = 'TIE';
    else if (localOk) stabilityWinner = 'LOCAL';
    else if (cloudOk) stabilityWinner = 'CLOUD';
  }

  const speedWinner = summary?.speedWinner ?? (
    isFull && localTotal != null && cloudTotal != null
      ? (localTotal < cloudTotal ? 'LOCAL' : localTotal > cloudTotal ? 'CLOUD' : 'TIE')
      : '—'
  );

  const cloudHeader = isFull
    ? '<th class="llm-compare-stats__col-cloud">☁️ CLOUD</th><th class="llm-compare-stats__col-verdict">Итог</th>'
    : '';
  const cloudColspan = isFull ? 4 : 2;

  const row = (metric, localVal, cloudVal, verdict, localClass = '', cloudClass = '') => {
    const cloudCells = isFull
      ? `<td class="${cloudClass}">${cloudVal}</td><td class="llm-compare-stats__col-verdict">${verdict}</td>`
      : '';
    return `<tr>
      <th scope="row">${metric}</th>
      <td class="llm-compare-stats__col-local ${localClass}">${localVal}</td>
      ${cloudCells}
    </tr>`;
  };

  const embedNote = isFull && (
    local?.retrievalMeta?.embeddingSource === 'KEYWORD_FALLBACK'
    || cloud?.retrievalMeta?.embeddingSource === 'KEYWORD_FALLBACK'
  )
    ? '<p class="llm-compare-stats__note">⚠️ CLOUD embedding: keyword-only (OpenRouter недоступен)</p>'
    : '';

  target.innerHTML = `
    ${embedNote}
    <table class="compare-table llm-compare-stats">
      <thead>
        <tr>
          <th>Метрика</th>
          <th class="llm-compare-stats__col-local">🦙 LOCAL</th>
          ${cloudHeader}
        </tr>
      </thead>
      <tbody>
        <tr class="llm-compare-stats__group">
          <td colspan="${cloudColspan}">⚡ Скорость</td>
        </tr>
        ${row(
          'Retrieval',
          formatMs(local?.retrievalDurationMs),
          isFull ? formatMs(cloud?.retrievalDurationMs) : '',
          '—',
        )}
        ${row(
          'Генерация',
          formatMs(local?.generationDurationMs),
          isFull ? formatMs(cloud?.generationDurationMs) : '',
          winnerLabel('LOCAL', summary?.speedWinner ?? speedWinner),
          (summary?.speedWinner ?? speedWinner) === 'LOCAL' ? 'llm-compare-stats__winner' : '',
          (summary?.speedWinner ?? speedWinner) === 'CLOUD' ? 'llm-compare-stats__winner' : '',
        )}
        ${row(
          'Итого (retrieval + gen)',
          formatMs(localTotal),
          isFull ? formatMs(cloudTotal) : '',
          winnerLabel('LOCAL', speedWinner) !== '—'
            ? winnerLabel('LOCAL', speedWinner)
            : winnerLabel('CLOUD', speedWinner),
          speedWinner === 'LOCAL' ? 'llm-compare-stats__winner' : '',
          speedWinner === 'CLOUD' ? 'llm-compare-stats__winner' : '',
        )}
        ${row(
          'Embedding',
          escapeHtml(local?.retrievalMeta?.embeddingSource ?? 'OLLAMA'),
          isFull ? escapeHtml(cloud?.retrievalMeta?.embeddingSource ?? 'OPENROUTER') : '',
          '—',
        )}

        <tr class="llm-compare-stats__group">
          <td colspan="${cloudColspan}">📚 Качество</td>
        </tr>
        ${row(
          'Confidence',
          escapeHtml(local?.confidence ?? '—'),
          isFull ? escapeHtml(cloud?.confidence ?? '—') : '',
          winnerLabel('LOCAL', qualityWinner) !== '—'
            ? winnerLabel('LOCAL', qualityWinner)
            : winnerLabel('CLOUD', qualityWinner),
        )}
        ${row(
          'Чанков в контексте',
          String(localChunks),
          isFull ? String(cloudChunks) : '',
          localChunks === cloudChunks ? '≈ одинаково' : (localChunks > cloudChunks ? '🦙 LOCAL' : '☁️ CLOUD'),
        )}
        ${row(
          'Источники',
          escapeHtml(formatSources(local)),
          isFull ? escapeHtml(formatSources(cloud)) : '',
          winnerLabel('LOCAL', qualityWinner) !== '—'
            ? winnerLabel('LOCAL', qualityWinner)
            : winnerLabel('CLOUD', qualityWinner),
          qualityWinner === 'LOCAL' ? 'llm-compare-stats__winner' : '',
          qualityWinner === 'CLOUD' ? 'llm-compare-stats__winner' : '',
        )}
        ${row(
          'Цитаты',
          String(localQuotes),
          isFull ? String(cloudQuotes) : '',
          localQuotes === cloudQuotes ? '≈ одинаково' : (localQuotes > cloudQuotes ? '🦙 LOCAL' : '☁️ CLOUD'),
        )}
        ${row(
          'Режим retrieval',
          escapeHtml(local?.mode ?? '—'),
          isFull ? escapeHtml(cloud?.mode ?? '—') : '',
          '—',
        )}
        ${isFull && summary?.qualityNote
          ? `<tr><th scope="row">Оценка</th><td colspan="3" class="llm-compare-stats__note-cell">${escapeHtml(summary.qualityNote)}</td></tr>`
          : ''}

        <tr class="llm-compare-stats__group">
          <td colspan="${cloudColspan}">🛡 Стабильность</td>
        </tr>
        ${row(
          'Статус генерации',
          statusBadge(local?.generationSuccess, local?.generationError),
          isFull ? statusBadge(cloud?.generationSuccess, cloud?.generationError) : '',
          winnerLabel('LOCAL', stabilityWinner) !== '—'
            ? winnerLabel('LOCAL', stabilityWinner)
            : (stabilityWinner === 'TIE' ? '✓ обе OK' : winnerLabel('CLOUD', stabilityWinner)),
        )}
        ${row(
          'Модель',
          escapeHtml(local?.llmModel ?? '—'),
          isFull ? escapeHtml(cloud?.llmModel ?? '—') : '',
          '—',
        )}
        ${row(
          'Токенов',
          local?.tokenCount ?? '—',
          isFull ? (cloud?.tokenCount ?? '—') : '',
          '—',
        )}
        ${isFull && summary?.stabilityNote
          ? `<tr><th scope="row">Оценка</th><td colspan="3" class="llm-compare-stats__note-cell">${escapeHtml(summary.stabilityNote)}</td></tr>`
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

function showScenarioPending(scenarioId, label) {
  const container = demoScenariosEl.querySelector(`[data-result-for="${scenarioId}"]`);
  if (!container) return;
  container.classList.remove('hidden');
  container.innerHTML = `<p class="rag-panel__hint">⏳ ${escapeHtml(label)}</p>`;
  container.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function countSourceMatches(scenario, response) {
  if (!scenario.expectedSources?.length) {
    return String(response?.confidence || '').toUpperCase() === 'UNKNOWN' ? 1 : 0;
  }
  let hits = 0;
  const answerLower = String(response?.answer || '').toLowerCase();
  for (const expected of scenario.expectedSources) {
    const needle = expected.toLowerCase();
    if (answerLower.includes(needle)) {
      hits += 1;
      continue;
    }
    for (const source of response?.sources || []) {
      const section = String(source.section || '').toLowerCase();
      if (section.includes(needle)) {
        hits += 1;
        break;
      }
    }
  }
  return hits;
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
        stabilityVerdict: '',
        localSpeedWins: 0,
        cloudSpeedWins: 0,
      },
    };
  }

  let localMsSum = 0;
  let cloudMsSum = 0;
  let localWins = 0;
  let cloudWins = 0;
  let localSuccess = 0;
  let cloudSuccess = 0;
  let localSourceHits = 0;
  let cloudSourceHits = 0;

  for (const result of results) {
    const compare = result.compare;
    const local = compare.localResponse;
    const cloud = compare.cloudResponse;
    const localMs = local?.generationDurationMs ?? 0;
    const cloudMs = cloud?.generationDurationMs ?? 0;
    localMsSum += localMs;
    cloudMsSum += cloudMs;

    if (local?.generationSuccess !== false) localSuccess += 1;
    if (cloud?.generationSuccess !== false) cloudSuccess += 1;

    if (compare.summary?.speedWinner === 'LOCAL') localWins += 1;
    else if (compare.summary?.speedWinner === 'CLOUD') cloudWins += 1;

    localSourceHits += countSourceMatches(result.scenario, local);
    cloudSourceHits += countSourceMatches(result.scenario, cloud);
  }

  const avgLocal = Math.round(localMsSum / count);
  const avgCloud = Math.round(cloudMsSum / count);
  const speedVerdict = avgLocal < avgCloud
    ? `LOCAL быстрее в среднем (${avgLocal} vs ${avgCloud} ms)`
    : `CLOUD быстрее в среднем (${avgCloud} vs ${avgLocal} ms)`;
  const qualityVerdict = localSourceHits >= cloudSourceHits
    ? `LOCAL: ${localSourceHits} совпадений источников, CLOUD: ${cloudSourceHits}`
    : `CLOUD: ${cloudSourceHits} совпадений источников, LOCAL: ${localSourceHits}`;
  const stabilityVerdict = `LOCAL ${localSuccess}/${count} успешных, CLOUD ${cloudSuccess}/${count} успешных.`;

  return {
    scenarioCount: count,
    totalDurationMs,
    summary: { speedVerdict, qualityVerdict, stabilityVerdict, localSpeedWins: localWins, cloudSpeedWins: cloudWins },
  };
}

function renderScenarioResult(scenarioId, result) {
  const container = demoScenariosEl.querySelector(`[data-result-for="${scenarioId}"]`);
  if (!container) return;

  const compare = result.compare;
  container.classList.remove('hidden');
  container.innerHTML = `
    <div class="local-llm-result__stats"></div>
    <p class="local-llm-result__label">LOCAL</p>
    <pre class="local-llm-result__block local-llm-result__block--answer">${escapeHtml(compare.localResponse?.answer ?? '—')}</pre>
    <p class="local-llm-result__label">CLOUD</p>
    <pre class="local-llm-result__block">${escapeHtml(compare.cloudResponse?.answer ?? '—')}</pre>`;
  renderCompareStats(compare, {
    mode: 'full',
    target: container.querySelector('.local-llm-result__stats'),
  });
}

function renderDemoRunSummary(data) {
  const s = data.summary;
  demoSummaryEl.classList.remove('hidden');
  demoSummaryEl.innerHTML = `
    <p><strong>Итог сценария</strong> (${data.scenarioCount} шагов, ${data.totalDurationMs} ms)</p>
    <p>${escapeHtml(s.speedVerdict)}</p>
    <p>${escapeHtml(s.qualityVerdict)}</p>
    <p>${escapeHtml(s.stabilityVerdict)}</p>
    <p>LOCAL побед по скорости: ${s.localSpeedWins}, CLOUD: ${s.cloudSpeedWins}</p>`;
}

function renderEvalSummary(data) {
  const local = data.localSummary;
  const cloud = data.cloudSummary;
  evalSummary.innerHTML = `
    <p><strong>LOCAL</strong> (${escapeHtml(local.provider.model)}):
      avg ${local.avgGenerationMs} ms, успех ${local.successCount}/${data.questionCount}.
      ${escapeHtml(local.qualityAssessment)}</p>
    <p><strong>CLOUD</strong> (${escapeHtml(cloud.provider.model)}):
      avg ${cloud.avgGenerationMs} ms, успех ${cloud.successCount}/${data.questionCount}.
      ${escapeHtml(cloud.qualityAssessment)}</p>`;
}

function renderEvalTable(results) {
  evalTableBody.innerHTML = '';
  if (!results?.length) {
    evalTableBody.innerHTML = '<tr><td colspan="6" class="eval-table__empty">Нет данных</td></tr>';
    return;
  }
  for (const row of results) {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${escapeHtml(row.question.id)}</td>
      <td class="eval-table__answer">${escapeHtml(row.question.question)}</td>
      <td>${row.localResponse?.generationDurationMs ?? '—'}</td>
      <td>${row.cloudResponse?.generationDurationMs ?? '—'}</td>
      <td class="eval-table__matched">${escapeHtml((row.localMatchedSources || []).join(', ') || '—')}</td>
      <td class="eval-table__matched">${escapeHtml((row.cloudMatchedSources || []).join(', ') || '—')}</td>`;
    evalTableBody.appendChild(tr);
  }
}

async function loadDemo() {
  const demo = await apiFetch('/api/rag/local/demo');
  demoScenarios = demo.scenarios || [];
  indexMeta.textContent = `LOCAL: ${demo.localIndexChunkCount} чанков · ${demo.localEmbeddingModel} · ${demo.localIndexDbPath}`;
  cloudIndexMeta.textContent = `CLOUD: ${demo.cloudIndexChunkCount} чанков · ${demo.cloudEmbeddingModel} · ${demo.cloudIndexDbPath}`;
  if (!demo.localIndexReady) {
    indexStatusEl.textContent = `Локальный индекс строится при старте backend (Ollama ${demo.localEmbeddingModel}). Подождите или перезапустите backend.`;
  } else {
    indexStatusEl.textContent = '';
  }
  renderDemoScenarios(demoScenarios);

  try {
    const llmStatus = await apiFetch('/api/local-llm/status');
    ollamaReady = llmStatus.online && llmStatus.modelAvailable;
    statusDot.className = `status-dot ${ollamaReady ? 'status-dot--online' : 'status-dot--offline'}`;
    statusMessage.textContent = `${demo.localLlmStatus} · chat ${demo.localChatModel} · embed ${demo.localEmbeddingModel}`;
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
  showScenarioPending(scenarioId, 'Выполняется retrieval + LOCAL + CLOUD…');

  try {
    const result = await apiFetch(`/api/rag/local/demo/run/${scenarioId}`, { method: 'POST' });
    renderScenarioResult(scenarioId, result);
    markScenarioState(scenarioId, 'done');
    demoStatusEl.textContent = `Шаг ${scenarioId} завершён.`;
  } catch (error) {
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
    demoStatusEl.textContent = 'Ollama недоступен — локальная часть может не выполниться.';
  }

  setBusy(true, '');
  demoSummaryEl.classList.add('hidden');

  demoScenarios.forEach((scenario) => {
    markScenarioState(scenario.id, null);
    const container = demoScenariosEl.querySelector(`[data-result-for="${scenario.id}"]`);
    if (container) {
      container.classList.add('hidden');
      container.innerHTML = '';
    }
  });

  const results = [];
  const startedAt = Date.now();
  const total = demoScenarios.length;

  for (let index = 0; index < total; index += 1) {
    const scenario = demoScenarios[index];
    const stepNum = index + 1;

    demoStatusEl.textContent = `Шаг ${stepNum}/${total}: ${scenario.title}…`;
    questionInput.value = scenario.question;
    markScenarioState(scenario.id, 'running');
    showScenarioPending(scenario.id, `Шаг ${stepNum}/${total} — retrieval + LOCAL + CLOUD…`);

    try {
      const result = await apiFetch(`/api/rag/local/demo/run/${scenario.id}`, { method: 'POST' });
      results.push(result);
      renderScenarioResult(scenario.id, result);
      markScenarioState(scenario.id, 'done');
      demoStatusEl.textContent = `Шаг ${stepNum}/${total} готов${stepNum < total ? ' — следующий…' : ''}`;
    } catch (error) {
      markScenarioState(scenario.id, 'error');
      const container = demoScenariosEl.querySelector(`[data-result-for="${scenario.id}"]`);
      if (container) {
        container.classList.remove('hidden');
        container.innerHTML = `<p class="local-llm-scenario__error">${escapeHtml(error.message)}</p>`;
      }
      demoStatusEl.textContent = `Ошибка на шаге ${stepNum}: ${error.message}`;
    }
  }

  const totalDurationMs = Date.now() - startedAt;
  if (results.length > 0) {
    renderDemoRunSummary(buildDemoSummaryFromResults(results, totalDurationMs));
  }
  demoStatusEl.textContent = results.length === total
    ? `Сценарий завершён: ${results.length}/${total} шагов за ${Math.round(totalDurationMs / 1000)} с.`
    : `Завершено с ошибками: ${results.length}/${total} шагов за ${Math.round(totalDurationMs / 1000)} с.`;

  setBusy(false);
}

async function runCompare() {
  const question = questionInput.value.trim();
  if (!question) {
    queryStatus.textContent = 'Введите вопрос.';
    return;
  }
  setBusy(true, 'Retrieval + генерация LOCAL и CLOUD…');
  try {
    const data = await apiFetch('/api/rag/query/llm/compare', {
      method: 'POST',
      body: JSON.stringify({ question, strategy: 'STRUCTURE', useRag: true }),
    });
    showCompare(data);
    queryStatus.textContent = 'Готово.';
  } catch (error) {
    queryStatus.textContent = error.message;
  } finally {
    setBusy(false);
  }
}

async function runLocalOnly() {
  const question = questionInput.value.trim();
  if (!question) {
    queryStatus.textContent = 'Введите вопрос.';
    return;
  }
  if (!ollamaReady) {
    queryStatus.textContent = 'Ollama недоступен.';
    return;
  }
  setBusy(true, 'Локальный RAG…');
  try {
    const response = await apiFetch('/api/rag/query', {
      method: 'POST',
      body: JSON.stringify({
        question,
        useRag: true,
        strategy: 'STRUCTURE',
        mode: 'FILTERED',
        llmProvider: 'LOCAL',
      }),
    });
    showLocalOnly(response);
    queryStatus.textContent = 'Готово.';
  } catch (error) {
    queryStatus.textContent = error.message;
  } finally {
    setBusy(false);
  }
}

async function runEval() {
  setBusy(true, 'Eval: 10 вопросов…');
  evalSection.classList.remove('hidden');
  try {
    const data = await apiFetch('/api/rag/eval/llm/compare', {
      method: 'POST',
      body: JSON.stringify({ strategy: 'STRUCTURE' }),
    });
    renderEvalSummary(data);
    renderEvalTable(data.results);
    queryStatus.textContent = `Eval: ${data.questionCount} вопросов.`;
  } catch (error) {
    queryStatus.textContent = error.message;
  } finally {
    setBusy(false);
  }
}

compareBtn.addEventListener('click', runCompare);
localOnlyBtn.addEventListener('click', runLocalOnly);
evalBtn.addEventListener('click', runEval);
demoRunAllBtn.addEventListener('click', runDemoAll);
async function init() {
  demoScenariosEl.addEventListener('click', onDemoScenarioClick);
  setBusy(true, 'Загрузка…');
  try {
    await loadDemo();
  } catch (error) {
    statusMessage.textContent = error.message;
  } finally {
    setBusy(false);
  }
}

init();
