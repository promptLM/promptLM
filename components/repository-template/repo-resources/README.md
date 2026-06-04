# {{REPO_NAME}}

This repository contains prompts for {{PROJECT_DESCRIPTION}}.

## Prompts

Add your prompts to the `prompts/` directory. Each prompt can be a separate file or organized in subdirectories.

## How releases work

This is a **bundle-release** repository: it publishes a versioned prompt bundle (a Maven jar containing the `prompts/` tree and `.promptlm/` metadata) to a Maven registry on every release. The default registry is **GitHub Packages** (`maven.pkg.github.com/<owner>/<repo>`); the workflow authenticates with the built-in `GITHUB_TOKEN`, so out-of-the-box no extra configuration is required.

> See `docs/release-classes.md` in the promptLM app for the platform-release vs bundle-release distinction.

A prompt-management-only mode (no CI/CD, no Maven coordinates) is planned for 0.2.0; for 0.1.0 every generated repo ships the bundle-release stack.

## Cutting a release

1. Create a GitHub Release with a semver tag (`vX.Y.Z`) — either in the UI, via `gh release create`, or via release-please.
2. The `Publish Prompt Bundle (GitHub Packages)` workflow (`bundle-release.yml`) fires on the `release: created` event, validates inputs, builds the jar via `tools/release/build-artifacts`, and publishes it via `tools/release/publish-artifacts`.

For ad-hoc releases (or to dry-run a build), trigger `bundle-release.yml` manually from the Actions tab with an explicit `version` input.

## Workflows

The generated repository ships these GitHub Actions workflows under `.github/workflows/`:

| Workflow | Triggers | What it does |
| --- | --- | --- |
| `validate.yml` | Push, pull request | Validates prompt files (non-empty, required metadata fields). |
| `release.yml` | Manual dispatch | Validates, packages, and attaches a `.zip` artifact to a GitHub Release. Provides a release-cut button when not using release-please. |
| `bundle-release.yml` | `release.created`, manual dispatch | Publishes the prompt bundle jar to the configured Maven registry (default: GitHub Packages). |
| `build-artifacts.yml` | Manual dispatch | Builds artifacts locally (no publish), for inspecting what a release would contain. |

## Configuration

### Default — GitHub Packages

No configuration required. The default trigger chain (`release.created` → `bundle-release.yml`) authenticates with `secrets.GITHUB_TOKEN`, builds the jar with the coordinates filled into `pom.xml` / `.promptlm/artifacts.toml` at rollout time, and publishes to `maven.pkg.github.com/<owner>/<repo>`.

### Optional — alternate Maven registry

To publish to a different Maven registry (e.g. Artifactory or a self-hosted Nexus), set repository variables / secrets:

- `BUNDLE_RELEASE_MAVEN_URL`: full registry URL (e.g. `https://artifactory.example.com/artifactory/libs-release-local`)
- `BUNDLE_RELEASE_USERNAME`: deploy username (repository variable)
- `BUNDLE_RELEASE_PASSWORD`: deploy password or API token (repository secret)

These override the defaults inside `bundle-release.yml` without any code changes.

## License

Specify your project's license here.
