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

function fmtNum(n, digits = 0) {
  if (n == null || Number.isNaN(n)) return '—';
  return digits > 0 ? Number(n).toFixed(digits) : String(Math.round(n));
}

function fmtDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('ru-RU');
  } catch {
    return iso;
  }
}

function setStatus(text, isError = false) {
  const el = document.getElementById('rag-action-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('demo-status--error', isError);
}

function renderList(id, items) {
  const el = document.getElementById(id);
  if (!el) return;
  el.innerHTML = (items || [])
    .map((item) => `<li>${escapeHtml(item)}</li>`)
    .join('');
}

function renderSteps(id, items) {
  const el = document.getElementById(id);
  if (!el) return;
  el.innerHTML = (items || [])
    .map((item) => `<li>${escapeHtml(item)}</li>`)
    .join('');
}

function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function renderCorpus(data) {
  const el = document.getElementById('rag-corpus');
  if (!el) return;
  el.innerHTML = `
    <div><dt>Файл</dt><dd>${escapeHtml(data.corpusFile)}</dd></div>
    <div><dt>Объём</dt><dd>~${data.corpusEstimatedPages} стр. · ${fmtNum(data.corpusTotalChars)} символов</dd></div>
    <div><dt>База индекса</dt><dd><code>${escapeHtml(data.indexDbPath)}</code></dd></div>
  `;
}

function renderCompareTable(fixed, structure) {
  const tbody = document.querySelector('#rag-compare-table tbody');
  if (!tbody) return;

  const rows = [
    ['Стратегия', fixed?.strategyLabel, structure?.strategyLabel],
    ['Описание', fixed?.strategyDescription, structure?.strategyDescription],
    ['Чанков в индексе', fmtNum(fixed?.chunkCount), fmtNum(structure?.chunkCount)],
    ['Средний размер (симв.)', fmtNum(fixed?.avgChunkSize, 0), fmtNum(structure?.avgChunkSize, 0)],
    ['Мин / макс (симв.)', `${fmtNum(fixed?.minChunkSize)} / ${fmtNum(fixed?.maxChunkSize)}`, `${fmtNum(structure?.minChunkSize)} / ${fmtNum(structure?.maxChunkSize)}`],
    ['Размерность embedding', fmtNum(fixed?.embeddingDimensions), fmtNum(structure?.embeddingDimensions)],
    ['Последняя индексация', fmtDate(fixed?.indexedAt), fmtDate(structure?.indexedAt)],
  ];

  tbody.innerHTML = rows
    .map(([label, a, b]) => `
      <tr>
        <th scope="row">${escapeHtml(label)}</th>
        <td>${escapeHtml(a ?? '—')}</td>
        <td>${escapeHtml(b ?? '—')}</td>
      </tr>`)
    .join('');
}

function pickStrategy(data, key) {
  if (!data) return null;
  if (data[key] != null) return data[key];
  const snake = key.replace(/([A-Z])/g, '_$1').toLowerCase();
  return data[snake.replace(/^_/, '')] ?? null;
}

function renderSampleTable(title, strategy, cssClass) {
  const chunks = strategy?.sampleChunks || strategy?.sample_chunks || [];
  const chunkCount = strategy?.chunkCount ?? strategy?.chunk_count ?? 0;

  if (chunkCount === 0 && chunks.length === 0) {
    return `
      <article class="sample-col">
        <h3 class="sample-col__title ${cssClass}">${escapeHtml(title)}</h3>
        <p class="sample-col__empty">Нет чанков — нажмите Compare</p>
      </article>`;
  }

  if (chunks.length === 0) {
    return `
      <article class="sample-col">
        <h3 class="sample-col__title ${cssClass}">${escapeHtml(title)} · ${chunkCount} чанков</h3>
        <p class="sample-col__empty">Чанки в индексе есть, но примеры не загрузились — нажмите «Обновить данные»</p>
      </article>`;
  }

  const rows = chunks.map((c) => `
    <tr>
      <td>${escapeHtml(c.position || '—')}</td>
      <td>${c.chunkIndex ?? c.chunk_index ?? '—'} / ${c.totalChunks ?? c.total_chunks ?? chunkCount}</td>
      <td><code>${escapeHtml(c.chunkId ?? c.chunk_id ?? '—')}</code></td>
      <td>${escapeHtml(c.section || '—')}</td>
      <td>${c.charStart ?? c.char_start ?? '—'}–${c.charEnd ?? c.char_end ?? '—'}</td>
      <td>${c.tokenCount ?? c.token_count ?? '—'}</td>
      <td class="sample-table__preview">${escapeHtml(c.preview || '')}…</td>
    </tr>
  `).join('');

  return `
    <article class="sample-col">
      <h3 class="sample-col__title ${cssClass}">${escapeHtml(title)} · ${chunkCount} чанков</h3>
      <div class="sample-table-wrap">
        <table class="sample-table">
          <thead>
            <tr>
              <th>Позиция</th>
              <th># / всего</th>
              <th>chunk_id</th>
              <th>section</th>
              <th>char</th>
              <th>tokens</th>
              <th>preview</th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </article>`;
}

function renderSamples(data) {
  const el = document.getElementById('rag-samples');
  if (!el) return;

  const fixed = pickStrategy(data, 'fixedSize');
  const structure = pickStrategy(data, 'structure');

  el.innerHTML = [
    renderSampleTable('FIXED_SIZE', fixed, 'sample-col__title--fixed'),
    renderSampleTable('STRUCTURE', structure, 'sample-col__title--structure'),
  ].join('');
}

function renderDemo(data) {
  const dayLabel = document.getElementById('rag-day-label');
  if (dayLabel) dayLabel.textContent = data.dayLabel;

  renderCorpus(data);
  renderList('rag-tech', data.technologies);
  renderSteps('rag-pipeline', data.pipelineSteps);

  const fixed = pickStrategy(data, 'fixedSize');
  const structure = pickStrategy(data, 'structure');
  renderCompareTable(fixed, structure);
  renderSamples(data);

  const indexed = (fixed?.chunkCount || 0) + (structure?.chunkCount || 0);
  if (indexed > 0 && !document.getElementById('rag-action-status')?.textContent) {
    setStatus(`Индекс готов: FIXED ${fixed.chunkCount} · STRUCTURE ${structure.chunkCount} чанков`);
  }
}

async function loadDemo() {
  try {
    const res = await apiFetch('/api/rag/index/demo');
    if (!res.ok) throw new Error(await parseError(res));
    renderDemo(await res.json());
  } catch (error) {
    setStatus(`Не удалось загрузить демо: ${error.message}`, true);
  }
}

async function ragIndex(strategy) {
  setStatus(`Индексация ${strategy}… (1–2 мин)`);
  try {
    const res = await apiFetch('/api/rag/index', {
      method: 'POST',
      body: JSON.stringify({ strategy }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    setStatus(`Готово: ${strategy} — ${data.chunkCount} чанков`);
    await loadDemo();
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function ragCompareChunking() {
  setStatus('Compare: индексация FIXED + STRUCTURE… (1–2 мин)');
  try {
    const res = await apiFetch('/api/rag/index/compare', { method: 'POST' });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    setStatus(`Compare завершён: FIXED ${data.fixedSize.chunkCount} · STRUCTURE ${data.structure.chunkCount} чанков`);
    await loadDemo();
  } catch (error) {
    setStatus(error.message, true);
  }
}

document.getElementById('rag-index-fixed')?.addEventListener('click', () => ragIndex('FIXED_SIZE'));
document.getElementById('rag-index-structure')?.addEventListener('click', () => ragIndex('STRUCTURE'));
document.getElementById('rag-compare-chunking')?.addEventListener('click', ragCompareChunking);
document.getElementById('rag-refresh-demo')?.addEventListener('click', () => {
  setStatus('Обновление…');
  loadDemo().then(() => setStatus('Данные обновлены из БД'));
});

function renderQueryCompare(data) {
  const el = document.getElementById('rag-query-compare');
  if (!el) return;
  el.classList.remove('hidden');
  const chunks = (data.withRag?.chunksUsed || [])
    .map((c) => `<li>[${(c.score ?? 0).toFixed?.(3) ?? c.score}] ${escapeHtml(c.section)}: ${escapeHtml(c.preview)}</li>`)
    .join('');
  el.innerHTML = `
    <div class="query-compare__col query-compare__col--plain">
      <p class="query-compare__title">Без RAG</p>
      <p class="query-compare__text">${escapeHtml(data.withoutRag?.answer || '—')}</p>
    </div>
    <div class="query-compare__col query-compare__col--rag">
      <p class="query-compare__title">С RAG</p>
      <p class="query-compare__text">${escapeHtml(data.withRag?.answer || '—')}</p>
      ${chunks ? `<ul class="query-compare__chunks">${chunks}</ul>` : ''}
    </div>`;
}

async function ragQueryCompare() {
  const question = document.getElementById('rag-question')?.value?.trim();
  if (!question) return;
  setStatus('Сравнение с/без RAG…');
  try {
    const res = await apiFetch('/api/rag/query/compare', {
      method: 'POST',
      body: JSON.stringify({ question, strategy: 'STRUCTURE', topK: 5 }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    renderQueryCompare(await res.json());
    setStatus('Сравнение готово');
  } catch (error) {
    setStatus(error.message, true);
  }
}

function setEvalStatus(text, isError = false) {
  const el = document.getElementById('rag-eval-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('demo-status--error', isError);
}

function renderEvalQuestions(questions, resultsById = {}) {
  const tbody = document.getElementById('rag-eval-body');
  if (!tbody) return;
  if (!questions?.length) {
    tbody.innerHTML = '<tr><td colspan="7" class="eval-table__empty">Вопросы не загружены</td></tr>';
    return;
  }
  tbody.innerHTML = questions.map((q) => {
    const result = resultsById[q.id];
    const without = result?.withoutRag?.answer;
    const withR = result?.withRag?.answer;
    const matched = (result?.sourcesMatched || []).join(', ');
    return `
      <tr>
        <td>${escapeHtml(q.id)}</td>
        <td>${escapeHtml(q.section)}</td>
        <td>${escapeHtml(q.question)}</td>
        <td>${escapeHtml(q.expectedAnswer)}</td>
        <td>${(q.expectedSources || []).map((s) => escapeHtml(s)).join(', ')}</td>
        <td class="eval-table__answer">${without ? escapeHtml(without) : '—'}</td>
        <td class="eval-table__answer">${withR ? escapeHtml(withR) : '—'}${matched ? `<div class="eval-table__matched">✓ ${escapeHtml(matched)}</div>` : ''}</td>
      </tr>`;
  }).join('');
}

async function loadEvalQuestions() {
  setEvalStatus('Загрузка вопросов…');
  try {
    const res = await apiFetch('/api/rag/eval/questions');
    if (!res.ok) throw new Error(await parseError(res));
    const questions = await res.json();
    renderEvalQuestions(questions);
    setEvalStatus(`Загружено ${questions.length} контрольных вопросов`);
  } catch (error) {
    setEvalStatus(error.message, true);
  }
}

async function runEval() {
  setEvalStatus('Eval: 10 вопросов × 2 режима… (3–5 мин)');
  try {
    const res = await apiFetch('/api/rag/eval/run', {
      method: 'POST',
      body: JSON.stringify({ strategy: 'STRUCTURE', topK: 5 }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    const byId = {};
    (data.results || []).forEach((r) => { byId[r.question.id] = r; });
    renderEvalQuestions(data.results.map((r) => r.question), byId);
    setEvalStatus(`Eval: ${data.ragWithSources}/${data.totalQuestions} ответов с RAG содержат ожидаемые источники`);
  } catch (error) {
    setEvalStatus(error.message, true);
  }
}

document.getElementById('rag-query-compare-btn')?.addEventListener('click', ragQueryCompare);
document.getElementById('rag-question')?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') ragQueryCompare();
});
document.getElementById('rag-eval-load-btn')?.addEventListener('click', loadEvalQuestions);
document.getElementById('rag-eval-run-btn')?.addEventListener('click', runEval);

loadDemo();
loadEvalQuestions();
