const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const comparisonEl = document.getElementById('response-comparison');
const logsEl = document.getElementById('logs');

const demoExamples = {
  reasoning: `У Маши было 8 конфет. Она съела 3 и купила ещё 5. Сколько конфет стало? Реши пошагово и объясни каждый шаг.`,
  explain: `Объясни, как работает блокчейн, простыми словами для новичка. Не более 5 предложений.`,
  code: `Напиши функцию на Python, которая проверяет, является ли строка палиндромом. Добавь краткий комментарий к коду.`,
  analysis: `Сравни плюсы и минусы удалённой работы. Дай ровно 4 пункта: 2 за, 2 против.`,
};

const modelRequests = [
  {
    responseEl: document.getElementById('response-model-weak'),
    metricsEl: document.getElementById('metrics-model-weak'),
    tier: 'weak',
    key: 'weak',
    label: 'Слабая (20B)',
  },
  {
    responseEl: document.getElementById('response-model-medium'),
    metricsEl: document.getElementById('metrics-model-medium'),
    tier: 'medium',
    key: 'medium',
    label: 'Средняя (GPT-4o mini)',
  },
  {
    responseEl: document.getElementById('response-model-strong'),
    metricsEl: document.getElementById('metrics-model-strong'),
    tier: 'strong',
    key: 'strong',
    label: 'Сильная (120B)',
  },
];

const modelCount = modelRequests.length;

let activeRequestId = 0;

function setLoading(isLoading, message = 'Думаю...') {
  sendBtn.disabled = isLoading;
  document.querySelectorAll('.demo-btn').forEach((button) => {
    button.disabled = isLoading;
  });
  loadingEl.textContent = message;
  loadingEl.classList.toggle('hidden', !isLoading);
}

function setPanelLoading(el, isLoading) {
  el.classList.toggle('response-box--loading', isLoading);
}

function showError(message) {
  errorEl.textContent = message;
  errorEl.classList.remove('hidden');
}

function clearError() {
  errorEl.textContent = '';
  errorEl.classList.add('hidden');
}

function formatCost(costUsd) {
  if (costUsd === 0 || costUsd === 0.0) {
    return '$0.00 (бесплатно)';
  }
  return `$${costUsd.toFixed(6)}`;
}

function renderMetrics(el, metrics) {
  if (!metrics) {
    el.classList.add('hidden');
    el.textContent = '';
    return;
  }

  el.classList.remove('hidden');
  el.innerHTML = `
    <dl class="metrics-list">
      <div><dt>Время</dt><dd>${metrics.responseTimeMs} ms</dd></div>
      <div><dt>Prompt tokens</dt><dd>${metrics.promptTokens}</dd></div>
      <div><dt>Completion tokens</dt><dd>${metrics.completionTokens}</dd></div>
      <div><dt>Total tokens</dt><dd>${metrics.totalTokens}</dd></div>
      <div><dt>Стоимость</dt><dd>${formatCost(metrics.costUsd)}</dd></div>
      <div class="metrics-model-id"><dt>Модель</dt><dd>${metrics.modelId}</dd></div>
    </dl>
  `;
}

function clearResponses() {
  modelRequests.forEach(({ responseEl, metricsEl }) => {
    responseEl.textContent = '';
    setPanelLoading(responseEl, false);
    renderMetrics(metricsEl, null);
  });
  comparisonEl.textContent = '';
  comparisonEl.classList.remove('response-box--loading');
  logsEl.textContent = '';
}

async function parseErrorResponse(response) {
  const text = await response.text();
  try {
    const json = JSON.parse(text);
    return json.error || json.message || text;
  } catch {
    return text || `Сервер вернул ошибку: ${response.status}`;
  }
}

async function fetchModel(prompt, tier) {
  const response = await fetch('/api/chat/model', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt, tier }),
  });

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response));
  }

  return response.json();
}

