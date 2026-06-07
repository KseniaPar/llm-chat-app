const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const responseEl = document.getElementById('response');
const logsEl = document.getElementById('logs');

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

async function sendPrompt() {
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showError('Введите промпт перед отправкой.');
    return;
  }

  clearError();
  setLoading(true);
  responseEl.textContent = '';
  logsEl.textContent = '';

  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt }),
    });

    if (!response.ok) {
      throw new Error(`Сервер вернул ошибку: ${response.status}`);
    }

    const data = await response.json();
    responseEl.textContent = data.response || 'Пустой ответ от LLM.';
    logsEl.textContent = Array.isArray(data.logs) && data.logs.length > 0
      ? data.logs.join('\n')
      : 'Логи не получены.';
  } catch (error) {
    showError(
      error.message.includes('Failed to fetch')
        ? 'Не удалось связаться с backend. Убедитесь, что Spring Boot запущен на порту 8080.'
        : error.message
    );
    responseEl.textContent = 'Ответ не получен.';
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
