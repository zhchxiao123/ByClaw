# Verification evidence, 2026-10-26, env 118.178.253.73 (clone of the 229 stack, OpenSandbox provider)

Branches: `fix/shared-host-token-renewal` (issue 01, stacked on PR #314) and the fixes pushed to
`feat/tool-sandbox-types-and-release` (PR #314). Deployed on 118: BE jar from the branch, harness plugin
builds (dsh-tool-env, byclaw-integration, dsh-idle-reporter, agent-teams) injected into the shared host
container, FE built with `max build publicPath=/beyond/`.

| file | shows |
|---|---|
| `logs/before-fix.chat-excerpt.txt` | the bug, reproduced on the unpatched host: a context from 2026-10-23 holding that day's token, every tool-sandbox launch 401 |
| `screenshots/evidence-token-rejected-then-recovered.png` + `logs/token-rejected-then-recovered.host.log` | after the fix: a PUT with an unusable token applied to the running context, BE rejects the next launch, the plugin takes the login token from Redis, launches B and the bash tool returns the hostname |
| `screenshots/evidence-314-employee-spec-b.png` + `logs/314-employee-spec-b.host.log` | PR #314: the employee bound to spec `byclaw-dsh-tools-b` runs its tool on a B of that spec; record `DSH_REPORTED`, `auto_release=0` |
| `screenshots/evidence-314-back-to-default.png` + `logs/314-back-to-default.host.log` | PR #314: back on the default spec after the switch, the shared key pair still logs in (this failed before the shared-keys fix) |
| `screenshots/evidence-314-sandbox-type-select*.png` | PR #314 FE: the "沙箱类型" select on the employee editor and its options |
| `logs/314-silent-release.be.log` | PR #314 BE: a B whose host died is released by the reported-release scan (`release.dsh.silent`) two minutes later |
