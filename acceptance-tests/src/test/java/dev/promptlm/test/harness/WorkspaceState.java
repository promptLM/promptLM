/*
 * Copyright 2025 promptLM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.promptlm.test.harness;

import dev.promptlm.domain.promptspec.PromptSpecLifecycleState;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only witness over the on-disk workspace + git repository.
 *
 * <p>{@code WorkspaceState} re-implements the four-line lifecycle derivation
 * rule from {@code dev.promptlm.web.PromptSpecLifecycleDeriver} (working-tree
 * vs HEAD blob → SAVED-or-better; HEAD-reachable-from-origin → PUSHED). This
 * is intentional duplication: the witness must catch the case where the
 * server's deriver disagrees with disk-and-git truth.
 *
 * <p>All methods are pure observers. The class exposes no mutators.
 */
public final class WorkspaceState {

    private static final int SHORT_SHA_LENGTH = 7;
    private static final String PROMPTS_DIR = "prompts";
    private static final String SPEC_FILENAME = "promptlm.yml";

    private final Path workspaceRoot;
    private final Path activeRepo;

    private WorkspaceState(Path workspaceRoot, Path activeRepo) {
        this.workspaceRoot = workspaceRoot;
        this.activeRepo = activeRepo;
    }

    /**
     * Captures a snapshot-style read-only view over the workspace.
     *
     * @param workspaceRoot directory under which user repositories live
     * @param activeRepo    absolute path to the currently active repository
     *                      (e.g. {@code workspaceRoot/myrepo})
     */
    public static WorkspaceState observe(Path workspaceRoot, Path activeRepo) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(activeRepo, "activeRepo");
        return new WorkspaceState(
                workspaceRoot.toAbsolutePath().normalize(),
                activeRepo.toAbsolutePath().normalize());
    }

    /**
     * Locates the on-disk YAML for the given prompt {@code group}/{@code name}.
     *
     * <p>Returns a lightweight handle ({@link PromptSpecRef}) carrying the
     * resolved absolute path plus the identifying fields. The witness layer
     * intentionally avoids parsing the YAML — assertions only need the file
     * location to compute lifecycle state and the original identifiers to
     * frame failure messages.
     *
     * @throws IllegalStateException if no spec file exists at the derived path
     */
    public PromptSpecRef specOnDisk(String group, String name) {
        Path specPath = activeRepo.resolve(PROMPTS_DIR).resolve(group).resolve(name).resolve(SPEC_FILENAME);
        if (!Files.exists(specPath)) {
            throw new IllegalStateException("No prompt spec file at " + specPath);
        }
        return new PromptSpecRef(group, name, specPath);
    }

    /**
     * Re-implements the four-line lifecycle rule from
     * {@code PromptSpecLifecycleDeriver.deriveResult} by hand so the test
     * can detect server-side regressions.
     */
    public LifecycleObservation lifecycleOf(PromptSpecRef spec) {
        Objects.requireNonNull(spec, "spec");
        Path specPath = spec.path();
        if (specPath == null) {
            throw new IllegalArgumentException("PromptSpecRef has no resolved path");
        }
        try (Git git = Git.open(activeRepo.toFile())) {
            Repository repo = git.getRepository();
            String gitPath = toGitPath(activeRepo, specPath);
            if (gitPath == null) {
                throw new IllegalStateException(
                        "Spec path " + specPath + " is not under repo " + activeRepo);
            }

            byte[] workingTree = Files.exists(specPath) ? Files.readAllBytes(specPath) : null;
            byte[] head = readBlobAtHead(repo, gitPath);

            boolean workingTreeMatchesHead = java.util.Arrays.equals(workingTree, head);
            String shortSha = shortShaOfHead(repo);

            if (!workingTreeMatchesHead) {
                return new LifecycleObservation(PromptSpecLifecycleState.SAVED,
                        false, false, null);
            }
            boolean reachable = isHeadReachableFromOrigin(repo);
            PromptSpecLifecycleState state = reachable
                    ? PromptSpecLifecycleState.PUSHED
                    : PromptSpecLifecycleState.COMMITTED;
            return new LifecycleObservation(state, true, reachable, shortSha);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads HEAD's sha + branch + tags from the active repository.
     */
    public GitRefs gitRefs() {
        try (Git git = Git.open(activeRepo.toFile())) {
            Repository repo = git.getRepository();
            ObjectId headId = repo.resolve("HEAD");
            String headSha = headId == null ? null : headId.getName();
            String shortSha = headSha == null || headSha.length() <= SHORT_SHA_LENGTH
                    ? headSha
                    : headSha.substring(0, SHORT_SHA_LENGTH);
            String branch = repo.getBranch();
            Set<String> tagNames = new HashSet<>();
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/tags/")) {
                tagNames.add(ref.getName().substring("refs/tags/".length()));
            }
            return new GitRefs(headSha, shortSha, branch, Collections.unmodifiableSet(tagNames));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the configured promptLM app-context json file, if any.
     *
     * <p>{@code AppContext} is left as an opaque record because the witness
     * does not need to interpret it — its presence alone is the assertion.
     */
    public Optional<AppContext> appContext() {
        Path contextFile = workspaceRoot.resolve(".promptlm").resolve("context.json");
        if (!Files.exists(contextFile)) {
            return Optional.empty();
        }
        try {
            byte[] raw = Files.readAllBytes(contextFile);
            return Optional.of(new AppContext(contextFile, raw.length));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Performs a {@code git fetch} of {@code origin} so subsequent lifecycle
     * checks see fresh remote-tracking refs.
     *
     * <p>Without this, PUSHED assertions race against a stale
     * {@code refs/remotes/origin/<branch>}.
     */
    public void refreshRemoteTracking() {
        try (Git git = Git.open(activeRepo.toFile())) {
            git.fetch().call();
        }
        catch (Exception e) {
            // Best effort — surface as runtime exception so the caller sees
            // the underlying transport / auth error in its assertion message.
            throw new IllegalStateException("git fetch failed for " + activeRepo, e);
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers — copied verbatim from PromptSpecLifecycleDeriver.
    // ---------------------------------------------------------------------

    private static String toGitPath(Path repoDir, Path specPath) {
        Path relative;
        try {
            relative = repoDir.relativize(specPath);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
        return relative.toString().replace('\\', '/');
    }

    private static byte[] readBlobAtHead(Repository repo, String relativeGitPath) throws IOException {
        ObjectId headId = repo.resolve("HEAD^{tree}");
        if (headId == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo);
             TreeWalk treeWalk = TreeWalk.forPath(repo, relativeGitPath, headId)) {
            if (treeWalk == null) {
                return null;
            }
            ObjectId blobId = treeWalk.getObjectId(0);
            if (blobId == null || blobId.equals(ObjectId.zeroId())) {
                return null;
            }
            try {
                return repo.open(blobId).getBytes();
            }
            catch (MissingObjectException missing) {
                return null;
            }
        }
    }

    private static boolean isHeadReachableFromOrigin(Repository repo) throws IOException {
        ObjectId headId = repo.resolve("HEAD");
        if (headId == null) {
            return false;
        }
        String branch = repo.getBranch();
        if (branch == null) {
            return false;
        }
        Ref originRef = repo.findRef("refs/remotes/origin/" + branch);
        if (originRef == null) {
            return false;
        }
        ObjectId originId = originRef.getObjectId();
        if (originId == null) {
            return false;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit headCommit;
            RevCommit originCommit;
            try {
                headCommit = walk.parseCommit(headId);
                originCommit = walk.parseCommit(originId);
            }
            catch (MissingObjectException missing) {
                return false;
            }
            return walk.isMergedInto(headCommit, originCommit);
        }
    }

    private static String shortShaOfHead(Repository repo) throws IOException {
        ObjectId headId = repo.resolve("HEAD");
        if (headId == null) {
            return null;
        }
        String full = headId.getName();
        if (full.length() <= SHORT_SHA_LENGTH) {
            return full;
        }
        return full.substring(0, SHORT_SHA_LENGTH);
    }

    // ---------------------------------------------------------------------
    // Observation value types.
    // ---------------------------------------------------------------------

    /**
     * The fully-derived lifecycle observation: the rule's verdict plus the
     * raw intermediate signals so test failures can pinpoint the disagreement.
     */
    public record LifecycleObservation(
            PromptSpecLifecycleState derivedByDiskAndGit,
            boolean workingTreeMatchesHead,
            boolean headReachableFromOrigin,
            String headShortSha) {
    }

    /**
     * HEAD git refs of the active repository at observation time.
     */
    public record GitRefs(String headSha, String shortSha, String branch, Set<String> tags) {
    }

    /**
     * Opaque witness for the existence of a promptLM app-context file.
     */
    public record AppContext(Path contextFile, long sizeBytes) {
    }

    /**
     * Light-weight reference to an on-disk prompt spec used by witness
     * methods. Carries only the identifying tuple + resolved path.
     */
    public record PromptSpecRef(String group, String name, Path path) {
    }
}
