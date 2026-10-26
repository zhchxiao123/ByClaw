# Verification of the D0.6.0-based branch (`fix/shared-host-token-renewal`, commit e4be1ae62), 118.178.253.73, 2026-10-26 16:06–16:33 CST

Deployed on 118: BE jar built from the branch (`byclaw-be:token-renewal-d060`), the branch's `dsh-tool-env` lib and `byclaw-multi-host.mjs` in the shared host container (all other plugins from the image), FE = the stock `byclaw-fe:D0.6.0` image.

| file | shows |
|---|---|
| `../logs/before-fix.chat-excerpt.txt` | the bug on the unpatched host (same env, earlier the same day) |
| `screenshots/token-rejected-then-recovered.png` + `logs/token-rejected-then-recovered.host.log` | the running context is given an unusable token through the admin PUT, its B is released; the next bash turn: BE answers 401, the plugin takes the login token from Redis, launches B, the tool returns the hostname |
| `logs/relogin-renews-token.host.log` | the context boots with an unusable token, the user logs in (BE's login auto-start PUT at 16:32:51 carries a fresh token), the launch at 16:33:13 goes through with no 401 |
| `logs/plugin-idle-release.txt` | B `933c12d2` created 16:06:58, released by the plugin at 16:17:00 after 10 idle minutes (`released tool environment … idle for 600000 ms; paused`), record `RELEASED` |
| `logs/plugin-release-on-context-dispose.txt` | the user's context is deleted (admin DELETE) at 16:31:26; B `7caf869a` is released by the plugin's close path (record released 16:31:24, container gone) |
