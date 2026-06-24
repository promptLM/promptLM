# Spike — `agent-judge` OSS adapter

- Started: 2026-05-23
- Owner: _unassigned_
- Tracks: [promptlm-evals#44](https://github.com/promptLM/promptlm-evals/issues/44)
- Design: [agent-judge-oss-adapter.md](../proposals/agent-judge-oss-adapter.md), [oss-eval-adapter-tier.md](../proposals/oss-eval-adapter-tier.md)
- Time-box: **2 working days**

## Goal

Answer one question with evidence: **adopt `agent-judge` as the first OSS community adapter, integrate it via an adapter we own, or draw inspiration and build our own?**

Decision must be backed by a working end-to-end run, not by reading docs.

## Out of scope for this spike

- GraalVM native-image support (separate milestone, see §"GraalVM gate" below).
- The commercial-side bridge in `promptlm-evals` that reads the extension slot. Confirm the payload is consumable; do not build the bridge.
- Multi-turn / agent-trace evaluation (note any blockers, do not solve).
- Production UI surfacing of structured `Judgment` data (note shape concerns, do not build).
- Spike does **not** merge to `main` — outputs are this doc, the proposals, and a throwaway branch with the prototype.

## Pre-flight (≤ 1 hour) — hard gates

- [ ] Confirm `agent-judge` license is Apache-2.0 / MIT / BSD-compatible. **Hard gate** — if not, stop and re-scope to "fork or build our own".
- [ ] Confirm `agent-judge` is published to Maven Central (not only JitPack / snapshot). Note coordinates and version.
- [ ] Skim the v0.11.0 changelog for breaking-change pattern; estimate churn risk.
- [ ] Note bus factor (maintainer count, last 3 months of commits).

## Phase 1 — JVM end-to-end (≤ 1 day)

- [ ] Branch `spike/agent-judge` off `agent-judge-integration-plan`.
- [ ] Scaffold `components/promptlm-evaluation-agent-judge/` per [agent-judge-oss-adapter.md](../proposals/agent-judge-oss-adapter.md). Set `<promptlm.eval.tier>community</promptlm.eval.tier>` in its pom; add a `COMMUNITY` badge in the README.
- [ ] Wire `AgentJudgeAutoConfiguration` with `@ConditionalOnClass(name = "ai.pollack.agentjudge.Judge")`.
- [ ] Implement `JudgmentMapper` for the **two-write** mapping:
  - v1 `EvaluationResult` (score + reasoning summary)
  - Full structured payload written to `extensions["x-evaluation-agent-judge"]` on the carrying `PromptSpec`.
- [ ] Build a minimal `CascadedJury` with three tiers, mirroring the Pollack post example:
  1. Deterministic check (always-pass stub or build-success).
  2. Deterministic numeric check (stub coverage threshold).
  3. LLM judge (Claude Haiku 4.5 via Spring AI, cheap).
- [ ] Run it end-to-end through `PromptEvaluator` against a canned `PromptSpec` + `Response` pair.
- [ ] Verify v1 `EvaluationResult` is attached to the spec via `EvaluationExtensionSupport.withResults(...)`.
- [ ] Verify `extensions["x-evaluation-agent-judge"]` is populated with the structured `Judgment` payload.
- [ ] Capture sample output (v1 POJO JSON + extension payload JSON) in this doc under "Evidence".

## Phase 2 — Payload-shape stress tests (≤ ½ day)

Probe whether the extension-slot payload carries `agent-judge`'s structure faithfully and is consumable by a hypothetical commercial bridge.

- [ ] Per-`Check` granularity preserved in the payload? (yes / no + why)
- [ ] Reasoning strings survive the round-trip un-truncated?
- [ ] Cascade ordering recoverable from the payload?
- [ ] Can we distinguish a "did not run because earlier tier failed" vs. "ran and failed"? If not, that is a payload-shape gap.
- [ ] Composite Jury vs. single-Judge results — do they serialise distinguishably?
- [ ] Mock a commercial-bridge read: parse the extension payload and project it into v2-shaped `subScores` + `diagnostics`. Note any information loss.

For each "no" or "loss", file a follow-up note (do not fix in the spike).

## Phase 3 — GraalVM gate (≤ ½ day)

This is a **scouting pass**, not a build-it-green pass.

- [ ] Build the adapter under native-image with `agent-judge` on classpath.
- [ ] Capture the native-image error log.
- [ ] Categorise gaps:
  - Reflection on `agent-judge` internal types
  - Reflection on user-supplied `Judge` / `Check` types
  - Jackson serializer hints for the extension payload
  - `ServiceLoader` / dynamic proxy use
- [ ] Estimate effort to land native support (S / M / L) and whether hints should live in this adapter or be contributed upstream.

Native milestone is **not blocked on this estimate being green** — we just need it written down before milestone 2 is scoped.

## Decision matrix (filled at end)

| Criterion                                                  | Result |
|------------------------------------------------------------|--------|
| License compatible                                         | ☐ pass / ☐ fail |
| Maven Central artifact present                             | ☐ pass / ☐ fail |
| JVM end-to-end run succeeds                                | ☐ pass / ☐ fail |
| v1 `EvaluationResult` round-trip clean                     | ☐ pass / ☐ partial / ☐ fail |
| Extension-slot payload carries `Judgment` shape losslessly | ☐ pass / ☐ partial / ☐ fail |
| Mock commercial-bridge read projects to v2 without loss    | ☐ pass / ☐ partial / ☐ fail |
| GraalVM gaps are S / M / L                                 | ☐ S / ☐ M / ☐ L / ☐ unknown |
| Bus factor / upstream activity acceptable                  | ☐ pass / ☐ concern / ☐ fail |

## Outcome (one of)

- [ ] **Adopt** — promote the spike into a real OSS community adapter PR.
- [ ] **Integrate via adapter we own** — keep the wrapper, but pin `agent-judge` to a known-good version and budget for forking.
- [ ] **Inspire** — close `agent-judge` integration, open a new proposal for native promptLM cascaded-jury primitives.

## Evidence

_Filled during the spike. Sample v1 `EvaluationResult` JSON, sample `extensions["x-evaluation-agent-judge"]` payload, native-image log excerpt, screenshots of any UI surfacing._
