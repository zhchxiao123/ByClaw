# Verification of PR #315 (`fix/shared-host-token-renewal`, commits e4be1ae62, 2e47182b8, f46164fe0), 2026-10-26

Deployed on the test environment: BE built from the branch, the branch's `dsh-tool-env` plugin and `byclaw-multi-host.mjs`
in the shared host container (every other plugin as shipped in the harness image), the stock `D0.6.0` FE image. Times are
the environment's local time.

| file | shows |
|---|---|
| `../logs/before-fix.chat-excerpt.txt` | the bug on the unpatched host (same environment, earlier the same day) |
| `screenshots/token-rejected-then-recovered.png` + `logs/token-rejected-then-recovered.host.log` | the running context is given an unusable token through the host's admin PUT, its tool sandbox is released; the next bash turn: BE answers 401, the plugin takes the user's current login token, launches a sandbox, the tool returns the hostname |
| `logs/relogin-renews-token.host.log` | the context boots with an unusable token, the user logs in (BE's login auto-start PUT at 16:32:51 carries a fresh token), the launch at 16:33:13 goes through with no 401 |
| `logs/plugin-idle-release.txt` | sandbox `933c12d2` created 16:06:58, released by the plugin at 16:17:00 after 10 idle minutes (`released tool environment … idle for 600000 ms; paused`), record `RELEASED` |
| `logs/plugin-release-on-context-dispose.txt` | the user's context is deleted (admin DELETE) at 16:31:26; sandbox `7caf869a` is released by the plugin's close path (record released 16:31:24, container gone) |
| `logs/kept-by-residual-process-then-released.txt` | idle period set to 2 minutes for the test; a detached `sleep` on the sandbox makes the first idle cycle log `… 1 process(es) still run on it (sleep[73]); keeping it`; after the process is killed the next cycle releases the sandbox (record `RELEASED`) |

The token screenshot/log were re-taken on the final build (17:37 local time); the idle, re-login and dispose logs are from the first D0.6.0-based build of the same day and the code paths are unchanged.
