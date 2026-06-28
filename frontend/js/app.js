const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const newDialogBtn = document.getElementById('new-dialog-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const messagesEl = document.getElementById('messages');
const statsPanel = document.getElementById('stats-panel');
const tokenCurrent = document.getElementById('token-current');
const tokenHistory = document.getElementById('token-history');
const tokenResponse = document.getElementById('token-response');
const tokenSession = document.getElementById('token-session');
const tokenCost = document.getElementById('token-cost');
const tokenContext = document.getElementById('token-context');
const tokenBar = document.getElementById('token-bar');
const tokenBarFill = document.getElementById('token-bar-fill');
const tokenWarning = document.getElementById('token-warning');
const authOverlay = document.getElementById('auth-overlay');
const appMain = document.getElementById('app-main');
const authForm = document.getElementById('auth-form');
const authUsername = document.getElementById('auth-username');
const authPassword = document.getElementById('auth-password');
const authError = document.getElementById('auth-error');
const authSubmit = document.getElementById('auth-submit');
const authTabLogin = document.getElementById('auth-tab-login');
const authTabRegister = document.getElementById('auth-tab-register');
const authUserLabel = document.getElementById('auth-user-label');
const logoutBtn = document.getElementById('logout-btn');
const memoryPanel = document.getElementById('memory-panel');
const memoryTabShort = document.getElementById('memory-tab-short');
const memoryTabWorking = document.getElementById('memory-tab-working');
const memoryTabLong = document.getElementById('memory-tab-long');
const memoryLogsEl = document.getElementById('memory-logs');
const profileForm = document.getElementById('profile-form');
const profileDisplayName = document.getElementById('profile-display-name');
const profileResponseStyle = document.getElementById('profile-response-style');
const profileResponseFormat = document.getElementById('profile-response-format');
const profileConstraints = document.getElementById('profile-constraints');
const profileActiveSummary = document.getElementById('profile-active-summary');
const profileStatus = document.getElementById('profile-status');
const taskPanel = document.getElementById('task-panel');
const taskPanelEmpty = document.getElementById('task-panel-empty');
const taskPanelContent = document.getElementById('task-panel-content');
const taskPhaseBadge = document.getElementById('task-phase-badge');
const taskTitleEl = document.getElementById('task-title');
const taskCurrentStep = document.getElementById('task-current-step');
const taskExpectedAction = document.getElementById('task-expected-action');
const taskPausedLabel = document.getElementById('task-paused-label');
const taskPauseBtn = document.getElementById('task-pause-btn');
const taskResumeBtn = document.getElementById('task-resume-btn');
const transitionsPanel = document.getElementById('transitions-panel');
const transitionsAllowedBadge = document.getElementById('transitions-allowed-badge');
const transitionsAllowedEl = document.getElementById('transitions-allowed');
const transitionsList = document.getElementById('transitions-list');
const transitionsEmpty = document.getElementById('transitions-empty');
const invariantsList = document.getElementById('invariants-list');
const invariantsCountBadge = document.getElementById('invariants-count-badge');
const mcpPanel = document.getElementById('mcp-panel');
const mcpStatusBadge = document.getElementById('mcp-status-badge');
const mcpStatusMessage = document.getElementById('mcp-status-message');
const mcpSandboxPath = document.getElementById('mcp-sandbox-path');
const mcpRefreshBtn = document.getElementById('mcp-refresh-btn');
const mcpToolsList = document.getElementById('mcp-tools-list');
const mcpToolsEmpty = document.getElementById('mcp-tools-empty');
const mcpStudyHighlight = document.getElementById('mcp-study-highlight');
const mcpPipelineHighlight = document.getElementById('mcp-pipeline-highlight');
const mcpSchedulerHighlight = document.getElementById('mcp-scheduler-highlight');
const mcpStudyToolsWrap = document.getElementById('mcp-study-tools-wrap');
const mcpStudyToolsList = document.getElementById('mcp-study-tools-list');
const mcpPipelineToolsWrap = document.getElementById('mcp-pipeline-tools-wrap');
const mcpPipelineToolsList = document.getElementById('mcp-pipeline-tools-list');
const mcpSchedulerToolsWrap = document.getElementById('mcp-scheduler-tools-wrap');
const mcpSchedulerToolsList = document.getElementById('mcp-scheduler-tools-list');
const mcpFsToolsWrap = document.getElementById('mcp-fs-tools-wrap');
const mcpFsToolsList = document.getElementById('mcp-fs-tools-list');
const mcpFsCount = document.getElementById('mcp-fs-count');
const pipelinePanel = document.getElementById('pipeline-panel');
const pipelineBadge = document.getElementById('pipeline-badge');
const pipelineStepsList = document.getElementById('pipeline-steps-list');
const pipelineEmpty = document.getElementById('pipeline-empty');
const pipelineFile = document.getElementById('pipeline-file');
const schedulerPanel = document.getElementById('scheduler-panel');
const schedulerBadge = document.getElementById('scheduler-badge');
const schedulerTasksList = document.getElementById('scheduler-tasks-list');
const schedulerTasksEmpty = document.getElementById('scheduler-tasks-empty');
const schedulerSummary = document.getElementById('scheduler-summary');

const PIPELINE_SCENARIOS = [
  {
    id: 'islam-pillars',
    label: 'Пять столпов ислама',
    userMessage: 'Готовлюсь к экзамену по исламу — можно краткий конспект про пять столпов?',
    query: 'пять столпов ислама',
    filename: 'islam-pillars.txt',
    triggers: ['пять столпов', 'столпов ислама', 'столп ислам'],
  },
  {
    id: 'iman',
    label: 'Шесть столпов веры',
    userMessage: 'Чем иман отличается от ежедневной практики? Хочу сохранить это в шпаргалку.',
    query: 'шесть столпов веры',
    filename: 'iman-notes.txt',
    triggers: ['иман', 'столпов веры', 'шесть столпов'],
  },
  {
    id: 'ramadan',
    label: 'Пост в Рамадан',
    userMessage: 'Расскажи про пост в Рамадан — и сохрани основные тезисы в файл, пожалуйста.',
    query: 'пост рамадан',
    filename: 'ramadan-notes.txt',
    triggers: ['рамадан', 'саум', 'пост ислам'],
  },
];

const SCHEDULER_DEMO_SINCE_KEY = 'llm-chat-scheduler-demo-since';

const CHAR_DELAY_MS = 18;
const SCHEDULER_POLL_MS = 5000;
const SESSION_STORAGE_KEY = 'llm-chat-session-id';
const JWT_STORAGE_KEY = 'llm-chat-jwt';
const AUTH_USER_STORAGE_KEY = 'llm-chat-username';

let authMode = 'login';
let authToken = localStorage.getItem(JWT_STORAGE_KEY);
let authUsernameValue = localStorage.getItem(AUTH_USER_STORAGE_KEY);
let activeRequestId = 0;
let sessionId = null;
let schedulerPollTimer = null;

function getAuthToken() {
  const stored = localStorage.getItem(JWT_STORAGE_KEY);
  if (stored && stored !== 'null' && stored !== 'undefined') {
    authToken = stored;
    return stored;
  }
  authToken = null;
  return null;
}

function getAuthHeaders(extraHeaders = {}) {
  const headers = { ...extraHeaders };
  if (!headers['Content-Type'] && !headers['content-type']) {
    headers['Content-Type'] = 'application/json';
  }
  const token = getAuthToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

async function apiFetch(url, options = {}) {
  const headers = getAuthHeaders(options.headers || {});
  const response = await fetch(url, { ...options, headers });
  const token = getAuthToken();
  if ((response.status === 401 || response.status === 403) && token) {
    const message = await parseErrorResponse(response);
    forceReauth(message);
    throw new Error(message);
  }
  return response;
}

async function apiFetchOptional(url, options = {}) {
  const headers = getAuthHeaders(options.headers || {});
  return fetch(url, { ...options, headers });
}

function forceReauth(message) {
  logout();
  if (authError) {
    authError.textContent = message || 'Сессия истекла. Войдите снова.';
    authError.classList.remove('hidden');
  }
}

function showAuthOverlay() {
  authOverlay?.classList.remove('hidden');
  appMain?.classList.add('hidden');
}

function hideAuthOverlay() {
  authOverlay?.classList.add('hidden');
  appMain?.classList.remove('hidden');
  if (authUserLabel && authUsernameValue) {
    authUserLabel.textContent = authUsernameValue;
  }
}

function setAuthMode(mode) {
  authMode = mode;
  authTabLogin?.classList.toggle('auth-card__tab--active', mode === 'login');
  authTabRegister?.classList.toggle('auth-card__tab--active', mode === 'register');
  if (authSubmit) {
    authSubmit.textContent = mode === 'login' ? 'Войти' : 'Зарегистрироваться';
  }
  authError?.classList.add('hidden');
}

async function handleAuthSubmit(event) {
  event.preventDefault();
  authError?.classList.add('hidden');
  const username = authUsername?.value?.trim();
  const password = authPassword?.value;
  if (!username || !password) {
    if (authError) {
      authError.textContent = 'Заполните имя пользователя и пароль.';
      authError.classList.remove('hidden');
    }
    return;
  }
  try {
    const endpoint = authMode === 'login' ? '/api/auth/login' : '/api/auth/register';
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (!response.ok) {
      throw new Error(await parseErrorResponse(response));
    }
    const data = await response.json();
    authToken = data.token;
    authUsernameValue = data.username;
    localStorage.setItem(JWT_STORAGE_KEY, authToken);
    localStorage.setItem(AUTH_USER_STORAGE_KEY, authUsernameValue);
    hideAuthOverlay();
    await Promise.all([
      restoreSessionFromStorage(),
      loadProfile(),
      loadTaskState(),
      loadTransitions(),
      loadInvariants(),
      loadMcpTools(),
    ]);
    if (pipelinePanel) pipelinePanel.classList.remove('hidden');
    promptInput?.focus();
  } catch (error) {
    if (authError) {
      authError.textContent = error.message;
      authError.classList.remove('hidden');
    }
  }
}

function logout() {
  stopSchedulerPolling();
  authToken = null;
  authUsernameValue = null;
  localStorage.removeItem(JWT_STORAGE_KEY);
  localStorage.removeItem(AUTH_USER_STORAGE_KEY);
  localStorage.removeItem(SESSION_STORAGE_KEY);
  sessionId = null;
  showAuthOverlay();
}

function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function renderMemoryPanel(snapshot, logs, personalizationLogs, profileSnapshot, taskStateLogs, invariantLogs) {
  if (!memoryPanel) return;
  memoryPanel.classList.remove('hidden');

  const shortMessages = snapshot?.shortTermInContext || [];
  memoryTabShort.innerHTML = shortMessages.length
    ? shortMessages
        .map(
          (m) =>
            `<div class="memory-panel__item"><span class="memory-panel__key">${escapeHtml(m.role)}:</span> ${escapeHtml(m.content)}</div>`,
        )
        .join('')
    : '<p>Нет сообщений</p>';

  const workingFacts = snapshot?.workingFactsInContext || {};
  const workingSummary = snapshot?.workingSummaryInContext;
  const workingEntries = Object.entries(workingFacts);
  memoryTabWorking.innerHTML =
    (workingSummary
      ? `<div class="memory-panel__item"><span class="memory-panel__key">summary:</span> ${escapeHtml(workingSummary)}</div>`
      : '') +
    (workingEntries.length
      ? workingEntries
          .map(
            ([key, value]) =>
              `<div class="memory-panel__item"><span class="memory-panel__key">${escapeHtml(key)}:</span> ${escapeHtml(value)}</div>`,
          )
          .join('')
      : workingSummary
        ? ''
        : '<p>Нет рабочих данных</p>');

  const longTerm = snapshot?.longTermInContext || {};
  const longHtml = Object.entries(longTerm)
    .map(([category, entries]) => {
      const rows = Object.entries(entries || {})
        .map(
          ([key, value]) =>
            `<div class="memory-panel__item"><span class="memory-panel__key">${escapeHtml(key)}:</span> ${escapeHtml(value)}</div>`,
        )
        .join('');
      return `<div><strong>${escapeHtml(category)}</strong>${rows || '<p>пусто</p>'}</div>`;
    })
    .join('');
  memoryTabLong.innerHTML = longHtml || '<p>Нет долговременных данных</p>';

  const allLogs = [...(invariantLogs || []), ...(taskStateLogs || []), ...(personalizationLogs || []), ...(logs || [])];
  if (memoryLogsEl) {
    if (profileSnapshot?.appliedToPrompt) {
      allLogs.unshift('Активный профиль применён к запросу');
    }
    memoryLogsEl.innerHTML = allLogs.length
      ? allLogs.map((line) => `<div>${escapeHtml(line)}</div>`).join('')
      : '';
  }
}

function renderTaskPanel(task) {
  if (!taskPanel) return;
  const active = task?.active;
  if (!active) {
    taskPanelEmpty?.classList.remove('hidden');
    taskPanelContent?.classList.add('hidden');
    return;
  }
  taskPanelEmpty?.classList.add('hidden');
  taskPanelContent?.classList.remove('hidden');
  if (taskPhaseBadge) {
    taskPhaseBadge.textContent = task.phaseLabel || task.phase || '—';
    taskPhaseBadge.className = `task-panel__phase task-panel__phase--${task.phase || 'planning'}`;
  }
  if (taskTitleEl) {
    taskTitleEl.textContent = task.taskTitle || '';
    taskTitleEl.classList.toggle('hidden', !task.taskTitle);
  }
  if (taskCurrentStep) taskCurrentStep.textContent = task.currentStep || '—';
  if (taskExpectedAction) taskExpectedAction.textContent = task.expectedAction || '—';
  const paused = !!task.paused;
  taskPausedLabel?.classList.toggle('hidden', !paused);
  taskPauseBtn?.classList.toggle('hidden', paused);
  taskResumeBtn?.classList.toggle('hidden', !paused);
}

async function loadTaskState() {
  if (!sessionId || !getAuthToken()) {
    renderTaskPanel(null);
    return;
  }
  try {
    const response = await apiFetch(`/api/agent/task?sessionId=${encodeURIComponent(sessionId)}`);
    if (!response.ok) return;
    const data = await response.json();
    renderTaskPanel(data.active ? data : null);
  } catch {
    // optional
  }
}

function formatTransitionTime(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch {
    return '—';
  }
}

function renderTransitionsPanel(allowedData, historyData) {
  if (!transitionsPanel) return;
  const allowed = allowedData?.allowedLabels || allowedData?.allowed || [];
  const items = historyData?.transitions || [];

  if (transitionsAllowedBadge) {
    transitionsAllowedBadge.textContent = String(allowed.length);
  }
  if (transitionsAllowedEl) {
    transitionsAllowedEl.innerHTML = allowed.length
      ? allowed.map((label) => `<span class="transitions-panel__chip">${escapeHtml(label)}</span>`).join('')
      : '<span class="transitions-panel__chip transitions-panel__chip--muted">нет активной задачи</span>';
  }

  transitionsEmpty?.classList.toggle('hidden', items.length > 0);
  if (transitionsList) {
    transitionsList.innerHTML = items.length
      ? items
          .map((item) => {
            const statusClass = item.accepted ? 'transitions-panel__item--ok' : 'transitions-panel__item--reject';
            const statusMark = item.accepted ? '✓' : '✗';
            const phaseLine = [item.fromPhase, item.toPhase].filter(Boolean).join(' → ') || '—';
            const codePrefix = !item.accepted && item.rejectionCode ? `${item.rejectionCode}: ` : '';
            const reason = item.rejectionReason
              ? `<p class="transitions-panel__reason">${escapeHtml(item.rejectionReason)}</p>`
              : '';
            return `<li class="transitions-panel__item ${statusClass}">
              <div class="transitions-panel__head">
                <span class="transitions-panel__status">${statusMark}</span>
                <span class="transitions-panel__type">${escapeHtml(item.transitionLabel || item.transitionType || '')}</span>
                <span class="transitions-panel__time">${formatTransitionTime(item.createdAt)}</span>
              </div>
              <p class="transitions-panel__phase">${escapeHtml(codePrefix + phaseLine)}</p>
              ${reason}
            </li>`;
          })
          .join('')
      : '';
  }
}

async function loadTransitions() {
  if (!sessionId || !getAuthToken()) {
    renderTransitionsPanel(null, null);
    return;
  }
  try {
    const [allowedRes, historyRes] = await Promise.all([
      apiFetchOptional(`/api/agent/task/transitions/allowed?sessionId=${encodeURIComponent(sessionId)}`),
      apiFetchOptional(`/api/agent/task/transitions?sessionId=${encodeURIComponent(sessionId)}&limit=30`),
    ]);
    if (allowedRes.status === 401 || allowedRes.status === 403 || historyRes.status === 401 || historyRes.status === 403) {
      renderTransitionsPanel(null, null);
      return;
    }
    const allowedData = allowedRes.ok ? await allowedRes.json() : null;
    const historyData = historyRes.ok ? await historyRes.json() : null;
    renderTransitionsPanel(allowedData, historyData);
  } catch {
    renderTransitionsPanel(null, null);
  }
}

function renderInvariantsPanel(rules) {
  if (!invariantsList) return;
  const items = rules || [];
  if (invariantsCountBadge) {
    invariantsCountBadge.textContent = String(items.length);
  }
  invariantsList.innerHTML = items.length
    ? items
        .map(
          (rule) => `<li class="invariants-panel__item">
          <div class="invariants-panel__head">
            <span class="invariants-panel__id">${escapeHtml(rule.id)}</span>
            ${rule.hardBlock ? '<span class="invariants-panel__tag">hard</span>' : ''}
          </div>
          <strong class="invariants-panel__name">${escapeHtml(rule.title)}</strong>
          <p class="invariants-panel__desc">${escapeHtml(rule.description || '')}</p>
        </li>`,
        )
        .join('')
    : '<li class="invariants-panel__empty">Нет правил</li>';
}

async function loadInvariants() {
  if (!getAuthToken()) return;
  try {
    const response = await apiFetch('/api/agent/invariants');
    if (!response.ok) return;
    renderInvariantsPanel(await response.json());
  } catch {
    // optional
  }
}

function formatSchedulerTime(iso) {
  if (!iso) return '—';
  try {
    const date = new Date(iso);
    return date.toLocaleString('ru-RU', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' });
  } catch {
    return iso;
  }
}

function formatSchedulerCountdown(iso) {
  if (!iso) return '';
  try {
    const diffMs = new Date(iso).getTime() - Date.now();
    if (diffMs <= 0) return ' · скоро выполнится';
    const sec = Math.ceil(diffMs / 1000);
    if (sec < 60) return ` · через ${sec} с`;
    return ` · через ${Math.ceil(sec / 60)} мин`;
  } catch {
    return '';
  }
}

function highlightPipelinePanel() {
  if (!pipelinePanel) return;
  pipelinePanel.classList.add('pipeline-panel--flash');
  window.setTimeout(() => pipelinePanel.classList.remove('pipeline-panel--flash'), 2500);
}

function renderPipelinePanel(data) {
  if (!pipelinePanel) return;
  pipelinePanel.classList.remove('hidden');

  const steps = Array.isArray(data?.steps) ? data.steps : [];
  if (pipelineBadge) {
    pipelineBadge.textContent = steps.length ? `${steps.length}/3` : '—';
  }
  if (pipelineStepsList) {
    pipelineStepsList.innerHTML = steps
      .map((step) => {
        const tool = escapeHtml(step.toolName || '');
        const preview = escapeHtml(formatMcpResultPreview(fixMojibake(step.resultPreview || '')));
        const args = escapeHtml((step.arguments || '').slice(0, 120));
        const ms = step.durationMs != null ? `${step.durationMs} ms` : '';
        return `<li class="pipeline-panel__step pipeline-panel__step--done">
          <div class="pipeline-panel__step-head">
            <span class="pipeline-panel__step-num">${step.step ?? ''}</span>
            <code class="pipeline-panel__step-tool">${tool}</code>
            <span class="pipeline-panel__step-ms">${ms}</span>
          </div>
          <p class="pipeline-panel__step-args">args: ${args}${(step.arguments || '').length > 120 ? '…' : ''}</p>
          <p class="pipeline-panel__step-result">${preview}</p>
        </li>`;
      })
      .join('');
  }
  if (pipelineEmpty) {
    pipelineEmpty.classList.toggle('hidden', steps.length > 0);
  }
  if (pipelineFile) {
    if (data?.filePath) {
      pipelineFile.textContent = `💾 ${data.filePath}`;
      pipelineFile.classList.remove('hidden');
    } else {
      pipelineFile.classList.add('hidden');
      pipelineFile.textContent = '';
    }
  }
}

function renderSchedulerPanel(tasksData, summaryData) {
  if (!schedulerPanel) return;
  schedulerPanel.classList.remove('hidden');

  const tasks = Array.isArray(tasksData?.tasks) ? tasksData.tasks : [];
  if (schedulerBadge) {
    schedulerBadge.textContent = String(tasks.length);
  }
  if (schedulerTasksList) {
    schedulerTasksList.innerHTML = tasks.length
      ? tasks
          .map(
            (task) => `<li class="scheduler-panel__item">
          <div class="scheduler-panel__head">
            <span class="scheduler-panel__type">${escapeHtml(task.taskType || '')}</span>
            <span class="scheduler-panel__id">#${escapeHtml(String(task.id ?? ''))}</span>
          </div>
          <p class="scheduler-panel__message">${escapeHtml(task.message || '')}</p>
          <p class="scheduler-panel__meta">Следующий запуск: ${escapeHtml(formatSchedulerTime(task.nextRun))}<strong>${escapeHtml(formatSchedulerCountdown(task.nextRun))}</strong></p>
        </li>`,
          )
          .join('')
      : '';
  }
  if (schedulerTasksEmpty) {
    schedulerTasksEmpty.classList.toggle('hidden', tasks.length > 0);
  }

  if (schedulerSummary) {
    const results = Array.isArray(summaryData?.results) ? summaryData.results : [];
    if (!results.length) {
      schedulerSummary.textContent = 'Пока нет выполненных задач.';
    } else {
      const latest = results[0];
      let body = `[${formatSchedulerTime(latest.ranAt)}] ${latest.taskType || ''}`;
      if (latest.message) body += `\n${latest.message}`;
      try {
        const parsed = JSON.parse(latest.resultJson || '{}');
        if (parsed.message) body += `\n→ ${parsed.message}`;
      } catch {
        // ignore
      }
      schedulerSummary.textContent = body;
    }
  }
}

async function loadSchedulerPanel() {
  if (!getAuthToken()) return;
  const demoSince = sessionStorage.getItem(SCHEDULER_DEMO_SINCE_KEY);
  const sinceParam = demoSince ? `?since=${encodeURIComponent(demoSince)}` : '';
  try {
    const [tasksRes, summaryRes] = await Promise.all([
      apiFetch('/api/mcp/scheduler/tasks'),
      apiFetch(`/api/mcp/scheduler/summary${sinceParam}`),
    ]);
    const tasksData = tasksRes.ok ? await tasksRes.json() : { tasks: [] };
    const summaryData = summaryRes.ok ? await summaryRes.json() : { results: [] };
    renderSchedulerPanel(tasksData, summaryData);
  } catch {
    renderSchedulerPanel({ tasks: [] }, { results: [] });
  }
}

function startSchedulerPolling() {
  stopSchedulerPolling();
  if (!getAuthToken()) return;
  schedulerPollTimer = window.setInterval(() => {
    loadSchedulerPanel();
  }, SCHEDULER_POLL_MS);
}

function stopSchedulerPolling() {
  if (schedulerPollTimer) {
    window.clearInterval(schedulerPollTimer);
    schedulerPollTimer = null;
  }
}

function renderMcpPanel(data) {
  if (!mcpPanel) return;
  mcpPanel.classList.remove('hidden');
  statsPanel?.classList.remove('hidden');

  const connected = Boolean(data?.connected);
  if (mcpStatusBadge) {
    mcpStatusBadge.textContent = connected ? 'Connected' : 'Offline';
    mcpStatusBadge.classList.toggle('mcp-panel__badge--online', connected);
    mcpStatusBadge.classList.toggle('mcp-panel__badge--offline', !connected);
  }
  if (mcpStatusMessage) {
    const toolCount = data?.toolCount ?? 0;
    const servers = Array.isArray(data?.servers) ? data.servers.join(', ') : '';
    mcpStatusMessage.textContent = connected
      ? `${toolCount} инструмент(ов) · сервер(ы): ${servers || 'filesystem'}`
      : data?.message || 'MCP недоступен. Установите Node.js и npx.';
  }
  if (mcpSandboxPath) {
    if (data?.sandboxPath) {
      mcpSandboxPath.textContent = `Sandbox: ${data.sandboxPath}`;
      mcpSandboxPath.classList.remove('hidden');
    } else {
      mcpSandboxPath.classList.add('hidden');
    }
  }

  const tools = Array.isArray(data?.tools) ? data.tools : [];
  const studyTools = tools.filter((t) => t.serverName === 'mcp-study');
  const pipelineTools = tools.filter((t) => t.serverName === 'mcp-pipeline');
  const schedulerTools = tools.filter((t) => t.serverName === 'mcp-scheduler');
  const fsTools = tools.filter(
    (t) =>
      t.serverName !== 'mcp-study'
      && t.serverName !== 'mcp-scheduler'
      && t.serverName !== 'mcp-pipeline',
  );

  if (mcpPipelineHighlight) {
    mcpPipelineHighlight.classList.toggle('hidden', !connected || pipelineTools.length === 0);
  }
  if (mcpSchedulerHighlight) {
    mcpSchedulerHighlight.classList.toggle('hidden', !connected || schedulerTools.length === 0);
  }
  if (mcpStudyHighlight) {
    mcpStudyHighlight.classList.toggle('hidden', !connected || studyTools.length === 0);
  }

  const renderToolItem = (tool, study) => `<li class="mcp-panel__item${study ? ' mcp-panel__item--study' : ''}">
          <div class="mcp-panel__head">
            <span class="mcp-panel__name">${escapeHtml(tool.name || '')}</span>
            ${!study ? `<span class="mcp-panel__server">${escapeHtml(tool.serverName || '')}</span>` : ''}
          </div>
          <p class="mcp-panel__desc">${escapeHtml((tool.description || '').split('\n')[0])}</p>
        </li>`;

  if (mcpPipelineToolsWrap && mcpPipelineToolsList) {
    mcpPipelineToolsWrap.classList.toggle('hidden', pipelineTools.length === 0);
    mcpPipelineToolsList.innerHTML = pipelineTools.map((t) => renderToolItem(t, true)).join('');
  }
  if (mcpSchedulerToolsWrap && mcpSchedulerToolsList) {
    mcpSchedulerToolsWrap.classList.toggle('hidden', schedulerTools.length === 0);
    mcpSchedulerToolsList.innerHTML = schedulerTools.map((t) => renderToolItem(t, true)).join('');
  }
  if (mcpStudyToolsWrap && mcpStudyToolsList) {
    mcpStudyToolsWrap.classList.toggle('hidden', studyTools.length === 0);
    mcpStudyToolsList.innerHTML = studyTools.map((t) => renderToolItem(t, true)).join('');
  }
  if (mcpFsToolsWrap && mcpFsToolsList) {
    mcpFsToolsWrap.classList.toggle('hidden', fsTools.length === 0);
    mcpFsToolsList.innerHTML = fsTools.map((t) => renderToolItem(t, false)).join('');
    if (mcpFsCount) mcpFsCount.textContent = fsTools.length ? `(${fsTools.length})` : '';
  }
  if (mcpToolsList) {
    mcpToolsList.classList.add('hidden');
    mcpToolsList.innerHTML = '';
  }
  if (mcpToolsEmpty) {
    mcpToolsEmpty.classList.toggle('hidden', tools.length > 0);
    if (!tools.length) {
      mcpToolsEmpty.textContent = connected
        ? 'Сервер подключён, но список инструментов пуст.'
        : 'Установите Node.js 18+ и перезапустите backend.';
    }
  }
}

async function loadMcpTools(refresh = false) {
  if (!getAuthToken()) return;
  try {
    const response = await apiFetch(refresh ? '/api/mcp/reconnect' : '/api/mcp/tools', refresh ? { method: 'POST' } : {});
    if (!response.ok) return;
    renderMcpPanel(await response.json());
  } catch {
    renderMcpPanel({
      connected: false,
      toolCount: 0,
      servers: [],
      tools: [],
      message: 'Не удалось загрузить MCP. Проверьте backend и Node.js.',
    });
  }
}

async function pauseTask() {
  const result = await postTaskPause();
  if (!result.ok) {
    showError(result.error);
  }
}

async function resumeTask() {
  const result = await postTaskResume();
  if (!result.ok) {
    showError(result.error);
  }
}

function renderProfileSummary(profile) {
  if (!profileActiveSummary) return;
  const parts = [];
  if (profile?.displayName) parts.push(`Имя: ${profile.displayName}`);
  if (profile?.responseStyle) parts.push(`Стиль: ${profile.responseStyle}`);
  if (profile?.responseFormat) parts.push(`Формат: ${profile.responseFormat}`);
  if (profile?.constraints) parts.push(`Ограничения: ${profile.constraints}`);
  if (!parts.length) {
    profileActiveSummary.classList.add('hidden');
    profileActiveSummary.textContent = '';
    return;
  }
  profileActiveSummary.textContent = `Активный профиль: ${parts.join(' · ')}`;
  profileActiveSummary.classList.remove('hidden');
}

function fillProfileForm(profile) {
  if (profileDisplayName) profileDisplayName.value = profile?.displayName || '';
  if (profileResponseStyle) profileResponseStyle.value = profile?.responseStyle || '';
  if (profileResponseFormat) profileResponseFormat.value = profile?.responseFormat || '';
  if (profileConstraints) profileConstraints.value = profile?.constraints || '';
  renderProfileSummary(profile);
}

function showProfileStatus(message, isError = false) {
  if (!profileStatus) return;
  profileStatus.textContent = message;
  profileStatus.classList.toggle('hidden', !message);
  profileStatus.style.color = isError ? '#b91c1c' : '';
}

async function loadProfile() {
  if (!getAuthToken()) return;
  try {
    const response = await apiFetch('/api/user/profile');
    if (!response.ok) return;
    const data = await response.json();
    fillProfileForm(data);
  } catch {
    // optional
  }
}

async function saveProfile(event) {
  event?.preventDefault();
  showProfileStatus('');
  if (!getAuthToken()) {
    showProfileStatus('Сначала войдите в аккаунт.', true);
    showAuthOverlay();
    return;
  }
  const payload = {
    displayName: profileDisplayName?.value?.trim() || null,
    responseStyle: profileResponseStyle?.value || null,
    responseFormat: profileResponseFormat?.value || null,
    constraints: profileConstraints?.value?.trim() || null,
  };
  try {
    const response = await apiFetch('/api/user/profile', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    if (!response.ok) throw new Error(await parseErrorResponse(response));
    const data = await response.json();
    fillProfileForm(data);
    showProfileStatus('Профиль сохранён');
  } catch (error) {
    showProfileStatus(error.message, true);
  }
}

async function loadMemoryPanel() {
  if (!sessionId) return;
  try {
    const response = await apiFetch(`/api/agent/memory?sessionId=${encodeURIComponent(sessionId)}`);
    if (!response.ok) return;
    const data = await response.json();
    renderMemoryPanel(
      {
        shortTermInContext: data.shortTerm?.messages || [],
        workingFactsInContext: data.working?.facts || {},
        workingSummaryInContext: data.working?.summary || null,
        longTermInContext: data.longTerm?.longTerm || {},
      },
      data.memoryLogs || [],
      [],
      null,
      [],
      [],
    );
  } catch {
    // optional
  }
}

function initMemoryTabs() {
  document.querySelectorAll('.memory-panel__tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      const target = tab.dataset.memoryTab;
      document.querySelectorAll('.memory-panel__tab').forEach((btn) => {
        btn.classList.toggle('memory-panel__tab--active', btn.dataset.memoryTab === target);
      });
      memoryTabShort?.classList.toggle('hidden', target !== 'short');
      memoryTabWorking?.classList.toggle('hidden', target !== 'working');
      memoryTabLong?.classList.toggle('hidden', target !== 'long');
    });
  });
}

async function postTaskPause() {
  if (!sessionId) {
    return { ok: false, error: 'Нет активной сессии' };
  }
  try {
    const response = await apiFetch('/api/agent/task/pause', {
      method: 'POST',
      body: JSON.stringify({ sessionId }),
    });
    if (!response.ok) {
      return { ok: false, error: await parseErrorResponse(response) };
    }
    const task = await response.json();
    renderTaskPanel(task);
    await loadTransitions();
    return { ok: true, task };
  } catch (error) {
    return { ok: false, error: error.message };
  }
}

async function postTaskResume() {
  if (!sessionId) {
    return { ok: false, error: 'Нет активной сессии' };
  }
  try {
    const response = await apiFetch('/api/agent/task/resume', {
      method: 'POST',
      body: JSON.stringify({ sessionId }),
    });
    if (!response.ok) {
      return { ok: false, error: await parseErrorResponse(response) };
    }
    const task = await response.json();
    renderTaskPanel(task);
    await loadTransitions();
    return { ok: true, task };
  } catch (error) {
    return { ok: false, error: error.message };
  }
}

function setControlsDisabled(isDisabled) {
  sendBtn.disabled = isDisabled;
  promptInput.disabled = isDisabled;
  newDialogBtn.disabled = isDisabled;
}

function setLoading(isLoading) {
  loadingEl.classList.toggle('hidden', !isLoading);
}

function showError(message) {
  errorEl.textContent = message;
  errorEl.classList.remove('hidden');
}

function clearError() {
  errorEl.textContent = '';
  errorEl.classList.add('hidden');
}

function persistSessionId(value) {
  if (value) {
    localStorage.setItem(SESSION_STORAGE_KEY, value);
  } else {
    localStorage.removeItem(SESSION_STORAGE_KEY);
  }
}

async function restoreSessionFromStorage() {
  const savedSessionId = localStorage.getItem(SESSION_STORAGE_KEY);
  if (!savedSessionId) return;

  try {
    const response = await apiFetch(`/api/agent/history?sessionId=${encodeURIComponent(savedSessionId)}`);
    if (!response.ok) {
      persistSessionId(null);
      return;
    }
    const data = await response.json();
    sessionId = data.sessionId || savedSessionId;
    clearMessages();
    for (const message of data.messages || []) {
      if (message.role === 'summary' || message.role === 'facts') continue;
      const role = message.role === 'user' ? 'user' : 'assistant';
      appendMessage(role, message.content);
    }
    await loadMemoryPanel();
    await loadTaskState();
  } catch {
    sessionId = savedSessionId;
  }
}

function resolvePipelineScenario(text) {
  const normalized = (text || '').trim().toLowerCase();
  if (!normalized) return null;

  for (const scenario of PIPELINE_SCENARIOS) {
    if (scenario.triggers.some((trigger) => normalized.includes(trigger))) {
      return scenario;
    }
  }

  const wantsSave = /конспект|шпаргал|сохран|в файл|запиши/i.test(normalized);
  const wantsSearch = /найди|собери|материал|расскажи|объясни|про\s+/i.test(normalized);
  if (wantsSave && wantsSearch) {
    return {
      id: 'custom',
      label: 'Свой запрос',
      userMessage: text.trim(),
      query: text.trim().replace(/^(найди|собери|расскажи|объясни)\s+(про\s+)?/i, '').slice(0, 120),
      filename: 'notes.txt',
      triggers: [],
    };
  }

  return null;
}

function renderPipelineScenariosHtml() {
  return PIPELINE_SCENARIOS.map(
    (scenario) => `<button type="button" class="demo-panel__scenario" data-scenario-id="${scenario.id}">
        <span class="demo-panel__scenario-label">${escapeHtml(scenario.label)}</span>
        <span class="demo-panel__scenario-msg">${escapeHtml(scenario.userMessage)}</span>
      </button>`,
  ).join('');
}

function getDemoEmptyHtml() {
  return `
    <div class="messages-empty" id="demo-panel">
      <div class="demo-panel">
        <div class="demo-panel__header">
          <span class="demo-panel__badge">Day 19</span>
          <h2 class="demo-panel__title">MCP Pipeline — демо</h2>
          <p class="demo-panel__desc">
            Выберите сценарий или напишите свой вопрос внизу — как в обычном чате.
            Backend запускает <code>search → summarize → saveToFile</code>, затем <strong>LLM</strong> отвечает.
          </p>
        </div>
        <div class="demo-panel__flow" aria-hidden="true">
          <div class="demo-panel__flow-step">💬 вопрос студента</div>
          <div class="demo-panel__flow-arrow">↓</div>
          <div class="demo-panel__flow-step">🔍 <code>search</code> → 📝 <code>summarize</code> → 💾 <code>saveToFile</code></div>
          <div class="demo-panel__flow-arrow">↓</div>
          <div class="demo-panel__flow-step demo-panel__flow-step--answer">🤖 LLM — ответ в чате</div>
        </div>
        <div class="demo-panel__scenarios">${renderPipelineScenariosHtml()}</div>
        <p class="demo-panel__hint">Или введите свой текст: «Расскажи про … и сохрани конспект в файл»</p>
      </div>
    </div>
  `;
}

function clearMessages() {
  messagesEl.innerHTML = getDemoEmptyHtml();
  initDemoPanel();
}

function initDemoPanel() {
  const wrap = document.getElementById('demo-scenarios');
  if (wrap) {
    wrap.innerHTML = renderPipelineScenariosHtml();
  }
  document.querySelectorAll('.demo-panel__scenario').forEach((btn) => {
    btn.addEventListener('click', () => {
      const id = btn.getAttribute('data-scenario-id');
      const scenario = PIPELINE_SCENARIOS.find((s) => s.id === id);
      if (scenario) runPipelineWithScenario(scenario);
    });
  });
}

async function runPipelineWithScenario(scenario) {
  if (!scenario?.query) return;
  clearError();
  const demoPanel = document.getElementById('demo-panel');
  if (demoPanel) demoPanel.remove();
  clearEmptyState();

  appendMessage('user', scenario.userMessage);

  setLoading(true);
  setControlsDisabled(true);

  try {
    const response = await apiFetch('/api/mcp/pipeline/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        query: scenario.query,
        filename: scenario.filename,
      }),
    });
    const data = response.ok ? await response.json() : null;
    if (!response.ok) {
      const errText = data?.message || `HTTP ${response.status}`;
      showError(errText);
      appendMessage('assistant', `Не удалось выполнить пайплайн: ${errText}`);
      return;
    }

    renderPipelinePanel(data);
    highlightPipelinePanel();

    const assistantText =
      fixMojibake(data.assistantMessage)
      || 'Пайплайн выполнен. Смотрите шаги справа и MCP-блоки ниже.';
    await appendMessageGradually('assistant', assistantText, data.mcpToolCalls || []);
    await loadMcpTools();
  } catch (error) {
    showError(error.message || 'Pipeline failed');
    appendMessage('assistant', `Ошибка: ${error.message || 'pipeline failed'}`);
  } finally {
    setLoading(false);
    setControlsDisabled(false);
  }
}

