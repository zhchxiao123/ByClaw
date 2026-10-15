// Direct HARNESS acceptance through the original public APIs and platform WebSocket.
// Runs against an isolated environment only; credentials stay in the private state file.
//
//   BYCLAW_TEST_BASE_URL   http://<be>/byaiService
//   BYCLAW_TEST_WS_URL     ws://<be-ws>/byaiService/ws
//   BYCLAW_TEST_STATE      private JSON state file (0600)
//   node direct-chat-acceptance.mjs login <userCode> <passwordFromFixtureDb>
//   node direct-chat-acceptance.mjs model <endpoint> <fixtureToken>
//   node direct-chat-acceptance.mjs employee <name>
//   node direct-chat-acceptance.mjs chat <prompt> [sessionId]
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';

const baseUrl = process.env.BYCLAW_TEST_BASE_URL;
const wsUrl = process.env.BYCLAW_TEST_WS_URL;
const stateFile = process.env.BYCLAW_TEST_STATE;
assert.ok(baseUrl && wsUrl && stateFile, 'Provide BYCLAW_TEST_BASE_URL, BYCLAW_TEST_WS_URL and BYCLAW_TEST_STATE');
const state = fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : {};
const save = () => fs.writeFileSync(stateFile, JSON.stringify(state, null, 2), { mode: 0o600 });
const [command, ...args] = process.argv.slice(2);

function headers(user = state.user) {
  const auth = state.users?.[user];
  assert.ok(auth, `login ${user} first`);
  return { 'Content-Type': 'application/json', 'Beyond-Token': auth.token, 'SSO-TOKEN': auth.ssoToken, 'x-session-id': auth.sessionId };
}

async function api(route, payload, user) {
  const response = await fetch(`${baseUrl}${route}`, {
    method: 'POST', headers: headers(user), body: JSON.stringify(payload), signal: AbortSignal.timeout(30000),
  });
  const json = await response.json();
  if (json.code !== 0) throw new Error(`${route}: ${json.msg}`);
  return json.data;
}

