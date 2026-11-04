# byclaw-desktop

ByClaw desktop client (macOS first) and the device node it embeds. The window is the normal
ByClaw web app; the node lets the user's own agent (A) run commands and touch files on this
computer, each step confirmed locally. Protocol and decisions: desktop-node PRD and `protocol.md`.

| Package | What |
|---|---|
| `node-runtime/` (`@byclaw/node-runtime`) | Pure Node (only dependency `ws`). Relay client, `device_shell` / `device_files` executors, path confinement, confirmation gate, audit log, pairing client, and the `byclaw-node` CLI. Runs on macOS and Linux. |
| `app/` (`@byclaw/desktop`) | Electron shell: window, tray, native confirmation dialogs, pairing from the logged-in page, settings and audit pages, macOS packaging. |

## Setup

Node 22+, pnpm 11 via corepack:

```sh
cd byclaw-desktop
corepack enable
pnpm install            # Electron's binary is fetched lazily on first `electron .` / build
pnpm -r test            # vitest in both packages
pnpm -r typecheck
```

## Headless node (`byclaw-node`)

```sh
pnpm --filter @byclaw/node-runtime build
CLI=node-runtime/dist/cli.js

# Pair with the web login JWT (localStorage "Beyond-Token" in the browser); writes ~/.byclaw-node/device-token (0600)
node $CLI --pair --be https://byclaw.example.com --login-token "$JWT" --name my-box

# Run
node $CLI --relay wss://byclaw.example.com/device-relay/node \
  --allow-dir ~/work --confirm prompt          # or auto-allow / auto-deny
```

Options: `--token` / `--token-file`, `--allow-dir` (repeatable), `--confirm auto-allow|auto-deny|prompt`
(default `prompt` on a terminal, else `auto-deny`), `--audit-dir`, `--state-dir` (default `~/.byclaw-node`:
token, `grants.json`, `audit/`), `--capabilities shell,files.read,files.write`, `--shell-timeout <s>`,
`--name`. `--pair` plus `--relay` pairs and then runs. Exit code 3 = the relay refused the token
(4401 invalid / 4403 revoked); pair again.

Interop smoke against the byclaw-device plugin's mock relay (Node only):
`node node-runtime/scripts/interop-smoke.mjs <…/byclaw-device/tests/e2e/relay.mjs>`.

### Runtime API

```ts
import { startNode, autoAllow, AuditLog, JsonGrantStore } from '@byclaw/node-runtime'
const node = startNode({ relayUrl, deviceToken, name, appVersion, allowedDirs: ['/Users/me/work'],
  confirm: autoAllow, audit: new AuditLog(dir), grants: new JsonGrantStore(file) })
node.on('status', info => {}) // connecting | online | paused | offline (+ reason: disconnected|unpaired|revoked|stopped)
node.pause(); node.resume(); await node.stop()
node.setAllowedDirs(dirs); node.setCapabilities(caps); node.setName(name)
```

`confirm({ tool, args, session, device, kind, summary, options, signal })` resolves to
`allow_once | allow_session | allow_always | deny`. Optional `requestAllowedDir({ path })` lets the UI
offer a folder picker when a path is outside every allowed dir.

## Behaviour (what to expect)

* **Confirmation.** Every call is confirmed unless a grant covers it. *本会话内允许* is remembered per
  A-side session id and kind: for shell it covers **any** command in that session; for files, reads
  (list/read/search) and writes are separate grants. *始终允许* is offered and honoured only for file
  reads, remembered per allowed dir (`grants.json`); removing the dir from the allowed list makes the
  grant inert. Fixed policies (`auto-allow`/`auto-deny`) send no `human_wait`. A confirmation open for
  300 s counts as denied.
* **Files.** Paths must be absolute (`~` expanded). Checked lexically, then after `realpath`; a new
  file's parent is resolved; dangling symlinks are refused for writes; search never follows symlinks
  and skips `.git`, `node_modules`, `.Trash`. Always forbidden: `~/.ssh`, `~/.gnupg`, keychains,
  Safari/Chrome/Chromium/Firefox/Edge/Brave profiles, `~/Library/Cookies`, and the client's own data dir.
  8 MB per read/write; binary files are not returned.
