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

const minSimilarityInput = document.getElementById('rag-min-similarity');
const minSimilarityValue = document.getElementById('rag-min-similarity-value');
minSimilarityInput?.addEventListener('input', () => {
  if (minSimilarityValue) minSimilarityValue.textContent = Number(minSimilarityInput.value).toFixed(2);
});

function setModesStatus(text, isError = false) {
  const el = document.getElementById('rag-modes-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('demo-status--error', isError);
}

function setModeEvalStatus(text, isError = false) {
  const el = document.getElementById('rag-mode-eval-status');
  if (!el) return;
  el.textContent = text || '';
  el.classList.toggle('demo-status--error', isError);
}

function renderModeCard(modeResult, bestAfter) {
  const retrieval = modeResult.retrieval || {};
  const response = modeResult.response || {};
  const isBest = retrieval.topKAfter === bestAfter && bestAfter > 0;
  const chunks = (response.chunksUsed || [])
    .map((c) => `<li>[${(c.score ?? 0).toFixed?.(3) ?? c.score}] ${escapeHtml(c.section)}</li>`)
    .join('');
  return `
    <article class="mode-card${isBest ? ' mode-card--best' : ''}">
      <p class="mode-card__title">${escapeHtml(modeResult.mode || '—')}</p>
      <p class="mode-card__stats">
        top-K: ${retrieval.topKBefore ?? '—'} → ${retrieval.topKAfter ?? '—'}
        · dropped ${retrieval.droppedCount ?? 0}
        · порог ${Number(retrieval.minSimilarity ?? 0).toFixed(2)}
      </p>
      ${retrieval.rewrittenQuery ? `<p class="mode-card__stats">rewrite: ${escapeHtml(retrieval.rewrittenQuery)}</p>` : ''}
      <p class="mode-card__answer">${escapeHtml(response.answer || '—')}</p>
      ${chunks ? `<ul class="mode-card__chunks">${chunks}</ul>` : ''}
    </article>`;
}

function renderModesCompare(data) {
  const el = document.getElementById('rag-modes-compare');
  if (!el) return;
  el.classList.remove('hidden');
  const modes = [data.raw, data.filtered, data.rewriteFiltered].filter(Boolean);
  const bestAfter = Math.max(...modes.map((m) => m?.retrieval?.topKAfter ?? 0));
  el.innerHTML = `
    <div class="modes-compare__summary">
      Pool ${data.searchPoolSize ?? '—'} · порог ${Number(data.minSimilarity ?? 0).toFixed(2)}
      ${data.rewrittenQuery ? ` · rewrite: ${escapeHtml(data.rewrittenQuery)}` : ''}
    </div>
    <div class="modes-compare__grid">
      ${modes.map((m) => renderModeCard(m, bestAfter)).join('')}
    </div>`;
}

async function ragModesCompare() {
  const question = document.getElementById('rag-filter-question')?.value?.trim();
  if (!question) return;
  const minSimilarity = Number(minSimilarityInput?.value ?? 0.65);
  setModesStatus('Сравнение RAW / FILTERED / REWRITE…');
  try {
    const res = await apiFetch('/api/rag/query/modes/compare', {
      method: 'POST',
      body: JSON.stringify({ question, strategy: 'STRUCTURE', topK: 5, minSimilarity }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    renderModesCompare(await res.json());
    setModesStatus('Сравнение режимов готово');
  } catch (error) {
    setModesStatus(error.message, true);
  }
}

function renderModeEvalResults(data) {
  const tbody = document.getElementById('rag-mode-eval-body');
  if (!tbody) return;
  tbody.innerHTML = (data.results || []).map((row) => `
    <tr>
      <td>${escapeHtml(row.question?.id || '—')}</td>
      <td>${escapeHtml(row.question?.question || '—')}</td>
      <td>${row.rawTopKAfter ?? '—'}</td>
      <td>${row.filteredTopKAfter ?? '—'}</td>
      <td>${row.rewriteFilteredTopKAfter ?? '—'}</td>
      <td>${(row.rawSourcesMatched || []).join(', ') || '—'}</td>
      <td>${(row.filteredSourcesMatched || []).join(', ') || '—'}</td>
      <td>${(row.rewriteFilteredSourcesMatched || []).join(', ') || '—'}</td>
    </tr>`).join('');
}

async function runModeEval() {
  const minSimilarity = Number(minSimilarityInput?.value ?? 0.65);
  setModeEvalStatus('Eval режимов: 10×3… (5–10 мин)');
  try {
    const res = await apiFetch('/api/rag/eval/modes/run', {
      method: 'POST',
      body: JSON.stringify({ strategy: 'STRUCTURE', topK: 5, minSimilarity }),
    });
    if (!res.ok) throw new Error(await parseError(res));
    const data = await res.json();
    renderModeEvalResults(data);
    setModeEvalStatus(
      `RAW ${data.rawWithSources}/${data.totalQuestions} · `
      + `FILTERED ${data.filteredWithSources}/${data.totalQuestions} · `
      + `REWRITE ${data.rewriteFilteredWithSources}/${data.totalQuestions} с источниками`,
    );
  } catch (error) {
    setModeEvalStatus(error.message, true);
  }
}

document.getElementById('rag-modes-compare-btn')?.addEventListener('click', ragModesCompare);
document.getElementById('rag-filter-question')?.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') ragModesCompare();
});
document.getElementById('rag-mode-eval-btn')?.addEventListener('click', runModeEval);
