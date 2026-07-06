const SESSION_KEY = 'rag-chat-session-id';

async function apiFetch(url, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };
  return fetch(url, { ...options, headers });
}

async function parseError(response) {
  try {
    const data = await response.json();
    return data.error || data.message || `Ошибка ${response.status}`;
  } catch {
    const text = await response.text();
    return text || `Ошибка ${response.status}`;
  }
}

function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function getSessionId() {
  return localStorage.getItem(SESSION_KEY) || '';
}

function setSessionId(id) {
  if (id) {
    localStorage.setItem(SESSION_KEY, id);
  } else {
    localStorage.removeItem(SESSION_KEY);
  }
  updateSessionLabel();
}

function updateSessionLabel() {
  const el = document.getElementById('rag-chat-session-label');
  if (!el) return;
  const id = getSessionId();
  el.textContent = id ? `session: ${id.slice(0, 8)}…` : 'новая сессия';
}

function setChatStatus(text, isError = false) {
  const el = document.getElementById('rag-chat-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('demo-status--error', isError);
}

function confidenceClass(confidence) {
  const value = String(confidence || '').toUpperCase();
  if (value === 'HIGH') return 'confidence-badge confidence-badge--high';
  if (value === 'MEDIUM') return 'confidence-badge confidence-badge--medium';
  if (value === 'LOW') return 'confidence-badge confidence-badge--low';
  if (value === 'UNKNOWN') return 'confidence-badge confidence-badge--unknown';
  return 'confidence-badge';
}

function extractHighlightTerms(text) {
  return String(text || '')
    .toLowerCase()
    .match(/[\p{Script=Cyrillic}]{4,}/gu)
    ?.filter((w) => !['что', 'такое', 'когда', 'какой', 'какие', 'какая', 'где', 'кто', 'кратко', 'назовите', 'перечислите', 'изложите', 'опишите', 'объясните', 'основы', 'православной', 'православная', 'церкви', 'церковь'].includes(w))
    ?? [];
}

function highlightQuoteText(text, terms) {
  let html = escapeHtml(text || '—');
  for (const term of terms) {
    if (term.length < 4) continue;
    const re = new RegExp(`(${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'giu');
    html = html.replace(re, '<mark class="quote-mark">$1</mark>');
  }
  return html;
}

function renderSources(sources) {
  if (!sources?.length) {
    return '<p class="citation-block__empty">Источники отсутствуют</p>';
  }
  return `<ol class="citation-sources citation-sources--compact">${sources.map((s, i) => `
    <li class="citation-sources__item">
      <span class="citation-sources__rank">#${i + 1}</span>
      <div>
        <p class="citation-sources__section">${escapeHtml(s.section || '—')}</p>
        <p class="citation-sources__meta">
          ${escapeHtml(s.source || '—')}
          · <code>${escapeHtml(s.chunkId || '—')}</code>
        </p>
      </div>
    </li>`).join('')}</ol>`;
}

function renderQuotes(quotes, question) {
  if (!quotes?.length) {
    return '<p class="citation-block__empty">Цитаты отсутствуют</p>';
  }
  const terms = extractHighlightTerms(question);
  return `<div class="citation-quotes citation-quotes--compact">${quotes.map((q) => `
    <article class="quote-card quote-card--compact">
      <header class="quote-card__header">
        <span class="quote-card__rank">#${q.rank ?? '—'}</span>
        <span class="quote-card__scores">sem ${Number(q.semanticScore ?? 0).toFixed(3)}</span>
      </header>
      <blockquote class="quote-card__text">${highlightQuoteText(q.text, terms)}</blockquote>
    </article>`).join('')}</div>`;
}

function renderMessage(msg, lastUserQuestion) {
  const isUser = msg.role === 'user';
  const roleLabel = isUser ? 'Вы' : 'Ассистент';
  let metaHtml = '';
  if (!isUser && msg.confidence) {
    metaHtml = `<span class="${confidenceClass(msg.confidence)}">${escapeHtml(msg.confidence)}</span>`;
  }
  let citationsHtml = '';
  if (!isUser && (msg.sources?.length || msg.quotes?.length)) {
    citationsHtml = `
      <div class="chat-msg__citations">
        <details class="chat-citation-details">
          <summary>Источники (${msg.sources?.length ?? 0}) · Цитаты (${msg.quotes?.length ?? 0})</summary>
          <div class="chat-citation-details__body">
            ${renderSources(msg.sources)}
            ${renderQuotes(msg.quotes, lastUserQuestion)}
          </div>
        </details>
      </div>`;
  }
  return `
    <article class="chat-msg chat-msg--${msg.role}">
      <header class="chat-msg__header">
        <span class="chat-msg__role">${roleLabel}</span>
        ${metaHtml}
      </header>
      <p class="chat-msg__text">${escapeHtml(msg.content || '')}</p>
      ${citationsHtml}
    </article>`;
}

function renderMessages(history) {
  const container = document.getElementById('rag-chat-messages');
  if (!container) return;
  if (!history?.length) {
    container.innerHTML = '<p class="chat-empty">Начните диалог или выберите подсказку из сценария справа.</p>';
    return;
  }
  let lastUserQuestion = '';
  container.innerHTML = history.map((msg) => {
    if (msg.role === 'user') {
      lastUserQuestion = msg.content;
    }
    return renderMessage(msg, lastUserQuestion);
  }).join('');
  container.scrollTop = container.scrollHeight;
}

function renderMemory(memory) {
  const goalEl = document.getElementById('memory-goal');
  const clarEl = document.getElementById('memory-clarifications');
  const termsEl = document.getElementById('memory-terms');
  if (!goalEl || !clarEl || !termsEl) return;

  goalEl.textContent = memory?.dialogGoal?.trim() || '—';

  const clarifications = memory?.clarifications || [];
  clarEl.innerHTML = clarifications.length
    ? `<ul class="memory-list">${clarifications.map((c) => `<li>${escapeHtml(c)}</li>`).join('')}</ul>`
    : '<span class="memory-empty">—</span>';

  const terms = memory?.fixedTerms || [];
  termsEl.innerHTML = terms.length
    ? `<ul class="memory-list memory-list--terms">${terms.map((t) => `<li>${escapeHtml(t)}</li>`).join('')}</ul>`
    : '<span class="memory-empty">—</span>';
}

async function loadHistoryAndMemory() {
  const sessionId = getSessionId();
  if (!sessionId) {
    renderMessages([]);
    renderMemory({});
    return;
  }
  try {
    const [histRes, memRes] = await Promise.all([
      apiFetch(`/api/rag/chat/history?sessionId=${encodeURIComponent(sessionId)}`),
      apiFetch(`/api/rag/chat/memory?sessionId=${encodeURIComponent(sessionId)}`),
    ]);
    if (histRes.ok) {
      renderMessages(await histRes.json());
    }
    if (memRes.ok) {
      renderMemory(await memRes.json());
    }
  } catch {
    /* ignore stale session */
  }
}

async function sendMessage(message, options = {}) {
  const { demo = false } = options;
  const sendBtn = document.getElementById('rag-chat-send-btn');
  const input = document.getElementById('rag-chat-input');
  if (sendBtn) sendBtn.disabled = true;
  if (!demo) setChatStatus('RAG-поиск и ответ…');

  try {
    const res = await apiFetch('/api/rag/chat', {
      method: 'POST',
      body: JSON.stringify({
        sessionId: getSessionId() || null,
        message,
        strategy: 'STRUCTURE',
        topK: 5,
        mode: 'REWRITE_FILTERED',
      }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    setSessionId(data.sessionId);
    renderMessages(data.history);
    renderMemory(data.taskMemory);
    const src = data.assistantMessage?.sources?.length ?? 0;
    const qts = data.assistantMessage?.quotes?.length ?? 0;
    const status = `${data.assistantMessage?.confidence || '—'} · ${src} источн. · ${qts} цитат`;
    if (demo) {
      return { data, status };
    }
    setChatStatus(status);
    return { data, status };
  } catch (error) {
    if (!demo) setChatStatus(error.message, true);
    throw error;
  } finally {
    if (sendBtn && !isDemoRunning()) sendBtn.disabled = false;
    if (input && !isDemoRunning()) input.focus();
  }
}

async function resetChat(options = {}) {
  const { silent = false } = options;
  if (!silent) setChatStatus('Сброс диалога…');
  try {
    const res = await apiFetch('/api/rag/chat/reset', {
      method: 'POST',
      body: JSON.stringify({ sessionId: getSessionId() || null }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    setSessionId(data.sessionId);
    renderMessages([]);
    renderMemory({});
    if (!silent) {
      setChatStatus('Новый диалог начат');
      document.getElementById('rag-chat-input')?.focus();
    }
    return data;
  } catch (error) {
    if (!silent) setChatStatus(error.message, true);
    throw error;
  }
}

document.getElementById('rag-chat-form')?.addEventListener('submit', (e) => {
  e.preventDefault();
  if (isDemoRunning()) return;
  const input = document.getElementById('rag-chat-input');
  const message = input?.value?.trim();
  if (!message) return;
  input.value = '';
  sendMessage(message);
});

document.getElementById('rag-chat-input')?.addEventListener('keydown', (e) => {
  if (isDemoRunning()) return;
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    document.getElementById('rag-chat-form')?.requestSubmit();
  }
});

document.getElementById('rag-chat-reset-btn')?.addEventListener('click', () => {
  if (isDemoRunning()) return;
  resetChat();
});

const DEMO = {
  running: false,
  abortController: null,
  scenarios: [],
};

const PAUSE_BEFORE_SEND_MS = 1200;
const PAUSE_AFTER_RESPONSE_MS = 3500;
const TYPEWRITER_CHAR_MS = 22;

function isDemoRunning() {
  return DEMO.running;
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    signal?.addEventListener('abort', onAbort);
  });
}

function setDemoProgress(text) {
  const el = document.getElementById('demo-progress');
  if (el) el.textContent = text;
}

function setDemoUiActive(active) {
  document.body.classList.toggle('demo-mode', active);
  const stopBtn = document.getElementById('demo-stop-btn');
  if (stopBtn) stopBtn.hidden = !active;
  document.querySelectorAll('.demo-play-btn').forEach((btn) => {
    btn.disabled = active;
  });
  const input = document.getElementById('rag-chat-input');
  const sendBtn = document.getElementById('rag-chat-send-btn');
  const resetBtn = document.getElementById('rag-chat-reset-btn');
  if (input) input.disabled = active;
  if (sendBtn) sendBtn.disabled = active;
  if (resetBtn) resetBtn.disabled = active;
}

function highlightScenarioStep(scenarioId, stepIndex) {
  document.querySelectorAll('.scenario-hint__list li').forEach((li) => {
    li.classList.remove('scenario-step--active', 'scenario-step--done');
  });
  document.querySelectorAll(`.scenario-hint[data-scenario-id="${scenarioId}"] .scenario-hint__list li`).forEach((li, index) => {
    if (index < stepIndex) li.classList.add('scenario-step--done');
    if (index === stepIndex) li.classList.add('scenario-step--active');
  });
}

function clearScenarioHighlights() {
  document.querySelectorAll('.scenario-hint__list li').forEach((li) => {
    li.classList.remove('scenario-step--active', 'scenario-step--done');
  });
}

async function typeIntoInput(text, signal) {
  const input = document.getElementById('rag-chat-input');
  if (!input) return;
  input.value = '';
  for (const ch of text) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    input.value += ch;
    input.scrollTop = input.scrollHeight;
    await sleep(TYPEWRITER_CHAR_MS, signal);
  }
}

function stopDemo() {
  if (DEMO.abortController) {
    DEMO.abortController.abort();
  }
}

function renderScenarios(scenarios) {
  const listEl = document.getElementById('scenarios-list');
  const buttonsEl = document.getElementById('demo-play-buttons');
  if (!listEl || !buttonsEl) return;

  buttonsEl.innerHTML = scenarios.map((scenario, index) => `
    <button
      type="button"
      class="rag-panel__btn rag-panel__btn--primary demo-play-btn"
      data-scenario-id="${escapeHtml(scenario.id)}"
    >▶ Сценарий ${index + 1}</button>`).join('');

  listEl.innerHTML = scenarios.map((scenario) => `
    <details class="scenario-hint" data-scenario-id="${escapeHtml(scenario.id)}" ${scenario.id === 'S1' ? 'open' : ''}>
      <summary>${escapeHtml(scenario.title)}</summary>
      <p class="scenario-hint__desc">${escapeHtml(scenario.description || '')}</p>
      <ol class="scenario-hint__list">
        ${(scenario.messages || []).map((msg) => `<li title="Клик — вставить в поле ввода">${escapeHtml(msg)}</li>`).join('')}
      </ol>
    </details>`).join('');

  buttonsEl.querySelectorAll('.demo-play-btn').forEach((btn) => {
    btn.addEventListener('click', () => runScenario(btn.dataset.scenarioId));
  });

  listEl.querySelectorAll('.scenario-hint__list li').forEach((li) => {
    li.addEventListener('click', () => {
      if (isDemoRunning()) return;
      const input = document.getElementById('rag-chat-input');
      if (input) {
        input.value = li.textContent.trim();
        input.focus();
      }
    });
  });
}

async function loadScenarios() {
  try {
    const res = await apiFetch('/api/rag/scenarios');
    if (!res.ok) throw new Error(await parseError(res));
    DEMO.scenarios = await res.json();
    renderScenarios(DEMO.scenarios);
    setDemoProgress('Готов к записи — нажмите ▶ Сценарий');
  } catch (error) {
    setDemoProgress(`Сценарии недоступны: ${error.message}`);
  }
}

async function runScenario(scenarioId) {
  if (isDemoRunning()) return;

  const scenario = DEMO.scenarios.find((s) => s.id === scenarioId);
  if (!scenario?.messages?.length) {
    setChatStatus('Сценарий пуст или не найден', true);
    return;
  }

  DEMO.running = true;
  DEMO.abortController = new AbortController();
  const { signal } = DEMO.abortController;
  setDemoUiActive(true);

  const total = scenario.messages.length;
  const chatPanel = document.querySelector('.chat-panel');

  try {
    setDemoProgress(`Подготовка · ${scenario.title}`);
    setChatStatus('Демо · новый диалог…');
    await resetChat({ silent: true });
    clearScenarioHighlights();

    for (let i = 0; i < total; i += 1) {
      if (signal.aborted) break;

      const step = i + 1;
      highlightScenarioStep(scenarioId, i);
      setDemoProgress(`Шаг ${step}/${total} · набор текста…`);
      setChatStatus(`Демо · шаг ${step}/${total} · набор…`);

      await typeIntoInput(scenario.messages[i], signal);
      await sleep(PAUSE_BEFORE_SEND_MS, signal);

      setDemoProgress(`Шаг ${step}/${total} · RAG-ответ…`);
      setChatStatus(`Демо · шаг ${step}/${total} · RAG-поиск…`);

      const { status } = await sendMessage(scenario.messages[i], { demo: true });
      document.getElementById('rag-chat-input').value = '';

      setChatStatus(`Демо · шаг ${step}/${total} · ${status}`);
      chatPanel?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      document.getElementById('rag-chat-messages')?.scrollTo({
        top: document.getElementById('rag-chat-messages').scrollHeight,
        behavior: 'smooth',
      });

      if (step < total) {
        setDemoProgress(`Шаг ${step}/${total} · пауза перед следующим…`);
        await sleep(PAUSE_AFTER_RESPONSE_MS, signal);
      }
    }

    if (!signal.aborted) {
      highlightScenarioStep(scenarioId, total);
      setDemoProgress(`Готово · ${scenario.title}`);
      setChatStatus(`Демо завершено · ${total} шагов`);
    }
  } catch (error) {
    if (error.name === 'AbortError') {
      setDemoProgress('Остановлено');
      setChatStatus('Демо остановлено');
    } else {
      setDemoProgress('Ошибка демо');
      setChatStatus(error.message, true);
    }
  } finally {
    DEMO.running = false;
    DEMO.abortController = null;
    setDemoUiActive(false);
    if (!document.getElementById('demo-progress')?.textContent?.startsWith('Готово')) {
      setDemoProgress('Готов к записи — нажмите ▶ Сценарий');
    }
  }
}

document.getElementById('demo-stop-btn')?.addEventListener('click', stopDemo);

updateSessionLabel();
loadHistoryAndMemory();
loadScenarios();