function shortToolName(name) {
  if (!name) return '';
  if (name.includes('searchTopic')) return 'searchTopic';
  if (name.includes('getExamOutline')) return 'getExamOutline';
  if (name.includes('saveToFile')) return 'saveToFile';
  if (name.includes('summarize')) return 'summarize';
  if (name.includes('search')) return 'search';
  if (name.includes('scheduleReminder')) return 'scheduleReminder';
  if (name.includes('schedulePeriodicSummary')) return 'schedulePeriodicSummary';
  if (name.includes('listScheduledTasks')) return 'listScheduledTasks';
  if (name.includes('getSummary')) return 'getSummary';
  if (name.includes('cancelTask')) return 'cancelTask';
  return name.replace(/^.*_/, '');
}

function fixMojibake(text) {
  if (!text || (!text.includes('Р') && !text.includes('Ð'))) return text;
  try {
    const bytes = new Uint8Array([...text].map((ch) => ch.charCodeAt(0) & 0xff));
    const decoded = new TextDecoder('utf-8').decode(bytes);
    if (/[а-яё]/i.test(decoded)) return decoded;
  } catch {
    // ignore
  }
  return text;
}

function formatMcpResultPreview(preview) {
  if (!preview) return '';
  const raw = preview.endsWith('…') ? preview.slice(0, -1) : preview;
  try {
    const data = JSON.parse(raw);
    if (Array.isArray(data.items) && data.items.length) {
      return data.items
        .slice(0, 3)
        .map((item) => `📌 ${fixMojibake(item.title || '')}\n   ${fixMojibake((item.snippet || '').slice(0, 100))}`)
        .join('\n\n');
    }
    if (data.summary || Array.isArray(data.keyPoints)) {
      const points = (data.keyPoints || []).slice(0, 3).map((p) => `• ${fixMojibake(String(p))}`).join('\n');
      const summary = fixMojibake((data.summary || '').slice(0, 180));
      return points ? `${summary}\n${points}` : summary;
    }
    if (data.path || data.filename) {
      return `💾 ${fixMojibake(data.path || data.filename || '')}`;
    }
    if (Array.isArray(data.matches) && data.matches.length) {
      return data.matches
        .slice(0, 3)
        .map((m) => `📌 ${m.topic}\n   ${(m.summary || '').slice(0, 120)}`)
        .join('\n\n');
    }
    if (Array.isArray(data.topics) && data.topics.length) {
      return data.topics.map((t, i) => `${i + 1}. ${t.topic}`).join('\n');
    }
    if (data.taskId && data.taskType) {
      const delay =
        data.delaySeconds != null
          ? `${data.delaySeconds} сек`
          : data.delayMinutes != null
            ? `${data.delayMinutes} мин`
            : '';
      return `✅ Задача #${data.taskId} (${data.taskType})${delay ? ` · через ${delay}` : ''}${data.message ? `\n${data.message}` : ''}`;
    }
    if (typeof data.count === 'number' && Array.isArray(data.tasks)) {
      return data.tasks.length
        ? data.tasks.map((t) => `#${t.id} ${t.taskType}: ${t.message || ''}`).join('\n')
        : 'Нет активных задач';
    }
  } catch {
    // not JSON
  }
  return preview.length > 220 ? `${preview.slice(0, 220)}…` : preview;
}

