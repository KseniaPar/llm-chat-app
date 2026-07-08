const API_BASE = '';

async function apiFetch(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
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
    throw new Error(data?.error || `HTTP ${response.status}`);
  }
  return data;
}

function setStatus(message, isError = false) {
  const el = document.getElementById('local-llm-status');
  if (!el) return;
  el.textContent = message || '';
  el.classList.toggle('demo-status--error', isError);
}

function escapeHtml(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function renderResult(container, prompt, answer, durationMs, model) {
  container.hidden = false;
  container.innerHTML = `
    <p class="local-llm-result__meta">Модель: <code>${escapeHtml(model)}</code> · ${durationMs} ms</p>
    <p class="local-llm-result__label">Prompt</p>
    <pre class="local-llm-result__block">${escapeHtml(prompt)}</pre>
    <p class="local-llm-result__label">Ответ</p>
    <pre class="local-llm-result__block local-llm-result__block--answer">${escapeHtml(answer)}</pre>
  `;
}

function renderScenarioCard(scenario, result = null, error = null) {
  const resultHtml = error
    ? `<p class="local-llm-scenario__error">${escapeHtml(error)}</p>`
    : result
      ? `
        <p class="local-llm-result__meta">${result.durationMs} ms · ${result.evalCount} tokens</p>
        <pre class="local-llm-result__block local-llm-result__block--answer">${escapeHtml(result.answer)}</pre>
      `
      : '';

  return `
    <article class="local-llm-scenario" data-scenario-id="${scenario.id}">
      <div class="local-llm-scenario__header">
        <span class="local-llm-scenario__badge">${escapeHtml(scenario.complexity)}</span>
        <button class="rag-panel__btn run-scenario-btn" type="button" data-scenario-id="${scenario.id}">
          Запустить #${scenario.id}
        </button>
      </div>
      <pre class="local-llm-result__block">${escapeHtml(scenario.prompt)}</pre>
      <div class="local-llm-scenario__result">${resultHtml}</div>
    </article>
  `;
}

async function loadStatus() {
  const indicator = document.getElementById('status-indicator');
  const messageEl = document.getElementById('status-message');
  const metaEl = document.getElementById('status-meta');

  try {
    const status = await apiFetch('/api/local-llm/status');
    const online = status.online && status.modelAvailable;
    indicator.className = `status-dot ${online ? 'status-dot--online' : 'status-dot--offline'}`;
    messageEl.textContent = status.message;
    metaEl.textContent = status.installedModels?.length
      ? `Модели: ${status.installedModels.join(', ')}`
      : `URL: ${status.baseUrl}`;
    return status;
  } catch (error) {
    indicator.className = 'status-dot status-dot--offline';
    messageEl.textContent = error.message;
    metaEl.textContent = '';
    throw error;
  }
}

async function loadDemo() {
  const demo = await apiFetch('/api/local-llm/demo');
  const count = demo.scenarios?.length ?? 0;
  document.getElementById('demo-description').textContent = demo.description;
  document.getElementById('scenarios-title').textContent = `Тестовые запросы (${count})`;
  document.getElementById('run-all-btn').textContent = `Запустить все ${count}`;
  const listEl = document.getElementById('scenarios-list');
  listEl.innerHTML = demo.scenarios.map((scenario) => renderScenarioCard(scenario)).join('');
  listEl.querySelectorAll('.run-scenario-btn').forEach((btn) => {
    btn.addEventListener('click', () => runScenario(Number(btn.dataset.scenarioId)));
  });
  return demo;
}

async function runScenario(scenarioId) {
  const card = document.querySelector(`.local-llm-scenario[data-scenario-id="${scenarioId}"]`);
  const btn = card?.querySelector('.run-scenario-btn');
  if (btn) btn.disabled = true;
  setStatus(`Запрос #${scenarioId}…`);

  try {
    const result = await apiFetch(`/api/local-llm/demo/run/${scenarioId}`, { method: 'POST' });
    const scenario = { id: scenarioId, complexity: card?.querySelector('.local-llm-scenario__badge')?.textContent || '', prompt: result.prompt };
    card.outerHTML = renderScenarioCard(scenario, result);
    document.querySelector(`.local-llm-scenario[data-scenario-id="${scenarioId}"] .run-scenario-btn`)
      ?.addEventListener('click', () => runScenario(scenarioId));
    setStatus(`Запрос #${scenarioId} выполнен (${result.durationMs} ms).`);
  } catch (error) {
    if (card) {
      const scenarioPrompt = card.querySelector('.local-llm-result__block')?.textContent || '';
      const complexity = card.querySelector('.local-llm-scenario__badge')?.textContent || '';
      card.outerHTML = renderScenarioCard({ id: scenarioId, complexity, prompt: scenarioPrompt }, null, error.message);
      document.querySelector(`.local-llm-scenario[data-scenario-id="${scenarioId}"] .run-scenario-btn`)
        ?.addEventListener('click', () => runScenario(scenarioId));
    }
    setStatus(error.message, true);
  } finally {
    const newBtn = document.querySelector(`.local-llm-scenario[data-scenario-id="${scenarioId}"] .run-scenario-btn`);
    if (newBtn) newBtn.disabled = false;
  }
}

async function runAllScenarios() {
  const runAllBtn = document.getElementById('run-all-btn');
  const demo = await apiFetch('/api/local-llm/demo');
  const count = demo.scenarios?.length ?? 0;
  runAllBtn.disabled = true;
  setStatus(`Запуск всех ${count} запросов (может занять несколько минут на CPU)…`);

  try {
    const response = await apiFetch('/api/local-llm/demo/run', { method: 'POST' });
    const listEl = document.getElementById('scenarios-list');
    listEl.innerHTML = demo.scenarios.map((scenario) => {
      const result = response.results.find((item) => item.prompt === scenario.prompt);
      return renderScenarioCard(scenario, result);
    }).join('');
    listEl.querySelectorAll('.run-scenario-btn').forEach((btn) => {
      btn.addEventListener('click', () => runScenario(Number(btn.dataset.scenarioId)));
    });
    setStatus(`Все ${count} запросов выполнены за ${response.totalDurationMs} ms.`);
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    runAllBtn.disabled = false;
  }
}

document.getElementById('status-refresh-btn')?.addEventListener('click', async () => {
  setStatus('Обновление статуса…');
  try {
    await loadStatus();
    setStatus('Статус обновлён.');
  } catch (error) {
    setStatus(error.message, true);
  }
});

document.getElementById('run-all-btn')?.addEventListener('click', runAllScenarios);

document.getElementById('custom-chat-form')?.addEventListener('submit', async (event) => {
  event.preventDefault();
  const promptEl = document.getElementById('custom-prompt');
  const prompt = promptEl.value.trim();
  if (!prompt) return;

  const sendBtn = document.getElementById('custom-send-btn');
  sendBtn.disabled = true;
  setStatus('Ollama генерирует ответ…');

  try {
    const result = await apiFetch('/api/local-llm/chat', {
      method: 'POST',
      body: JSON.stringify({ prompt }),
    });
    renderResult(document.getElementById('custom-result'), result.prompt, result.answer, result.durationMs, result.model);
    setStatus(`Ответ получен (${result.durationMs} ms).`);
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    sendBtn.disabled = false;
  }
});

async function init() {
  try {
    await Promise.all([loadStatus(), loadDemo()]);
    setStatus('Готово. Запустите тестовые запросы или отправьте свой prompt.');
  } catch (error) {
    setStatus(error.message, true);
  }
}

init();
