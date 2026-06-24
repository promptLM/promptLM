# `agent-judge` OSS evaluation adapter

**Status:** Proposal — for team discussion
**Author:** Fabian
**Date:** 2026-05-23
**Tracks:** [promptlm-evals#44](https://github.com/promptLM/promptlm-evals/issues/44)
**Depends on:** [oss-eval-adapter-tier.md](oss-eval-adapter-tier.md)

## Why now

Nick Pollack's [Here Comes the Judge](https://blog.pollack.ai/here-comes-the-judge/) introduces [`agent-judge`](https://blog.pollack.ai/here-comes-the-judge/) (v0.11.0) — a JVM-native, typed, composable evaluation library with `Judge`, `Jury` (`SimpleJury`, `CascadedJury`), and structured `Judgment` (score + reasoning + per-criterion `Check`) primitives.

It targets the same gap promptLM operates against (Java / Spring AI lacks first-class eval primitives equivalent to Python's deepeval / ragas) and ships bridges for Spring AI and LangChain4j — the runtimes our users are on.

This proposal specifies how `agent-judge` lands as the **first OSS community adapter** under the tier introduced in [oss-eval-adapter-tier.md](oss-eval-adapter-tier.md).

## Decision

Add `components/promptlm-evaluation-agent-judge/`, an Apache-2.0 module that implements the host `dev.promptlm.domain.promptspec.Evaluation` interface and is auto-wired when `agent-judge` is on the classpath.

### Module layout

```
components/promptlm-evaluation-agent-judge/
├── pom.xml                 # <promptlm.eval.tier>community</promptlm.eval.tier>
│                           # agent-judge pinned to exact version
├── README.md               # COMMUNITY tier badge, supported judge types, native status
└── src/
    ├── main/java/dev/promptlm/evaluation/agentjudge/
    │   ├── AgentJudgeAutoConfiguration.java   # @ConditionalOnClass(Judge.class)
    │   ├── AgentJudgeEvaluation.java          # implements Evaluation
    │   ├── JudgmentMapper.java                # Judgment -> v1 EvaluationResult + extension slot payload
    │   ├── JuryFactory.java                   # builds CascadedJury/SimpleJury from config
    │   └── AgentJudgeProperties.java          # @ConfigurationProperties
    ├── main/resources/META-INF/native-image/  # reachability hints (milestone 2)
    └── test/...
```

Upstream `agent-judge` is pinned to an exact version (no ranges). Dependabot bumps open PRs that do not auto-merge — community-tier CI policy per [oss-eval-adapter-tier.md](oss-eval-adapter-tier.md).

### Result mapping (Option 4 from the design discussion)

Two writes, by design:

1. **Return a v1 `EvaluationResult`** as `Evaluation.evaluate(Response)` requires:
   - `evaluator` = `agent-judge:<judge-or-jury-id>`
   - `type` = `agent-judge`
   - `score` = `Judgment.score()` (normalised to [0,1])
   - `reasoning` = `Judgment.reasoning()` (human summary; cascade-aware)
   - `comments` = short, human-readable annotation (e.g. `"3/3 tiers passed"`) — **not** structured JSON

2. **Write the full structured payload to a dedicated extension slot** on the carrying `PromptSpec`:
   - Key: `extensions["x-evaluation-agent-judge"]`
   - Value: a JSON-serialised record containing the full `Judgment` tree — per-`Check` results, cascade order, deterministic-vs-LLM tier markers, per-check reasoning, per-check scores.

The first write satisfies the host evaluator contract and the OSS persistence path. The second preserves the per-`Check` structure that is the load-bearing argument from the Pollack post (Kamoi et al., He et al., Zhuo et al.) — structure that the narrow v1 POJO cannot carry on its own.

Any OSS consumer (UI, CLI, API) can read `extensions["x-evaluation-agent-judge"]` directly. The commercial `promptlm-evals` bridge, when present, reads the same slot and lifts it into v2 `subScores` + `diagnostics` cleanly — no JSON-in-`comments` re-parsing.

### Auto-configuration

```java
@AutoConfiguration
@ConditionalOnClass(name = "ai.pollack.agentjudge.Judge")
@EnableConfigurationProperties(AgentJudgeProperties.class)
public class AgentJudgeAutoConfiguration {
    // Discovers user-defined Judge / Jury beans and registers them as Evaluation instances.
    // No effect if agent-judge is not on the classpath.
}
```

- Activates only when the upstream library is on the classpath.
- Discovers user-defined `Judge` beans and registers each as a named `Evaluation`.
- Discovered `Jury` beans become composite `Evaluation`s.
- A `JuryFactory` allows declarative `application.yaml` composition for users who do not want to author Java beans:

```yaml
promptlm:
  evaluation:
    agent-judge:
      jury:
        type: cascaded
        stages:
          - judge: build-success
          - judge: jacoco-coverage-threshold
            args: { threshold: 0.8 }
          - judge: llm-test-quality
            model: claude-haiku-4-5
```

### Precedence with native promptLM evaluators

When the same logical eval step is configured both natively (a `PromptSpec`-declared `Evaluation`) and via `agent-judge`:

- **Default:** native promptLM evaluator wins; agent-judge runs as an attached comparator (results recorded, not gating). Both produce v1 `EvaluationResult`s; both can write extension payloads under their own keys.
- Override per step with `promptlm.evaluation.<step>.backend: agent-judge`.
- Rationale: keeps the user's golden path stable; lets them adopt or compare `agent-judge` without flipping CI red.

### GraalVM native-image

**Scope for the first milestone:** JVM mode only. Native-image support is a **second milestone**, tracked as a follow-up issue. The integration is designed to *not block* a native build, but is not guaranteed to *work* under native until milestone 2 lands.

Native work, when undertaken:

1. CI matrix entry adds a native-image build with `agent-judge` on classpath running the example cascaded jury.
2. Reachability hints under `src/main/resources/META-INF/native-image/dev.promptlm.evaluation/agent-judge/` covering:
   - Reflection on user `Judge` / `Check` subtypes.
   - Jackson serialisers for the extension-slot payload.
   - Any `ServiceLoader` / dynamic proxy use inside `agent-judge` itself (discovered during the spike).
3. Adapter README documents the supported subset: e.g. "LLM judges and deterministic checks: supported. User-defined reflective judges: bring your own hints."

### Licensing & upstream check (hard gates)

Before merge of the adapter module:

- Confirm `agent-judge`'s license is Apache-2.0 / MIT / BSD-compatible. If not, this proposal is rejected and we either fork-under-Apache or build our own.
- Confirm groupId/artifactId stability and that a Maven Central artifact exists (not a JitPack snapshot).
- Open a courtesy thread with the upstream author signalling integration intent — not a blocker, but valuable for roadmap alignment.

## Consequences

**Positive**
- First worked example of the OSS community tier (validates [oss-eval-adapter-tier.md](oss-eval-adapter-tier.md)).
- Brings cascaded cheap → expensive judging to promptLM users at near-zero authoring cost.
- Defines the `extensions["x-evaluation-<adapter>"]` convention by walking it for real.
- OSS-only customers get full functionality; commercial customers get richer v2 schema lifted automatically.

**Negative**
- Adds a pre-1.0 dependency, even if gated. Upstream churn will require occasional adapter maintenance.
- Two ways to express "an eval" (native vs. agent-judge) — needs clear docs on when to pick which.
- The two-write pattern (v1 POJO + extension slot) is more work for adapter authors than a single write would be. The cost is paid once per adapter; future structured OSS adapters follow the same pattern.

**Neutral**
- No changes to host `Evaluation` or v1 `EvaluationResult`.
- If the spike discovers the extension-slot payload shape cannot carry per-`Check` granularity faithfully (e.g. serialisation gaps), that becomes its own proposal.

## Alternatives considered

1. **Build our own cascaded jury primitives in core.** Defer — see how `agent-judge` shapes up first. Cheaper to integrate than to design from scratch.
2. **Wrap `agent-judge` only at the CLI layer.** Rejected: loses Spring integration and the auto-discovery story.
3. **Fork `agent-judge` into the promptLM org.** Rejected for now. Revisit if upstream stalls or licensing is incompatible.
4. **Pack `Judgment.checks()` as JSON in v1 `comments`** — rejected on result-mapping grounds above.
5. **Skip v1 entirely, write only to the commercial v2 schema** — would require BSL-licensed code in this Apache-2.0 module. Rejected.

## Follow-ups

- Spike: [docs/spikes/2026-05-agent-judge.md](../spikes/2026-05-agent-judge.md).
- Issue: GraalVM native-image milestone for `agent-judge` (file after spike concludes).
- Issue: commercial bridge in `promptlm-evals` that reads `extensions["x-evaluation-agent-judge"]` and lifts to v2 (file *after* OSS adapter ships; do not pre-plan).
- Documentation: when `docs/eval-adapters.md` manifest is created (per [oss-eval-adapter-tier.md](oss-eval-adapter-tier.md)), register `agent-judge` as tier `community`.