function clearEmptyState() {
  const empty = messagesEl.querySelector('.messages-empty');
  if (empty) empty.remove();
}

function resizeInput() {
  promptInput.style.height = 'auto';
  const maxHeight = 120;
  const nextHeight = Math.min(promptInput.scrollHeight, maxHeight);
  promptInput.style.height = `${nextHeight}px`;
  promptInput.style.overflowY = promptInput.scrollHeight > maxHeight ? 'auto' : 'hidden';
}

function scrollToBottom() {
  messagesEl.scrollTop = messagesEl.scrollHeight;
}

function createMessageElement(role) {
  clearEmptyState();
  const messageEl = document.createElement('article');
  messageEl.className = `message message--${role}`;

  const avatar = role === 'user' ? 'Вы' : 'AI';
  const label = role === 'user' ? 'Вы' : 'Агент';

  const avatarEl = document.createElement('div');
  avatarEl.className = 'message__avatar';
  avatarEl.textContent = avatar;
  avatarEl.setAttribute('aria-hidden', 'true');

  const bubbleEl = document.createElement('div');
  bubbleEl.className = 'message__bubble';

  const labelEl = document.createElement('span');
  labelEl.className = 'message__label';
  labelEl.textContent = label;

  const textEl = document.createElement('div');
  textEl.className = 'message__text';

  bubbleEl.append(labelEl, textEl);
  messageEl.append(avatarEl, bubbleEl);
  messagesEl.appendChild(messageEl);
  return { textEl, bubbleEl };
}

