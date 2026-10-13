# Rubric — Sanctity console

- Project, audience, task: First graphical console for Sanctity, a BIDS-native neuroimaging repository (Scala API).
  Audience: laboratory curators, researchers and data custodians (PRD §3). Core tasks: inspect a release and why
  each metadata value has its value (inheritance origin); review admission findings/content checks and acquisition
  relationships; propose a metadata correction, review its preview and accept it; freeze a query selection; grant/revoke access.
- Identity and user preferences: "world class, beautiful, functional, stylish, not overly complex". Archival / scientific
  instrument character: calm paper-and-ink palette, serif display headings, monospace for paths/ids, one indigo accent.
  No framework, no build step, no network dependencies (system fonts only). Must remain simple.
- Specimens: connect, release (selected BOLD file with inherited/overridden metadata), release dark, release 390px,
  assessment tab, relationships tab, correction preview (admissible), correction preview (blocked), correction 390px,
  frozen selection results, access (grant created). Desktop 1440x1000, mobile 390x844.
- Frozen content: the /v1 HTTP API contract (modules/http/.../RepositoryHttp.scala, PublicJson.scala) — the UI may
  not invent endpoints or fields. Error semantics (not_found merges denied). Demo fixtures are synthetic but must be
  internally consistent and shaped exactly like PublicJson output.
- References: none supplied; judge against best-in-class data tools (e.g. Linear, Vercel, GitHub, Observable) for craft.
- Budget: baseline v0 plus up to three improvement rounds. Blockers: lost functionality, gate failures
  (driven state, overflow, serious/critical axe violations), API-contract deviations, misleading scientific display.

Dimensions (1–10): typography, color, layout, hierarchy, components, function, themes, mobile, identity, fidelity.
- function = task completion with keyboard/pointer, states, errors, focus, accessibility.
- fidelity = UI states faithfully represent API data/semantics (origins, overrides, admissibility, digests, units);
  nothing misleading.

## Required critique format
Start with `## Scores` and one table `| dimension | score | justification |` with every dimension once, plain numbers.
Then `## Previous round`, `## Claims`, `## Issues`, `## Regressions`, `## Proposals`.
