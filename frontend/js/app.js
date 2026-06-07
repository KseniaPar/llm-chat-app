const promptInput = document.getElementById('prompt');
const sendBtn = document.getElementById('send-btn');
const loadingEl = document.getElementById('loading');
const errorEl = document.getElementById('error');
const directEl = document.getElementById('response-direct');
const stepByStepEl = document.getElementById('response-step-by-step');
const metaPromptTextEl = document.getElementById('response-meta-prompt-text');
const metaPromptAnswerEl = document.getElementById('response-meta-prompt-answer');
const expertsEl = document.getElementById('response-experts');
const comparisonEl = document.getElementById('response-comparison');
const logsEl = document.getElementById('logs');

const demoExamples = {
  professions: `У Ани, Бори и Вити разные профессии: инженер, врач, учитель.
Аня не врач. Боря не инженер. Витя — учитель.
Кто кем работает?`,
  knights: `На острове живут рыцари (всегда говорят правду) и лжецы (всегда лгут).
А сказал: «Б — лжец».
Б сказал: «А и В одного типа».
В сказал: «А — рыцарь».
Кто рыцарь, а кто лжец?`,
  ages: `У трёх друзей — Аня, Боря и Витя — разный возраст: один старший, один средний, один младший.
Аня старше Бори.
Витя не самый младший.
Боря не самый старший.
Расставьте их по возрасту от старшего к младшему.`,
  switches: `В комнате 3 лампы, снаружи 3 выключателя.
Вы можете зайти в комнату только один раз.
Как определить, какой выключатель к какой лампе относится?`,
};

const responseFields = [
  { el: directEl, key: 'direct', fallback: 'Пустой ответ от LLM.' },
  { el: stepByStepEl, key: 'stepByStep', fallback: 'Пустой ответ от LLM.' },
  { el: expertsEl, key: 'experts', fallback: 'Пустой ответ от LLM.' },
  { el: comparisonEl, key: 'comparison', fallback: 'Пустой ответ от LLM.' },
];

function setLoading(isLoading) {
  sendBtn.disabled = isLoading;
  document.querySelectorAll('.demo-btn').forEach((button) => {
    button.disabled = isLoading;
  });
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
  metaPromptTextEl.textContent = message;
  metaPromptAnswerEl.textContent = message;
  logsEl.textContent = message;
}

async function sendPrompt() {
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showError('Введите логическую задачу перед отправкой.');
    return;
  }

  clearError();
  setLoading(true);
  clearResponses('');

  try {
    const response = await fetch('/api/chat/compare-reasoning', {
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
    metaPromptTextEl.textContent = data.metaPrompt || 'Пустой ответ от LLM.';
    metaPromptAnswerEl.textContent = data.metaPromptAnswer || 'Пустой ответ от LLM.';
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
