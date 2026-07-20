const WELCOME_HTML = `
  <div class="exam-app__welcome">
    <p class="exam-app__welcome-title">Привет! Готовимся к экзамену вместе</p>
    <ol class="exam-app__steps">
      <li>Загрузите MP3 лекции слева — конспект создастся автоматически</li>
      <li>Дождитесь статуса «Готово» (длинные записи обрабатываются по частям)</li>
      <li>Задавайте вопросы здесь — поиск идёт по <strong>всем</strong> вашим лекциям</li>
    </ol>
    <p class="exam-app__welcome-text">Ответы приходят с цитатами и таймкодами — можно проверить в транскрипте.</p>
    <div class="exam-app__hints" role="group" aria-label="Примеры вопросов">
      <button type="button" class="exam-app__hint" data-prompt="Кто такой Рудольф Отто и что такое numinous?">Отто / numinous</button>
      <button type="button" class="exam-app__hint" data-prompt="Назовите этапы феноменологии религии">Этапы феноменологии</button>
      <button type="button" class="exam-app__hint" data-prompt="Чем отличается классическая феноменология от неоклассической?">Классика vs неоклассика</button>
    </div>
  </div>`;

const STATUS_LABELS = {
  QUEUED: 'В очереди',
  TRANSCRIBING: 'Расшифровка…',
  CLEANING: 'Очистка текста…',
  INDEXING: 'Индексация…',
  CONSPECT: 'Конспект…',
  READY: 'Готово',
  FAILED: 'Ошибка',
};

const STATUS_CLASS = {
  QUEUED: 'exam-app__status--pending',
  TRANSCRIBING: 'exam-app__status--pending',
  CLEANING: 'exam-app__status--pending',
  INDEXING: 'exam-app__status--pending',
  CONSPECT: 'exam-app__status--pending',
  READY: 'exam-app__status--ready',
  FAILED: 'exam-app__status--failed',
};

const messagesEl = document.getElementById('exam-messages');
const promptEl = document.getElementById('exam-prompt');
const chatFormEl = document.getElementById('exam-chat-form');
const sendBtn = document.getElementById('exam-send-btn');
const uploadFormEl = document.getElementById('exam-upload-form');
const uploadBtn = document.getElementById('exam-upload-btn');
const fileInput = document.getElementById('exam-file');
const fileLabel = document.getElementById('exam-file-label');
const titleInput = document.getElementById('exam-title');
const subjectInput = document.getElementById('exam-subject');
const errorEl = document.getElementById('exam-error');
const metaEl = document.getElementById('exam-meta');
const contextLabel = document.getElementById('exam-context-label');
const statusPill = document.getElementById('exam-status-pill');
const tagline = document.getElementById('exam-tagline');
const jobListEl = document.getElementById('exam-job-list');
const lecturePanel = document.getElementById('exam-lecture-panel');
const lectureEmpty = document.getElementById('exam-lecture-empty');
const lectureTitle = document.getElementById('exam-lecture-title');
const lectureStatus = document.getElementById('exam-lecture-status');
const transcriptBody = document.getElementById('exam-transcript-body');
const conspectBtn = document.getElementById('exam-conspect-btn');
const conspectPanel = document.getElementById('exam-conspect-panel');
const statChunks = document.getElementById('stat-chunks');
const statReady = document.getElementById('stat-ready');
const newChatBtn = document.getElementById('exam-new-chat-btn');
const tabChat = document.getElementById('exam-tab-chat');
const tabLecture = document.getElementById('exam-tab-lecture');
const paneChat = document.getElementById('exam-pane-chat');
const paneLecture = document.getElementById('exam-pane-lecture');

let ready = false;
let busy = false;
let selectedJobId = null;
let pollTimer = null;
let thinkingEl = null;

