# Mac verification (desktop client, ad-hoc signed)

Target: a validation environment (replace `<host>` with its address throughout), web `http://<host>:18080/beyond/` (log in with the page's own 登录 button). Needs the device relay routed at
`http://<host>:18080/device-relay/` and BE pairing enabled
(`BYCLAW_DEVICE_TOKEN_SECRET`). Quick check from the Mac:
`curl -s -o /dev/null -w '%{http_code}\n' http://<host>:18080/device-relay/node` should print
`400`/`401`/`426` (relay answering), not `404`/`502`.

The code must be reachable from the Mac: branch `feat/byclaw-desktop` (push it to the fork first, or
copy the `byclaw-desktop/` directory over).

## Prompt for Codex (paste as is)

```text
You are on my Mac. Build and launch the ByClaw desktop client from source and report back.
Do not change any source files; if a step fails, stop and show me the exact error output.

1. Check tools: `xcode-select -p` (install Command Line Tools if missing), `node -v` (need >= 22),
   `corepack --version`. Print `uname -m`.
2. Get the code: `git clone --branch feat/byclaw-desktop --depth 1 https://github.com/zhchxiao123/ByClaw.git ~/byclaw-desktop-src`
   (if it already exists: `git -C ~/byclaw-desktop-src fetch --depth 1 origin feat/byclaw-desktop && git -C ~/byclaw-desktop-src reset --hard FETCH_HEAD`).
3. `cd ~/byclaw-desktop-src/byclaw-desktop && corepack enable && pnpm install`
4. Run the tests on macOS: `pnpm -r test` and `pnpm -r typecheck`. Report the pass/fail counts per package.
5. Build: `pnpm --filter @byclaw/desktop dist:mac`. List `app/release/` with sizes.
6. Check the app for this Mac's architecture (`app/release/mac-arm64/ByClaw.app` on Apple Silicon,
   `app/release/mac/ByClaw.app` on Intel):
   - `codesign -dv --verbose=2 <app> 2>&1 | grep -E 'Signature|Identifier|flags'` → expect `Signature=adhoc`
   - `codesign --verify --deep --strict <app>` → no output, exit 0
   - `npx --yes @electron/asar list <app>/Contents/Resources/app.asar | grep -E 'node-runtime/dist/index.js|/ws/package.json|dist/main.js|dist/preload.cjs'`
     → all four present
   - `ls <app>/Contents/Resources/bycli/node_modules/@sovovs/bycli/dist/src/daemon.js <app>/Contents/Resources/bycli/node_modules/@sovovs/bycli/extension/manifest.json`
     and `node -p "require('<app>/Contents/Resources/bycli/node_modules/@sovovs/bycli/package.json').version"`
     → both files exist (plain files, not in app.asar), version `2.1.78`
7. Install: quit any running ByClaw, `rm -rf /Applications/ByClaw.app`, copy the app to /Applications,
   then `xattr -dr com.apple.quarantine /Applications/ByClaw.app`.
8. Preconfigure for the test environment (skips the first-run page):
   mkdir -p ~/Library/Application\ Support/ByClaw
   cat > ~/Library/Application\ Support/ByClaw/config.json <<'EOF'
   { "webUrl": "http://<host>:18080/beyond/",
     "extraOrigins": [], "allowedDirs": [], "capabilities": ["shell", "files.read", "files.write", "browser"],
     "launchAtLogin": true, "paused": false }
   EOF
   (If config.json already exists from an earlier run, show it to me first instead of overwriting.)
9. Check the relay answers: `curl -s -o /dev/null -w '%{http_code}\n' http://<host>:18080/device-relay/node`.
10. Launch: `open /Applications/ByClaw.app`, wait 10 s, then report: whether a window and a menu-bar
    icon appeared, `ls -la ~/Library/Application\ Support/ByClaw/`, and
    `tail -50 ~/Library/Application\ Support/ByClaw/main.log`.
    Also report the byCLI daemon: `lsof -nP -iTCP:19825 -sTCP:LISTEN` (expect one `ByClaw` process on
    `127.0.0.1:19825`, nothing on `*:19825`), `curl -s -H 'X-byCLI: 1' http://127.0.0.1:19825/status`
    (expect `"daemonVersion":"2.1.78"`), and `ls ~/Library/Application\ Support/ByClaw/chrome-extension`
    (expect `manifest.json`, `dist/`).
    If the window is blank, also run it from the terminal for 30 s and show the output:
    `BYCLAW_DESKTOP_DEVTOOLS=1 ELECTRON_ENABLE_LOGGING=1 /Applications/ByClaw.app/Contents/MacOS/ByClaw`
    (a DevTools window opens; copy any red errors from its Console), then `tail -80 ~/Library/Application\ Support/ByClaw/main.log`.