async function fetchComparison(prompt, answers, metrics) {
  const response = await fetch('/api/chat/compare-models-analysis', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      prompt,
      weak: answers.weak,
      medium: answers.medium,
      strong: answers.strong,
      weakMetrics: metrics.weak,
      mediumMetrics: metrics.medium,
      strongMetrics: metrics.strong,
    }),
  });

  if (!response.ok) {
    throw new Error(await parseErrorResponse(response));
  }

  return response.json();
}

function sleep(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function sendPrompt() {
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showError('Введите запрос перед отправкой.');
    return;
  }

  const requestId = ++activeRequestId;
  clearError();
  clearResponses();
  setLoading(true, 'Запросы отправляются по очереди...');

  const answers = { weak: '', medium: '', strong: '' };
  const metricsByTier = { weak: null, medium: null, strong: null };
  const logChunks = [];
  let completedCount = 0;
  let hasError = false;

  comparisonEl.textContent = `Появится после получения всех ${modelCount} ответов...`;
  comparisonEl.classList.add('response-box--loading');

  for (const { responseEl, metricsEl, tier, key, label } of modelRequests) {
    if (requestId !== activeRequestId) {
      return;
    }

    responseEl.textContent = 'Ожидание ответа...';
    setPanelLoading(responseEl, true);
    setLoading(true, `Запрос к модели: ${label}...`);

    try {
      const data = await fetchModel(prompt, tier);
      if (requestId !== activeRequestId) {
        return;
      }

      const answer = data.response || 'Пустой ответ от LLM.';
      answers[key] = answer;
      metricsByTier[key] = data.metrics || null;
      responseEl.textContent = answer;
      renderMetrics(metricsEl, data.metrics);
      setPanelLoading(responseEl, false);

      if (Array.isArray(data.logs)) {
        logChunks.push(`=== ${label} ===\n${data.logs.join('\n')}`);
      }

      completedCount += 1;
      setLoading(true, `Получено ${completedCount} из ${modelCount} ответов...`);
    } catch (error) {
      if (requestId !== activeRequestId) {
        return;
      }
      hasError = true;
      responseEl.textContent = 'Ошибка получения ответа.';
      setPanelLoading(responseEl, false);
      showError(
        error.message.includes('Failed to fetch')
          ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
          : error.message
      );
      break;
    }

    if (completedCount < modelCount) {
      await sleep(3000);
    }
  }

  if (requestId !== activeRequestId) {
    return;
  }

  const allAnswersReady = modelRequests.every(({ key }) => answers[key]);
  const allMetricsReady = modelRequests.every(({ key }) => metricsByTier[key]);
  if (!hasError && allAnswersReady && allMetricsReady) {
    setLoading(true, 'Формирую сравнение...');
    try {
      const comparisonData = await fetchComparison(prompt, answers, metricsByTier);
      if (requestId !== activeRequestId) {
        return;
      }

      comparisonEl.textContent = comparisonData.response || 'Пустой ответ от LLM.';
      if (Array.isArray(comparisonData.logs)) {
        logChunks.push(`=== Сравнение ===\n${comparisonData.logs.join('\n')}`);
      }
    } catch (error) {
      comparisonEl.textContent = 'Не удалось получить сравнение.';
      showError(error.message);
    }
  } else if (hasError) {
    comparisonEl.textContent = 'Сравнение недоступно из-за ошибки.';
  }

  comparisonEl.classList.remove('response-box--loading');
  logsEl.textContent = logChunks.join('\n\n') || 'Логи не получены.';
  setLoading(false);
}

function selectDemo(demoId) {
  const text = demoExamples[demoId];
  if (!text) {
    return;
  }
  promptInput.value = text;
  clearError();
  promptInput.focus();
}

sendBtn.addEventListener('click', sendPrompt);

document.querySelectorAll('.demo-btn').forEach((button) => {
  button.addEventListener('click', () => {
    selectDemo(button.dataset.demo);
  });
});

promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    sendPrompt();
  }
});
