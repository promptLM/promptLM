# OSS evaluation adapter tier

**Status:** Proposal — for team discussion
**Author:** Fabian
**Date:** 2026-05-23
**Tracks:** [promptlm-evals#44](https://github.com/promptLM/promptlm-evals/issues/44)

## Why now

Issue #44 raised `agent-judge` (Nick Pollack's [Here Comes the Judge](https://blog.pollack.ai/here-comes-the-judge/), v0.11.0) as a candidate for the **first OSS community evaluator** that promptLM auto-wires when present on the classpath. Working through it surfaced the licensing boundary between this Apache-2.0 repo and the BSL-licensed `promptlm-evals` commercial layer — and forced a clearer story about where OSS adapters live, what bar they meet, and how they coexist with the commercial layer.

This proposal asks the team to make two decisions.

---

## Background — the two evaluation surfaces

promptLM already operates two evaluation surfaces by deliberate design:

| | OSS layer (this repo) | Commercial layer (`promptlm-evals`) |
|---|---|---|
| License | Apache-2.0 | BSL 1.1 |
| Interface | `dev.promptlm.domain.promptspec.Evaluation` — `evaluate(Response) → EvaluationResult` | `dev.promptlm.evals.spi.Evaluator` — `evaluate(EvaluationContext) → EvaluationResult` (richer) |
| Result POJO | v1: evaluator, type, score, reasoning, comments | v2 record: + `outcome`, `threshold`, `metadata`, `subScores`, `comparison`, `diagnostics`, `schemaVersion` |
| Runner | `PromptEvaluator` (Spring `@Service`, sequential) | `EvaluatorDispatcher` (dispatch policy, batch judging) |
| Coexistence | v1 written to `extensions["x-evaluation"]` on `PromptSpec` | v2 written to `extensions["x-evaluation-v2"]`; `BridgingResultMapper` (in `promptlm-evals-runtime`) translates v1 ↔ v2 |

The commercial layer is **deliberately a superset** that bridges back to the OSS POJO via persistence extensions — not a replacement. An OSS user gets a working evaluation story; a commercial user gets richer structure on top, automatically lifted from the same source data.

Today, the OSS layer has no formal home for *third-party* adapters. `components/promptlm-evaluation/` houses only the runner and host types. Adding one OSS adapter is fine; adding several without a convention is how repos drift into incoherence.

---

## Decision 1 — Where OSS evaluation adapters live and how they declare their tier

| Option | Summary |
|---|---|
| A. Each adapter as its own top-level component module under `components/promptlm-evaluation-<vendor>/`, mirroring the existing per-component layout. Tier signalled by a Maven property + README badge + a manifest. | **Recommended** |
| B. New parent module `components/promptlm-evaluation-adapters/` with each adapter as a sub-module. | More structure, more nesting; not justified at one adapter. |
| C. Separate `promptLM/promptlm-evals-community` repo from day one. | Premature; SPI is `Evaluation` (host), evolves with this repo, cross-repo PRs would be painful. |
| D. No convention; first adapter just lands where it fits. | Drift bait. |

**Recommendation: A.** Concretely:

- Adapters use the naming `components/promptlm-evaluation-<vendor>/` (e.g. `promptlm-evaluation-agent-judge`).
- They implement the existing host `Evaluation` interface — no new SPI, no fork.
- They are part of the default reactor and default `mvn verify`.
- **Tier is signalled, not enforced by build location:**
  - Maven property in each adapter pom: `<promptlm.eval.tier>community</promptlm.eval.tier>` (omitted ≡ `core`).
  - A `TIER` badge in each adapter's `README.md`.
  - A repo-root manifest `docs/eval-adapters.md` listing every adapter with its tier — kept in sync via a CI check.
- Auto-configuration is gated on `@ConditionalOnClass` against the upstream library, so users opt in by placing the upstream jar on their classpath. No tier-based gating at runtime.

**Why the metadata-flag approach over a separate `community/` subdirectory or `-Pcommunity` profile:**

- A separate profile would mean `mvn verify` on the default profile silently skips community modules. CI gaps follow.
- Promotion from community to core via a *file move* is friction we do not need. A metadata flip is one-line and reviewable.
- Future split to a dedicated `promptlm-evals-community` repo remains possible if the catalogue grows. The metadata-only approach makes that split *easier*, not harder — filter by tier, `git filter-repo`, done.

### CI implications (worth acknowledging)

Because community adapters are in the default reactor, an upstream-pre-1.0 break **will** turn the build red. Mitigations:

- Each community adapter pins its upstream to an exact version (no ranges).
- Dependabot bumps to community-tier upstreams open PRs that **do not auto-merge**, even if green.
- Repeated unmaintained breakage triggers demotion-to-removed (see below).

### Graduation criteria (community → core)

Promotion is a metadata flip + README update, allowed when **all** of:

1. Upstream library has cut a 1.0 (or equivalent) and has more than one active maintainer.
2. License is Apache-2.0 / MIT / BSD-compatible (verified, not assumed).
3. Adapter has GraalVM native-image CI passing.
4. Adapter has end-to-end coverage in `acceptance-tests/` or `examples/`.
5. A core team member commits to ownership.

### Demotion / removal

- Core → community if upstream becomes unmaintained or licensing changes.
- Community → removed after two consecutive releases with broken upstream and no maintainer response.

---

## Decision 2 — Coexistence with the commercial layer

When the commercial `promptlm-evals` layer is *also* on the classpath, an OSS adapter should produce results that the commercial bridge can lift to v2 without loss. The mechanism is the existing two-slot persistence pattern (D-33 in `promptlm-evals`):

- The OSS adapter writes a v1 `EvaluationResult` (score + reasoning) as the host `Evaluation` interface requires.
- The OSS adapter **also** writes any richer structured payload to a dedicated extension slot on `PromptSpec`, conventionally named `extensions["x-evaluation-<adapter>"]`.
- The commercial bridge, if present, reads that slot and maps directly into v2 `subScores` + `diagnostics` — preserving structure that the v1 POJO cannot.
- If the commercial layer is **not** present, the structured payload is still there for any OSS consumer (UI, CLI, API) that wants to surface it.

**Decision: adopt `extensions["x-evaluation-<adapter>"]` as the OSS convention for structured payloads.** This proposal defines the convention; the first adapter ([`promptlm-evaluation-agent-judge`](agent-judge-oss-adapter.md)) is the forcing function.

**Alternatives rejected:**

- Pack structured data as JSON inside the v1 `comments` field — `comments` is documented as "adapter-side annotation"; JSON-in-a-string is opaque to tooling and breaks display.
- Add an optional `extensions` map to the v1 `EvaluationResult` POJO itself — pulls v1 toward v2 and re-fragments the schema landscape. v2 already exists *because* v1 was deliberately kept narrow.
- Collapse to score + reasoning in OSS and ship structure only in the commercial bridge — defeats the integration's value proposition (per-`Check` structure is the load-bearing argument from the Pollack post: Kamoi et al., He et al., Zhuo et al.) and weakens the OSS-only experience.

---

## Consequences

**Positive**
- Clear, honest signal to users about which integrations carry an SLA.
- Lowest-friction path to a first OSS-tier adapter (`agent-judge`) without inventing parallel build structure.
- Structured payloads are first-class in OSS *and* bridge cleanly into the commercial v2 schema.
- Tier promotion is a one-line metadata change, not a directory move.

**Negative**
- Community-tier upstream churn can redden the OSS build. Accepted, with mitigations above.
- Two-tier story raises documentation burden — every adapter README must state its tier, and the manifest needs to stay in sync.
- The `extensions["x-evaluation-<adapter>"]` payload key is now an inter-repo contract (OSS writes, commercial reads). Needs to be documented once and held to.

**Neutral**
- No changes to `Evaluation` or the v1 `EvaluationResult` POJO.
- Existing `promptlm-evaluation` runner module untouched.

---

## Follow-ups

- [agent-judge-oss-adapter.md](agent-judge-oss-adapter.md) — concrete design for the first community adapter.
- [docs/spikes/2026-05-agent-judge.md](../spikes/2026-05-agent-judge.md) — 2-day time-boxed spike.
- Create `docs/eval-adapters.md` manifest (initially: one entry for `agent-judge`, tier `community`).
- CI check asserting every `components/promptlm-evaluation-*/pom.xml` has a `<promptlm.eval.tier>` property matching the manifest.
- A *small* follow-up proposal in `promptlm-evals` defining the BSL-side commercial bridge that reads `extensions["x-evaluation-agent-judge"]` and lifts to v2 — to land *after* the OSS adapter ships, not before.
