# @byclaw/dsh-db

Stores the shared DSH host's per-user state in openGauss/PostgreSQL instead of files under `<dsh-home>`. Two plugins share one connection pool per database:

- **`@byclaw/dsh-db/session`** (row `session-persistence-db`): session logs, behind the same `ctx.sessionPersistence` service as the JSONL files.
- **`@byclaw/dsh-db/storage`** (row `storage-db`): a DSH storage backend (`ctx.storage`) registered as `db`. Units opened through `storage-domain` land in the database instead of `<dsh-home>/storages/*.json`: workspace records, ByClaw session roots, the session projection cache, schedules and group task publications.

A stateless shared DSH host (`BYCLAW_MULTI_HOST_STATELESS=1`) turns both on for every user it serves. It connects with the deployment's existing database settings, `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASS`, `DB_DATABASE` and `DB_SCHEMA` (default `byai`). It disables `session-persistence-jsonl` and routes `storage-domain` to `db`. A host without the switch keeps every user's state in files under their home. Only the shared host may use these plugins: in a per-user sandbox model-generated commands run beside the harness and could read the password. Every shared host removes the `DB_*` settings from its own environment at start and keeps them out of launch environments for the same reason.

The tables (`byai.dsh_session`, `byai.dsh_session_event`, `byai.dsh_storage_unit`, `byai.dsh_storage_record`) are created on first use. Every context in the process and both plugins draw on one pool (`poolMax`, default 8, set by the first to open it).

A stateless host also needs the service secret (`BYCLAW_CREDENTIAL_SECRET_FILE`, or `BYCLAW_CREDENTIAL_SECRET`); with it, the host keeps each user's credential store (`.credentials.yaml`) and tool-environment SSH identities and sandbox records in `byai.dsh_host_file`, encrypted (AES-256-GCM, key derived from the secret, bound to user and file name). It restores them before a context boots and writes each change back (`scripts/host-durable-files.mjs`). They are secrets, so they never go to the user's `/by`, which commands in the tool sandbox can read. Each boot of a user's context raises that user's epoch in the table (a `.owner` row), and a host holding an older one gets its writes refused and stops storing the user's files. A host that cannot open a stored row (its service secret differs, or the row is damaged) logs an `ERROR`, leaves the row alone and stores nothing for that user until the secret matches or the row is removed.

## Behaviour

- **One writer, enforced by the database.** Opening a session for writing, or a storage unit, raises its epoch. A host still holding the old epoch gets `SessionOwnershipLostError` or `StorageOwnershipLostError` on its next write; a session's turn fails at the next `session/flush`.
- **Versions follow the storage backend contract.** A `single` unit stamped with another version is refused untouched (`version-mismatch`). A `per-record` unit reads the record versions it accepts (`version`, `compatibleVersions`) and treats others as missing; `backupRecord` moves a record to `#backup:<table>` rows instead of deleting it.
- **A database failure fails the operation.** Statements time out after `queryTimeoutMs` (30 s). A session batch stays buffered and is retried; a commit whose reply was lost is not written twice. Storage writes are overwrites, so repeating one after a lost reply is safe.
- **A connection dropped inside a transaction fails that write**, not the process.
- **Users are separated by `user_code` in every statement.** They share one process and one database account, so this guards against mistakes, not against plugin code in the host.
- openGauss has no `ON CONFLICT`; a storage write locks the unit row, then updates the record and inserts it when absent.

## Deploying

- **Two pools.** Users on the mounted pool keep their homes on shared storage; users on the stateless pool keep nothing on the host. A host serves one kind only, so the stateless pool is its own host template: the mounted one without the shared-contexts volume and `BYCLAW_MULTI_HOST_USER_ROOT`, with `BYCLAW_MULTI_HOST_STATELESS=1` and the service secret. BE picks each user's pool (system parameter `BYCLAW_HARNESS_STATELESS_POLICY`).
- **The platform must pass the database settings to shared hosts.** A shared host gets only a fixed list of the platform's own environment plus `byclaw.sandbox.shared.host-env.*`. Add `byclaw.sandbox.shared.host-env.DB_HOST=${DB_HOST}` and the same for `DB_PORT`, `DB_USER`, `DB_PASS`, `DB_DATABASE` and `DB_SCHEMA` to the platform's configuration. Do not put them in the `byclaw-dsh` template: that template also starts per-user sandboxes.
- **Moving an existing user.** A user switched without migration starts empty on the stateless pool: earlier chats, workspace pins, session roots, schedules, credentials and SSH identities stay on the mounted pool's storage, unread. To keep them, an admin first releases the user's shared context, then runs `scripts/migrate-to-stateless.mjs --user-code <code> --env-from-pid auto` on a mounted host, then switches the user right after it exits 0. The script copies sessions, storage units, credentials and SSH identities into the database and images into the user's space. It changes nothing on the host, can run again after a failure, and refuses a context a host still serves. Copying images needs the user's current login token. Moving back is not supported.
- **Every context boot uses a new by-framework worker id.** The id carries a random part per boot, so that a restarted host reclaims its predecessor's open commands. Each id leaves a consumer in the consumer group of the user's command stream (`byai_gateway:consumer_group:agent_engines:<digest>`), a worker control stream (`byai_gateway:ctrl:worker:<worker id>`) and, after a kill, its registry membership. by-framework 1.8.0 and later remove them: a worker stopped normally cleans up after itself, and a running worker of the same user removes dead ones that hold no pending commands and have been idle for `BYAI_DEAD_WORKER_CLEANUP_IDLE_SECONDS` (default one hour).
- **Every stateless host needs the same service secret.** A host with another one cannot read the users' credential stores and SSH identities, and does not write its own: users served there work from that host's local files only (`cannot be opened` in its log).

## Not done yet

- The migration copies the units that are files under `storages/`; a unit the stateless host already created (the user was switched before the migration) is left as it is and reported as present.
- Only the current session format is stored. After a DSH format upgrade old sessions are refused (`SessionFormatUnsupportedError`) until a migration exists.
- Nothing deletes old sessions or rows.

`tests/session/contract/` and `tests/storage/contract.ts` are copied from `deepseek-harness` (the latter from `packages/storage/storage/tests`), which does not publish these suites.