function appendMcpToolCalls(bubbleEl, toolCalls, insertBefore = null) {
  if (!bubbleEl || !Array.isArray(toolCalls) || toolCalls.length === 0) return;
  const isPipeline = toolCalls.some(
    (c) =>
      (c.serverName || '').includes('pipeline')
      || ['search', 'summarize', 'saveToFile'].some((t) => (c.toolName || '').includes(t)),
  );
  const isScheduler = !isPipeline && toolCalls.some(
    (c) =>
      (c.serverName || '').includes('scheduler') ||
      (c.toolName || '').includes('schedule'),
  );
  const blockTitle = isPipeline
    ? '🔗 MCP Pipeline (3 tools) + ответ LLM ниже'
    : isScheduler
      ? '⚡ MCP Tool Call — scheduleReminder'
      : 'MCP Tool Call — справочник';
  const block = document.createElement('div');
  block.className = `message__mcp-calls${
    isPipeline ? ' message__mcp-calls--pipeline' : isScheduler ? ' message__mcp-calls--scheduler' : ''
  }`;
  block.innerHTML = `<div class="message__mcp-calls-title">${blockTitle}</div>${toolCalls
    .map((call) => {
      const name = shortToolName(call.toolName || '');
      const result = formatMcpResultPreview(call.resultPreview || '');
      return `<div class="message__mcp-call">
        <div class="message__mcp-call-head">
          <span class="message__mcp-call-name">${escapeHtml(name)}</span>
          <span class="message__mcp-call-server">${escapeHtml(call.serverName || 'mcp-study')}</span>
          ${call.durationMs != null ? `<span class="message__mcp-call-ms">${call.durationMs} ms</span>` : ''}
        </div>
        ${result ? `<div class="message__mcp-call-result">${escapeHtml(result)}</div>` : ''}
        <details class="message__mcp-call-details"><summary>JSON args / raw</summary>
          <pre>${escapeHtml(`args: ${call.arguments || ''}\n\nraw: ${call.resultPreview || ''}`)}</pre>
        </details>
      </div>`;
    })
    .join('')}`;
  if (insertBefore) {
    bubbleEl.insertBefore(block, insertBefore);
  } else {
    bubbleEl.appendChild(block);
  }
}

