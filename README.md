# Split parity verification, 2026-10-27

Two users of the same test deployment (standalone deploy kit, OpenSandbox provider):

- **user-own-sandbox** — the pre-split deployment: harness, commands, browser and byCLI in one per-user sandbox.
- **user-shared-host** — the split deployment: the user's context on the shared DSH host, commands in the user's tool sandbox (B).

The tool sandbox image is this branch's tool image applied over the image already deployed there (so the shared host and B keep matching SSH helper builds); the browser supervisor in it is that image's generation with this branch's `--secret-file` change, because the deployed byCLI predates the newest supervisor's extension checks. The backend is this branch's change on an earlier point of the release branch, because the newest backend needs a newer platform service than the environment runs.

## In the sandbox: `probe.sh` and `compare.mjs`

| File | What |
|---|---|
| `before.jsonl` | probe output, user-own-sandbox |
| `after-1.jsonl` | probe output, user-shared-host, B just created |
| `after-2.jsonl` | after B was released and recreated (home marker kept; this probe version still asked `bycli doctor` before any browser command, see below) |
| `after-3.jsonl` | the fixed probe on a freshly created B |

`node compare.mjs before.jsonl after-3.jsonl` → `parity: every check has the same outcome` (environment variable names identical without any ignore list; byCLI browser command and doctor; a service-discovered platform CLI; persistent home; a home marker written by the previous B is still there after B was released and recreated).

`after-2` differed only in `bycli-doctor`: the browser starts on demand in both deployments and `doctor` does not start it, so on a fresh sandbox the old probe asked too early. The probe now runs a browser command first (`after-3`).

## Outside the sandbox

| Check | Evidence | Result |
|---|---|---|
| Account login navigation, recorder, browser sandbox key | `be-checks.txt` | navigation succeeds for both; recorder daemon and extension ok for both; `GET /sandbox/browserServiceKey` names the tool sandbox for the shared-host user only |
| Native `browser` tool on B | `browser-processes.txt`, `browser-tool-desktop.png` | one Chromium (one profile, byCLI extension loaded), one desktop; the tool's bridge attached to it; the frontend drawer shows that desktop (B's `vnc` instance on 8081) |
| Instruction files while B is down | `agents-md-while-b-down.png`, `model-only-turns.txt` | B released; a first model-only turn; an `AGENTS.md` written straight into the session directory on the volume; a second model-only turn answers from it (`agent-instructions` injected); no tool sandbox was started by either turn |
| CodeGraph on the shared host | `codegraph.png`, `codegraph.txt` | the tool is available and answers from a server on B; its handshake is cached; the server is gone ~2.5 minutes after the call, so it does not keep B awake |
| Plugins writing the user tree from the host | `host-eacces.txt` | no `EACCES` in five turns (before the change every turn logged one from the group-chat disposition) |

Not exercised end to end: image and speech generation (the environment's default image model answers 401 in both deployments); their file writes are covered by the tool-environment verification in the branch.
