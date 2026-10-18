# Triage Labels

This local tracker uses the skill's default canonical role names.

| Canonical role | Local Status value | Meaning |
| --- | --- | --- |
| `needs-triage` | `needs-triage` | Maintainer needs to evaluate the requirement. |
| `needs-info` | `needs-info` | Waiting for necessary information. |
| `ready-for-agent` | `ready-for-agent` | Specified well enough for an agent to begin. |
| `ready-for-human` | `ready-for-human` | Requires human implementation. |
| `wontfix` | `wontfix` | Will not be actioned. |

When a skill says to apply a triage label, update the local `Status:` line. Do not call GitHub label APIs.

`ready-for-agent` is a planning state. It does not prove code completion, deployment, or runtime acceptance. Execution states such as `claimed` and `resolved` are distinct from these five triage roles.
