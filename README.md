# Acceptance evidence for beyonai/ByClaw#328

A test environment running the standalone deploy kit: the platform's shared pool on the OpenSandbox provider,
two shared host templates for `byclaw-dsh` (mounted and stateless), the backend and the harness built from the
PR branch. Test accounts only.

| File | What it shows |
|---|---|
| `01-mounted-before-migration.png` | A user on the mounted pool recalls a fact planted earlier in the conversation. |
| `02-stateless-user-on-a-fresh-host.png` | A stateless-pool user after every host and tool sandbox was deleted: a fresh host with an empty disk reads the earlier image again, the file in the user's space is there, and the conversation recalls its facts. |
| `03-migrated-user-on-the-stateless-pool.png` | The user from 01 after the admin migration and the switch: same conversation on the stateless pool, the image read again, the tool sandbox file there, the facts recalled. |
| `migration-refused-while-served.json` | The migration script refusing while a host still serves the user. |
| `migration-run.json` | The migration after the user's context was released: every step passed. |