function appendMessage(role, text, mcpToolCalls) {
  const { textEl, bubbleEl } = createMessageElement(role);
  if (role === 'assistant' && mcpToolCalls?.length) {
    appendMcpToolCalls(bubbleEl, mcpToolCalls, textEl);
  }
  textEl.textContent = text;
  if (role !== 'assistant' || !mcpToolCalls?.length) {
    appendMcpToolCalls(bubbleEl, mcpToolCalls);
  }
  scrollToBottom();
}

async function revealText(textEl, text) {
  textEl.textContent = '';
  for (const char of text) {
    textEl.textContent += char;
    scrollToBottom();
    await sleep(CHAR_DELAY_MS);
  }
}

async function appendMessageGradually(role, text, mcpToolCalls) {
  const { textEl, bubbleEl } = createMessageElement(role);
  if (role === 'assistant' && mcpToolCalls?.length) {
    appendMcpToolCalls(bubbleEl, mcpToolCalls, textEl);
    scrollToBottom();
  }
  await revealText(textEl, text);
  if (role !== 'assistant' || !mcpToolCalls?.length) {
    appendMcpToolCalls(bubbleEl, mcpToolCalls);
  }
}

function formatTokens(value) {
  return value == null ? '—' : `~${Number(value).toLocaleString('ru-RU')}`;
}

