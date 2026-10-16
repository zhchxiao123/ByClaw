# Direct HARNESS runtime

Personal HARNESS chat without the Worker and Redis Stream: the backend prepares the turn,
ensures the owner's private CubeSandbox runtime, signs a short admission and proxies the turn to
the Harness Direct Remote ingress. Runtime output is fed into the original projection pipeline
(`StreamRecordProcessor` → `SessionStreamEventRouter`), so persistence, snapshots and the page
behave as for Legacy turns.

Disabled unless `byclaw.direct.enabled=true`. Legacy stays the route for every other session.

## Routing

- A session becomes Direct only when the server created it in this request, for a canary owner
  (`byclaw.direct.canary-user-codes`), over the platform WebSocket, for a personal HARNESS
  employee without lanes or group coordination, outside a project (a project's workspace is
  storage shared by its members, which a private runtime does not mount). The choice is stored once in
  `byclaw_runtime_session_route`; a Direct session never falls back to the Worker.
- Only the owner scope (`u<userId>.e<enterpriseId>`) may send to a Direct session; others are
  rejected before any message is written.

## Scope authority and revocation

`RuntimeScopeAuthority` answers whether an owner scope may act now: the user exists and is
enabled, and outside the default enterprise the user is an active member. It is asked at the
Direct session gate before any message is written, before a wake, again after a cold wake before
sending, and for job access. The idle controller's sweep stops the work of a revoked scope
(unsent turns cancelled, running turns stopped on the runtime) and then drains and pauses its
instance without waiting for idle, logging owner, reason, stopped requests and sandbox. Data and
instance stay; restored access resumes them. Other scopes are untouched.

The owner scope uses the login's current enterprise: the WebSocket handshake reads it from the
shared HTTP session like the HTTP interceptor, an enterprise switch closes the channels opened
before it, and logout revokes the login's tokens (`LoginRevocation`).

Runtime secrets never reach tools: the Bash provider withholds the admission secret, the DSH web
token and the model token key, plus every `secret-env` name (passed as `BYCLAW_TOOL_ENV_DENY`).

## Runtime binding

`byclaw_runtime_binding` holds one row per owner scope. The create intent is committed before
CubeAPI is called; an unknown create result is reconciled by the `byclaw_intent` sandbox
metadata, never by creating a second instance. Ready requires a signed status probe answering
with protocol `byclaw.direct/v1`, the owner scope and the binding generation. Missing or stopped
instances fail closed; replacement is a recovery operation. The binding service bean must not be
named `*Service`: the global transaction proxy would roll back committed lifecycle transitions.

Instances are created with `timeout=-1`, `autoResume=false`, `allowPublicTraffic=false` and an
owner-only host mount. After create, the per-owner environment (runtime secret, owner, generation,
shared runtime env) is written through envd as root; the image launcher starts DSH with it.

## Resources and business tools

The user's grant hash (`USER:RESOURCES:AUTH:<user>`) spans every enterprise; a runtime belongs to
one. `DirectResourceScope` keeps a copy per owner (`USER:RESOURCES:AUTH:<user>:E<enterprise>`)
holding only grants for resources of that enterprise (`ss_resource.com_acct_id`), rebuilt on every
ensure and idle sweep and swapped in atomically; the runtime reads and watches it
(`BYCLAW_RESOURCE_AUTH_KEY`), so a revoked grant leaves the running catalog.

Business CLIs find platform services through the discovery registry. Tools get a read-only Redis
user limited to `byai_gateway:sd:*` (`DirectToolRedis`, password derived from the master secret,
`byclaw.direct.runtime.tool-redis-user`) and the platform's service name, never the shared Redis
password.

## Desktop viewer

The runtime's browser desktop (noVNC, port `byclaw.direct.runtime.desktop-port`, default 8081,
which the template must expose) is reached at `/direct-desktop/<ticket>/`. `getSandboxInfo` lists it
for the caller with a ticket that names the owner scope and the login session, signed with a key
derived from the master secret and valid for 8 hours; a logout or a revoked scope invalidates it.
The ticket sits in the path because the viewer page runs without the platform's headers, so it
authenticates the page, its assets and the websockify stream alike (the token interceptor skips
the prefix). Opening the page wakes an existing runtime but never creates one; assets and the
stream never wake. A connected viewer counts as activity, so the runtime is not paused meanwhile.

## Session files

A Direct runtime works in its owner's state mount, so the platform serves a Direct session's
attachments, file panel, diffs, reply artifacts and previews from that mount on the host
(`DirectSessionFiles`): owner only, never waking the runtime, never following a link out of the
mount, and refusing a missing or replaced owner directory. Attachments must be this session's own
uploads; they are copied into the mount at admission and the turn names their runtime path. An
upload that creates a session before its first message records the Direct route then.

## Messages, feedback, favorites and shares

A Direct turn's question and answer keep the platform message ids reserved at admission
(`user_message_id`/`answer_message_id`), whether the answer arrives live or from outbox
reconciliation, so feedback, favorites (showcase), share links and the open API refer to the same
numeric ids as Legacy history. The page APIs read and change messages only for the owner scope of a
Direct session (`SessionReadAccess`); Legacy sessions keep the platform view rule. A share link is
the owner's explicit grant: its viewers read the shared messages, never the session, and nothing
there wakes or runs the runtime. The admission status carries `projectedThroughSeq`, the runtime
log position the platform has projected.

