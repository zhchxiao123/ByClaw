/** Live public API contract. Requires an isolated, already-populated Chat fixture. */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const baseUrl = process.env.BYCLAW_TEST_BASE_URL;
const authFile = process.env.BYCLAW_TEST_AUTH_FILE;
const sessionId = process.env.BYCLAW_TEST_SESSION_ID;
const expectedWorkspace = process.env.BYCLAW_TEST_EXPECTED_WORKSPACE;
assert.ok(baseUrl && authFile && sessionId, 'Provide the explicit acceptance URL, private auth file and session ID');
const { body: auth } = JSON.parse(await fs.readFile(authFile, 'utf8'));
const response = await fetch(`${baseUrl}/assiman/getMessages`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Beyond-Token': auth.token,
    'SSO-TOKEN': auth.ssoToken, 'x-session-id': auth.sessionId },
  body: JSON.stringify({ sessionId, pageNum: 1, pageSize: 100 }),
  signal: AbortSignal.timeout(15000),
});
assert.equal(response.status, 200, 'Original history API must respond successfully');
const json = await response.json();
assert.equal(json.code, 0, 'Original history API must return its success code');
assert.ok(Array.isArray(json.data?.list) && json.data.list.length >= 4, 'Persisted multi-turn history must be available');
const text = JSON.stringify(json.data.list);
assert.ok(text.includes('legacy-tool-proof'), 'History must retain the independently known command output');
assert.ok(text.includes('Legacy 回归：Legacy第四轮'), 'History must retain the completed fourth-turn answer');
if (expectedWorkspace) {
  assert.ok(text.includes(expectedWorkspace), 'The actual tool cwd must match the trusted project workspace');
}
for (const value of [auth.token, auth.ssoToken]) {
  if (typeof value === 'string' && value.length > 0) {
    assert.ok(!text.includes(value), 'Public history must not return authentication credentials');
  }
}
console.log(JSON.stringify({ sessionId, messageCount: json.data.list.length, history: 'passed',
  actualToolOutput: 'passed', completedAnswer: 'passed', credentialExposure: 'absent',
  projectWorkspace: expectedWorkspace ? 'passed' : 'not requested' }));
