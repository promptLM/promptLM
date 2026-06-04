# Release classes

promptLM has **two distinct release pipelines** that publish to different
registries on different schedules for different audiences. Naming them up
front prevents the conflation that has crept into config files and docs in
the past.

| Class | What it is | Audience | Default target | Cadence | Governed by |
| --- | --- | --- | --- | --- | --- |
| **platform-release** | The promptLM org's own artifacts: `promptlm-platform`, `promptlm-app`, the `promptlm-clients` SDKs | External consumers of promptLM itself | Maven Central / PyPI / npm (snapshots → Central Portal) | `release.created` via release-please | [`promptlm-release/docs/ci-workflow-design.md`](https://github.com/promptLM/promptlm-release/blob/main/docs/ci-workflow-design.md) — five-job topology with promote/environment gate (publish is irreversible) |
| **bundle-release** | A versioned **prompt bundle** (Maven jar containing `prompts/` + `.promptlm/`) published from an end-user prompt store created via the store-create flow | The user's own applications that load prompts at runtime | **GitHub Packages** (Maven) by default; alternate Maven registries (Artifactory, self-hosted Nexus) supported via `BUNDLE_RELEASE_MAVEN_URL` override | `release.created` + `workflow_dispatch`, no `push: tags:` | This document; the canonical workflow ships under [`components/repository-template/repo-resources/.github/workflows/bundle-release.yml`](../components/repository-template/repo-resources/.github/workflows/bundle-release.yml) |

The names match the existing module taxonomy (`promptlm-store-*` vs
`promptlm-platform`), they're directional rather than ownership-flavoured
("third-party" would be wrong — the user owns their store), and they survive
the eventual un-parking of Python / JS / Artifactory targets.

## platform-release

Out of scope for this document. The org-wide design lives in the
`promptlm-release` repo; see the link in the table above. Touch points in
this repo are limited to the in-repo `.github/workflows/release.yml` and its
companions, all of which target the org-managed Central / PyPI / npm
endpoints, not a store.

## bundle-release

A bundle-release builds a versioned prompt-bundle jar from a prompt-store
repo's `prompts/` tree and publishes it to a Maven registry. The jar's
coordinates are derived from the store's owner/name at rollout time by
[`ArtifactCoordinateSanitizer`](../components/repository-template/src/main/java/dev/promptlm/repository/template/ArtifactCoordinateSanitizer.java)
(e.g. `io.github.<owner>:<repo>:1.2.3`).

### Scope

- **0.1.0**: single target language (Java), single default registry (GitHub
  Packages). The shipped workflow is
  [`bundle-release.yml`](../components/repository-template/repo-resources/.github/workflows/bundle-release.yml);
  the multi-language tooling
  ([`tools/release/build-artifacts`](../components/repository-template/repo-resources/tools/release/build-artifacts) and
  [`tools/release/publish-artifacts`](../components/repository-template/repo-resources/tools/release/publish-artifacts))
  understands java, python, and js, but only java is enabled by default in
  the shipped [`artifacts.toml`](../components/repository-template/repo-resources/.promptlm/artifacts.toml).
- **0.2.0+**: progressive disclosure between Mode 1 (prompt-management only,
  no CI/CD) and Mode 2 (release-enabled). The
  [`release.enabled` gate in the extractor](../components/promptlm-store/promptlm-store-github/src/main/java/dev/promptlm/store/github/ZipFileRepositoryTemplateExtractor.java)
  is already wired; only the `promptlm.yml` default is held at `true` for
  the 0.1.0 ship.

### Triggers

```yaml
on:
  workflow_dispatch:
    inputs:
      version:   { required: true,  type: string }
      dry-run:   { required: false, type: boolean, default: false }
  release:
    types: [created]
```

`release.created` (not `published`) is the production path — it fires once
when a GitHub Release is created, manually or by release-please. `push:
tags:` is **never** used; tags are movable, leave no audit trail, and provide
no Release object.

### Configuration knobs (repo variables / secrets)

| Name | Default | Purpose |
| --- | --- | --- |
| `BUNDLE_RELEASE_MAVEN_URL` | `https://maven.pkg.github.com/<owner>/<repo>` | Maven registry the jar is `PUT`-ed to. Override for Artifactory / Nexus / a local testcontainer. |
| `BUNDLE_RELEASE_USERNAME` | `${{ github.actor }}` | Registry deploy username. |
| `BUNDLE_RELEASE_PASSWORD` | `${{ secrets.GITHUB_TOKEN }}` | Registry deploy token or password. Must be a **secret**, not a variable. |

The workflow writes a Maven `~/.m2/settings.xml` from these values at
runtime and lets `tools/release/publish-artifacts` route the publish through
it (the github profile in `artifacts.toml` already pins `maven_repo_id =
"github"` to match).

### Acceptance test

[`CiWorkflowHarnessTest`](../acceptance-tests/src/test/java/dev/promptlm/test/CiWorkflowHarnessTest.java)
exercises bundle-release end-to-end:

1. Seed the shipped template into a Gitea testcontainer.
2. Set `BUNDLE_RELEASE_MAVEN_URL` / `_USERNAME` / `_PASSWORD` to point at an
   Artifactory testcontainer playing the role of a Maven registry.
3. Dispatch `bundle-release.yml` via Gitea's `workflow_dispatch` REST endpoint.
4. Wait for the run to complete and assert the published jar's contract via
   [`ReleaseArtifactContractDelegate`](../acceptance-tests/src/test/java/dev/promptlm/test/support/ReleaseArtifactContractDelegate.java).

The substitution of `BUNDLE_RELEASE_MAVEN_URL` keeps a single test exercising
the real workflow on a real Maven endpoint without needing a network round
trip to `maven.pkg.github.com`.

## See also

- Issue [#311](https://github.com/promptLM/promptlm-app/issues/311) — make
  GitHub Packages the bundle-release default.
- Issue [#326](https://github.com/promptLM/promptlm-app/issues/326) — pick
  one canonical release pipeline.
- Issue [#275](https://github.com/promptLM/promptlm-app/issues/275) —
  progressive disclosure for Mode 1 ↔ Mode 2 (0.2.0+).