## Faults

`RuntimeFault` is the single identity of why a runtime cannot serve a turn (`capacity`,
`template_unavailable`, `resume_failed`, `instance_missing`, `state_missing`, `restore_pending`,
`protocol_incompatible`, ...). The page message, the binding's `last_error` prefix, the log field
`fault=` and the admission detail (`runtime <code>`) all use it. Every fault keeps the binding,
the instance and the owner's data: a refused create created nothing and a retry creates once; a
refused or failed resume keeps the paused instance and a retry resumes the same one. CubeMaster's
500 `130404 template not found` is a definite refusal, not an unknown create.

The owner directory carries a read-only `.byclaw-owner` marker (`<owner>:<bindingId>`) written when
the binding is created. A binding that already has an instance is never started or resumed unless
its directory exists, is non-empty and is marked for it (a non-empty directory from before markers
is adopted once); the directory is never recreated for it. `restore-pending` (set by a data
recovery) is never ready, never woken by reads and never paused.

## Idle pause and resume

`DirectIdleController` periodically asks `RuntimeBindingService.pauseIfIdle` for every ready owner.
Under the owner's ensure lock it pauses only a runtime that has been ready for the idle timeout and
whose signed status shows no work lease, no running admission and no pending projection for the
idle timeout. It moves the binding `ready → draining`, asks the runtime to drain (close admission
and flush; 409 when work appeared), then pauses through CubeAPI and records `paused`. A refused or
failed drain or pause reopens admission and returns to `ready`; an unreadable status is busy.
Instances are never deleted. The next `ensure` resumes the same instance (`resuming`), coalescing
concurrent callers, and undrains it if needed. Login, history and status queries never wake a
runtime. The binding's `update_time` is compared with the JVM clock: the database session and the
JVM must use the same time zone.

A `draining` binding seen under the owner lock was left by a controller that stopped mid-pause
(a live pause holds that lock). Ensure reconciles it from the Cube state: a running instance is
undrained after its signed status answers, a paused one is resumed. A pauser re-reads the binding
after drain and pauses only if it is still the same generation in `draining`.

## Admission states

`reserved` (before messages exist) → `dispatching` (bound to the turn's messages, not sent) →
`sending` (claimed by compare-and-set right before the request leaves) → `accepted` (runtime
durably admitted) → `completed` / `failed` / `cancelled`; `unknown` when the outcome is lost,
`rejected` when the runtime never admitted it. A stop that arrives while the runtime is being
created or resumed cancels a `dispatching` turn so it is never sent; from `sending` on, only the
runtime can stop it. Open admissions are reconciled from the runtime's own record, never by age.

## Wire contract (`byclaw.direct/v1`)

- `POST /plugins/byclaw-direct/v1/turns`: body `{protocol, command}` where `command` is the
  by-framework AskAgent dictionary. Headers `x-byclaw-direct-admission` (base64url JSON:
  ownerScope, userCode, generation, requestId, sessionId, traceId, bodySha256, expiresAt) and
  `x-byclaw-direct-signature` (hex HMAC-SHA256 of the header value). Response is NDJSON:
  `admission` (accepted or duplicate) → `event`* (Redis-compatible gateway events) → `end`.
- `GET /plugins/byclaw-direct/v1/status` and `/sessions/<session>/turns/<requestId>` require
  `x-byclaw-direct-control: <ms>.<hex HMAC of "GET\n<path>\n<ms>">`.
- `POST /drain` and `/undrain` (control-signed) close and reopen admission for idle pause.
- The runtime secret is derived per binding from `byclaw.direct.master-secret`; it is never stored.

## Configuration

| Property | Meaning |
| --- | --- |
| `byclaw.direct.enabled` | Master switch (default `false`) |
| `byclaw.direct.canary-user-codes` | Owners whose new HARNESS sessions use Direct |
| `byclaw.direct.master-secret` | ≥ 32 characters; server-only |
| `byclaw.direct.cube.api-url` / `api-key` | CubeAPI |
| `byclaw.direct.cube.proxy-host` / `proxy-port` / `sandbox-domain` | CubeProxy routing |
| `byclaw.direct.cube.template-id` | Pinned READY Direct template |
| `byclaw.direct.cube.allow-out[n]` | Private egress CIDRs the runtime needs (control plane, Redis) |
| `byclaw.direct.runtime.host-mount-root` / `local-mount-root` | Owner directories on the Cube node / as seen here |
| `byclaw.direct.runtime.idle-timeout` | Idle time before pause (default 300 s) |
| `byclaw.direct.idle-check-interval-ms` / `idle-initial-delay-ms` | Idle check period and first delay (default 30 s) |
| `byclaw.direct.runtime.env[KEY]` / `secret-env[KEY]` | Shared runtime environment; secret values are never logged |

The acceptance drivers are in `byclaw-qa/tests/acceptance/direct-*.mjs`.
