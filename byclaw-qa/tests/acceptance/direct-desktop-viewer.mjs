// Open the owner's desktop viewer stream through the platform like the noVNC page does (the viewer
// ticket is in the URL path) and report the VNC server greeting; optionally hold it open.
//   node direct-desktop-viewer.mjs <wsUrl> <stateFile> [holdMs]
const [url, stateFile, hold] = process.argv.slice(2);
void stateFile; // the ticket in the URL authenticates; no platform headers, like a browser frame
const socket = new WebSocket(url);
socket.binaryType = 'arraybuffer';
const result = await new Promise((resolve) => {
  const timer = setTimeout(() => resolve({ greeting: null, error: 'timeout' }), 20000);
  socket.addEventListener('message', (event) => {
    clearTimeout(timer);
    const text = Buffer.from(event.data).toString('latin1');
    resolve({ greeting: text.trim() });
  }, { once: true });
  socket.addEventListener('error', () => { clearTimeout(timer); resolve({ greeting: null, error: 'refused' }); });
  socket.addEventListener('close', (event) => { clearTimeout(timer); resolve({ greeting: null, closed: event.code }); });
});
console.log(JSON.stringify(result));
if (result.greeting && Number(hold ?? 0) > 0) await new Promise((resolve) => setTimeout(resolve, Number(hold)));
socket.close();