function escapeHtml(text) {
  return String(text ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function formatDuration(ms) {
  if (ms == null || Number.isNaN(ms)) return '—';
  if (ms < 1000) return `${ms} мс`;
  return `${(ms / 1000).toFixed(1)} с`;
}

function formatClock(seconds) {
  const total = Math.max(0, Math.round(Number(seconds) || 0));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function statusLabel(status) {
  return STATUS_LABELS[status] || status;
}

function showError(msg) {
  if (!msg) {
    errorEl.classList.add('hidden');
    errorEl.textContent = '';
    return;
  }
  errorEl.textContent = msg;
  errorEl.classList.remove('hidden');
}

function setBusy(isBusy) {
  busy = isBusy;
  sendBtn.disabled = isBusy || !ready;
  promptEl.disabled = isBusy || !ready;
  uploadBtn.disabled = isBusy;
  if (isBusy) {
    showThinking();
  } else {
    hideThinking();
  }
}

function showThinking() {
  if (thinkingEl) return;
  clearWelcome();
  thinkingEl = document.createElement('div');
  thinkingEl.className = 'exam-app__bubble exam-app__bubble--bot exam-app__thinking';
  thinkingEl.innerHTML = '<span class="exam-app__dots"><span></span><span></span><span></span></span> Ищу в лекциях…';
  messagesEl.appendChild(thinkingEl);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function hideThinking() {
  thinkingEl?.remove();
  thinkingEl = null;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: options.body instanceof FormData
      ? undefined
      : { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { message: text };
  }
  if (!response.ok) {
    const msg = data?.message || data?.error || text || `HTTP ${response.status}`;
    throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
  }
  return data;
}

function clearWelcome() {
  messagesEl.querySelector('.exam-app__welcome')?.remove();
}

function appendBubble(role, html) {
  clearWelcome();
  const div = document.createElement('div');
  div.className = `exam-app__bubble exam-app__bubble--${role}`;
  div.innerHTML = html;
  messagesEl.appendChild(div);
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function buildDetailsHtml(sources, citations, toolCalls, trustCited) {
  let inner = '';
  if (trustCited) {
    inner += '<p class="exam-app__details-label">✓ Ответ с опорой на источники</p>';
  }
  if (citations?.length) {
    inner += '<p class="exam-app__details-label">Цитаты</p>';
    inner += citations.map((c) => `
      <div class="exam-app__citation">
        <strong>${escapeHtml(c.lecture)} ${escapeHtml(c.timestamp)}</strong>
        <div>${escapeHtml(c.quote)}</div>
      </div>`).join('');
  } else if (sources?.length) {
    inner += '<p class="exam-app__details-label">Источники</p><ul>'
      + sources.map((s) => `<li>${escapeHtml(s)}</li>`).join('') + '</ul>';
  }
  if (toolCalls?.length) {
    inner += '<p class="exam-app__details-label">Инструменты</p><ul>'
      + toolCalls.map((t) => `<li>${escapeHtml(t.toolName)} · ${formatDuration(t.durationMs)}</li>`).join('')
      + '</ul>';
  }
  if (!inner) return '';
  return `<details class="exam-app__details"><summary>Источники и инструменты</summary>${inner}</details>`;
}

function switchTab(tab) {
  const isChat = tab === 'chat';
  tabChat.classList.toggle('exam-app__tab--active', isChat);
  tabLecture.classList.toggle('exam-app__tab--active', !isChat);
  paneChat.classList.toggle('exam-app__pane--active', isChat);
  paneLecture.classList.toggle('exam-app__pane--active', !isChat);
}

function startNewChat() {
  showError('');
  metaEl.textContent = '';
  hideThinking();
  messagesEl.innerHTML = WELCOME_HTML;
  switchTab('chat');
  promptEl.focus();
}

function renderJobs(jobs) {
  if (!jobs?.length) {
    jobListEl.innerHTML = '<p class="exam-app__job-empty">Пока нет лекций — загрузите первую выше</p>';
    return;
  }
  jobListEl.innerHTML = jobs.map((job) => {
    const badgeClass = STATUS_CLASS[job.status] || 'exam-app__status--pending';
    const conspectMark = job.notesPath ? '<span class="exam-app__job-badge exam-app__job-badge--ok">конспект</span>' : '';
    return `
    <button type="button" class="exam-app__job${job.id === selectedJobId ? ' exam-app__job--active' : ''}" data-job-id="${escapeHtml(job.id)}">
      <div class="exam-app__job-row">
        <span class="exam-app__job-title">${escapeHtml(job.title)}</span>
        <span class="exam-app__status ${badgeClass}">${escapeHtml(statusLabel(job.status))}</span>
      </div>
      <div class="exam-app__job-meta">${job.segmentCount ? `${job.segmentCount} фрагм.` : ''}${conspectMark}</div>
      ${job.message ? `<div class="exam-app__job-progress">${escapeHtml(job.message)}</div>` : ''}
    </button>`;
  }).join('');
  jobListEl.querySelectorAll('.exam-app__job').forEach((btn) => {
    btn.addEventListener('click', () => selectJob(btn.dataset.jobId));
  });
}

function renderLecture(job) {
  if (!job) {
    lecturePanel.classList.add('hidden');
    lectureEmpty.classList.remove('hidden');
    return;
  }
  lectureEmpty.classList.add('hidden');
  lecturePanel.classList.remove('hidden');
  lectureTitle.textContent = job.title;
  const badgeClass = STATUS_CLASS[job.status] || '';
  lectureStatus.innerHTML = `<span class="exam-app__status ${badgeClass}">${escapeHtml(statusLabel(job.status))}</span>`
    + (job.message ? ` · ${escapeHtml(job.message)}` : '');

  if (job.segments?.length) {
    transcriptBody.innerHTML = job.segments.map((seg) => `
      <div class="exam-app__segment" data-start="${seg.startSec}">
        <span class="exam-app__segment-time">${formatClock(seg.startSec)}</span>
        ${escapeHtml(seg.text)}
      </div>`).join('');
  } else {
    transcriptBody.innerHTML = '<p class="exam-app__empty-text">Транскрипт появится после расшифровки</p>';
  }
}

function updateConspectButton(job) {
  const hasConspect = Boolean(job?.notesPath);
  conspectBtn.disabled = !hasConspect || busy;
  conspectBtn.textContent = hasConspect ? 'Конспект лекции' : 'Конспект ещё готовится…';
}

function hideConspectPanel() {
  conspectPanel.classList.add('hidden');
  conspectPanel.innerHTML = '';
}

async function showConspect(jobId) {
  setBusy(true);
  showError('');
  try {
    const data = await api(`/api/exam/jobs/${jobId}/conspect`);
    conspectPanel.classList.remove('hidden');
    conspectPanel.innerHTML = `
      <div class="exam-app__conspect-head">
        <p class="exam-app__conspect-title">Конспект лекции</p>
        <button type="button" class="exam-app__conspect-close" aria-label="Скрыть конспект">✕</button>
      </div>
      <div class="exam-app__conspect-body">${escapeHtml(data.markdown)}</div>`;
    conspectPanel.querySelector('.exam-app__conspect-close')?.addEventListener('click', hideConspectPanel);
  } catch (err) {
    showError(err.message);
  } finally {
    setBusy(false);
  }
}

async function selectJob(jobId) {
  selectedJobId = jobId;
  hideConspectPanel();
  switchTab('lecture');
  try {
    const job = await api(`/api/exam/jobs/${jobId}`);
    renderJobs(await api('/api/exam/jobs'));
    renderLecture(job);
    updateConspectButton(job);
  } catch (err) {
    showError(err.message);
  }
}

async function loadStatus() {
  try {
    const status = await api('/api/exam/status');
    ready = status.cloudConfigured;
    statusPill.textContent = ready ? 'онлайн' : 'нет API-ключа';
    statusPill.className = `exam-app__pill ${ready ? 'exam-app__pill--online' : 'exam-app__pill--offline'}`;
    tagline.textContent = `${status.transcriptionModel} · ${status.chatModel}`;
    statChunks.textContent = String(status.chunkCount);
    statReady.textContent = `${status.readyJobs} из ${status.jobCount}`;
    sendBtn.disabled = !ready || busy;
    promptEl.disabled = !ready || busy;
    if (!ready) {
      contextLabel.textContent = 'Добавьте OPENROUTER_API_KEY в backend/.env';
    }
  } catch (err) {
    statusPill.textContent = 'офлайн';
    showError(err.message);
  }
}

async function refreshJobs() {
  try {
    const jobs = await api('/api/exam/jobs');
    renderJobs(jobs);
    const pending = jobs.some((j) => ['QUEUED', 'TRANSCRIBING', 'CLEANING', 'INDEXING', 'CONSPECT'].includes(j.status));
    if (pending && !pollTimer) {
      pollTimer = setInterval(refreshJobs, 4000);
    } else if (!pending && pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
    if (selectedJobId) {
      const current = jobs.find((j) => j.id === selectedJobId);
      if (current) {
        renderLecture(current);
        updateConspectButton(current);
      }
    }
  } catch (err) {
    showError(err.message);
  }
}

uploadFormEl.addEventListener('submit', async (event) => {
  event.preventDefault();
  showError('');
  const file = fileInput.files?.[0];
  if (!file) {
    showError('Выберите аудиофайл');
    return;
  }
  setBusy(true);
  try {
    const form = new FormData();
    form.append('file', file);
    if (titleInput.value.trim()) form.append('title', titleInput.value.trim());
    if (subjectInput.value.trim()) form.append('subject', subjectInput.value.trim());
    const job = await api('/api/exam/upload', { method: 'POST', body: form });
    selectedJobId = job.id;
    hideConspectPanel();
    switchTab('chat');
    appendBubble('bot', `Лекция «<strong>${escapeHtml(job.title)}</strong>» загружена.<br>${escapeHtml(statusLabel(job.status))} — ${escapeHtml(job.message || 'обработка началась')}`);
    fileInput.value = '';
    fileLabel.textContent = 'Нажмите, чтобы выбрать файл';
    titleInput.value = '';
    await refreshJobs();
    await loadStatus();
  } catch (err) {
    showError(err.message);
  } finally {
    setBusy(false);
  }
});

fileInput.addEventListener('change', () => {
  const file = fileInput.files?.[0];
  fileLabel.textContent = file ? `${file.name} (${(file.size / 1024 / 1024).toFixed(1)} МБ)` : 'Нажмите, чтобы выбрать файл';
});

chatFormEl.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = promptEl.value.trim();
  if (!text || busy || !ready) return;
  showError('');
  setBusy(true);
  appendBubble('user', escapeHtml(text));
  promptEl.value = '';
  try {
    const data = await api('/api/exam/chat', {
      method: 'POST',
      body: JSON.stringify({ question: text }),
    });
    hideThinking();
    appendBubble('bot', escapeHtml(data.answer)
      + buildDetailsHtml(data.sources, data.citations, data.toolCalls, data.trustCited));
    metaEl.textContent = `${data.model} · ${formatDuration(data.durationMs)} · все лекции`;
  } catch (err) {
    hideThinking();
    showError(err.message);
    appendBubble('bot', escapeHtml(`Не удалось получить ответ: ${err.message}`));
  } finally {
    setBusy(false);
  }
});

conspectBtn.addEventListener('click', async () => {
  if (!selectedJobId) {
    showError('Сначала выберите лекцию в списке слева');
    return;
  }
  if (conspectPanel.classList.contains('hidden')) {
    await showConspect(selectedJobId);
  } else {
    hideConspectPanel();
  }
});

newChatBtn.addEventListener('click', startNewChat);
tabChat.addEventListener('click', () => switchTab('chat'));
tabLecture.addEventListener('click', () => switchTab('lecture'));

messagesEl.addEventListener('click', (event) => {
  const hint = event.target.closest('.exam-app__hint');
  if (!hint) return;
  promptEl.value = hint.dataset.prompt || '';
  promptEl.focus();
});

promptEl.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    chatFormEl.requestSubmit();
  }
});

messagesEl.innerHTML = WELCOME_HTML;
loadStatus();
refreshJobs();
