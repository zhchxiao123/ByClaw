# Domain Docs

## Layout

This repository uses a single context:

- Root `CONTEXT.md`: shared domain vocabulary, created lazily when terms are resolved.
- `docs/architecture/decisions/`: the existing architecture decision directory. Reuse it; do not establish a second parallel ADR directory.
- `docs/agents/`: engineering-skill configuration.
- `.scratch/<feature>/`: local requirements and tickets, not domain documentation.

## Before exploring

Read `CONTEXT.md` if present and relevant ADRs from `docs/architecture/decisions/`.

If a vocabulary file is absent, proceed without fabricating it or asking to create one merely for setup. The domain-modeling workflow can create it later when actual terms or decisions are resolved.

Use the glossary's terms consistently. Flag a conflict with an accepted ADR explicitly rather than silently overriding it. An ADR template is not an accepted architectural decision.

## Product boundaries

ByClaw is the frontend and business control plane. byclaw-harness is a separate runtime product. The shared CubeSandbox/DSH PRD remains in this repository's local tracker; consult the other repository's instructions and versioned API contract before implementation there. This setup does not modify that repository's agent configuration.
