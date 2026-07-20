#!/usr/bin/env node
/**
 * Day 32 — standalone PR review for GitHub Actions / local CLI.
 * Uses OpenRouter + README/project docs + diff (no Spring Boot required).
 *
 * Usage:
 *   OPENROUTER_API_KEY=... node scripts/pr-review.mjs \
 *     --diff pr.diff --files changed.txt --title "PR title" --out review.md
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');

const MAX_DIFF = 28000;
const MAX_DOCS = 14000;
const MAX_EXCERPT = 2400;
const MAX_FILES = 10;

const SYSTEM_PROMPT = `Ты — senior reviewer репозитория llm-chat-app (Spring Boot + Vite + MCP + RAG).
Пиши ревью на русском, конкретно и по делу. Ссылайся на файлы/символы из diff.
Не выдумывай код, которого нет в контексте.
Обязательно используй ровно три секции с такими заголовками Markdown:

## Потенциальные баги
## Архитектурные проблемы
## Рекомендации

В каждой секции — маркированный список (или «не обнаружено» с кратким пояснением).
В конце можно добавить одну строку «Вердикт: …».`;

function parseArgs(argv) {
  const out = {
    diff: null,
    files: null,
    title: '',
    base: '',
    head: '',
    out: 'review.md',
    model: process.env.OPENROUTER_MODEL || 'openai/gpt-4o-mini',
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    const next = argv[i + 1];
    if (a === '--diff' && next) { out.diff = next; i++; }
    else if (a === '--files' && next) { out.files = next; i++; }
    else if (a === '--title' && next) { out.title = next; i++; }
    else if (a === '--base' && next) { out.base = next; i++; }
    else if (a === '--head' && next) { out.head = next; i++; }
    else if (a === '--out' && next) { out.out = next; i++; }
    else if (a === '--model' && next) { out.model = next; i++; }
  }
  return out;
}

function truncate(text, max, label) {
  if (!text) return '';
  if (text.length <= max) return text;
  return `${text.slice(0, max)}\n… [${label} truncated at ${max} chars]`;
}

function loadDocs() {
  const sources = [];
  const chunks = [];
  const readme = path.join(REPO_ROOT, 'README.md');
  const docsDir = path.join(REPO_ROOT, 'project', 'docs');
  if (fs.existsSync(readme)) {
    sources.push('README.md');
    chunks.push(`## README.md\n${fs.readFileSync(readme, 'utf8')}\n`);
  }
  if (fs.existsSync(docsDir)) {
    for (const name of fs.readdirSync(docsDir).sort()) {
      if (!/\.(md|txt)$/i.test(name)) continue;
      const rel = `project/docs/${name}`;
      sources.push(rel);
      chunks.push(`## ${rel}\n${fs.readFileSync(path.join(docsDir, name), 'utf8')}\n`);
    }
  }
  let text = '';
  for (const chunk of chunks) {
    if (text.length + chunk.length > MAX_DOCS) {
      text += '… [docs truncated]\n';
      break;
    }
    text += chunk + '\n';
  }
  return { text: text.trim() || '(no project docs)', sources };
}

function shouldSkip(file) {
  const lower = file.toLowerCase();
  return /\.(png|jpe?g|gif|webp|ico|jar|class|map)$/.test(lower)
    || lower.includes('/target/')
    || lower.includes('/node_modules/')
    || lower.includes('/.git/');
}

function loadExcerpts(files) {
  const sources = [];
  let text = '';
  let count = 0;
  for (const rel of files) {
    if (!rel || shouldSkip(rel)) continue;
    if (count >= MAX_FILES) {
      text += '… [more files omitted]\n';
      break;
    }
    const abs = path.join(REPO_ROOT, rel);
    if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) continue;
    try {
      const content = fs.readFileSync(abs, 'utf8');
      text += `### ${rel}\n\`\`\`\n${truncate(content, MAX_EXCERPT, rel)}\n\`\`\`\n\n`;
      sources.push(rel);
      count++;
    } catch {
      // skip binary / unreadable
    }
  }
  return { text: text.trim() || '(could not read changed files)', sources };
}

function parseFilesFromDiff(diff) {
  const files = new Set();
  for (const line of diff.split(/\r?\n/)) {
    if (line.startsWith('+++ b/') || line.startsWith('--- a/')) {
      const p = line.slice(6).trim();
      if (p && p !== '/dev/null') files.add(p.replace(/\\/g, '/'));
    } else if (line.startsWith('diff --git ')) {
      const parts = line.split(/\s+/);
      if (parts.length >= 4 && parts[3].startsWith('b/')) {
        files.add(parts[3].slice(2));
      }
    }
  }
  return [...files];
}

function ensureSections(markdown) {
  const lower = (markdown || '').toLowerCase();
  if (lower.includes('потенциальные баги')
    && lower.includes('архитектурные проблемы')
    && lower.includes('рекомендации')) {
    return markdown.trim();
  }
  return `## Потенциальные баги
- см. текст ниже (модель не разметила секции)

## Архитектурные проблемы
- см. текст ниже

## Рекомендации
- см. текст ниже

---
${markdown || ''}`.trim();
}

async function callOpenRouter({ apiKey, model, userPrompt }) {
  const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
      'HTTP-Referer': 'https://github.com/KseniaPar/llm-chat-app',
      'X-OpenRouter-Title': 'llm-chat-app-pr-review',
    },
    body: JSON.stringify({
      model,
      temperature: 0.2,
      max_tokens: 1800,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: userPrompt },
      ],
    }),
  });
  const raw = await res.text();
  if (!res.ok) {
    throw new Error(`OpenRouter HTTP ${res.status}: ${raw.slice(0, 500)}`);
  }
  const json = JSON.parse(raw);
  const content = json?.choices?.[0]?.message?.content;
  if (!content || !String(content).trim()) {
    throw new Error('OpenRouter returned empty content');
  }
  return String(content).trim();
}

async function main() {
  const args = parseArgs(process.argv);
  const apiKey = (process.env.OPENROUTER_API_KEY || '').trim();
  if (!apiKey || apiKey === 'local-llm-not-used') {
    console.error('OPENROUTER_API_KEY is required');
    process.exit(1);
  }
  if (!args.diff || !fs.existsSync(args.diff)) {
    console.error('--diff <file> is required and must exist');
    process.exit(1);
  }

  const diffRaw = fs.readFileSync(args.diff, 'utf8');
  const diff = truncate(diffRaw, MAX_DIFF, 'diff');
  let files = [];
  if (args.files && fs.existsSync(args.files)) {
    files = fs.readFileSync(args.files, 'utf8')
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean);
  } else {
    files = parseFilesFromDiff(diffRaw);
  }

  const docs = loadDocs();
  const excerpts = loadExcerpts(files);
  const filesList = files.map((f) => `- ${f}`).join('\n') || '(не указаны)';

  const userPrompt = `Заголовок PR/изменения: ${args.title || '(без заголовка)'}
Base: ${args.base || '(не указан)'}
Head: ${args.head || '(не указан)'}

Изменённые файлы:
${filesList}

--- Документация проекта ---
${docs.text}

--- Фрагменты изменённых файлов ---
${excerpts.text}

--- Unified diff ---
${diff}`;

  console.error(`pr-review: files=${files.length} diffChars=${diff.length} model=${args.model}`);
  const review = ensureSections(await callOpenRouter({
    apiKey,
    model: args.model,
    userPrompt,
  }));

  const header = `<!-- llm-chat-app day32 ai-review -->
## AI Code Review (Day 32)

_Автоматическое ревью по diff + документации проекта (\`README\`, \`project/docs\`)._

`;
  const body = `${header}${review}\n`;
  const outPath = path.isAbsolute(args.out) ? args.out : path.join(process.cwd(), args.out);
  fs.writeFileSync(outPath, body, 'utf8');
  console.error(`pr-review: wrote ${outPath} (${body.length} chars)`);
  process.stdout.write(body);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