Then tell me to continue with the manual checklist.
```

## Manual checklist

Use a user that is **on** the shared-host list and one that is not, if time allows.

1. **Open** – if macOS blocks the app: right-click → 打开 (or the `xattr` command above). Window opens on
   the ByClaw page.
2. **Login** – click 登录 on the page and log in with a test user; the window stays on the ByClaw chat page. Chat once: same as
   the web version (streaming, history, attachments, voice input asks for the microphone once).
3. **Pairing** – within ~5 s the menu-bar menu shows `ByClaw · 在线` and `设备：<Mac name>`;
   「我的设备」 in the web lists this Mac as online. Settings (授权目录…) show a 设备 ID.
4. **Links** – a link to an outside site opens in the default browser, not in the window.
5. **Tray / window** – close the window: app stays in the menu bar; 打开 ByClaw brings it back;
   Cmd+Q / 退出 quits.
6. **Confirm dialog (shell)** – ask the agent: “在我的电脑上运行 `sw_vers`”. A native dialog shows
   device name, 执行命令 and the command, buttons 允许一次 / 本会话内允许 / 拒绝 (no 始终允许 for shell).
   允许一次 → the agent answers with the macOS version. Ask again → dialog again.
7. **Deny** – ask for `touch ~/Desktop/byclaw-should-not-exist`, click 拒绝 → agent says the user denied
   it; the file does not exist.
8. **Session allow** – 本会话内允许 on one command; a second command in the same chat runs without a
   dialog; in a new chat the dialog comes back.
9. **Hidden window notification** – close the window, then trigger a command from the web (another
   browser) or IM: a notification “ByClaw 需要你确认” appears and the dialog comes up.
10. **Allowed dir** – ask the agent to list `~/Documents`. First time: “不在授权目录内” → 选择授权目录…
    → pick `~/Documents` → then the read confirmation (offers 始终允许（仅读取文件）). Choose 始终允许;
    a second list/read in `~/Documents` runs without a dialog. (macOS itself may first ask whether
    ByClaw may access the Documents folder; allow it.) Settings show `~/Documents`; remove it
    there → the next read asks for a folder again.
11. **Forbidden** – ask to read `~/.ssh/config` (or add `~/.ssh` as a dir): refused as not allowed, no
    dialog, settings refuse to add it.
12. **Audit** – 查看操作记录 lists every step above with decision and outcome; no file contents or
    command output.
13. **Pause / resume** – 暂停我的电脑: the menu shows 已暂停; after ~60 s the agent no longer has device
    tools (ask “列出我的设备”). 恢复 → 在线, tools come back. Pause survives an app restart.
14. **Autostart** – menu 开机自启 is checked; System Settings → General → Login Items lists ByClaw.
    Reboot (or log out/in): ByClaw starts into the menu bar without a window and goes 在线.
15. **Unpair** – in 「我的设备」 unbind this Mac: the menu changes to `未连接：这台电脑已在「我的设备」中解绑`
    and offers 重新配对; the agent loses device tools. 重新配对 pairs again as a new device.
16. **Rename** – settings → 设备名称 → 保存: the menu and the relay presence show the new name (the BE
    device list keeps the name given at pairing, see open questions).

### Chrome (device browser, P2a)

Needs Google Chrome on the Mac and the plugin side of issue 07 (`device_browser`) on the host.

17. **Install the extension** – tray → 授权目录… (settings) → 「连接 Chrome」 shows 状态
    `未安装扩展（或 Chrome 没有打开）` and the steps. 打开扩展目录 opens
    `~/Library/Application Support/ByClaw/chrome-extension` in Finder. In Chrome: `chrome://extensions`
    → 开启「开发者模式」 → 「加载已解压的扩展程序」 → pick that folder. The byCLI extension appears
    (note its ID for the report).
18. **Chrome 已连接** – within ~10 s the settings show `已连接` (steps hidden) and the tray shows
    `Chrome：已连接`. Quit Chrome: back to `Chrome：未连接` within ~10 s, and after the host's debounce
    the agent no longer offers the Chrome tool. Reopen Chrome: connected again without reloading the
    extension.
19. **Open and read** – in a chat ask: 「在我电脑上的 Chrome 里打开 https://example.com 并告诉我标题」.
    A dialog `AI 请求在你的 Chrome 里访问 example.com` shows the command and offers 允许一次 /
    本会话内允许这个网站 / 拒绝. Choose 本会话内允许这个网站 → a Chrome window/tab opens example.com and the
    agent answers `Example Domain`. A follow-up on the same site in the same chat (「截个图给我看」) runs
    without a dialog.
20. **Bind a logged-in tab** – in Chrome, open a site you are logged in to (e.g. your mail) and keep
    that tab in front. Ask: 「接管我 Chrome 当前的标签页，告诉我页面上有什么」. A dialog
    `AI 请求接管你当前的 Chrome 标签页` shows that tab's 标题 and 网址; allow → the agent describes the
    page as you see it (your login, no new login prompt).
21. **Click** – on the bound tab ask the agent to click a harmless link or button it found; it happens
    in your Chrome without another dialog (site already allowed in this chat).
22. **Screenshot** – ask for a screenshot of the bound tab; the chat shows the image (the model
    describes it correctly). Full-page screenshots of long pages still arrive (shrunk to ≤ 2 MB).
23. **eval asks every time** – ask the agent to run `document.title` with eval (「用 eval 读一下
    document.title」): a warning dialog `AI 请求在 <site> 上运行脚本` shows the script with only 允许一次 /
    拒绝. Allow once; ask again → the dialog comes again.
24. **Deny** – ask to open another site (e.g. https://www.wikipedia.org) and click 拒绝: the agent says
    you refused; Chrome did not navigate. In a new chat, example.com asks again.
25. **Audit** – 查看操作记录 lists the device_browser steps as `<session> <command> @<site>` with
    decisions; typed text shows only as `…（N 个字符）`, and no page content appears.
26. **Quit** – 退出 ByClaw: `lsof -nP -iTCP:19825` shows nothing (the daemon stopped with the app);
    Chrome keeps running, the extension shows the daemon as unreachable.

Report per item: pass / fail + screenshot of anything unexpected.
