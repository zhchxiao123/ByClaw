// Send one signed Direct admission straight to a runtime (isolated acceptance only).
// Used to drive runtime crash windows without the platform in between.
//   node direct-runtime-turn.mjs <baseUrl> <secretFile> <ownerScope> <userCode> <generation> \
//        <sessionId> <requestId> <text> [intentDigest]
import crypto from 'node:crypto';
import fs from 'node:fs';

const [baseUrl, secretFile, ownerScope, userCode, generation, sessionId, requestId, text, intent] = process.argv.slice(2);
const secret = fs.readFileSync(secretFile, 'utf8').trim();
const traceId = crypto.createHash('sha256').update(`${sessionId}:${requestId}`).digest('hex').slice(0, 32);
const body = Buffer.from(JSON.stringify({
  protocol: 'byclaw.direct/v1',
  command: {
    header: {
      message_id: `${Date.now()}`, session_id: sessionId, trace_id: traceId, source_agent_type: '',
      target_agent_type: `BYCLAW_DSH_${userCode}`, parent_message_id: '-1', user_code: userCode, user_name: userCode,
      metadata: { requestId, language: 'zh-CN' },
    },
    body: { content: text, wait_for_reply: false, extra_payload: { agent_id: process.env.BYCLAW_TEST_AGENT_ID ?? '' } },
  },
}));
const admission = Buffer.from(JSON.stringify({
  protocol: 'byclaw.direct/v1', ownerScope, userCode, generation: Number(generation), requestId, sessionId, traceId,
  bodySha256: crypto.createHash('sha256').update(body).digest('hex'),
  intentDigest: intent ?? crypto.createHash('sha256').update(`${requestId}:${text}`).digest('hex'),
  expiresAt: Date.now() + 60_000,
})).toString('base64url');
const signature = crypto.createHmac('sha256', secret).update(admission).digest('hex');
let response;
try {
  response = await fetch(`${baseUrl}/plugins/byclaw-direct/v1/turns`, {
    method: 'POST', body, signal: AbortSignal.timeout(120_000),
    headers: { 'content-type': 'application/json', 'x-byclaw-direct-admission': admission, 'x-byclaw-direct-signature': signature },
  });
} catch (error) {
  console.log(JSON.stringify({ transport: 'failed', error: String(error?.cause?.code ?? error) }));
  process.exit(0);
}
const lines = [];
try {
  const raw = await response.text();
  for (const line of raw.trim().split('\n').filter(Boolean)) lines.push(JSON.parse(line));
} catch (error) {
  lines.push({ type: 'broken', error: String(error?.cause?.code ?? error) });
}
const answer = lines.filter((l) => l.type === 'event' && l.data?.event_type === 'answerDelta')
  .map((l) => l.data.data?.choices?.[0]?.delta?.content ?? '').join('');
console.log(JSON.stringify({ http: response.status, types: lines.map((l) => l.type),
  admission: lines.find((l) => l.type === 'admission')?.state, end: lines.find((l) => l.type === 'end')?.state,
  error: lines.find((l) => l.error)?.error ?? (response.ok ? undefined : lines), answer: answer.slice(0, 120) }));
