// Isolated acceptance only: pass-through proxy in front of the real CubeAPI and CubeProxy that
// injects the faults named in a control file, so capacity, pause failure and protocol mismatch
// can be driven through the platform without touching shared Cube quotas.
//   node direct-cube-fault-proxy.mjs <faultsFile> <apiListen> <apiTarget> <proxyListen> <proxyTarget>
//   e.g. /qa/cube-faults.json 0.0.0.0:13100 127.0.0.1:3100 0.0.0.0:13080 172.21.231.223:80
// faultsFile (re-read per request; absent or {} = no faults):
//   {"create": 429, "connect": 429, "pause": 500, "statusProtocol": "byclaw.direct/v0"}
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';

const [faultsFile, apiListen, apiTarget, proxyListen, proxyTarget] = process.argv.slice(2);
const split = (value) => { const at = value.lastIndexOf(':'); return [value.slice(0, at), Number(value.slice(at + 1))]; };

function faults() {
  try { return JSON.parse(fs.readFileSync(faultsFile, 'utf8')); } catch { return {}; }
}

function apiFault(req) {
  const f = faults();
  if (req.method === 'POST' && req.url.split('?')[0] === '/sandboxes' && f.create) return ['create', f.create];
  if (req.method === 'POST' && /\/sandboxes\/[^/]+\/connect$/.test(req.url.split('?')[0]) && f.connect) return ['connect', f.connect];
  if (req.method === 'POST' && /\/sandboxes\/[^/]+\/pause$/.test(req.url.split('?')[0]) && f.pause) return ['pause', f.pause];
  return null;
}

function forward(req, res, [host, port], rewrite) {
  const upstream = http.request({ host, port, method: req.method, path: req.url, headers: req.headers }, (answer) => {
    if (!rewrite) {
      res.writeHead(answer.statusCode, answer.headers);
      answer.pipe(res);
      return;
    }
    const chunks = [];
    answer.on('data', (chunk) => chunks.push(chunk));
    answer.on('end', () => {
      const body = rewrite(Buffer.concat(chunks).toString('utf8'));
      const headers = { ...answer.headers, 'content-length': Buffer.byteLength(body) };
      delete headers['transfer-encoding'];
      res.writeHead(answer.statusCode, headers);
      res.end(body);
    });
  });
  upstream.on('error', (error) => { res.writeHead(502); res.end(String(error.code ?? error)); });
  req.pipe(upstream);
}

http.createServer((req, res) => {
  const fault = apiFault(req);
  if (fault) {
    const [call, status] = fault;
    console.log(JSON.stringify({ injected: call, status, url: req.url }));
    req.resume();
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ code: status, message: `injected ${call} fault: insufficient capacity or node refused` }));
    return;
  }
  forward(req, res, split(apiTarget));
}).listen(split(apiListen)[1], split(apiListen)[0]);

const proxyServer = http.createServer((req, res) => {
  const protocol = faults().statusProtocol;
  const status = req.method === 'GET' && req.url.split('?')[0] === '/plugins/byclaw-direct/v1/status';
  if (protocol && status) console.log(JSON.stringify({ injected: 'statusProtocol', protocol }));
  forward(req, res, split(proxyTarget), protocol && status ? (body) => {
    try { return JSON.stringify({ ...JSON.parse(body), protocol }); } catch { return body; }
  } : null);
});
// WebSocket upgrades (e.g. the desktop viewer stream) pass through untouched.
proxyServer.on('upgrade', (req, client, head) => {
  const [host, port] = split(proxyTarget);
  const upstream = net.connect(port, host, () => {
    const lines = [`${req.method} ${req.url} HTTP/${req.httpVersion}`];
    for (let index = 0; index < req.rawHeaders.length; index += 2) lines.push(`${req.rawHeaders[index]}: ${req.rawHeaders[index + 1]}`);
    upstream.write(`${lines.join('\r\n')}\r\n\r\n`);
    if (head.length > 0) upstream.write(head);
    client.pipe(upstream);
    upstream.pipe(client);
  });
  upstream.on('error', () => client.destroy());
  client.on('error', () => upstream.destroy());
});
proxyServer.listen(split(proxyListen)[1], split(proxyListen)[0]);

console.log(JSON.stringify({ api: apiListen, proxy: proxyListen }));