function formatExactTokens(value) {
  return value == null ? '—' : Number(value).toLocaleString('ru-RU');
}

function formatCost(value) {
  if (value == null) return '—';
  const num = Number(value);
  return num < 0.0001 ? `$${num.toFixed(6)}` : `$${num.toFixed(4)}`;
}

function formatContextPercent(used, limit) {
  if (!limit || used <= 0) return '0%';
  const percent = (used / limit) * 100;
  if (percent >= 100) return '100%';
  if (percent >= 10) return `${Math.round(percent)}%`;
  if (percent >= 1) return `${percent.toFixed(1)}%`;
  return '<0.01%';
}

function contextBarWidth(used, limit) {
  if (!limit || used <= 0) return 0;
  return Math.max(Math.min(100, (used / limit) * 100), 0.5);
}

function updateTokenPanel(tokens) {
  if (!tokens) {
    statsPanel.classList.add('hidden');
    return;
  }

  tokenCurrent.textContent = formatTokens(tokens.currentPromptTokens);
  tokenHistory.textContent = formatTokens(tokens.historyTokens);
  tokenResponse.textContent = formatExactTokens(tokens.responseTokens);
  tokenSession.textContent = formatExactTokens(tokens.sessionTotalTokens);

  const promptActual = tokens.promptTokensActual || tokens.requestTokensEstimate || 0;
  const limit = tokens.modelContextLimit > 0 ? tokens.modelContextLimit : 128000;

  tokenCost.textContent = formatCost(tokens.sessionCostUsd);
  tokenContext.textContent = `${formatExactTokens(promptActual)} / ${formatExactTokens(limit)} (${formatContextPercent(promptActual, limit)})`;

  tokenBar.classList.remove('hidden');
  tokenBarFill.style.width = `${contextBarWidth(promptActual, limit)}%`;
  tokenBarFill.classList.toggle('token-bar__fill--warn', tokens.nearContextLimit);
  tokenBarFill.classList.toggle('token-bar__fill--danger', tokens.contextOverflow);

  if (tokens.nearContextLimit || tokens.contextOverflow) {
    tokenWarning.textContent = tokens.contextOverflow
      ? 'Контекст переполнен — запрос не помещается в окно модели.'
      : 'Контекст почти заполнен.';
    tokenWarning.classList.remove('hidden');
  } else {
    tokenWarning.classList.add('hidden');
  }

  statsPanel.classList.remove('hidden');
}

