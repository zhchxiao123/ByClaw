# Verification evidence, 2026-10-26

Test environment: the standalone deploy kit (BE, shared DSH host, FE) with the OpenSandbox provider for tool
sandboxes. Top level: an earlier, since-withdrawn version of the change (stacked on PR #314). `d060/`: the
version in PR #315 (one commit on top of `D0.6.0`).

| file | shows |
|---|---|
| `logs/before-fix.chat-excerpt.txt` | the bug on an unpatched shared host: a user context from three days earlier still holding that day's token, every tool-sandbox launch answered 401 |
| `d060/…` | see `d060/README.md` |
