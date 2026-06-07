const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const temp0El = document.getElementById('response-temp-0');
const temp07El = document.getElementById('response-temp-07');
const temp12El = document.getElementById('response-temp-12');
const comparisonEl = document.getElementById('response-comparison');
const logsEl = document.getElementById('logs');

const demoExamples = {
  sleep: `Кратко: 3 факта о пользе сна и одна метафора для детей.`,
  startup: `Придумай название экостартапа против пищевых отходов. Объясни в 2 предложениях.`,
  quantum: `Кратко объясни квантовую запутанность и приведи одну бытовую аналогию.`,
  water: `3 причины пить воду и короткий слоган для приложения-напоминалки.`,
};

const temperatureRequests = [
  { el: temp0El, temperature: 0, key: 'temp0', label: 'temperature = 0' },
  { el: temp07El, temperature: 0.7, key: 'temp07', label: 'temperature = 0.7' },
  { el: temp12El, temperature: 1.2, key: 'temp12', label: 'temperature = 1.2' },
];

const temperatureCount = temperatureRequests.length;

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

function clearResponses() {
  temperatureRequests.forEach(({ el }) => {
    el.textContent = '';
    setPanelLoading(el, false);
  });
  comparisonEl.textContent = '';
  comparisonEl.classList.remove('response-box--loading');
  logsEl.textContent = '';
}

async function fetchTemperature(prompt, temperature) {
  const response = await fetch('/api/chat/temperature', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt, temperature }),
  });

  if (!response.ok) {
    throw new Error(`Сервер вернул ошибку: ${response.status}`);
  }

  return response.json();
}

async function fetchComparison(prompt, answers) {
  const response = await fetch('/api/chat/compare-temperature-analysis', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      prompt,
      temp0: answers.temp0,
      temp07: answers.temp07,
      temp12: answers.temp12,
    }),
  });

  if (!response.ok) {
    throw new Error(`Сервер вернул ошибку: ${response.status}`);
  }

  return response.json();
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
  setLoading(true, 'Запросы отправлены параллельно...');

  const answers = { temp0: '', temp07: '', temp12: '' };
  const logChunks = [];
  let completedCount = 0;
  let hasError = false;

  temperatureRequests.forEach(({ el }) => {
    el.textContent = 'Ожидание ответа...';
    setPanelLoading(el, true);
  });
  comparisonEl.textContent = `Появится после получения всех ${temperatureCount} ответов...`;
  comparisonEl.classList.add('response-box--loading');

  const temperaturePromises = temperatureRequests.map(async ({ el, temperature, key, label }) => {
    try {
      const data = await fetchTemperature(prompt, temperature);
      if (requestId !== activeRequestId) {
        return;
      }

      const answer = data.response || 'Пустой ответ от LLM.';
      answers[key] = answer;
      el.textContent = answer;
      setPanelLoading(el, false);

      if (Array.isArray(data.logs)) {
        logChunks.push(`=== ${label} ===\n${data.logs.join('\n')}`);
      }

      completedCount += 1;
      setLoading(true, `Получено ${completedCount} из ${temperatureCount} ответов...`);
    } catch (error) {
      if (requestId !== activeRequestId) {
        return;
      }
      hasError = true;
      el.textContent = 'Ошибка получения ответа.';
      setPanelLoading(el, false);
      showError(
        error.message.includes('Failed to fetch')
          ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
          : error.message
      );
    }
  });

  await Promise.all(temperaturePromises);

  if (requestId !== activeRequestId) {
    return;
  }

  const allAnswersReady = temperatureRequests.every(({ key }) => answers[key]);
  if (!hasError && allAnswersReady) {
    setLoading(true, 'Формирую сравнение...');
    try {
      const comparisonData = await fetchComparison(prompt, answers);
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