if (command === 'login') {
  const [userCode, pwd] = args;
  const response = await fetch(`${baseUrl}/system/session/loginByUsername`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ loginType: '1', accountCode: userCode, accountPwd: pwd }),
  });
  const body = await response.json();
  assert.equal(body.code, 0, `login failed: ${body.msg}`);
  state.users = { ...(state.users ?? {}), [userCode]: {
    token: body.token, ssoToken: body.ssoToken, sessionId: body.sessionId, userId: body.data?.userId } };
  state.user = userCode;
  save();
  console.log(JSON.stringify({ login: 'ok', userCode, userId: body.data?.userId }));
}
else if (command === 'model') {
  const [endpoint, token] = args;
  const saved = await api('/new/model/upsertModel', { displayName: 'Direct 受控验收模型', providerName: 'OpenAI',
    modelProtocol: 'OpenAI', modelCode: 'byclaw-direct-fixture', modelType: 'LLM', status: 'DISABLED',
    contextTokens: 32768, abilities: ['3', '1'], systems: [], apiEndpoint: endpoint, apiToken: token,
    maxTokens: 4096, temperature: 0, connectTimeoutSec: 5, readTimeoutSec: 60, maxRetries: 0 });
  await api('/new/model/setModelStatus', { id: saved.id, status: 'ENABLED' });
  await api('/new/model/setDefaultModel', { modelId: saved.id, modelType: 'LLM' });
  state.modelId = saved.id;
  save();
  console.log(JSON.stringify({ modelId: saved.id, enabled: true, defaultLLM: true, provider: 'deterministic fixture' }));
}
else if (command === 'employee') {
  const [name] = args;
  const saved = await api('/digitalEmployeeController/saveDigitalEmployee', {
    resourceName: name, resourceDesc: 'Direct 隔离验收员工', resourceBizType: 'DIG_EMPLOYEE', agentType: '1', ownerType: 'enterprise',
    prologue: JSON.stringify({ modelId: state.modelId, modelInfo: { modelId: state.modelId } }), relIds: [],
  });
  state.employeeId = saved.resourceId ?? saved.ssResource?.resourceId ?? saved.id;
  save();
  console.log(JSON.stringify({ employeeId: state.employeeId, keys: Object.keys(saved) }));
}
else if (command === 'use') {
  state.user = args[0];
  save();
  console.log(JSON.stringify({ user: state.user }));
}
else if (command === 'chat') {
  const [prompt, sessionId] = args;
  const auth = state.users[state.user];
  const socket = new WebSocket(`${wsUrl}?beyond-token=${encodeURIComponent(auth.token)}&language=zh-CN&scoped-delta-version=1`);
  const frames = [];
  const started = Date.now();
  const result = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => resolve({ timeout: true }), Number(process.env.BYCLAW_TEST_TIMEOUT_MS ?? 240000));
    socket.addEventListener('error', (event) => reject(new Error(`websocket error ${event.message ?? ''}`)));
    // Same keep-alive as the original page (websocket.ts sends NOTIFICATION every 6s).
    const heartbeat = setInterval(() => socket.readyState === WebSocket.OPEN && socket.send(JSON.stringify({ type: 'NOTIFICATION' })), 6000);
    socket.addEventListener('close', () => clearInterval(heartbeat));
    socket.addEventListener('open', () => {
      const queryId = crypto.randomUUID().replace(/-/g, '');
      const answerId = crypto.randomUUID().replace(/-/g, '');
      socket.send(JSON.stringify({
        type: 'LLM_MESSAGE', clientRequestId: `${queryId}_${answerId}`, language: 'zh-CN', chatContent: prompt,
        relModelId: -1, accessTerminal: 'Web', agentId: state.employeeId, ...(sessionId ? { sessionId: Number(sessionId), chatId: Number(sessionId) } : {}),
        extParams: { requestId: process.env.BYCLAW_TEST_REQUEST_ID ?? crypto.randomUUID() },
        ...(process.env.BYCLAW_TEST_SOURCE_AGENT_TYPE ? { sourceAgentType: process.env.BYCLAW_TEST_SOURCE_AGENT_TYPE } : {}),
      }));
    });
    socket.addEventListener('message', (event) => {
      const frame = JSON.parse(event.data);
      frames.push(frame);
      if (frame.type !== 'CHAT_STREAM' && frame.type !== 'ERROR') return;
      const sub = typeof frame.data === 'string' ? (() => { try { return JSON.parse(frame.data); } catch { return {}; } })() : frame.data ?? {};
      const eventName = frame.event ?? sub.event;
      if (eventName === 'appStreamResponse' || eventName === 'error' || frame.type === 'ERROR') {
        clearTimeout(timer);
        resolve({ terminal: eventName ?? frame.type });
      }
    });
  });
  socket.close();
  const raw = JSON.stringify(frames);
  const events = frames.map((frame) => frame.event ?? (() => { try { return JSON.parse(frame.data).event; } catch { return undefined; } })()).filter(Boolean);
  const created = frames.map((frame) => { try { return JSON.parse(frame.data); } catch { return undefined; } })
    .find((data) => data?.event === 'createSession' || data?.sessionId);
  const session = sessionId ?? String(raw.match(/"sessionId":"?(\d+)/)?.[1] ?? created?.sessionId ?? '');
  fs.writeFileSync(`${stateFile}.frames-${Date.now()}.json`, raw, { mode: 0o600 });
  state.lastSessionId = session;
  save();
  for (const secret of [process.env.BYCLAW_TEST_FORBIDDEN_VALUES ?? ''].join(',').split(',').filter(Boolean)) {
    assert.ok(!raw.includes(secret), 'browser frames must not contain server-only credentials');
  }
  let answer = '';
  let reasoning = '';
  for (const frame of frames) {
    const data = typeof frame.data === 'object' ? frame.data : undefined;
    const content = data?.choices?.[0]?.delta?.content;
    if (typeof content !== 'string') continue;
    if (data.eventType === 'answerDelta') answer += content;
    else if (data.eventType === 'reasoningLogDelta' && String(data.metadata ?? '').includes('"think"')) reasoning += content;
  }
  const errors = frames.filter((frame) => frame.type === 'ERROR' || frame.data?.eventType === 'error'
    || String(frame.event ?? '') === 'error').map((frame) => JSON.stringify(frame).slice(0, 300));
  fs.writeFileSync(`${stateFile}.last-answer.txt`, answer, { mode: 0o600 });
  console.log(JSON.stringify({ ...result, sessionId: session, frames: frames.length, ms: Date.now() - started,
    events: [...new Set(events)], answer: answer.slice(0, 400), reasoning: reasoning.slice(0, 200), errors }));
}
else if (command === 'status') {
  const [sessionId, requestId] = args;
  const response = await fetch(`${baseUrl}/direct/admission/status`, {
    method: 'POST', headers: headers(), body: JSON.stringify({ sessionId: Number(sessionId), requestId }),
  });
  console.log(JSON.stringify(await response.json()));
}
else if (command === 'compare') {
  // The live answer assembled from the stream must equal the persisted history content exactly.
  const [sessionId] = args;
  const live = fs.readFileSync(`${stateFile}.last-answer.txt`, 'utf8');
  const data = await api('/assiman/getMessages', { sessionId, pageNum: 1, pageSize: 10 });
  const latest = (data.list ?? []).filter((m) => typeof m.messageContent === 'string' && m.inferLog)
    .sort((a, b) => Number(b.createTimeMillis) - Number(a.createTimeMillis))[0];
  const stored = latest?.messageContent ?? '';
  const sha = (value) => crypto.createHash('sha256').update(value).digest('hex').slice(0, 16);
  console.log(JSON.stringify({ liveLength: live.length, storedLength: stored.length, identical: live === stored,
    liveSha: sha(live), storedSha: sha(stored), answers: (data.list ?? []).filter((m) => m.messageId === latest?.messageId).length,
    hasTable: stored.includes('| --- | ---: |'), hasCode: stored.includes('```python'), hasLink: stored.includes('](https://') }));
}
else if (command === 'history') {
  const [sessionId] = args;
  const data = await api('/assiman/getMessages', { sessionId, pageNum: 1, pageSize: 100 });
  const text = JSON.stringify(data.list ?? []);
  for (const value of Object.values(state.users[state.user]).filter((v) => typeof v === 'string' && v.length > 20)) {
    assert.ok(!text.includes(value), 'history must not return authentication credentials');
  }
  console.log(JSON.stringify({ sessionId, messages: (data.list ?? []).length,
    contains: Object.fromEntries((args.slice(1)).map((needle) => [needle, text.includes(needle)])) }));
}
else {
  throw new Error(`unknown command ${command}`);
}