* **Shell.** Runs `$SHELL -c` in the login-shell environment (captured once with `$SHELL -ilc` on
  macOS, 10 s timeout, fallback `process.env`), minus `BYCLAW_*`, `ELECTRON_*`, `*DEVICE_TOKEN*`,
  `NODE_OPTIONS` and any value containing the device token. `cwd` must be in an allowed dir (default:
  home). 120 s timeout → `timeout`; cancel / timeout / pause kill the process group. stdout and stderr are
  each capped at 64 KB with a marker. Non-zero exit → `failed` with exit code and output. The shell is
  not sandboxed: the per-command confirmation is the boundary.
* **Connection.** 10 s heartbeat plus ws ping; reconnect 1, 2, 4 … 30 s; close codes 4401/4403 (or HTTP
  401/403) stop reconnecting. A disconnect cancels running calls (A reports them as interrupted).
* **Audit.** `audit/audit-YYYY-MM-DD.jsonl`: time, invoke id, session, tool, command/path summary
  (truncated), cwd, decision, outcome, exit code, ms. Never file contents or command output. 30 days.

## Desktop app

### Dev run (macOS)

```sh
BYCLAW_DESKTOP_URL=http://<host>:18080/beyond/ pnpm --filter @byclaw/desktop start
```

Without the env var, first launch shows a setup page (web URL, optional login entry such as a mock
login page, optional extra SSO origins). Settings live in `~/Library/Application Support/ByClaw/`:

| File | |
|---|---|
| `config.json` | `webUrl`, `loginUrl`, `extraOrigins`, `relayUrl` / `apiBaseUrl` overrides, `deviceName`, `deviceId`, `allowedDirs`, `capabilities`, `launchAtLogin`, `paused` |
| `device-token.bin` | device token encrypted with Electron `safeStorage` (key in the login keychain) |
| `grants.json`, `audit/` | always-allow read grants, audit log |
| `main.log` | main-process log (status, pairing, relay errors; no tokens), reset past 5 MB |

Relay URL default: same origin as the web URL, `ws(s)://<host>[:port]/device-relay/node`
(`BYCLAW_DESKTOP_RELAY_URL` or `relayUrl` overrides). Pair API: `<web origin>/byaiService/device/pair`.

**Pairing.** When the window is on the web origin and logged in, main reads `localStorage["Beyond-Token"]`
in an isolated world (page scripts cannot intercept it) and calls the pair API once through the window's
session (`session.fetch`, so cookies match). If there is no token but the host has a `SESSION` cookie
(a login done on another port, e.g. mock login), the pair call relies on that cookie. The device token
is stored encrypted and is never exposed to the page: the web window has no preload, no Node, sandbox
on; navigation is limited to the web origin (+ login/SSO origins), everything else and `window.open`
go to the system browser. After the relay refuses the token (unpaired in 我的设备), the token is
deleted and the tray offers 重新配对; it does not re-pair on its own.

**Tray.** Status (在线 / 连接中 / 离线 / 已暂停 / 未配对), 暂停我的电脑 / 恢复 (persisted), 授权目录…,
查看操作记录, 开机自启 (default on), 打开登录页 (if configured), 退出. Closing the window hides it.

### Build (macOS, arm64 + x64)

```sh
pnpm --filter @byclaw/desktop dist:mac
# → app/release/ByClaw-<version>-arm64.dmg, ByClaw-<version>.dmg (x64), and .zip for each
```

No certificate: `mac.identity: null` skips electron-builder's signing and `app/build/after-sign.cjs`
runs `codesign --force --deep --sign -` (ad-hoc) before the dmg/zip are built. Hardened runtime off,
no notarization, no publish/auto-update. First open of the app: right-click → Open, or
`xattr -dr com.apple.quarantine /Applications/ByClaw.app`. macOS may ask again for permissions after
each rebuild (ad-hoc identity changes). Mac verification steps: `docs/mac-verification.md`.
