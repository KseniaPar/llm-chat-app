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

const minConfidenceInput = document.getElementById('rag-min-confidence');
const minConfidenceValue = document.getElementById('rag-min-confidence-value');
minConfidenceInput?.addEventListener('input', () => {
  if (minConfidenceValue) {
    minConfidenceValue.textContent = Number(minConfidenceInput.value).toFixed(2);
  }
});

function setCitationStatus(text, isError = false) {
  const el = document.getElementById('rag-citation-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('demo-status--error', isError);
}

function setCitationEvalStatus(text, isError = false) {
  const el = document.getElementById('rag-citation-eval-status');
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

function extractHighlightTerms(question) {
  return String(question || '')
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
  return `<ol class="citation-sources">${sources.map((s, i) => `
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
  return `<div class="citation-quotes">${quotes.map((q) => `
    <article class="quote-card">
      <header class="quote-card__header">
        <span class="quote-card__rank">Цитата #${q.rank ?? '—'}</span>
        <span class="quote-card__scores">
          semantic ${Number(q.semanticScore ?? 0).toFixed(3)}
          · rank ${Number(q.relevanceScore ?? 0).toFixed(3)}
        </span>
      </header>
      <p class="quote-card__section">${escapeHtml(q.section || '—')}</p>
      <blockquote class="quote-card__text">${highlightQuoteText(q.text, terms)}</blockquote>
      <footer class="quote-card__footer">
        ${escapeHtml(q.source || '—')} · <code>${escapeHtml(q.chunkId || '—')}</code>
      </footer>
    </article>`).join('')}</div>`;
}

function renderCitationResult(data, question) {
  const el = document.getElementById('rag-citation-result');
  if (!el) return;
  el.classList.remove('hidden');
  const meta = data.retrievalMeta || {};
  const chunks = data.chunksUsed || [];
  el.innerHTML = `
    <div class="citation-result__header">
      <span class="${confidenceClass(data.confidence)}">${escapeHtml(data.confidence || '—')}</span>
      <span class="citation-result__meta">
        ${escapeHtml(data.mode || '—')}
        · ${chunks.length} фрагм.
        · max semantic ${Math.max(...chunks.map((c) => c.score ?? 0), 0).toFixed(3)}
        ${meta.rewrittenQuery ? ` · rewrite: ${escapeHtml(meta.rewrittenQuery)}` : ''}
      </span>
    </div>
    <article class="citation-answer">
      <h3 class="citation-block__title">Ответ</h3>
      <p>${escapeHtml(data.answer || '—')}</p>
    </article>
    <article class="citation-block">
      <h3 class="citation-block__title">Источники (${data.sources?.length ?? 0})</h3>
      ${renderSources(data.sources)}
    </article>
    <article class="citation-block citation-block--quotes">
      <h3 class="citation-block__title">Прямые цитаты из текста (${data.quotes?.length ?? 0})</h3>
      <p class="citation-block__hint">Дословные фрагменты вокруг ключевых слов запроса, по убыванию semantic score</p>
      ${renderQuotes(data.quotes, question)}
    </article>`;
}

async function ragCitationQuery() {
  const question = document.getElementById('rag-citation-question')?.value?.trim();
  if (!question) return;
  setCitationStatus('Поиск и извлечение цитат…');
  try {
    const res = await apiFetch('/api/rag/query', {
      method: 'POST',
      body: JSON.stringify({
        question,
        strategy: 'STRUCTURE',
        topK: 5,
        useRag: true,
        mode: 'REWRITE_FILTERED',
      }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    renderCitationResult(data, question);
    const src = data.sources?.length ?? 0;
    const qts = data.quotes?.length ?? 0;
    setCitationStatus(
      `${data.confidence || '—'} · ${src} источн. · ${qts} цитат`,
    );
  } catch (error) {
    setCitationStatus(error.message, true);
  }
}

function checkCell(ok) {
  return ok
    ? '<span class="eval-check eval-check--ok">✓</span>'
    : '<span class="eval-check eval-check--fail">✗</span>';
}

function renderCitationEvalResults(data) {
  const tbody = document.getElementById('rag-citation-eval-body');
  if (!tbody) return;
  tbody.innerHTML = (data.results || []).map((row) => `
    <tr class="${row.passed ? 'eval-row--pass' : 'eval-row--fail'}">
      <td>${escapeHtml(row.question?.id || '—')}</td>
      <td>${escapeHtml(row.question?.question || '—')}</td>
      <td>${checkCell(row.hasSources)}</td>
      <td>${checkCell(row.hasQuotes)}</td>
      <td>${checkCell(row.quotesValid)}</td>
      <td>${checkCell(row.meaningAligned)}</td>
      <td>${row.passed ? 'PASS' : 'FAIL'}</td>
    </tr>`).join('');
}

async function runCitationEval() {
  setCitationEvalStatus('Eval цитирования: 10 вопросов… (5–10 мин)');
  try {
    const res = await apiFetch('/api/rag/eval/validate', {
      method: 'POST',
      body: JSON.stringify({ strategy: 'STRUCTURE', topK: 5 }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    renderCitationEvalResults(data);
    setCitationEvalStatus(
      `Пройдено ${data.passed}/${data.totalQuestions} · провалено ${data.failed}`,
    );
  } catch (error) {
    setCitationEvalStatus(error.message, true);
  }
}

document.getElementById('rag-citation-query-btn')?.addEventListener('click', ragCitationQuery);
document.getElementById('rag-citation-question')?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') ragCitationQuery();
});
document.getElementById('rag-citation-eval-btn')?.addEventListener('click', runCitationEval);
