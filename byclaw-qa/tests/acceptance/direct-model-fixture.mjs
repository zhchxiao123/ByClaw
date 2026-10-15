// Deterministic OpenAI-compatible chat completions endpoint for Direct acceptance runs.
// It only decides what the model "says"; tools and the runtime execute for real.
//   - prompts containing [tool:bash] request one bash call, then summarise its real result
//   - every answer streams reasoning_content before content, as DeepSeek-style models do
// Usage: BYCLAW_FIXTURE_TOKEN=... node direct-model-fixture.mjs <host> <port>
import http from 'node:http';
import crypto from 'node:crypto';

const [host = '127.0.0.1', port = '18991'] = process.argv.slice(2);
const token = process.env.BYCLAW_FIXTURE_TOKEN ?? '';

function text(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((part) => part?.text ?? '').join('');
  return '';
}

function sse(res, payload) {
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

const server = http.createServer(async (req, res) => {
  if (req.method !== 'POST' || !req.url.endsWith('/chat/completions')) {
    res.writeHead(404).end();
    return;
  }
  const auth = req.headers.authorization ?? '';
  const expected = `Bearer ${token}`;
  if (!token || auth.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(auth), Buffer.from(expected))) {
    res.writeHead(401).end();
    return;
  }
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  const messages = body.messages ?? [];
  const lastUserIndex = messages.findLastIndex((m) => m.role === 'user');
  // Trusted platform context precedes the user text; the fixture answers only the visible request.
  const prompt = text(messages[lastUserIndex]?.content).replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<byclaw-inbound-turn>[\s\S]*?<\/byclaw-inbound-turn>/g, '').trim();
  const after = messages.slice(lastUserIndex + 1);
  const toolResult = after.findLast((m) => m.role === 'tool');
  const wantsTool = prompt.includes('[tool:bash]') && (body.tools ?? []).some((t) => t.function?.name === 'bash');
  const base = { id: `fixture-${crypto.randomUUID()}`, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: body.model };
  const usage = { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 };

  if (!body.stream) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ...base, object: 'chat.completion', choices: [{ index: 0, message: { role: 'assistant', content: 'fixture ok' }, finish_reason: 'stop' }], usage }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
  if (wantsTool && !toolResult) {
    const args = JSON.stringify({ command: "printf 'direct-tool-proof\\n'; id -un; pwd", description: '输出 Direct 验收标记' });
    sse(res, { ...base, choices: [{ index: 0, delta: { role: 'assistant', reasoning_content: '需要在私有运行环境里执行一次命令。' }, finish_reason: null }] });
    sse(res, { ...base, choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: `call_${crypto.randomUUID().slice(0, 8)}`, type: 'function', function: { name: 'bash', arguments: args } }] }, finish_reason: null }] });
    sse(res, { ...base, choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }], usage });
    res.end('data: [DONE]\n\n');
    return;
  }
  const reasoning = '正在核对 Direct 会话与可信上下文。';
  const answer = toolResult
    ? `Direct 工具结果：\n\n\`\`\`\n${text(toolResult.content).trim()}\n\`\`\`\n\n- 命令在私有 Cube 中实际执行`
    : `Direct 回答：${prompt}\n\n| 项 | 结果 |\n| --- | --- |\n| 通道 | Direct |\n| 运行环境 | 私有 Cube |`;
  sse(res, { ...base, choices: [{ index: 0, delta: { role: 'assistant', reasoning_content: reasoning }, finish_reason: null }] });
  for (const piece of answer.match(/[\s\S]{1,24}/g) ?? []) {
    sse(res, { ...base, choices: [{ index: 0, delta: { content: piece }, finish_reason: null }] });
  }
  sse(res, { ...base, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage });
  res.end('data: [DONE]\n\n');
});

server.listen(Number(port), host, () => console.log(JSON.stringify({ listening: `${host}:${port}` })));