function resetTokenPanel() {
  statsPanel.classList.add('hidden');
  tokenWarning.classList.add('hidden');
  tokenBar.classList.add('hidden');
}

async function parseErrorResponse(response) {
  try {
    const data = await response.json();
    return data.error || data.message || `Сервер вернул ошибку: ${response.status}`;
  } catch {
    try {
      const text = await response.text();
      if (text) return text;
    } catch {
      // ignore
    }
    if (response.status === 401 || response.status === 403) {
      return 'Требуется авторизация. Войдите снова.';
    }
    return `Сервер вернул ошибку: ${response.status}`;
  }
}

async function startNewDialog() {
  const previousSessionId = sessionId;
  sessionId = null;
  persistSessionId(null);
  clearMessages();
  resetTokenPanel();
  if (memoryTabShort) memoryTabShort.innerHTML = '';
  if (memoryTabWorking) memoryTabWorking.innerHTML = '';
  if (memoryTabLong) memoryTabLong.innerHTML = '';
  if (memoryLogsEl) memoryLogsEl.innerHTML = '';
  renderTaskPanel(null);

  if (previousSessionId) {
    try {
      await apiFetch('/api/agent/reset', {
        method: 'POST',
        body: JSON.stringify({ sessionId: previousSessionId }),
      });
    } catch {
      // не критично
    }
  }
}

