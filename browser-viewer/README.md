# Verification: browser desktop of the tool sandbox in the chat drawer (PR for `fix/browser-viewer-on-tool-sandbox`)

Test environment: the standalone deploy kit with the OpenSandbox provider; the browser runs in each user's tool sandbox
(`BYCLAW_BROWSER_ON_TOOL_ENV=1`, desktop on port 8082 declared as instance `vnc`). FE and the browser plugin built from the branch.

| file | shows |
|---|---|
| `before-fix-error-page.png` | before: the drawer opened at the first browser tool call, before the desktop listened; the iframe shows the sandbox proxy's "could not connect" error |
| `auto-open-connected.png` | after: the opener probes the desktop (proxy answers 502, 502, then 302) and opens the drawer only then; it shows "已连接" and the page the assistant opened |
| `manual-click-no-desktop.png` | after: the sandbox is running but its browser is closed; clicking "远程电脑" shows "远程桌面暂不可用：助理的浏览器未打开或已关闭" instead of an empty drawer |
