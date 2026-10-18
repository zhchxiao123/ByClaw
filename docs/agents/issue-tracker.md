# Issue tracker: Local Markdown

Issues and PRDs for this repository live as Markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`.
- The canonical PRD is `.scratch/<feature-slug>/PRD.md`.
- Implementation issues are `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`.
- A `Status:` line near the top records the role defined in `triage-labels.md`. A confirmed PRD may use `Status: ready-for-agent`; it does not mean the implementation or runtime acceptance is complete.
- Append comments and decisions under a `## Comments` heading.
- Keep one authoritative PRD body. Old research locations may retain links, but must not maintain a second copy.

## When a skill says "publish to the issue tracker"

Create or update the corresponding local Markdown file. Do not create, reopen, comment on, or label remote issues for tracking this work unless the user explicitly changes this local-only choice.

The repository's GitHub remote is a source-control location, not this workflow's issue tracker. External pull requests are not a triage request surface.

## When a skill says "fetch the relevant ticket"

Read the referenced local file. Resolve a numbered ticket within its feature directory; do not assume a GitHub issue number.

## Current feature

- Feature: `byclaw-cubesandbox-direct-dsh`.
- Canonical PRD: `.scratch/byclaw-cubesandbox-direct-dsh/PRD.md`.
- This PRD covers both ByClaw and byclaw-harness. Implementation work belongs in the relevant repository, while the shared requirement remains in this canonical local file.
- Research evidence remains under `docs/architecture/research/`.

## Wayfinding operations

- Map: `.scratch/<effort>/map.md`.
- Child ticket: `.scratch/<effort>/issues/NN-<slug>.md`.
- Use `Type:` for `research`, `prototype`, `grilling`, or `task`.
- `Blocked by: NN, NN` lists prerequisites.
- `Status: claimed` and `Status: resolved` describe execution ownership and resolution, not canonical triage labels.
- Resolve a ticket by appending its answer under `## Answer`, recording `Status: resolved`, and adding a context pointer to the map.