async function sendMessage(prompt, options = {}) {
  const requestId = ++activeRequestId;
  setControlsDisabled(true);
  appendMessage('user', prompt);

  const payload = { prompt };
  if (sessionId) payload.sessionId = sessionId;

  try {
    setLoading(true);
    const response = await apiFetch('/api/agent/chat', {
      method: 'POST',
      body: JSON.stringify(payload),
    });

    if (requestId !== activeRequestId) return { ok: false, error: 'Запрос отменён' };
    if (!response.ok) throw new Error(await parseErrorResponse(response));

    const data = await response.json();
    sessionId = data.sessionId || sessionId;
    persistSessionId(sessionId);
    updateTokenPanel(data.tokens);

    if (data.memorySnapshot) {
      renderMemoryPanel(
        data.memorySnapshot,
        data.memoryLogs || [],
        data.personalizationLogs || [],
        data.profileSnapshot || null,
        data.taskStateLogs || [],
        data.invariantLogs || [],
      );
    } else {
      await loadMemoryPanel();
    }
    if (data.invariantsSnapshot?.rules?.length) {
      renderInvariantsPanel(data.invariantsSnapshot.rules);
    } else {
      await loadInvariants();
    }
    if (data.taskStateSnapshot) {
      renderTaskPanel({
        ...data.taskStateSnapshot,
        active: data.taskStateSnapshot.active,
      });
    } else {
      await loadTaskState();
    }
    await loadTransitions();

    setLoading(false);
    if (options.skipTyping) {
      appendMessage('assistant', data.response || 'Пустой ответ.', data.mcpToolCalls);
    } else {
      await appendMessageGradually('assistant', data.response || 'Пустой ответ.', data.mcpToolCalls);
    }
    return { ok: true, data };
  } catch (error) {
    if (requestId !== activeRequestId) return { ok: false, error: 'Запрос отменён' };
    const message = error.message.includes('Failed to fetch')
      ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
      : error.message;
    showError(message);
    return { ok: false, error: message };
  } finally {
    if (requestId === activeRequestId) {
      setLoading(false);
      setControlsDisabled(false);
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function sendPrompt() {
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showError('Введите сообщение перед отправкой.');
    return;
  }
  clearError();
  const pipelineScenario = resolvePipelineScenario(prompt);
  promptInput.value = '';
  resizeInput();
  if (pipelineScenario) {
    await runPipelineWithScenario({
      ...pipelineScenario,
      userMessage: prompt,
      query: pipelineScenario.query || prompt,
    });
    promptInput.focus();
    return;
  }
  await sendMessage(prompt);
  promptInput.focus();
}

sendBtn.addEventListener('click', sendPrompt);
newDialogBtn.addEventListener('click', startNewDialog);
logoutBtn?.addEventListener('click', logout);
authForm?.addEventListener('submit', handleAuthSubmit);
authTabLogin?.addEventListener('click', () => setAuthMode('login'));
authTabRegister?.addEventListener('click', () => setAuthMode('register'));
profileForm?.addEventListener('submit', saveProfile);
taskPauseBtn?.addEventListener('click', pauseTask);
taskResumeBtn?.addEventListener('click', resumeTask);
mcpRefreshBtn?.addEventListener('click', () => loadMcpTools(true));
promptInput.addEventListener('input', resizeInput);
promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    sendPrompt();
  }
});

initMemoryTabs();
initDemoPanel();
resizeInput();

if (getAuthToken()) {
  hideAuthOverlay();
  Promise.all([
    restoreSessionFromStorage(),
    loadProfile(),
    loadTaskState(),
    loadTransitions(),
    loadInvariants(),
    loadMcpTools(),
  ]).finally(() => {
    if (pipelinePanel) pipelinePanel.classList.remove('hidden');
    promptInput.focus();
  });
} else {
  showAuthOverlay();
}
