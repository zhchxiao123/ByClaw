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
  employee without lanes or group coordination. The choice is stored once in
  `byclaw_runtime_session_route`; a Direct session never falls back to the Worker.
- Only the owner scope (`u<userId>.e<enterpriseId>`) may send to a Direct session; others are
  rejected before any message is written.

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

## Wire contract (`byclaw.direct/v1`)

- `POST /plugins/byclaw-direct/v1/turns`: body `{protocol, command}` where `command` is the
  by-framework AskAgent dictionary. Headers `x-byclaw-direct-admission` (base64url JSON:
  ownerScope, userCode, generation, requestId, sessionId, traceId, bodySha256, expiresAt) and
  `x-byclaw-direct-signature` (hex HMAC-SHA256 of the header value). Response is NDJSON:
  `admission` (accepted or duplicate) → `event`* (Redis-compatible gateway events) → `end`.
- `GET /plugins/byclaw-direct/v1/status` and `/sessions/<session>/turns/<requestId>` require
  `x-byclaw-direct-control: <ms>.<hex HMAC of "GET\n<path>\n<ms>">`.
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
| `byclaw.direct.runtime.env[KEY]` / `secret-env[KEY]` | Shared runtime environment; secret values are never logged |

The acceptance drivers are in `byclaw-qa/tests/acceptance/direct-*.mjs`.
