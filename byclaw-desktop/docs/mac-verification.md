# Mac verification (desktop client, ad-hoc signed)

Target: the 118 validation environment, web `http://118.178.253.73:18080/beyond/`, mock login
`http://118.178.253.73:8888/mockLogin/`. Needs the device relay routed at
`http://118.178.253.73:18080/device-relay/` (issue 02 / 06) and BE pairing enabled
(`BYCLAW_DEVICE_TOKEN_SECRET`, issue 01). Quick check from the Mac:
`curl -s -o /dev/null -w '%{http_code}\n' http://118.178.253.73:18080/device-relay/node` should print
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
7. Install: quit any running ByClaw, `rm -rf /Applications/ByClaw.app`, copy the app to /Applications,
   then `xattr -dr com.apple.quarantine /Applications/ByClaw.app`.
8. Preconfigure for the 118 test environment (skips the first-run page):
   mkdir -p ~/Library/Application\ Support/ByClaw
   cat > ~/Library/Application\ Support/ByClaw/config.json <<'EOF'
   { "webUrl": "http://118.178.253.73:18080/beyond/", "loginUrl": "http://118.178.253.73:8888/mockLogin/",
     "extraOrigins": [], "allowedDirs": [], "capabilities": ["shell", "files.read", "files.write"],
     "launchAtLogin": true, "paused": false }
   EOF
   (If config.json already exists from an earlier run, show it to me first instead of overwriting.)
9. Check the relay answers: `curl -s -o /dev/null -w '%{http_code}\n' http://118.178.253.73:18080/device-relay/node`.
10. Launch: `open /Applications/ByClaw.app`, wait 10 s, then report: whether a window and a menu-bar
    icon appeared, `ls -la ~/Library/Application\ Support/ByClaw/`, and
    `tail -50 ~/Library/Application\ Support/ByClaw/main.log`.
Then tell me to continue with the manual checklist.
```

## Manual checklist

Use a user that is **on** the 118 shared-host list and one that is not, if time allows.

1. **Open** – if macOS blocks the app: right-click → 打开 (or the `xattr` command above). Window opens on
   the mock login page.
2. **Login** – log in through mock login; the window lands on the ByClaw chat page. Chat once: same as
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

Report per item: pass / fail + screenshot of anything unexpected.
