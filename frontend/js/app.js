const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const unrestrictedEl = document.getElementById('response-unrestricted');
const formatOnlyEl = document.getElementById('response-format-only');
const lengthOnlyEl = document.getElementById('response-length-only');
const stopOnlyEl = document.getElementById('response-stop-only');
const fullControlEl = document.getElementById('response-full-control');
const logsEl = document.getElementById('logs');

const responseFields = [
  { el: unrestrictedEl, key: 'unrestricted', fallback: 'Пустой ответ от LLM.' },
  { el: formatOnlyEl, key: 'formatOnly', fallback: 'Пустой ответ от LLM.' },
  { el: lengthOnlyEl, key: 'lengthOnly', fallback: 'Пустой ответ от LLM.' },
  { el: stopOnlyEl, key: 'stopOnly', fallback: 'Пустой ответ от LLM.' },
  { el: fullControlEl, key: 'fullControl', fallback: 'Пустой ответ от LLM.' },
];

function setLoading(isLoading) {
  sendBtn.disabled = isLoading;
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

function clearResponses(message = '') {
  responseFields.forEach(({ el }) => {
    el.textContent = message;
  });
  logsEl.textContent = message;
}

async function sendPrompt() {
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showError('Введите промпт перед отправкой.');
    return;
  }

  clearError();
  setLoading(true);
  clearResponses('');

  try {
    const response = await fetch('/api/chat/compare', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt }),
    });

    if (!response.ok) {
      throw new Error(`Сервер вернул ошибку: ${response.status}`);
    }

    const data = await response.json();
    responseFields.forEach(({ el, key, fallback }) => {
      el.textContent = data[key] || fallback;
    });
    logsEl.textContent = data.logs || 'Логи не получены.';
  } catch (error) {
    showError(
      error.message.includes('Failed to fetch')
        ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
        : error.message
    );
    clearResponses('Ответ не получен.');
    logsEl.textContent = 'Логи недоступны из-за ошибки.';
  } finally {
    setLoading(false);
  }
}

sendBtn.addEventListener('click', sendPrompt);

promptInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    sendPrompt();
  }
});
